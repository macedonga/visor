package com.macedonga.visor.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.log.EventLog
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * svc41111, internet_proxy_remote, HTTP relay: the Display sends an HttpRequest (type 8) on a channel of its own, and the
 * phone answers on that channel with an HttpResponse (type 11), the body in chunks (12), then an empty end (13). The reply
 * types follow the Display's reader of HttpResponse (GreatwhiteSystemUIRelease, X/C0OQ.java). The request type 8 and a
 * streamed request body (chunks until 13) are not in the research; they are guessed from that reader.
 */
@Singleton
class PhoneHttpRelay @Inject constructor(
    private val requests: RequestLog,
    private val log: Provider<EventLog>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> log.get().add("Internet: a request failed: $e") })
    // Requests whose body the Display streams after them, by channel.
    private val streaming = ConcurrentHashMap<Int, Streamed>()

    fun handle(message: DataXMessage, connection: DataXConnection) {
        val channel = message.info.channelId
        when (message.info.messageType) {
            MT_REQUEST -> {
                val request = runCatching { Request.decode(message.payload) }.getOrElse {
                    scope.launch { answer(channel, connection, error = true) }
                    return
                }
                if (request.streamed) streaming[channel] = Streamed(request, connection, ByteArrayOutputStream())
                else scope.launch { serve(channel, request, null, connection) }
            }
            MT_BODY -> streaming[channel]?.body?.write(message.payload)
            MT_END -> streaming.remove(channel)?.let { scope.launch { serve(channel, it.request, it.body.toByteArray(), it.mux) } }
        }
    }

    fun forget(channel: Int) {
        streaming.remove(channel)
    }

    private suspend fun serve(channel: Int, request: Request, body: ByteArray?, mux: DataXConnection) {
        val id = requests.start(request.url, SOURCE_TUNNEL, request.method)
        var answered = false
        try {
            val url = URL(request.url)
            require(url.protocol == "http" || url.protocol == "https") { "not an http link" }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                instanceFollowRedirects = !request.disableRedirects
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                for ((name, value) in request.headers) setRequestProperty(name, value)
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body) }
                }
            }
            val code = conn.responseCode
            mux.send(DataXConnection.mirror(channel), response(code, conn.responseMessage.orEmpty(), conn), messageType = MT_RESPONSE)
            answered = true
            var sent = 0L
            (if (code >= 400) conn.errorStream else conn.inputStream)?.use { input ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    mux.send(DataXConnection.mirror(channel), buffer.copyOf(n), messageType = MT_BODY)
                    sent += n
                }
            }
            mux.send(DataXConnection.mirror(channel), ByteArray(0), messageType = MT_END)
            requests.finish(id, "HTTP $code", failed = code >= 400, bytes = sent)
        } catch (e: Exception) {
            requests.finish(id, "failed: ${e.message}", failed = true)
            answer(channel, mux, error = !answered)
        }
    }

    /** The reply that ends a request the phone could not serve: an error and the end, so the Display stops waiting. */
    private suspend fun answer(channel: Int, mux: DataXConnection, error: Boolean) {
        val reply = DataXConnection.mirror(channel)
        runCatching {
            if (error) mux.send(reply, Wire.fieldVarint(4, ERROR_FAILED), messageType = MT_RESPONSE)
            mux.send(reply, ByteArray(0), messageType = MT_END)
        }
    }

    private fun response(code: Int, message: String, conn: HttpURLConnection): ByteArray {
        var out = Wire.fieldVarint(1, code.toLong()) + Wire.fieldBytes(2, message.toByteArray())
        for ((name, values) in conn.headerFields) {
            if (name == null) continue
            for (value in values) out += Wire.fieldBytes(3, Wire.fieldBytes(1, name.toByteArray()) + Wire.fieldBytes(2, value.toByteArray()))
        }
        return out
    }

    private class Request(
        val url: String,
        val method: String,
        val headers: List<Pair<String, String>>,
        val streamed: Boolean,
        val disableRedirects: Boolean,
    ) {
        companion object {
            // The request's uuid (field 1) is not needed: each request has its own channel.
            fun decode(payload: ByteArray): Request {
                val f = Wire.parse(payload)
                return Request(
                    url = f.bytes(2).decodeToString(),
                    method = f.bytes(7).decodeToString().ifEmpty { "GET" },
                    headers = f.repeatedBytes(5).map { h -> Wire.parse(h).let { it.bytes(1).decodeToString() to it.bytes(2).decodeToString() } },
                    streamed = f.varint(6) != 0L,
                    disableRedirects = f.varint(8) != 0L,
                )
            }
        }
    }

    private class Streamed(val request: Request, val mux: DataXConnection, val body: ByteArrayOutputStream)

    companion object {
        private const val MT_REQUEST = 8
        private const val MT_RESPONSE = 11
        private const val MT_BODY = 12
        private const val MT_END = 13
        // HttpResponse.error for a request the phone could not make. The Display's error codes are not in the research.
        private const val ERROR_FAILED = 1L
        // The chunk size the data fetcher uses by default, which the Display already reads.
        private const val CHUNK = 1024
        private const val CONNECT_MS = 10_000
        private const val READ_MS = 30_000
        // The request log's source for traffic of the tunnel rather than of the data fetcher.
        private const val SOURCE_TUNNEL = -1
    }
}
