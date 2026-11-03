package com.macedonga.visor.core.media

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The picture at the end of [picture] as a JPEG, decoded on a decoder of its own from the keyframe before it, so
 * it works with no screen showing the stream. Null if the decoder gave nothing.
 */
suspend fun grabJpeg(picture: Replay, quality: Int = 92): ByteArray? = withContext(Dispatchers.Default) {
    val (width, height) = videoSize(picture.config) ?: return@withContext null
    val videoCodec = picture.video.firstOrNull()?.codec ?: configCodec(picture.config)
    val format = MediaFormat.createVideoFormat(videoCodec.mime, width, height).apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(picture.config))
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
    }
    val codec = MediaCodec.createDecoderByType(videoCodec.mime)
    try {
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var next = 0
        var last: ByteArray? = null
        var ended = false
        val deadline = System.currentTimeMillis() + 4_000
        while (!ended && System.currentTimeMillis() < deadline) {
            if (next <= picture.video.size) {
                val i = codec.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    if (next == picture.video.size) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        val f = picture.video[next]
                        val buffer = codec.getInputBuffer(i)!!
                        buffer.clear()
                        buffer.put(f.data)
                        codec.queueInputBuffer(i, 0, f.data.size, f.ptsUs, 0)
                    }
                    next++
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5_000)
            if (o >= 0) {
                if (info.size > 0) codec.getOutputImage(o)?.use { last = jpegOf(it, width, height, quality) }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
            }
        }
        last
    } catch (_: Exception) {
        null
    } finally {
        runCatching { codec.stop() }
        codec.release()
    }
}

/** A YUV_420_888 image to JPEG through NV21, the one layout YuvImage takes. */
private fun jpegOf(image: Image, width: Int, height: Int, quality: Int): ByteArray {
    val w = minOf(width, image.width)
    val h = minOf(height, image.height)
    val nv21 = ByteArray(w * h * 3 / 2)
    val (y, u, v) = image.planes
    var o = 0
    for (row in 0 until h) {
        val base = row * y.rowStride
        for (col in 0 until w) nv21[o++] = y.buffer.get(base + col * y.pixelStride)
    }
    for (row in 0 until h / 2) {
        for (col in 0 until w / 2) {
            nv21[o++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
            nv21[o++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
        }
    }
    val out = ByteArrayOutputStream()
    YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
    return out.toByteArray()
}
