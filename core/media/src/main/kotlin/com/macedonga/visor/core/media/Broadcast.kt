package com.macedonga.visor.core.media

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where a broadcast goes: a server address like rtmp://a.rtmp.youtube.com/live2 and its stream key. */
data class BroadcastTarget(val url: String, val key: String, val codec: BroadcastCodec)

enum class BroadcastPhase { OFF, CONNECTING, LIVE, RECONNECTING, FAILED }

data class BroadcastState(
    val phase: BroadcastPhase = BroadcastPhase.OFF,
    /** When it went live, by the wall clock; 0 before. */
    val since: Long = 0,
    val kbps: Int = 0,
    val problem: String = "",
)

/**
 * The stream going out to an RTMP server while it runs: frames are queued here and sent from one coroutine, so a
 * slow network never holds up the glasses. Behind by more than a few seconds, it drops video up to the next
 * keyframe. A dropped connection is retried a few times before it gives up.
 */
class Broadcast(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(BroadcastState())
    val state: StateFlow<BroadcastState> = _state.asStateFlow()

    private sealed interface Item
    private class Video(val frame: VideoFrame) : Item
    private class Audio(val packet: AudioPacket) : Item

    private var queue = Channel<Item>(Channel.UNLIMITED)
    private val queued = AtomicLong()
    private var job: Job? = null
    @Volatile private var skipToKey = false

    val running: Boolean get() = job?.isActive == true

    fun start(target: BroadcastTarget, config: ByteArray, frameRate: Int, bitrate: Int, audio: () -> AacFormat?) {
        if (running) return
        queue = Channel(Channel.UNLIMITED)
        queued.set(0)
        _state.value = BroadcastState(BroadcastPhase.CONNECTING)
        job = scope.launch(Dispatchers.IO) { run(target, config, frameRate, bitrate, audio) }
    }

    fun video(frame: VideoFrame) {
        if (!running) return
        if (queued.get() > BEHIND_BYTES) skipToKey = true
        if (skipToKey) {
            if (!frame.isKey || queued.get() > BEHIND_BYTES / 2) return
            skipToKey = false
        }
        queued.addAndGet(frame.data.size.toLong())
        queue.trySend(Video(frame))
    }

    fun audio(packet: AudioPacket) {
        if (running) queue.trySend(Audio(packet))
    }

    fun stop() {
        job?.cancel()
        job = null
        queue.close()
        _state.value = BroadcastState()
    }

    private suspend fun run(target: BroadcastTarget, config: ByteArray, frameRate: Int, bitrate: Int, audioFormat: () -> AacFormat?) {
        var tries = 0
        while (scope.isActive) {
            val rtmp = RtmpPublisher(target.url, target.key)
            var transcoder: AvcTranscoder? = null
            try {
                rtmp.connect(scope)
                // H.264 from the glasses goes out as it is; HEVC goes out as HEVC, or is re-encoded to H.264.
                val input = configCodec(config)
                val out = if (input == VideoCodec.AVC) BroadcastCodec.H264 else target.codec
                val (width, height) = videoSize(config) ?: (1080 to 1920)
                val audio = audioFormat()
                rtmp.metadata(width, height, frameRate, out, audio)
                if (audio != null) rtmp.audioHeader(audio)
                var base = -1L
                fun ms(pts: Long) = ((pts - base) / 1000).coerceAtLeast(0)
                if (out == BroadcastCodec.HEVC) {
                    rtmp.videoHeader(BroadcastCodec.HEVC, checkNotNull(hvcC(config)) { "the stream's parameter sets do not parse" })
                } else if (input == VideoCodec.AVC) {
                    rtmp.videoHeader(BroadcastCodec.H264, checkNotNull(avcC(config)) { "the stream's parameter sets do not parse" })
                } else {
                    var headerSent = false
                    transcoder = AvcTranscoder(width, height, config, bitrate, frameRate,
                        onConfig = { csd ->
                            avcC(csd)?.let { rtmp.videoHeader(BroadcastCodec.H264, it) }
                            headerSent = true
                        },
                        onFrame = { f -> if (headerSent && base >= 0) runCatching { rtmp.video(BroadcastCodec.H264, ms(f.ptsUs), f.key, annexBToLengthPrefixed(f.data)) } },
                    )
                }
                _state.update { it.copy(phase = BroadcastPhase.LIVE, since = if (it.since == 0L) System.currentTimeMillis() else it.since, problem = "") }
                tries = 0
                var sent = 0L
                var windowStart = System.currentTimeMillis()
                for (item in queue) {
                    when (item) {
                        is Video -> {
                            queued.addAndGet(-item.frame.data.size.toLong())
                            if (base < 0) {
                                if (!item.frame.isKey) continue
                                base = item.frame.ptsUs
                            }
                            if (transcoder != null) {
                                check(!transcoder.failed) { "the phone's H.264 encoder stopped" }
                                transcoder.feed(item.frame)
                            } else {
                                val units = annexBToLengthPrefixed(item.frame.data, dropParameterSets = out == BroadcastCodec.HEVC)
                                rtmp.video(out, ms(item.frame.ptsUs), item.frame.isKey, units)
                            }
                            sent += item.frame.data.size
                        }
                        is Audio -> if (base >= 0 && item.packet.ptsUs >= base) {
                            rtmp.audio(ms(item.packet.ptsUs), item.packet.data)
                            sent += item.packet.data.size
                        }
                    }
                    rtmp.failure?.let { error(it) }
                    val now = System.currentTimeMillis()
                    if (now - windowStart >= 1_000) {
                        val kbps = (sent * 8 / (now - windowStart)).toInt()
                        _state.update { it.copy(kbps = kbps) }
                        sent = 0
                        windowStart = now
                    }
                }
                return
            } catch (e: Exception) {
                if (!scope.isActive || job?.isCancelled == true) return
                val why = rtmp.failure ?: e.message ?: e.javaClass.simpleName
                if (++tries > RETRIES) {
                    _state.update { it.copy(phase = BroadcastPhase.FAILED, problem = why) }
                    queue.close()
                    return
                }
                _state.update { it.copy(phase = BroadcastPhase.RECONNECTING, problem = why) }
                delay(2_000L shl (tries - 1))
                // What piled up while away is old news; start again from a keyframe.
                while (queue.tryReceive().isSuccess) Unit
                queued.set(0)
                skipToKey = true
            } finally {
                transcoder?.release()
                rtmp.close()
            }
        }
    }

    private companion object {
        const val RETRIES = 5
        /** About eight seconds of 4 Mbit/s video waiting to go out. */
        const val BEHIND_BYTES = 4_000_000L
    }
}
