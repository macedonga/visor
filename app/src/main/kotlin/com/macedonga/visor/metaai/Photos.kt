package com.macedonga.visor.metaai

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The diary's photos, decoded and cached in memory. */
object DiaryPhotos {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(path: String, maxSide: Int): ImageBitmap? = cache.get("$path:$maxSide")

    suspend fun load(path: String, maxSide: Int): ImageBitmap? = cached(path, maxSide) ?: withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            ?.also { cache.put("$path:$maxSide", it) }
    }
}

@Composable
fun rememberDiaryPhoto(path: String?, maxSide: Int = 1080): State<ImageBitmap?> =
    produceState(path?.let { DiaryPhotos.cached(it, maxSide) }, path, maxSide) {
        value = path?.let { DiaryPhotos.cached(it, maxSide) }
        if (path != null && value == null) value = DiaryPhotos.load(path, maxSide)
    }

/** A picture's colour as one value, for the light to lean towards. */
fun averageColor(image: ImageBitmap): Color {
    val bitmap = image.asAndroidBitmap()
    val px = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    var r = 0L
    var g = 0L
    var b = 0L
    for (p in px) {
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
    }
    val n = px.size.coerceAtLeast(1)
    return Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}
