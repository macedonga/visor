package com.macedonga.visor.core.media

import java.security.SecureRandom
import android.util.Log
import android.content.Context
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.connection.MT_PEER_APP_INFO
import com.macedonga.visor.core.connection.MT_PEER_DEVICE_INFO
import com.macedonga.visor.core.connection.SVC_PEER_BUILD_INFO
import com.macedonga.visor.core.connection.SessionHost
import com.macedonga.visor.core.connection.SessionHostFactory
import com.macedonga.visor.core.connection.subscribeToDeviceStatus
import com.macedonga.visor.core.connection.DeviceState
import com.macedonga.visor.core.connection.decodeDeviceStateUpdate
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.connection.isDeviceStateType
import com.macedonga.visor.core.connection.mergeDeviceState
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.transport.LinkMeter
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.LinkStateChanged
import com.macedonga.visor.core.session.ProtocolError
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.core.session.Responder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// svc28 app manager. The Meta app starts the glasses-side app this way before it opens svc51350.
private const val MT_START_APP = 2000
private const val MT_START_APP_RESPONSE = 2001
private const val MT_STOP_APP = 2002

/** The glasses-side app a stream session runs in: the app manager's AppId. */
enum class GlassesApp(val id: Int, val label: String) {
    LIVE_STREAMING(3, "Livestream"),
    STREAMING_SDK(27, "Device toolkit"),
    MMAI_LIVE(28, "Live AI"),
    WARP_LIVE_STREAMING(40, "WARP livestream"),
}

/** The SessionSettings ApplicationType. */
enum class SessionApp(val id: Int, val label: String) {
    UNKNOWN(0, "Unset"),
    FACEBOOK(StreamApp.FACEBOOK, "Facebook"),
    INSTAGRAM(StreamApp.INSTAGRAM, "Instagram"),
    FAIR_DEMO(StreamApp.FAIR_DEMO, "Demo"),
    MMAI_LIVE(StreamApp.MMAI_LIVE, "Live AI"),
}

/** The Meta app's bitrate ladder tops out at 720p; 1080p is here to see whether the glasses take it. */
enum class StreamQuality(val width: Int, val height: Int, val label: String) {
    VERY_LOW(360, 640, "360p"),
    LOW(432, 768, "432p"),
    MEDIUM(504, 896, "504p"),
    HIGH(720, 1280, "720p"),
    FULL_HD(1080, 1920, "1080p"),
    /** What Live AI streams at, from the glasses' own MobileConfig (mmai_live_video_res_*). */
    LIVE_AI(432, 576, "432x576"),
}

/**
 * One reassembled frame: the glasses' presentation time in microseconds, then Annex-B video. [seq]
 * counts frames, so a reader can tell one was lost and wait for the next keyframe.
 */
class VideoFrame(
    val seq: Long,
    val ptsUs: Long,
    val data: ByteArray,
    val arrivedNs: Long = System.nanoTime(),
    val codec: VideoCodec = VideoCodec.HEVC,
) {
    val isKey: Boolean by lazy { codec.isKeyFrame(data) }
}

/**
 * [delayMs] is how much later than the first frame, by the glasses' own clock, the latest frame
 * arrived: flat means no queue anywhere, growing means one is filling up. [maxGapMs] is the longest
 * wait between two frames in the last second.
 */
data class StreamStats(
    val frames: Int = 0,
    val bytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val fps: Int = 0,
    val delayMs: Long = 0,
    val maxGapMs: Long = 0,
)

/** Where starting the stream has got to. */
enum class LivePhase { IDLE, WIFI, GLASSES, CAMERA, RUNNING, RESTARTING, FAILED }

/** Why it did not start, or stopped by itself. */
enum class LiveProblem { NOT_CONNECTED, IN_CASE, WIFI_OFF, WIFI_DIRECT_DENIED, NO_KEY, JOIN_FAILED, SESSION_FAILED, NO_VIDEO, GAVE_UP, VPN }

data class LiveStatus(val phase: LivePhase = LivePhase.IDLE, val problem: LiveProblem? = null)

/**
 * The svc51350 responder. A frame comes as VIDEO_CAST_BUFFER chunks closed by VIDEO_CAST_BUFFER_END,
 * which carries data too; codec parameter sets come on CODEC_CONFIGURATION_UPDATE_REQUEST. Audio comes the
 * same way as AAC access units, and a photo asked for mid-stream as PHOTO_CAST_BUFFER chunks of a HEIC.
 */
class StreamReceiver : Responder {

    private val frame = ByteArrayOutputStream()

    private val _frames = MutableSharedFlow<VideoFrame>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames: SharedFlow<VideoFrame> = _frames.asSharedFlow()

    private val _codecConfig = MutableStateFlow<ByteArray?>(null)
    val codecConfig: StateFlow<ByteArray?> = _codecConfig.asStateFlow()

    // HEVC until the parameter sets or a picture say otherwise.
    private var codec = VideoCodec.HEVC
    private val _videoCodec = MutableStateFlow<VideoCodec?>(null)
    val videoCodec: StateFlow<VideoCodec?> = _videoCodec.asStateFlow()

    private val audioChunks = ByteArrayOutputStream()
    private val _audio = MutableSharedFlow<AudioPacket>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val audio: SharedFlow<AudioPacket> = _audio.asSharedFlow()

    private val _aac = MutableStateFlow<AacFormat?>(null)
    val aac: StateFlow<AacFormat?> = _aac.asStateFlow()

    private val photoChunks = ByteArrayOutputStream()
    private val _photos = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    val photos: SharedFlow<ByteArray> = _photos.asSharedFlow()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    // Everything that is not a media chunk, for LiveStream to read.
    private val _control = MutableSharedFlow<DataXMessage>(extraBufferCapacity = 64)
    val control: SharedFlow<DataXMessage> = _control.asSharedFlow()

    @Volatile
    var recording: FileOutputStream? = null

    /** When the latest frame arrived, by the wall clock; 0 before the first. */
    @Volatile
    var lastFrameAt = 0L
        private set

    private var seq = 0L
    private var windowStart = 0L
    private var windowBytes = 0L

    // Phone clock minus the glasses' pts at the first frame, both in microseconds.
    private var clockOffset: Long? = null
    private var lastArrival = 0L
    private var fpsStart = 0L
    private var fpsCount = 0
    private var gapMax = 0L

    fun reset() {
        frame.reset()
        audioChunks.reset()
        photoChunks.reset()
        _aac.value = null
        lastFrameAt = 0
        clockOffset = null
        lastArrival = 0
        fpsStart = 0
        fpsCount = 0
        gapMax = 0
        _codecConfig.value = null
        codec = VideoCodec.HEVC
        _videoCodec.value = null
        _stats.value = StreamStats()
    }

    private fun noteCodec(data: ByteArray) {
        if (_videoCodec.value != null) return
        detectVideoCodec(data)?.let {
            codec = it
            _videoCodec.value = it
        }
    }

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val payload = message.payload
        when (message.info.messageType) {
            StreamType.VIDEO_CAST_BUFFER -> chunk(payload)
            StreamType.VIDEO_CAST_BUFFER_END -> {
                chunk(payload)
                finishFrame()
            }
            StreamType.CODEC_CONFIGURATION_UPDATE_REQUEST -> {
                val f = Wire.parse(payload)
                val config = f.bytes(3)
                val type = f.varint(2)
                // Field 2 says which; failing that, video parameter sets are Annex-B and an AudioSpecificConfig is not.
                if (type == CodecType.VIDEO || (type != CodecType.AUDIO && nalTypes(config).isNotEmpty())) {
                    _codecConfig.value = config
                    noteCodec(config)
                    recording?.write(config)
                } else {
                    aacFormatOf(config)?.let { _aac.value = it }
                }
                _control.emit(message)
            }
            StreamType.AUDIO_CAST_BUFFER -> audioChunks.write(payload)
            StreamType.AUDIO_CAST_BUFFER_END -> {
                audioChunks.write(payload)
                val whole = audioChunks.toByteArray()
                audioChunks.reset()
                if (whole.size > 8) {
                    val pts = ByteBuffer.wrap(whole, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
                    // Some glasses never send an AudioSpecificConfig (#31): the format asked for stands in until one comes.
                    if (_aac.value == null) _aac.value = aacFormatFor(LIVE_AUDIO)
                    _audio.tryEmit(AudioPacket(pts, whole.copyOfRange(8, whole.size)))
                }
            }
            StreamType.PHOTO_CAST_BUFFER -> photoChunks.write(payload)
            StreamType.PHOTO_CAST_BUFFER_END -> {
                photoChunks.write(payload)
                val whole = photoChunks.toByteArray()
                photoChunks.reset()
                // The glasses send the HEIC bare; a reader that expects a pts first finds ftyp eight bytes later.
                val bare = whole.size > 12 && String(whole, 4, 4, Charsets.US_ASCII) == "ftyp"
                _photos.tryEmit(if (bare || whole.size <= 16) whole else whole.copyOfRange(8, whole.size))
            }
            else -> _control.emit(message)
        }
    }

    private fun chunk(payload: ByteArray) {
        frame.write(payload)
        val now = System.currentTimeMillis()
        if (now - windowStart >= 1000) {
            _stats.update { it.copy(bytesPerSecond = windowBytes * 1000 / maxOf(1, now - windowStart)) }
            windowStart = now
            windowBytes = 0
        }
        windowBytes += payload.size
    }

    private fun finishFrame() {
        val whole = frame.toByteArray()
        frame.reset()
        if (whole.size <= 8) return
        val pts = ByteBuffer.wrap(whole, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val data = whole.copyOfRange(8, whole.size)
        recording?.write(data)
        lastFrameAt = System.currentTimeMillis()

        val now = SystemClock.elapsedRealtimeNanos() / 1000
        val offset = clockOffset ?: (now - pts).also { clockOffset = it }
        if (lastArrival > 0) gapMax = maxOf(gapMax, now - lastArrival)
        lastArrival = now
        fpsCount++
        var fps = -1
        var gap = -1L
        if (now - fpsStart >= 1_000_000) {
            fps = (fpsCount * 1_000_000L / maxOf(1, now - fpsStart)).toInt()
            gap = gapMax / 1000
            fpsStart = now
            fpsCount = 0
            gapMax = 0
        }
        _stats.update {
            it.copy(
                frames = it.frames + 1,
                bytes = it.bytes + whole.size,
                delayMs = (now - pts - offset) / 1000,
                fps = if (fps >= 0) fps else it.fps,
                maxGapMs = if (gap >= 0) gap else it.maxGapMs,
            )
        }
        noteCodec(data)
        _frames.tryEmit(VideoFrame(seq++, pts, data, codec = codec))
    }
}

/**
 * Asks the glasses for their camera over svc51350, the way the Meta app's media stream client does:
 * start the glasses-side app on svc28, then SessionSettings, ConfigureVideoStreamRequest and a
 * ControlStreamRequest START on one channel. Every step and every answer goes to [log].
 *
 * Over Wi-Fi the same messages run on a second session: the glasses join this phone's Wi-Fi Direct
 * group exactly as for media import, and Bluetooth is paused until the stream stops.
 */
class LiveStream(
    private val context: Context,
    private val connection: GlassesConnection,
    val receiver: StreamReceiver,
    private val store: IdentityStore,
    private val hosts: SessionHostFactory,
    private val meter: LinkMeter,
    private val spool: File,
    val outputs: LiveOutputs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    /** What the decoder says the frames are, e.g. "1280x720". */
    val decodedSize = MutableStateFlow<String?>(null)

    /** Frames not shown because one before them was lost, counted by the decoder's feeder. */
    val skipped = MutableStateFlow(0)

    /** Frames the decoder put on screen in the last second. */
    val shownFps = MutableStateFlow(0)

    /** How long the latest frame waited between arriving and going into the decoder. */
    val waitMs = MutableStateFlow(0L)

    /** Bumped at every start and size change, so the decoder is rebuilt for the size asked for. */
    val generation = MutableStateFlow(0)

    private val _status = MutableStateFlow(LiveStatus())
    val status: StateFlow<LiveStatus> = _status.asStateFlow()

    /** When the first frame of this Live came, by the wall clock; 0 before. Restarts do not reset it. */
    private val _since = MutableStateFlow(0L)
    val since: StateFlow<Long> = _since.asStateFlow()

    /** The glasses' device state while streaming: Bluetooth's at the start, then what they push over Wi-Fi. */
    private val _device = MutableStateFlow<DeviceState?>(null)
    val device: StateFlow<DeviceState?> = _device.asStateFlow()

    /** What the stream is asked to be right now, and whether Visor picks it. */
    private val _rung = MutableStateFlow<Rung?>(null)
    val rung: StateFlow<Rung?> = _rung.asStateFlow()
    private val _auto = MutableStateFlow(true)
    val auto: StateFlow<Boolean> = _auto.asStateFlow()

    private val pilot = QualityPilot()

    /** Heat and battery as the stream itself reports them, for when svc28 says nothing over Wi-Fi. */
    private val _health = MutableStateFlow(StreamHealth())
    val health: StateFlow<StreamHealth> = _health.asStateFlow()

    /** Why the glasses stopped the camera on their own, as ControlStreamStopped gives it. */
    private val _stopped = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val stopped: SharedFlow<Long> = _stopped.asSharedFlow()

    /** The Wi-Fi session while the Live runs on one: the assistant moves onto it, since Bluetooth is paused. */
    private val _wifiSession = MutableStateFlow<DataXConnection?>(null)
    val wifiSession: StateFlow<DataXConnection?> = _wifiSession.asStateFlow()

    // What the glasses send on the Wi-Fi session, for work run there with overWifi.
    private val _wifiData = kotlinx.coroutines.flow.MutableSharedFlow<ServiceData>(extraBufferCapacity = 256)
    val wifiData: kotlinx.coroutines.flow.SharedFlow<ServiceData> = _wifiData

    /** The picture now as a JPEG, decoded from the replay ring; null when no Live runs. */
    suspend fun frameNow(): ByteArray? = if (_running.value) outputs.replay.lastPicture()?.let { grabJpeg(it, quality = 88) } else null

    // The glasses' own ceiling, from their SupportedConfiguration: 0 LOW, 1 MEDIUM, 2 HIGH.
    @Volatile
    private var supported: Int? = null

    @Volatile
    var requested: StreamQuality = StreamQuality.LOW
        private set

    private val answers = ConcurrentHashMap<Long, CompletableDeferred<StreamAnswer>>()
    private var startAnswer: CompletableDeferred<Long>? = null
    private var joined: CompletableDeferred<JoinResult>? = null
    // The answer to CreateSoftApNetwork, with the ssid and password it was asked for.
    private var hosted: Pair<CompletableDeferred<SoftApInfo>, Pair<String, String>>? = null
    private var onGlassesNetwork = false
    private var glassesNetwork: GlassesNetwork? = null
    // Restarts of this stream on the phone's group: two make the glasses' own network worth a try.
    private var groupStalls = 0
    private val _suggestGlassesHost = MutableStateFlow(false)
    val suggestGlassesHost: StateFlow<Boolean> = _suggestGlassesHost.asStateFlow()
    private var nonce = 1
    private var job: Job? = null
    private var lastConfig: VideoConfig? = null
    private var keyframeAskedAt = 0L

    private data class Params(
        val app: GlassesApp,
        val session: SessionApp,
        val quality: StreamQuality,
        val bitrate: Int,
        val frameRate: Int,
        val bypassDonCheck: Boolean,
        val overWifi: Boolean,
        val groupBand: Int,
        val auto: Boolean = false,
        val liveAi: Boolean = false,
        val glassesHost: Boolean = false,
    )

    // What the user asked for, kept so the watchdog can start the same stream again.
    private var params: Params? = null

    @Volatile
    private var wanted = false
    private var watchdog: Job? = null
    private var startedAt = 0L

    @Volatile
    private var linkDropped = false

    private var stopping: Job? = null

    @Volatile
    private var tearingDown = false

    // The Wi-Fi keepalive: the phone is otherwise silent once the stream runs.
    private var keepalive: Job? = null

    @Volatile
    private var keepaliveAnsweredAt = 0L

    // When the Wi-Fi session last produced a DataX message of any service.
    @Volatile
    private var messageAt = 0L

    // A restart that has not brought video back yet, and how many were tried in a row.
    @Volatile
    private var recovering = false
    private var attempts = 0

    // When START last went out. A restart is judged from here: rejoining Wi-Fi alone can take half a minute.
    @Volatile
    private var commandedAt = 0L

    // The Wi-Fi session while the stream runs on one, with what it took to bring it up.
    @Volatile
    private var wifi: SessionHost? = null
    private var wifiEvents: Job? = null
    private var group: PhoneGroup? = null
    private var groupBand = BAND_AUTO


    /** The file the last stream was written to: raw Annex-B, playable with ffplay -f hevc. */
    val recordingFile: File get() = File(spool, "live.hevc")

    init {
        scope.launch { receiver.control.collect(::onControl) }
        scope.launch { receiver.frames.collect(outputs::onVideo) }
        scope.launch { receiver.codecConfig.collect { if (it != null) outputs.onConfig(it) } }
        scope.launch { receiver.videoCodec.filterNotNull().collect { say("Video codec: ${it.label}") } }
        scope.launch { receiver.audio.collect(outputs::onAudio) }
        scope.launch { receiver.aac.collect { if (it != null) outputs.onAudioFormat(it) } }
        scope.launch {
            connection.serviceData.collect {
                when {
                    it.serviceId == 28 && it.messageType == MT_START_APP_RESPONSE -> startAnswer?.complete(Wire.parse(it.payload).varint(1))
                    it.serviceId == SVC_ATC && it.messageType == MT_JOIN_ACCESS_POINT_RESULT -> joined?.complete(parseJoinResult(it.payload))
                    it.serviceId == SVC_ATC && it.messageType == MT_SOFT_AP_INFO -> hosted?.let { (answer, asked) ->
                        answer.complete(parseSoftApInfo(it.payload, asked.first, asked.second))
                    }
                }
            }
        }
        scope.launch {
            connection.warnings.collect { if ("service 51350" in it || "service 28 " in it) say("Glasses: $it") }
        }
    }

    /** The Live view's start: over Wi-Fi Direct, automatic quality unless the settings fix one. */
    fun start(settings: LiveSettings) {
        val top = LADDER.first()
        start(
            GlassesApp.LIVE_STREAMING, SessionApp.FAIR_DEMO,
            if (settings.manual) settings.quality else top.quality,
            if (settings.manual) settings.bitrate else top.bitrate,
            if (settings.manual) settings.frameRate else top.frameRate,
            bypassDonCheck = true, overWifi = true, groupBand = BAND_2GHZ, auto = !settings.manual,
            glassesHost = settings.glassesHost,
        )
    }

    /**
     * The camera for a Live AI session, as the Meta app opens it: the glasses' MMAI_LIVE app over the Bluetooth
     * session, 432x576 at 2 fps and 250 kbit/s, every frame a keyframe, OCR allowed, no audio (that is on svc15).
     */
    fun startLiveAi() {
        if (job?.isActive == true || _running.value) return
        start(
            GlassesApp.MMAI_LIVE, SessionApp.MMAI_LIVE, StreamQuality.LIVE_AI, 250_000, 2,
            bypassDonCheck = true, overWifi = false, liveAi = true,
        )
    }

    /** Whether the stream running now is Live AI's, and not the Live view's. */
    val forLiveAi: Boolean get() = _running.value && params?.liveAi == true

    fun start(
        app: GlassesApp,
        session: SessionApp,
        quality: StreamQuality,
        bitrate: Int,
        frameRate: Int,
        bypassDonCheck: Boolean,
        overWifi: Boolean,
        groupBand: Int = BAND_2GHZ,
        auto: Boolean = false,
        liveAi: Boolean = false,
        glassesHost: Boolean = false,
    ) {
        if (job?.isActive == true) return
        val p = Params(app, session, quality, bitrate, frameRate, bypassDonCheck, overWifi, groupBand, auto, liveAi, glassesHost)
        params = p
        groupStalls = 0
        _suggestGlassesHost.value = false
        _auto.value = auto
        pilot.reset(System.currentTimeMillis())
        _rung.value = Rung(quality, bitrate, frameRate)
        _device.value = connection.state.value.device
        _health.value = StreamHealth()
        _status.value = LiveStatus(LivePhase.WIFI)
        _since.value = 0
        supported = null
        outputs.onStreamStarted()
        wanted = true
        job = scope.launch { run(p) }
        watchdog?.cancel()
        watchdog = scope.launch { watch() }
    }

    private suspend fun run(p: Params) {
        val reuse = p.overWifi && wifi != null && !linkDropped && groupBand == p.groupBand && onGlassesNetwork == p.glassesHost
        if (!reuse && wifi != null) tearDownWifi()
        if (!reuse && connection.state.value.link != LinkState.READY) {
            say("Not connected.")
            fail(LiveProblem.NOT_CONNECTED)
            return
        }
        // Charging folded means docked in the case, and there the glasses keep the camera off. Open, it is a cable.
        if (!reuse && inCase()) {
            say("Charging in the case: the glasses keep the camera off there.")
            fail(LiveProblem.IN_CASE)
            return
        }
        if (reuse && _status.value.phase != LivePhase.RESTARTING) _status.value = LiveStatus(LivePhase.CAMERA)
        _running.value = true
        receiver.reset()
        decodedSize.value = null
        requested = _rung.value?.quality ?: p.quality
        skipped.value = 0
        generation.value += 1
        linkDropped = false
        startedAt = System.currentTimeMillis()
        spool.mkdirs()
        receiver.recording?.close()
        receiver.recording = FileOutputStream(recordingFile)

        if (reuse) {
            say("Still on Wi-Fi from the last stream")
        } else if (p.overWifi && !(if (p.glassesHost) bringUpGlassesNetwork() else bringUpWifi(p.groupBand))) {
            finish()
            return
        }
        // A Wi-Fi session has not subscribed to anything yet; the Meta app does this before it starts an app.
        wifi?.submit(::subscribeToDeviceStatus)
        if (_status.value.phase != LivePhase.RESTARTING) _status.value = LiveStatus(LivePhase.CAMERA)

        say("Starting ${p.app.label} (app ${p.app.id}) on the glasses")
        val started = CompletableDeferred<Long>().also { startAnswer = it }
        send { it.sendOnService(28, MT_START_APP, Wire.fieldVarint(1, p.app.id.toLong())) }
        val code = withTimeoutOrNull(7_000) { started.await() }
        say(if (code == null) "No answer from the app manager in 7 s, going on" else "App manager: ${snappWords(code)}")

        say("Session settings: ${p.session.label}, don check ${if (p.bypassDonCheck) "bypassed" else "on"}")
        send { it.sendOnService(SVC_MEDIA_STREAMING_CONTROL, StreamType.SESSION_SETTINGS, sessionSettings(p.session.id, 30, 600, p.bypassDonCheck, allowOcr = p.liveAi)) }

        // A restart asks for what the pilot had reached, not what the stream began with.
        val r = _rung.value ?: Rung(p.quality, p.bitrate, p.frameRate)
        var config = VideoConfig(width = r.quality.width, height = r.quality.height, bitrate = r.bitrate, frameRate = r.frameRate, iFrameInterval = if (p.liveAi) 0 else 1)
        lastConfig = config
        var configured = ask(StreamType.CONFIGURE_VIDEO_STREAM_REQUEST) { configureVideo(it, config) }
        say("Configure video ${r.quality.label}, ${r.bitrate / 1000} kbit/s, ${r.frameRate} fps: ${configured.words()}")
        // Too much for these glasses: the pilot walks down until they take one.
        while (p.auto && configured != null && configured.code != 0L) {
            val next = pilot.refused(System.currentTimeMillis()) ?: break
            _rung.value = next
            requested = next.quality
            config = VideoConfig(width = next.quality.width, height = next.quality.height, bitrate = next.bitrate, frameRate = next.frameRate, iFrameInterval = 1)
            lastConfig = config
            configured = ask(StreamType.CONFIGURE_VIDEO_STREAM_REQUEST) { configureVideo(it, config) }
            say("Configure video ${next.quality.label}, ${next.bitrate / 1000} kbit/s, ${next.frameRate} fps: ${configured.words()}")
        }

        // The glasses' microphones, as the Meta app asks for them: 48 kHz stereo AAC-LC. Live AI hears on svc15 instead.
        val audio = ask(StreamType.CONFIGURE_AUDIO_STREAM_REQUEST) { configureAudio(it, LIVE_AUDIO, enabled = !p.liveAi) }
        say("Configure audio ${if (p.liveAi) "off" else "48 kHz stereo AAC"}: ${audio.words()}")

        val control = ask(StreamType.CONTROL_STREAM_REQUEST) { controlStream(it, StreamAction.START) }
        commandedAt = System.currentTimeMillis()
        say("Start: ${control.words()}")
    }

    /**
     * Starts the same stream again when video stops for 4 s or the Wi-Fi link drops, but only after
     * it had worked: a stream that never produced a frame is left alone instead of retried forever.
     */
    private suspend fun watch() {
        var tick = 0
        while (wanted) {
            delay(1_000)
            if (++tick % 2 == 0) {
                // Followed over adb: logcat -s VisorLive.
                val s = receiver.stats.value
                Log.i(
                    "VisorLive",
                    "frames=${s.frames} fps=${s.fps} kbit/s=${s.bytesPerSecond * 8 / 1000} delay=${s.delayMs} gap=${s.maxGapMs} " +
                        "shown=${shownFps.value} skipped=${skipped.value} wait=${waitMs.value} rx=${(System.currentTimeMillis() - meter.lastRxAt)}ms ago",
                )
            }
            val now = System.currentTimeMillis()
            val last = receiver.lastFrameAt
            if (last > startedAt && _status.value.phase != LivePhase.RUNNING && now - last < 2_000) {
                _status.value = LiveStatus(LivePhase.RUNNING)
                if (_since.value == 0L) _since.value = now
            }
            if (params?.auto == true && _status.value.phase == LivePhase.RUNNING && job?.isActive != true) steer(now)
            if (!wanted || job?.isActive == true) continue
            if (last > startedAt) {
                recovering = false
                attempts = 0
            }
            val why = when {
                linkDropped -> "the Wi-Fi link dropped"
                last > startedAt && now - last > 4_000 -> "no video for ${(now - last) / 1000} s"
                recovering && commandedAt > startedAt && now - commandedAt > 12_000 -> "the restart brought no video"
                recovering && commandedAt < startedAt -> "the restart did not get the stream going"
                else -> null
            } ?: continue
            say("Stalled: $why. ${stallWords(now)}")
            if (++attempts > 5) {
                say("Giving up after 5 restarts")
                stop()
                fail(LiveProblem.GAVE_UP)
                return
            }
            if (params?.let { it.overWifi && !it.glassesHost } == true && ++groupStalls >= 2) _suggestGlassesHost.value = true
            say("Restarting the stream (attempt $attempts)")
            _status.value = LiveStatus(LivePhase.RESTARTING)
            recovering = true
            restart()
        }
    }

    /** One second of the pilot: a new rung goes to the glasses as a configure, as the Meta app changes bitrate. */
    private fun steer(now: Long) {
        val holdSize = outputs.recording.value != null || outputs.broadcast.running
        val next = pilot.step(now, receiver.stats.value, _device.value?.thermal?.health ?: _health.value.thermal, supported, holdSize) ?: return
        val before = _rung.value
        _rung.value = next
        if (before?.quality != next.quality) {
            requested = next.quality
            generation.value += 1
        }
        val config = VideoConfig(width = next.quality.width, height = next.quality.height, bitrate = next.bitrate, frameRate = next.frameRate, iFrameInterval = 1)
        lastConfig = config
        scope.launch {
            // A stall can tear the session down first; the restart asks for the new rung itself.
            if (noSession) return@launch
            val answer = ask(StreamType.CONFIGURE_VIDEO_STREAM_REQUEST) { configureVideo(it, config) }
            say("Quality to ${next.quality.label}, ${next.bitrate / 1000} kbit/s, ${next.frameRate} fps: ${answer.words()}")
        }
    }

    private fun fail(problem: LiveProblem) {
        // Whatever broke, glasses put in the case while it was starting will not go on.
        _status.value = LiveStatus(LivePhase.FAILED, if (inCase()) LiveProblem.IN_CASE else problem)
    }

    private fun inCase() = connection.state.value.device?.let { it.battery?.charging == true && it.hinge != "OPEN" } == true

    // A Wi-Fi stream between sessions: its stream went with the old one, and Bluetooth has none (it answers 0xc001).
    private val noSession: Boolean get() = params?.overWifi == true && wifi == null

    /** Which layer went quiet: the link, the DataX messages on it, or only the video. */
    private fun stallWords(now: Long): String {
        if (params?.overWifi != true) return ""
        fun ago(t: Long) = if (t == 0L) "never" else "${(now - t) / 1000} s ago"
        return "Last bytes on the Wi-Fi link ${ago(meter.lastRxAt)}, last message ${ago(messageAt)}, " +
            "last keepalive answer ${ago(keepaliveAnsweredAt)}, last frame ${ago(receiver.lastFrameAt)}"
    }

    private suspend fun restart() {
        val p = params ?: return
        job?.cancel()
        tearDownWifi()
        if (connection.state.value.link != LinkState.READY) {
            withTimeoutOrNull(30_000) { connection.state.first { it.link == LinkState.READY } }
                ?: return say("Bluetooth did not come back; trying again shortly")
        }
        if (wanted) job = scope.launch { run(p) }
    }

    fun stop() {
        // Once is enough: a second call while the first tears down, or after, does nothing.
        if (stopping?.isActive == true || (!wanted && !_running.value)) return
        wanted = false
        watchdog?.cancel()
        _status.value = LiveStatus()
        outputs.onStreamEnded()
        stopping = scope.launch {
            job?.cancel()
            if (wifi != null || (!noSession && connection.state.value.link == LinkState.READY)) {
                val answer = ask(StreamType.CONTROL_STREAM_REQUEST) { controlStream(it, StreamAction.STOP) }
                say("Stop: ${answer.words()}")
            }
            finish()
        }
    }

    /** Wi-Fi goes at once, so the Bluetooth session is back as soon as the Live ends. */
    private suspend fun finish() {
        receiver.recording?.close()
        receiver.recording = null
        tearDownWifi()
        _running.value = false
        val stats = receiver.stats.value
        say("Stopped after ${stats.frames} frames, ${sizeWords(stats.bytes)}. Raw stream in ${recordingFile.name}")
    }

    /** Put the glasses on this phone's Wi-Fi Direct group and open a session there. False if it did not work. */
    private suspend fun bringUpWifi(band: Int): Boolean {
        when {
            !wifiIsOn(context) -> return false.also { say("Turn on Wi-Fi first"); fail(LiveProblem.WIFI_OFF) }
            !wifiDirectAllowed(context) -> return false.also { say("Allow Wi-Fi Direct first"); fail(LiveProblem.WIFI_DIRECT_DENIED) }
        }
        val target = connection.state.value.target ?: return false.also { fail(LiveProblem.NOT_CONNECTED) }
        val bundle = withContext(Dispatchers.IO) { store.all().firstOrNull { it.serial == target.serial } }
            ?: return false.also { say("These glasses have no key on this phone"); fail(LiveProblem.NO_KEY) }
        return try {
            val g = PhoneGroup(context).also { group = it }
            say("Starting Wi-Fi Direct")
            val mhz = g.create(band)
            groupBand = band
            say("The phone's group is up on $mhz MHz")
            val own = phoneWifiMhz(context)
            if (radioTakesTurns(context, own, mhz)) say("The phone's own Wi-Fi is on $own MHz: its radio takes turns between that and the group")
            var result: JoinResult? = null
            // Fresh out of a group, or of a Wi-Fi firmware restart, they answer INTERNAL_ERROR for up to half a minute.
            for (attempt in 1..JOIN_TRIES) {
                if (attempt > 1) delay(4_000)
                val answer = CompletableDeferred<JoinResult>().also { joined = it }
                connection.submit { joinAccessPoint(it, g.ssid, g.passphrase, BW_20MHZ) }
                say("Asking the glasses to join ${g.ssid}" + if (attempt > 1) " (try $attempt)" else "")
                val r = withTimeoutOrNull(45_000) { answer.await() }
                if (r == null) {
                    say("The glasses did not answer the Wi-Fi join")
                    continue
                }
                if (r.ok) {
                    result = r
                    break
                }
                say("The glasses could not join Wi-Fi Direct (${r.statusName})")
            }
            if (result == null) return false.also { if (_status.value.phase != LivePhase.RESTARTING) fail(LiveProblem.JOIN_FAILED) }
            say("The glasses joined at ${result.address}:${result.port}")
            if (_status.value.phase != LivePhase.RESTARTING) _status.value = LiveStatus(LivePhase.GLASSES)
            openWifiSession(target, bundle, checkNotNull(result.address), result.port)
        } catch (unavailable: WifiUnavailable) {
            say("Wi-Fi Direct: ${unavailable.message}")
            if (_status.value.phase != LivePhase.RESTARTING) fail(LiveProblem.JOIN_FAILED)
            false
        } finally {
            joined = null
        }
    }

    /**
     * The glasses host the network and the phone joins it: their own 2.4 GHz network at 20 MHz, where they keep the
     * width they are asked for. Android asks the person to allow the join. False if it did not work.
     */
    private suspend fun bringUpGlassesNetwork(): Boolean {
        if (!wifiIsOn(context)) return false.also { say("Turn on Wi-Fi first"); fail(LiveProblem.WIFI_OFF) }
        val target = connection.state.value.target ?: return false.also { fail(LiveProblem.NOT_CONNECTED) }
        val bundle = withContext(Dispatchers.IO) { store.all().firstOrNull { it.serial == target.serial } }
            ?: return false.also { say("These glasses have no key on this phone"); fail(LiveProblem.NO_KEY) }
        val random = SecureRandom()
        val ssid = "Visor-" + (1..4).joinToString("") { "%x".format(random.nextInt(16)) }
        val password = (1..20).joinToString("") { NETWORK_ALPHABET[random.nextInt(NETWORK_ALPHABET.length)].toString() }
        return try {
            val answer = CompletableDeferred<SoftApInfo>().also { hosted = it to (ssid to password) }
            connection.submit { createSoftAp(it, ssid, password, countryCode(context)) }
            say("Asking the glasses to host a network")
            val info = withTimeoutOrNull(30_000) { answer.await() }
                ?: return false.also { say("The glasses did not answer"); if (_status.value.phase != LivePhase.RESTARTING) fail(LiveProblem.JOIN_FAILED) }
            if (!info.ok) return false.also { say("The glasses could not host a network (${info.statusName})"); if (_status.value.phase != LivePhase.RESTARTING) fail(LiveProblem.JOIN_FAILED) }
            onGlassesNetwork = true
            say("The glasses host ${info.ssid} at ${info.address}:${info.port}; joining it")
            val net = GlassesNetwork(context).also { glassesNetwork = it }
            val network = net.join(info.ssid, info.password)
            // A VPN that apps may not bypass refuses every socket on another network.
            runCatching { network.socketFactory.createSocket().close() }.exceptionOrNull()?.let { refused ->
                say("A VPN on this phone keeps Visor off the glasses' network (${refused.message})")
                fail(LiveProblem.VPN)
                return false
            }
            say("The phone is on the glasses' network")
            if (_status.value.phase != LivePhase.RESTARTING) _status.value = LiveStatus(LivePhase.GLASSES)
            openWifiSession(target, bundle, checkNotNull(info.address), info.port, network.socketFactory)
        } catch (unavailable: WifiUnavailable) {
            say("Wi-Fi: ${unavailable.message}")
            if (_status.value.phase != LivePhase.RESTARTING) fail(LiveProblem.JOIN_FAILED)
            false
        } finally {
            hosted = null
        }
    }

    /** The stream that runs on the phone's group goes on over the glasses' own network instead. */
    fun useGlassesHost() {
        val p = params ?: return
        if (!p.overWifi || p.glassesHost) return
        params = p.copy(glassesHost = true)
        _suggestGlassesHost.value = false
        say("Switching to the glasses' own network")
        if (wanted) scope.launch {
            _status.value = LiveStatus(LivePhase.RESTARTING)
            restart()
        }
    }

    /** A session with the glasses at [address]:[port] over Wi-Fi, its socket from [sockets] when given. */
    private suspend fun openWifiSession(
        target: GlassesTarget,
        bundle: com.macedonga.visor.core.identity.IdentityBundle,
        address: String,
        port: Int,
        sockets: javax.net.SocketFactory? = null,
    ): Boolean {
        val host = hosts.create(GlassesTarget(GlassesLink.Wifi(address, port, sockets), target.serial), bundle)
        val ready = CompletableDeferred<Boolean>()
        // Connect first and let go of Bluetooth after: the window to connect is short.
        host.start(scope)
        wifiEvents = scope.launch {
            host.events.collect { event ->
                when (event) {
                    is LinkStateChanged -> when (event.state) {
                        LinkState.READY -> ready.complete(true)
                        LinkState.LOST, LinkState.DISCONNECTED -> if (ready.isCompleted && !tearingDown) {
                            say("The Wi-Fi link dropped" + if (event.reason.isNotBlank()) ": ${event.reason}" else "")
                            linkDropped = true
                        }
                        else -> Unit
                    }
                    is ServiceData -> {
                        messageAt = System.currentTimeMillis()
                        _wifiData.tryEmit(event)
                        onWifiMessage(event)
                    }
                    is ProtocolError -> say("Wi-Fi: ${event.detail}")
                    else -> Unit
                }
            }
        }
        wifi = host
        connection.pause("Paused while the live view runs over Wi-Fi")
        return if (withTimeoutOrNull(45_000) { ready.await() } != true) {
            say("The glasses did not open a session over Wi-Fi")
            if (_status.value.phase != LivePhase.RESTARTING) fail(LiveProblem.SESSION_FAILED)
            false
        } else {
            say("Connected over Wi-Fi")
            keepalive = scope.launch { keepAlive(host) }
            host.submit { _wifiSession.value = it }
            true
        }
    }

    private fun onWifiMessage(event: ServiceData) {
        when {
            event.serviceId == SVC_MEDIA_STREAMING_CONTROL -> Unit
            event.serviceId == SVC_PEER_BUILD_INFO && event.messageType == MT_PEER_DEVICE_INFO ->
                keepaliveAnsweredAt = System.currentTimeMillis()
            event.serviceId == 28 && event.messageType == MT_START_APP_RESPONSE ->
                startAnswer?.complete(Wire.parse(event.payload).varint(1))
            event.serviceId == 28 && isDeviceStateType(event.messageType) -> runCatching {
                val update = decodeDeviceStateUpdate(event.messageType, event.payload, display = isDisplayBuild(connection.state.value.info?.build)).state
                _device.update { it?.let { base -> mergeDeviceState(base, update) } ?: update }
            }
            // Airship, whose receiver reports for itself; everything else goes in the log.
            event.serviceId == 28 || event.serviceId == SVC_AIRSHIP -> Unit
            // The Display's screencast, which reads its own.
            event.serviceId == SVC_HN_SCREENCAST || event.serviceId == SVC_HN_SCREENCAST_MANAGER -> Unit
            else -> say(
                "Wi-Fi: service ${event.serviceId} type ${event.messageType}, ${event.payload.size} bytes " +
                    event.payload.take(16).joinToString("") { "%02x".format(it) },
            )
        }
    }

    /**
     * Every 2 s, the empty svc100 announce the glasses always answer with their device info. It keeps
     * traffic flowing from the phone, and an answer missing for 8 s means the session is dead.
     */
    private suspend fun keepAlive(host: SessionHost) {
        keepaliveAnsweredAt = 0
        val since = System.currentTimeMillis()
        var told = false
        while (wifi === host) {
            host.submit { it.sendOnService(SVC_PEER_BUILD_INFO, MT_PEER_APP_INFO, ByteArray(0)) }
            delay(2_000)
            val answered = keepaliveAnsweredAt
            val now = System.currentTimeMillis()
            when {
                // Only an answer that stops counts; a session that never answered may simply not host svc100.
                answered > 0 && now - answered > 8_000 && !linkDropped && wifi === host -> {
                    say("The glasses stopped answering over Wi-Fi (${(now - answered) / 1000} s)")
                    linkDropped = true
                }
                answered == 0L && now - since > 8_000 && !told -> {
                    say("svc100 does not answer over Wi-Fi; the keepalive only sends")
                    told = true
                }
            }
        }
    }

    private suspend fun tearDownWifi() {
        keepalive?.cancel()
        keepalive = null
        tearingDown = true
        _wifiSession.value = null
        val host = wifi
        wifi = null
        if (host != null) {
            // Leave the group before it goes, as the Meta app does, so the next join finds their Wi-Fi free; their own
            // network they are asked to close instead.
            if (!linkDropped) {
                val leave = if (onGlassesNetwork) MT_DESTROY_SOFT_AP else MT_LEAVE_ACCESS_POINT
                host.submit { runCatching { it.sendOnService(SVC_ATC, leave, ByteArray(0)) } }
                delay(300)
            }
            host.stop()
            wifiEvents?.cancel()
            wifiEvents = null
        }
        group?.remove()
        group = null
        glassesNetwork?.leave()
        glassesNetwork = null
        if (host != null) {
            connection.resume()
            say("Back on Bluetooth")
        }
        // Over Wi-Fi they may not have heard it; they close their network on their own soon after anyway.
        if (onGlassesNetwork) connection.submit { runCatching { it.sendOnService(SVC_ATC, MT_DESTROY_SOFT_AP, ByteArray(0)) } }
        onGlassesNetwork = false
        tearingDown = false
    }

    /**
     * Configure the stream again with the same settings. The Meta app does this mid-stream to change
     * bitrate; the encoder restarts on a keyframe, which is what a stalled decoder needs.
     */
    fun requestKeyframe() {
        val config = lastConfig ?: return
        val now = System.currentTimeMillis()
        if (!_running.value || noSession || now - keyframeAskedAt < 2_000) return
        keyframeAskedAt = now
        scope.launch {
            val answer = ask(StreamType.CONFIGURE_VIDEO_STREAM_REQUEST) { configureVideo(it, config) }
            say("No keyframe for a while; configured again: ${answer.words()}")
        }
    }

    /**
     * A photo from the camera while it streams, as the glasses encode it: a HEIC at their frame-capture size.
     * Asked for with a UserEventNotification; null if none came in a few seconds.
     */
    suspend fun capturePhoto(): ByteArray? {
        if (!_running.value || noSession) return null
        return coroutineScope {
            val photo = async(start = CoroutineStart.UNDISPATCHED) { receiver.photos.first() }
            send { it.sendOnService(SVC_MEDIA_STREAMING_CONTROL, StreamType.USER_EVENT_NOTIFICATION, requestPhotoCapture()) }
            say("Asked the glasses for a photo")
            withTimeoutOrNull(PHOTO_WAIT_MS) { photo.await() }.also {
                photo.cancel()
                say(if (it == null) "No photo came in ${PHOTO_WAIT_MS / 1000} s" else "A photo came, ${it.size / 1000} KB")
            }
        }
    }

    /**
     * [work] on a Wi-Fi Direct session with the glasses, for a transfer too large for Bluetooth, which pauses
     * meanwhile. Null when it ran, else why not.
     */
    suspend fun overWifi(work: suspend (DataXConnection) -> String?): String? {
        if (_running.value || wifi != null) return "the live view is using Wi-Fi"
        if (!bringUpWifi(BAND_2GHZ)) return "Wi-Fi Direct did not come up"
        return try {
            val mux = withTimeoutOrNull(10_000) { wifiSession.first { it != null } } ?: return "no session over Wi-Fi"
            work(mux)
        } finally {
            tearDownWifi()
            _status.value = LiveStatus()
        }
    }

    /** A line in the log from outside, such as the decoder. */
    fun note(text: String) = say(text)

    /** Run on the Wi-Fi session while there is one, else on Bluetooth. */
    private fun send(work: suspend (DataXConnection) -> Unit) {
        val host = wifi
        if (host != null) host.submit(work) else connection.submit(work)
    }

    private suspend fun ask(type: Int, body: (Int) -> ByteArray): StreamAnswer? {
        val n = nonce++
        val answer = CompletableDeferred<StreamAnswer>().also { answers[n.toLong()] = it }
        send { it.sendOnService(SVC_MEDIA_STREAMING_CONTROL, type, body(n)) }
        return withTimeoutOrNull(5_000) { answer.await() }.also { answers.remove(n.toLong()) }
    }

    private fun onControl(message: DataXMessage) {
        val type = message.info.messageType
        when (type) {
            StreamType.CONFIGURE_VIDEO_STREAM_RESPONSE, StreamType.CONTROL_STREAM_RESPONSE,
            StreamType.CONFIGURE_AUDIO_STREAM_RESPONSE -> {
                val answer = parseStreamAnswer(message.payload)
                answers[answer.nonce]?.complete(answer) ?: say("Answer type $type for nonce ${answer.nonce}: ${answer.words()}")
            }
            StreamType.CONTROL_STREAM_STOPPED -> {
                val reason = parseStreamStopped(message.payload)
                say("The glasses stopped the stream: ${stopReasonWords(reason)}")
                _stopped.tryEmit(reason)
            }
            StreamType.SYSTEM_HEALTH_EVENT -> {
                val h = parseHealthEvent(message.payload)
                when (h.type) {
                    2L -> _health.update { it.copy(battery = h.level.toInt()) }
                    3L -> _health.update { it.copy(thermal = healthThermalWords(h.status)) }
                }
                say("Health: type ${h.type}, status ${h.status}, level ${h.level}")
            }
            // SupportedConfiguration: 1 nonce, 2 videoQuality, the ceiling the glasses offer right now.
            StreamType.SUPPORTED_CONFIGURATION_UPDATE_REQUEST -> {
                val q = Wire.parse(message.payload).varint(2)
                supported = q.toInt()
                say("The glasses support up to ${listOf("LOW", "MEDIUM", "HIGH").getOrElse(q.toInt()) { "quality $q" }}")
            }
            StreamType.CODEC_CONFIGURATION_UPDATE_REQUEST -> {
                val f = Wire.parse(message.payload)
                say("Codec config, type ${f.varint(2)}, ${f.bytes(3).size} bytes, NAL types ${nalTypes(f.bytes(3))}")
            }
            else -> say("Type $type, ${message.payload.size} bytes: ${message.payload.take(24).joinToString("") { "%02x".format(it) }}")
        }
    }

    private fun StreamAnswer?.words(): String = when {
        this == null -> "no answer in 5 s"
        code == 0L -> "ok"
        else -> streamErrorWords(code) + if (reason.isNotEmpty()) " ($reason)" else ""
    }

    private fun say(text: String) {
        _log.update { (it + text).takeLast(200) }
        _notes.tryEmit(text)
    }
}

/** Heat and battery from the stream's SystemHealthEvents; null until one says. */
data class StreamHealth(val thermal: String? = null, val battery: Int? = null)

private const val JOIN_TRIES = 8
private const val PHOTO_WAIT_MS = 8_000L

// The audio every Live asks for: AAC-LC at 48 kHz, stereo.
private val LIVE_AUDIO = AudioConfig()

/** The Wi-Fi Direct group's band, as [PhoneGroup.create] takes it. */
const val BAND_AUTO = 0
const val BAND_2GHZ = 2
const val BAND_5GHZ = 5

private fun snappWords(code: Long): String = when (code) {
    1L -> "started"
    12L -> "already running"
    5L -> "unsupported app"
    6L -> "not enough battery"
    7L -> "thermal throttling"
    9L -> "start failed"
    13L -> "not enough priority"
    15L -> "denied by peak power"
    else -> "error $code"
}

/** The HEVC NAL unit types in an Annex-B buffer. */
fun nalTypes(data: ByteArray): List<Int> {
    val out = ArrayList<Int>()
    var i = 0
    while (i + 3 < data.size) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
            out += (data[i + 3].toInt() shr 1) and 0x3F
            i += 3
        } else {
            i++
        }
    }
    return out
}

// For the glasses' network's password: letters and digits only, as Android takes any.
private const val NETWORK_ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
