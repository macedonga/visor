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
    val intent: String,
) {
    val glassesCaptured: Boolean get() = inline != null || imageUuid.isNotEmpty() || captureHandle.isNotEmpty()

    companion object {
        fun from(request: Map<Int, Any?>): Visual {
            val understanding = request[2].asStruct()
            val context = understanding[4].asStruct()
            val onDevice = request[55].asStruct()
            val inline = context[4].asBytes().takeIf { it.size > 100 } ?: context[16].asBytes().takeIf { it.size > 100 }
            val intent = onDevice[1].asText()
            return Visual(
                inline = inline,
                imageUuid = context[3].asText(),
                captureHandle = context[8].asText(),
                // IMPLICIT_DESCRIBE_IMAGE ("what can I cook with this") sets none of the flags, but it is a picture question.
                glassesWantImage = context[20] == true || understanding[5] == true || onDevice[8] == true || "IMAGE" in intent,
                intent = intent,
            )
        }
    }
}

/** A question that is about what the wearer is looking at. */
internal fun soundsVisual(transcript: String): Boolean {
    val t = transcript.lowercase()
    return VISUAL_WORDS.any { it in t }
}

private val VISUAL_WORDS = listOf(
    "look", "see", "seeing", "read this", "read that", "read the", "describe", "what is this", "what's this",
    "what is that", "what's that", "in front of me", "identify", "what am i", "which is this", "how much is this",
    "translate this", "picture", "photo",
    "guarda", "vedi", "vedo", "leggi", "descrivi", "cos'è questo", "cos'è quello", "che cos'è", "cosa c'è",
    "davanti a me", "inquadr", "foto", "cosa sto",
)

/**
 * Whatever the glasses sent, as a JPEG a model takes: the file may be a bare JPEG or HEIC, or
 * packed behind the assistant's event data, so it is searched for an image and re-encoded.
 */
internal fun toModelJpeg(bytes: ByteArray, maxSide: Int = 1568): ByteArray? {
    val bitmap = decodeAt(bytes, 0)
        ?: indexOf(bytes, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))?.let { decodeAt(bytes, it) }
        ?: indexOf(bytes, "ftyp".toByteArray())?.takeIf { it >= 4 }?.let { decodeAt(bytes, it - 4) }
        ?: return null
    val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
    val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    val out = ByteArrayOutputStream()
    sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return out.toByteArray()
}

private fun decodeAt(bytes: ByteArray, offset: Int): Bitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, offset, bytes.size - offset) }.getOrNull()

private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return null
}
