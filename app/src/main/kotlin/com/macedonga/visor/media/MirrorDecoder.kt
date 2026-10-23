package com.macedonga.visor.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.macedonga.visor.core.media.ScreencastFrame

/** H.264 decoding of the Display's screen onto a Surface. One thread only; starts on a parameter set or key frame. */
class MirrorDecoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private var lastPtsUs = -1L
    private val info = MediaCodec.BufferInfo()

    fun feed(frame: ScreencastFrame) {
        val c = codec ?: (if (frame.key || frame.config) start() else null) ?: return
        try {
            val i = c.dequeueInputBuffer(INPUT_WAIT_US)
            if (i >= 0) {
                c.getInputBuffer(i)!!.apply { clear(); put(frame.data) }
                lastPtsUs = maxOf(frame.timestampMs * 1000, lastPtsUs + 1)
                val flags = if (frame.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                c.queueInputBuffer(i, 0, frame.data.size, lastPtsUs, flags)
            }
            drain(c)
        } catch (e: RuntimeException) {
            // A codec error: the next key frame starts a new decoder.
            release()
        }
    }

    fun release() {
        codec?.let { runCatching { it.stop(); it.release() } }
        codec = null
    }

    private fun start(): MediaCodec? = try {
        MediaCodec.createDecoderByType(MIME).apply {
            configure(MediaFormat.createVideoFormat(MIME, MAX_W, MAX_H), surface, null, 0)
            setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            start()
        }.also { codec = it }
    } catch (e: Exception) {
        null
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (o >= 0) c.releaseOutputBuffer(o, true)
        }
    }

    private companion object {
        const val MIME = "video/avc"
        // The size the Display picks is read from the stream's parameter sets; this is only the most it may pick.
        const val MAX_W = 1920
        const val MAX_H = 1080
        const val INPUT_WAIT_US = 10_000L
    }
}
