package com.macedonga.visor.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What [finishVideo] did to a video. */
internal class Finished(val steadied: Boolean, val landscape: Boolean)

/**
 * [input] as the gallery keeps it, in [output]: steadied on the phone when [steadyOnPhone] says so and the glasses did not
 * ([steadiedOnGlasses] says by its size), with its motion logs here, cut to a 4:3 band when [landscape]. One re-encode,
 * HEVC, the sound as it was. Null when there is nothing to do; throws when it cannot be done.
 */
@OptIn(UnstableApi::class)
internal fun finishVideo(
    context: Context,
    input: File,
    output: File,
    imu: File,
    frames: File,
    steadiness: Int,
    landscape: Boolean,
    steadyOnPhone: () -> Boolean,
    steadiedOnGlasses: (Int, Int) -> Boolean,
): Finished? {
    val retriever = MediaMetadataRetriever()
    val (width, height, bitrate) = try {
        retriever.setDataSource(input.path)
        fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
        // The glasses write portrait frames with no rotation; one that has it is not theirs to change.
        if (int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) != 0) return null
        Triple(int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), int(MediaMetadataRetriever.METADATA_KEY_BITRATE))
    } finally {
        retriever.release()
    }
    check(width > 0 && height > 0) { "no video track" }
    val cut = landscape && width < height
    val steady = steadyOnPhone() && !steadiedOnGlasses(width, height)
    if (!cut && !steady) return null
    val warps = Steady.plan(width, height, frameTimesUs(input).toLongArray(), readImu(imu), readFrames(frames), steadiness, steady, cut) ?: return null
    val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
        .setEffects(Effects(emptyList(), listOf(WarpEffect(warps))))
        .build()
    val share = warps.outWidth.toDouble() * warps.outHeight / (width.toDouble() * height)
    val encoders = DefaultEncoderFactory.Builder(context)
        .apply { if (bitrate > 0) setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate((bitrate * share).toInt()).build()) }
        .build()
    val thread = HandlerThread("finish-video").apply { start() }
    val done = CountDownLatch(1)
    var failure: ExportException? = null
    var transformer: Transformer? = null
    try {
        // A Transformer lives on the thread whose looper it was built with.
        val handler = Handler(thread.looper)
        handler.post {
            transformer = Transformer.Builder(context)
                .setLooper(thread.looper)
                .setVideoMimeType(MimeTypes.VIDEO_H265)
                .setEncoderFactory(encoders)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) = done.countDown()

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        failure = exportException
                        done.countDown()
                    }
                })
                .build()
                .also { it.start(item, output.path) }
        }
        if (!done.await(EXPORT_MINUTES, TimeUnit.MINUTES)) {
            handler.post { transformer?.cancel() }
            error("it took over $EXPORT_MINUTES minutes")
        }
        failure?.let { throw it }
    } finally {
        thread.quitSafely()
    }
    return Finished(warps.steadied, cut)
}

private const val EXPORT_MINUTES = 15L

/** Each frame drawn through its own warp. */
@OptIn(UnstableApi::class)
private class WarpEffect(private val warps: Warps) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = try {
        WarpProgram(warps, useHdr)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
}

@OptIn(UnstableApi::class)
private class WarpProgram(private val warps: Warps, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(VERTEX, FRAGMENT).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int) = Size(warps.outWidth, warps.outHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uWarp", warps.at(presentationTimeUs))
            program.setFloatsUniform("uOutSize", floatArrayOf(warps.outWidth.toFloat(), warps.outHeight.toFloat()))
            program.setFloatsUniform("uInSize", floatArrayOf(warps.inWidth.toFloat(), warps.inHeight.toFloat()))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        // Texture rows run bottom up, the warps' pixels top down.
        const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform mat3 uWarp;
uniform vec2 uOutSize;
uniform vec2 uInSize;
varying vec2 vTexSamplingCoord;
void main() {
  vec3 p = uWarp * vec3(vTexSamplingCoord.x * uOutSize.x, (1.0 - vTexSamplingCoord.y) * uOutSize.y, 1.0);
  vec2 q = p.xy / p.z;
  gl_FragColor = texture2D(uTexSampler, clamp(vec2(q.x / uInSize.x, 1.0 - q.y / uInSize.y), 0.0, 1.0));
}
"""
    }
}
