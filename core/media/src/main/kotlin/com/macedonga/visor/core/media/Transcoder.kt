package com.macedonga.visor.core.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One H.264 frame out of [AvcTranscoder]: Annex-B, on the glasses' clock. */
class AvcFrame(val ptsUs: Long, val key: Boolean, val data: ByteArray)

/**
 * The glasses' HEVC re-encoded to H.264 for services that take nothing else: a hardware decoder draws straight
 * into a hardware encoder's input surface, so no frame passes through memory. [onConfig] gets the SPS and PPS,
 * [onFrame] every frame, both on the encoder's thread.
 */
class AvcTranscoder(
    width: Int,
    height: Int,
    hevcConfig: ByteArray,
    bitrate: Int,
    frameRate: Int,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (AvcFrame) -> Unit,
) {
    private val thread = HandlerThread("avc-transcoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val encoder = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }
        .getOrElse { thread.quitSafely(); throw it }
    private val decoder = runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC) }
        .getOrElse { encoder.release(); thread.quitSafely(); throw it }
    private val freeInputs = LinkedBlockingQueue<Int>()
    private var surface: Surface? = null

    @Volatile
    var failed = false
        private set

    init {
        try {
            val out = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                // A frame is made only when one comes in; without this some encoders repeat the last one.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, -1)
            }
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    runCatching {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(bytes)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) onConfig(bytes)
                            else onFrame(AvcFrame(info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, bytes))
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    failed = true
                }
            }, handler)
            encoder.configure(out, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val drawn = encoder.createInputSurface().also { surface = it }
            encoder.start()

            val input = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(hevcConfig))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 shl 20)
            }
            decoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    freeInputs.offer(index)
                }

                // Drawn into the encoder with its own time, which the encoder takes as the frame's.
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    runCatching { codec.releaseOutputBuffer(index, info.presentationTimeUs * 1000) }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    failed = true
                }
            }, handler)
            decoder.configure(input, drawn, null, 0)
            decoder.start()
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    fun feed(frame: VideoFrame) {
        if (failed) return
        try {
            val index = freeInputs.poll(200, TimeUnit.MILLISECONDS) ?: return
            val buffer = decoder.getInputBuffer(index) ?: return
            buffer.clear()
            if (buffer.capacity() < frame.data.size) {
                decoder.queueInputBuffer(index, 0, 0, frame.ptsUs, 0)
                return
            }
            buffer.put(frame.data)
            decoder.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
        } catch (_: IllegalStateException) {
            failed = true
        }
    }

    fun release() {
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        surface?.release()
        surface = null
        thread.quitSafely()
    }
}
