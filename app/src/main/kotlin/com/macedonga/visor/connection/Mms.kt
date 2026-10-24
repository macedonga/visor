package com.macedonga.visor.connection

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One picture, with an optional caption, as an MMS through Android's MMS service: an M-Send.req (OMA MMS 1.2) with a
 * multipart/related body of SMIL, the JPEG and the text. Android files what a non-default app sends in the SMS store.
 */
object Mms {
    /** Sends [image] to [to]; null when the carrier took it, else why not. */
    suspend fun send(context: Context, to: String, image: ByteArray, caption: String): String? {
        val jpeg = fit(image, maxBytes(context)) ?: return "The picture could not be read"
        val pdu = sendReq(to, jpeg, caption)
        val dir = File(context.cacheDir, "mms").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.pdu").apply { writeBytes(pdu) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        // The MMS service reads the message from the uri in its own process.
        for (pkg in listOf("com.android.phone", "com.android.mms.service")) {
            runCatching { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val action = "${context.packageName}.MMS_SENT.${UUID.randomUUID()}"
        val result = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                result.complete(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        return try {
            val sent = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms.sendMultimediaMessage(context, uri, null, null, sent)
            when (val code = withTimeoutOrNull(SEND_TIMEOUT_MS) { result.await() }) {
                null -> "No answer from the phone's MMS service"
                Activity.RESULT_OK -> null
                else -> "Not sent (MMS error $code)"
            }
        } catch (e: Exception) {
            e.message ?: e.toString()
        } finally {
            context.unregisterReceiver(receiver)
            file.delete()
        }
    }

    // SmsManager's MMS config needs no permission; the carrier config would need the phone state.
    private fun maxBytes(context: Context): Int {
        val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val max = runCatching { sms.carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0) }.getOrDefault(0)
        return (if (max > 0) max else DEFAULT_MAX_BYTES) - PDU_ROOM
    }

    /** The picture as a JPEG under [maxBytes]: scaled to at most 1600 px, then lower quality until it fits. */
    private fun fit(image: ByteArray, maxBytes: Int): ByteArray? {
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(image))) { decoder, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                if (side > MAX_SIDE) decoder.setTargetSampleSize((side + MAX_SIDE - 1) / MAX_SIDE)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return null
        var scaled = bitmap
        while (true) {
            for (quality in intArrayOf(85, 70, 55, 40)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= maxBytes) return out.toByteArray()
            }
            if (scaled.width < 320) return null
            scaled = Bitmap.createScaledBitmap(scaled, scaled.width * 3 / 4, scaled.height * 3 / 4, true)
        }
    }

    private fun sendReq(to: String, jpeg: ByteArray, caption: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x8C.toByte(), 0x80.toByte())) // X-Mms-Message-Type: m-send-req
        out.write(0x98) // X-Mms-Transaction-ID
        out.write(text(UUID.randomUUID().toString().replace("-", "")))
        out.write(byteArrayOf(0x8D.toByte(), 0x92.toByte())) // X-Mms-MMS-Version: 1.2
        out.write(byteArrayOf(0x89.toByte(), 0x01, 0x81.toByte())) // From: insert-address-token
        out.write(0x97) // To
        out.write(text("${to.filter { it.isDigit() || it == '+' }}/TYPE=PLMN"))
        // Content-Type, last of the headers: multipart/related, start <smil>, type application/smil.
        out.write(0x84)
        out.write(valueLength(byteArrayOf(0xB3.toByte()) + byteArrayOf(0x8A.toByte()) + text("<smil>") + byteArrayOf(0x89.toByte()) + text("application/smil")))

        val parts = mutableListOf(
            part(text("application/smil"), "smil.xml", "<smil>", smil(caption.isNotBlank()).encodeToByteArray()),
            // image/jpeg; name=image.jpg
            part(valueLength(byteArrayOf(0x9E.toByte(), 0x85.toByte()) + text("image.jpg")), "image.jpg", "<image>", jpeg),
        )
        if (caption.isNotBlank()) {
            // text/plain; charset=utf-8
            parts += part(valueLength(byteArrayOf(0x83.toByte(), 0x81.toByte(), 0xEA.toByte())), "text.txt", "<text>", caption.encodeToByteArray())
        }
        out.write(uintvar(parts.size.toLong()))
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** One body part: its encoded content type, then Content-Location and Content-ID, then the data. */
    private fun part(contentType: ByteArray, location: String, id: String, data: ByteArray): ByteArray {
        val headers = contentType + byteArrayOf(0x8E.toByte()) + text(location) + byteArrayOf(0xC0.toByte(), 0x22) + id.encodeToByteArray() + byteArrayOf(0)
        return uintvar(headers.size.toLong()) + uintvar(data.size.toLong()) + headers + data
    }

    private fun smil(withText: Boolean): String =
        "<smil><head><layout><root-layout/>" +
            "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>" +
            "<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>" +
            "</layout></head><body><par dur=\"5000ms\"><img src=\"image.jpg\" region=\"Image\"/>" +
            (if (withText) "<text src=\"text.txt\" region=\"Text\"/>" else "") +
            "</par></body></smil>"

    /** Text-string: a leading byte at or above 0x80 is quoted, and the string ends with a zero. */
    private fun text(s: String): ByteArray {
        val b = s.encodeToByteArray()
        return (if (b.isNotEmpty() && (b[0].toInt() and 0xFF) >= 0x80) byteArrayOf(0x7F) else ByteArray(0)) + b + byteArrayOf(0)
    }

    private fun valueLength(value: ByteArray): ByteArray =
        (if (value.size <= 30) byteArrayOf(value.size.toByte()) else byteArrayOf(31) + uintvar(value.size.toLong())) + value

    private fun uintvar(v: Long): ByteArray {
        var n = v
        val groups = ArrayList<Int>()
        do {
            groups.add((n and 0x7F).toInt())
            n = n shr 7
        } while (n > 0)
        return ByteArray(groups.size) { i -> (groups[groups.size - 1 - i] or if (i < groups.size - 1) 0x80 else 0).toByte() }
    }

    private const val DEFAULT_MAX_BYTES = 300 * 1024
    private const val PDU_ROOM = 8 * 1024
    private const val MAX_SIDE = 1600
    private const val SEND_TIMEOUT_MS = 120_000L
}
