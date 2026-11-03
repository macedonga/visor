package com.macedonga.visor.core.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A recording in progress: when it began, by the wall clock. */
data class RecordingState(val since: Long)

/**
 * Where the stream goes besides the screen: the replay ring always, an MP4 while recording, an RTMP server while
 * broadcasting. Fed by [LiveStream] with every frame and audio packet as it arrives.
 */
class LiveOutputs(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    val replay = ReplayRing(DEFAULT_REPLAY_US)
    val broadcast = Broadcast(scope)

    private val _recording = MutableStateFlow<RecordingState?>(null)
    val recording: StateFlow<RecordingState?> = _recording.asStateFlow()

    /** The last thing saved, for the screen to show; a video or a photo. */
    private val _saved = MutableStateFlow<Uri?>(null)
    val saved: StateFlow<Uri?> = _saved.asStateFlow()

    @Volatile private var mp4: Mp4Recording? = null
    @Volatile var config: ByteArray? = null
        private set
    @Volatile var aac: AacFormat? = null
        private set

    fun onConfig(next: ByteArray) {
        config = next
        replay.config(next)
    }

    fun onAudioFormat(format: AacFormat) {
        if (aac?.asc?.contentEquals(format.asc) == true) return
        aac = format
        synchronized(this) {
            player?.release()
            player = runCatching { AacPlayer(format) }.getOrNull()?.also { it.listening = listening }
        }
    }

    private var player: AacPlayer? = null

    /** The glasses' microphones in the phone's speaker. */
    @Volatile
    var listening = false
        set(value) {
            field = value
            synchronized(this) { player?.listening = value }
        }

    fun onVideo(input: VideoFrame) {
        val frame = VideoFrame(input.seq, rebase(input.ptsUs), input.data, input.arrivedNs, input.codec)
        replay.add(frame)
        mp4?.let { r -> runCatching { synchronized(r) { r.video(frame) } } }
        broadcast.video(frame)
    }

    fun onAudio(input: AudioPacket) {
        val packet = AudioPacket(input.ptsUs + shift, input.data)
        replay.add(packet)
        mp4?.let { r -> runCatching { synchronized(r) { r.audio(packet) } } }
        broadcast.audio(packet)
        if (listening) synchronized(this) { player?.feed(input) }
    }

    // A restarted stream starts its clock again; everything downstream wants one clock that only goes forward.
    @Volatile private var shift = 0L
    private var lastIn = -1L
    private var lastOut = -1L

    private fun rebase(pts: Long): Long {
        if (lastIn >= 0 && (pts <= lastIn || pts - lastIn > JUMP_US)) shift = lastOut + FRAME_US - pts
        lastIn = pts
        lastOut = pts + shift
        return lastOut
    }

    /** Starts writing an MP4 from the next keyframe. False if the stream has not said what it is yet. */
    fun startRecording(): Boolean {
        val c = config ?: return false
        if (mp4 != null) return true
        val now = System.currentTimeMillis()
        mp4 = runCatching { Mp4Recording(context, c, aac, "Visor_${stamp(now)}.mp4", now) }.getOrNull() ?: return false
        _recording.value = RecordingState(now)
        return true
    }

    suspend fun stopRecording(): Uri? = withContext(Dispatchers.IO) {
        val r = mp4 ?: return@withContext null
        mp4 = null
        _recording.value = null
        synchronized(r) { r.finish() }.also { _saved.value = it }
    }

    /** What the replay ring holds, written out as its own video. Null if there was nothing to save. */
    suspend fun saveReplay(): Uri? = lock.withLock {
        withContext(Dispatchers.IO) {
            val held = replay.take() ?: return@withContext null
            val duration = (held.video.last().ptsUs - held.video.first().ptsUs) / 1000
            val takenAt = System.currentTimeMillis() - duration
            val r = Mp4Recording(context, held.config, aac.takeIf { held.audio.isNotEmpty() }, "Visor_${stamp(takenAt)}_replay.mp4", takenAt)
            // Interleaved by time, as the muxer prefers.
            var a = 0
            for (frame in held.video) {
                while (a < held.audio.size && held.audio[a].ptsUs <= frame.ptsUs) r.audio(held.audio[a++])
                r.video(frame)
            }
            r.finish().also { _saved.value = it }
        }
    }

    /** The picture now, decoded from the ring and saved to Pictures/Visor. */
    suspend fun saveFrame(): Uri? = withContext(Dispatchers.IO) {
        val jpeg = replay.lastPicture()?.let { grabJpeg(it, quality = 95) } ?: return@withContext null
        savePhoto(jpeg, System.currentTimeMillis())
    }

    /** A photo into Pictures/Visor, as the gallery lists it: a JPEG from the screen, or the glasses' own HEIC. */
    fun savePhoto(bytes: ByteArray, takenAt: Long, heic: Boolean = false): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Visor_${stamp(takenAt)}" + if (heic) ".heic" else ".jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, if (heic) "image/heic" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Visor")
            put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        _saved.value = uri
        return uri
    }

    /** The person stopped the stream: a recording in progress is closed and kept, a broadcast ends. */
    fun onStreamEnded() {
        scope.launch { stopRecording() }
        broadcast.stop()
    }

    /** A new stream: the ring starts empty. */
    fun onStreamStarted() {
        replay.clear()
    }

    private fun stamp(at: Long) = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(at))

    companion object {
        const val DEFAULT_REPLAY_US = 120_000_000L
        private const val JUMP_US = 3_000_000L
        private const val FRAME_US = 33_000L
    }
}
