package com.macedonga.visor.core.identity

import java.net.HttpURLConnection
import java.net.URL

/** A reply's status and body. */
class HttpReply(val status: Int, val body: ByteArray)

/** A POST; error statuses come back as replies, not exceptions. */
fun interface HttpPost {
    fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpReply
}

/** 30 s connect and read timeouts. */
object UrlConnectionPost : HttpPost {

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return HttpReply(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MILLIS = 30_000
}

/** Python's `urllib.parse.urlencode(fields)` for a dict of strings, in insertion order. */
fun pyUrlencode(fields: Map<String, String>): String =
    fields.entries.joinToString("&") { (key, value) -> pyQuotePlus(key) + "=" + pyQuotePlus(value) }

/** Python's `urllib.parse.quote_plus`, which `urlencode` applies to every key and value. */
private fun pyQuotePlus(value: String): String {
    val out = StringBuilder()
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        when {
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "_.-~" -> out.append(c)
            c == ' ' -> out.append('+')
            else -> out.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
