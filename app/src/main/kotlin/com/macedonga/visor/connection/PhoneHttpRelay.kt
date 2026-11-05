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
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * svc41111, internet_proxy_remote, HTTP relay: the Display sends an HttpRequest (type 8) on a channel of its own, then a
 * streamed request's body in pieces (9) and its end (10). The phone answers on that channel with an HttpResponse
 * {1 status, 2 message, 3 headers} (11), the body in pieces (12), then the end, HttpResponse {4 error} (13): 4096 when the
 * response came whole, 4097 when it failed. A request that fails before its response gets only the end.
 */
@Singleton
class PhoneHttpRelay @Inject constructor(
    private val requests: RequestLog,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> log.add("Internet: a request failed: $e") })
    // Requests whose body the Display streams after them, by channel.
    private val streaming = ConcurrentHashMap<Int, Streamed>()

    fun handle(message: DataXMessage, connection: DataXConnection) {
        val channel = message.info.channelId
        when (message.info.messageType) {
            MT_REQUEST -> {
                val request = runCatching { Request.decode(message.payload) }.getOrElse {
                    scope.launch { failed(channel, connection) }
                    return
                }
                // Only a streamed request has a body; any other is served at once, as the Meta app does.
                if (request.streamed) streaming[channel] = Streamed(request, connection, ByteArrayOutputStream())
                else scope.launch { serve(channel, request, null, connection) }
            }
            MT_REQUEST_BODY -> streaming[channel]?.body?.write(message.payload)
            // The end may carry the body's last bytes.
            MT_REQUEST_END -> streaming.remove(channel)?.let {
                it.body.write(message.payload)
                scope.launch { serve(channel, it.request, it.body.toByteArray(), it.mux) }
            }
        }
    }

    fun forget(channel: Int) {
        streaming.remove(channel)
    }

    fun clear() = streaming.clear()

    private suspend fun serve(channel: Int, request: Request, body: ByteArray?, mux: DataXConnection) {
        val id = requests.start(request.url, RequestSource.TUNNEL, request.method)
        val reply = DataXConnection.mirror(channel)
        var conn: HttpURLConnection? = null
        try {
            val url = URL(request.url)
            require(url.protocol == "http" || url.protocol == "https") { "not an http link" }
            conn = url.openConnection() as HttpURLConnection
            conn.apply {
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
            mux.send(reply, response(code, conn.responseMessage.orEmpty(), conn), messageType = MT_RESPONSE)
            var sent = 0L
            (if (code >= 400) conn.errorStream else conn.inputStream)?.use { input ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    mux.send(reply, buffer.copyOf(n), messageType = MT_BODY)
                    sent += n
                }
            }
            mux.send(reply, Wire.fieldVarint(4, END_OK), messageType = MT_END)
            requests.finish(id, "HTTP $code", failed = code >= 400, bytes = sent)
        } catch (e: Exception) {
            requests.finish(id, "failed: ${e.message}", failed = true)
            conn?.disconnect()
            failed(channel, mux)
        }
    }

    /** The end of a request the phone could not serve, so the Display stops waiting. */
    private suspend fun failed(channel: Int, mux: DataXConnection) {
        runCatching { mux.send(DataXConnection.mirror(channel), Wire.fieldVarint(4, END_FAILED), messageType = MT_END) }
    }

    private fun response(code: Int, message: String, conn: HttpURLConnection): ByteArray {
        var out = Wire.fieldVarint(1, code.toLong()) + Wire.fieldString(2, message)
        for ((name, values) in conn.headerFields) {
            if (name == null) continue
            for (value in values) out += Wire.fieldBytes(3, Wire.fieldString(1, name) + Wire.fieldString(2, value))
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
        private const val MT_REQUEST_BODY = 9
        private const val MT_REQUEST_END = 10
        private const val MT_RESPONSE = 11
        private const val MT_BODY = 12
        private const val MT_END = 13
        // HttpResponse.error in the end: the response came whole, or the request failed.
        private const val END_OK = 4096L
        private const val END_FAILED = 4097L
        // The chunk size the data fetcher uses by default, which the Display already reads.
        private const val CHUNK = 1024
        private const val CONNECT_MS = 10_000
        private const val READ_MS = 30_000
    }
}
