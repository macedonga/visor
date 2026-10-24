package com.macedonga.visor.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
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

/**
 * Landscape captures. The glasses always take the full portrait frame and mark the capture landscape; the phone keeps
 * its middle 4:3 band, the whole width across, as the Meta app's landscape crop does when it has nothing to follow.
 */
internal object Landscape {
    /** The band's share of a portrait frame's height. */
    private fun share(width: Int, height: Int) = width * 3f / 4f / height

    fun crop(bitmap: Bitmap): Bitmap {
        if (bitmap.width >= bitmap.height) return bitmap
        val band = bitmap.width * 3 / 4
        return Bitmap.createBitmap(bitmap, 0, (bitmap.height - band) / 2, bitmap.width, band)
    }

    /** [input]'s middle band, re-encoded into [output]; the sound goes across as it is. Throws when it cannot. */
    @OptIn(UnstableApi::class)
    fun crop(context: Context, input: File, output: File) {
        val retriever = MediaMetadataRetriever()
        val (width, height, bitrate) = try {
            retriever.setDataSource(input.path)
            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            val turned = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0
            val w = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            Triple(if (turned) h else w, if (turned) w else h, int(MediaMetadataRetriever.METADATA_KEY_BITRATE))
        } finally {
            retriever.release()
        }
        check(width > 0 && height > 0) { "no video track" }
        if (width >= height) {
            input.copyTo(output, overwrite = true)
            return
        }
        val share = share(width, height)
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(Effects(emptyList(), listOf(Crop(-1f, 1f, -share, share))))
            .build()
        val encoders = DefaultEncoderFactory.Builder(context)
            .apply { if (bitrate > 0) setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate((bitrate * share).toInt()).build()) }
            .build()
        val thread = HandlerThread("landscape").apply { start() }
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
                error("the crop took over $EXPORT_MINUTES minutes")
            }
            failure?.let { throw it }
        } finally {
            thread.quitSafely()
        }
    }

    private const val EXPORT_MINUTES = 15L
}
