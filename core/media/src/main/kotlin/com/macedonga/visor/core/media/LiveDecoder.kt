package com.macedonga.visor.core.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hardware HEVC or H.264 decoding onto a Surface, one decoder per stream, asynchronous so a decoded frame is
 * shown the moment it exists rather than when the next one arrives. It is configured for the size
 * asked for with room up to 1920 either way, because a decoder fed a stream larger than its
 * configured size draws garbage on many phones.
 */
class LiveDecoder(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val onSize: (Int, Int) -> Unit,
) {

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private val freeInputs = LinkedBlockingQueue<Int>()

    // Frames decoded only to keep the reference chain whole, by presentation time; not shown.
    private val hidden = ConcurrentHashMap.newKeySet<Long>()

    /** Frames rendered so far. */
    @Volatile
    var shown = 0L
        private set

    /** The codec reported an error; this decoder is done and a new one is needed. */
    @Volatile
    var failed = false
        private set

    val started: Boolean get() = codec != null

    /**
     * Decode one frame, and show it only if [show]. False when it was not used, and the caller should
     * wait for a keyframe.
     */
    fun feed(frame: VideoFrame, config: ByteArray?, show: Boolean = true): Boolean {
        val c = codec ?: run {
            if (!frame.isKey) return false
            start(config, frame.codec)
        }
        if (frame.data.size > MAX_INPUT) return false
        return queue(c, frame.data, frame.ptsUs, 0).also { if (it && !show) hidden.add(frame.ptsUs) }
    }

    /** New parameter sets mid-stream, as the glasses send them when their encoder is reconfigured. */
    fun feedConfig(config: ByteArray): Boolean {
        val c = codec ?: return false
        return queue(c, config, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
    }

    private fun queue(c: MediaCodec, data: ByteArray, ptsUs: Long, flags: Int): Boolean = try {
        val index = freeInputs.poll(500, TimeUnit.MILLISECONDS)
        val buffer = index?.let { c.getInputBuffer(it) }
        when {
            index == null || buffer == null -> false
            buffer.capacity() < data.size -> {
                c.queueInputBuffer(index, 0, 0, ptsUs, 0)
                false
            }
            else -> {
                buffer.clear()
                buffer.put(data)
                c.queueInputBuffer(index, 0, data.size, ptsUs, flags)
                true
            }
        }
    } catch (dead: IllegalStateException) {
        failed = true
        false
    }

    private fun start(config: ByteArray?, videoCodec: VideoCodec): MediaCodec {
        val format = MediaFormat.createVideoFormat(videoCodec.mime, width, height)
        format.setInteger(MediaFormat.KEY_MAX_WIDTH, MAX_SIDE)
        format.setInteger(MediaFormat.KEY_MAX_HEIGHT, MAX_SIDE)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        if (config != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(config))

        val t = HandlerThread("live-decoder").also { it.start(); thread = it }
        return MediaCodec.createDecoderByType(videoCodec.mime).also {
            it.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    freeInputs.offer(index)
                }

                // Shown now, not at the presentation time: that is on the glasses' clock, and a
                // Surface holds a frame stamped up to a second ahead until then.
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val show = !hidden.remove(info.presentationTimeUs)
                    runCatching { if (show) codec.releaseOutputBuffer(index, System.nanoTime()) else codec.releaseOutputBuffer(index, false) }
                    if (show) shown++
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    val w = if (format.containsKey("crop-right")) format.getInteger("crop-right") - format.getInteger("crop-left") + 1 else format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1 else format.getInteger(MediaFormat.KEY_HEIGHT)
                    onSize(w, h)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    failed = true
                }
            }, Handler(t.looper))
            it.configure(format, surface, null, 0)
            it.start()
            codec = it
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        thread?.quitSafely()
        thread = null
        freeInputs.clear()
        hidden.clear()
    }

    private companion object {
        const val MAX_SIDE = 1920
        const val MAX_INPUT = 4 shl 20
    }
}
