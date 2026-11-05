package com.macedonga.visor.core.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** What a fulfilment request says about the camera. */
internal class Visual(
    // UnderstandingResult.visualContext (2.4): the image itself, when it is small enough to ship inline.
    val inline: ByteArray?,
    val imageUuid: String,
    val captureHandle: String,
    // The glasses decided this question needs the camera (visualContext.requiresImageCapture,
    // UnderstandingResult.imageCaptureConfirmation, OnDeviceIntent.multimodalInvocationConfirmed).
    val glassesWantImage: Boolean,
) {
    val glassesCaptured: Boolean get() = inline != null || imageUuid.isNotEmpty() || captureHandle.isNotEmpty()

    companion object {
        val NONE = Visual(null, "", "", false)

        fun from(request: Map<Int, Any?>): Visual {
            val understanding = request[2].asStruct()
            val context = understanding[4].asStruct()
            val onDevice = request[55].asStruct()
            val inline = context[4].asBytes().takeIf { it.size > 100 } ?: context[16].asBytes().takeIf { it.size > 100 }
            return Visual(
                inline = inline,
                imageUuid = context[3].asText(),
                captureHandle = context[8].asText(),
                glassesWantImage = context[20] == true || understanding[5] == true || onDevice[8] == true,
            )
        }
    }
}

/** A question that is about what the wearer is looking at. */
internal fun soundsVisual(transcript: String): Boolean = VISUAL.containsMatchIn(transcript.lowercase())

private val VISUAL_WORDS = listOf(
    "look", "looking", "see", "seeing", "read this", "read that", "read the", "describe", "what is this", "what's this",
    "what is that", "what's that", "in front of me", "identify", "what am i", "which is this", "how much is this",
    "translate this", "picture", "pictures", "photo", "photos",
    "guarda", "vedi", "vedo", "leggi", "leggimi", "descrivi", "descrivimi", "cos'è questo", "cos'è quello", "che cos'è", "cosa c'è",
    "davanti a me", "foto", "cosa sto",
    "nhìn thấy", "đang nhìn", "trước mặt", "cái gì đây", "đây là gì", "cái này là gì", "đọc giúp", "đọc cái", "mô tả",
    "dịch cái này", "tấm ảnh", "bức ảnh",
)

// Whole words only, so "seems" or "outlook" do not count; "inquadr" takes any ending.
private val VISUAL = Regex("(?<!\\p{L})(" + VISUAL_WORDS.joinToString("|") { Regex.escape(it) } + "|inquadr\\p{L}*)(?!\\p{L})")

/**
 * Whatever the glasses sent, as a JPEG a model takes: the file may be a bare JPEG or HEIC, or
 * packed behind the assistant's event data, so it is searched for an image and re-encoded.
 */
internal fun toModelJpeg(bytes: ByteArray, maxSide: Int = 1568): ByteArray? {
    val bitmap = decodeAt(bytes, 0, maxSide)
        ?: indexOf(bytes, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))?.let { decodeAt(bytes, it, maxSide) }
        ?: indexOf(bytes, "ftyp".toByteArray())?.takeIf { it >= 4 }?.let { decodeAt(bytes, it - 4, maxSide) }
        ?: return null
    val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
    val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    val out = ByteArrayOutputStream()
    sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return out.toByteArray()
}

// Decoded at the largest power-of-two reduction that keeps the long side at least [maxSide].
private fun decodeAt(bytes: ByteArray, offset: Int, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, offset, bytes.size - offset, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeByteArray(bytes, offset, bytes.size - offset, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return null
}
