package com.macedonga.visor.gallery

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded thumbnails and screen-sized pictures in one LRU cache. */
object Images {
    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(key: String): ImageBitmap? = cache.get(key)

    /** A square-ish thumbnail at least [THUMB_PX] on its short side. */
    suspend fun thumb(context: Context, item: GalleryItem, previews: Map<String, File>): ImageBitmap? = load(thumbKey(item)) {
        val phone = item.phone
        if (phone != null) {
            context.contentResolver.loadThumbnail(phone.uri, Size(THUMB_PX, THUMB_PX), null).asImageBitmap()
        } else {
            item.capture?.preview?.let { previews[it.id] }?.let { ImageDecoder.decodeBitmap(ImageDecoder.createSource(it)).asImageBitmap() }
        }
    }

    /** The phone copy decoded to fit within [maxSide] pixels on its long side, upright. */
    suspend fun picture(context: Context, item: GalleryItem, maxSide: Int): ImageBitmap? {
        val phone = item.phone ?: return null
        return load(pictureKey(item, maxSide)) {
            if (phone.video) {
                context.contentResolver.loadThumbnail(phone.uri, Size(maxSide, maxSide), null).asImageBitmap()
            } else {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, phone.uri)) { decoder, info, _ ->
                    val long = max(info.size.width, info.size.height)
                    if (long > maxSide) {
                        val scale = maxSide.toFloat() / long
                        decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                    }
                }.asImageBitmap()
            }
        }
    }

    /** A gallery thumbnail by its uri, [px] square, video posters included. */
    suspend fun thumb(context: Context, uri: Uri, px: Int): ImageBitmap? = load(thumbKey(uri, px)) {
        context.contentResolver.loadThumbnail(uri, Size(px, px), null).asImageBitmap()
    }

    /** A preview file scaled to fit within [px] on its long side. */
    suspend fun preview(file: File, px: Int): ImageBitmap? = load(previewKey(file, px)) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val scale = px.toFloat() / max(info.size.width, info.size.height)
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
        }.asImageBitmap()
    }

    private suspend fun load(key: String, decode: () -> ImageBitmap?): ImageBitmap? {
        cache.get(key)?.let { return it }
        val image = withContext(Dispatchers.IO) { runCatching { decode() }.getOrNull() } ?: return null
        cache.put(key, image)
        return image
    }

    fun forget(item: GalleryItem) {
        val uri = item.phone?.uri ?: return
        for (key in cache.snapshot().keys) if (key.startsWith("t:$uri:") || key.startsWith("p:$uri:")) cache.remove(key)
    }

    /** A phone copy's keys carry its size, so an edited photo is decoded again. */
    fun thumbKey(item: GalleryItem) = "t:" + (item.phone?.let { "${it.uri}:${it.size}" } ?: item.key)

    fun pictureKey(item: GalleryItem, maxSide: Int) = "p:${item.phone?.uri}:${item.phone?.size}:$maxSide"

    fun thumbKey(uri: Uri, px: Int) = "t:$uri:${px}px"

    fun previewKey(file: File, px: Int) = "f:${file.path}:${file.lastModified()}:$px"

    private const val CACHE_BYTES = 96 * 1024 * 1024
    const val THUMB_PX = 320
}

@Composable
fun rememberThumb(item: GalleryItem?, previews: Map<String, File>): State<ImageBitmap?> {
    val context = LocalContext.current
    val key = item?.phone?.uri ?: item?.capture?.preview?.let { previews[it.id] }
    return produceState(item?.let { Images.cached(Images.thumbKey(it)) }, item?.key, key, item?.phone?.size) {
        if (item != null) Images.thumb(context, item, previews)?.let { value = it }
    }
}

/** The thumbnail at once, then the picture at [maxSide] when it is decoded. */
@Composable
fun rememberPicture(item: GalleryItem?, previews: Map<String, File>, maxSide: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState(item?.let { Images.cached(Images.pictureKey(it, maxSide)) ?: Images.cached(Images.thumbKey(it)) }, item?.key, item?.phone?.uri, item?.phone?.size, maxSide) {
        if (item == null) return@produceState
        if (value == null) Images.thumb(context, item, previews)?.let { value = it }
        Images.picture(context, item, maxSide)?.let { value = it }
    }
}
