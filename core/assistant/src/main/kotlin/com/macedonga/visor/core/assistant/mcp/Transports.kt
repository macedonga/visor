package com.macedonga.visor.core.assistant.mcp

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/** Why a server could not be used, sorted by what the person can do about it. */
class McpException(
    val kind: Kind,
    message: String,
    // WWW-Authenticate, for UNAUTHORIZED.
    val challenge: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Kind {
        // No connection: off the network it lives on, a wrong address, or down.
        UNREACHABLE,
        // Android refused the local network to this app.
        LOCAL_NETWORK,
        UNAUTHORIZED,
        FORBIDDEN,
        // Something answered, but not as an MCP endpoint.
        NOT_MCP,
        // The server forgot the session: initialize again.
        SESSION_GONE,
        TIMEOUT,
        PROTOCOL,
        // The server answered the request with a JSON-RPC error.
        ERROR,
    }
}

internal val JSON = "application/json".toMediaType()

/** Runs the call, cancelled for real with the coroutine; [read] gets the response, which is closed after. */
internal suspend fun <T> Call.fetch(read: (Response) -> T): T = coroutineScope {
    val call = this@fetch
    val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
    try {
        withContext(Dispatchers.IO) { call.execute().use(read) }
    } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        throw error.asMcp()
    } finally {
        watcher.cancel()
    }
}

/** An I/O failure in the words of [McpException]. */
internal fun IOException.asMcp(): McpException = when {
    this is McpException -> this
    // Android 16's local network protection refuses the connect with EPERM.
    "EPERM" in message.orEmpty() || message.orEmpty().contains("permission denied", ignoreCase = true) ->
        McpException(McpException.Kind.LOCAL_NETWORK, "Android refused the local network", cause = this)
    this is InterruptedIOException -> McpException(McpException.Kind.TIMEOUT, "the server took too long", cause = this)
    this is UnknownHostException -> McpException(McpException.Kind.UNREACHABLE, "no such address (${message.orEmpty()})", cause = this)
    this is ConnectException || this is NoRouteToHostException -> McpException(McpException.Kind.UNREACHABLE, "nothing answers there", cause = this)
    this is SSLException -> McpException(McpException.Kind.UNREACHABLE, "secure connection failed: ${message.orEmpty()}", cause = this)
    else -> McpException(McpException.Kind.UNREACHABLE, message ?: javaClass.simpleName, cause = this)
}

/** Fails with the right [McpException] unless the status is a success. */
internal fun Response.requireSuccess(session: Boolean) {
    if (isSuccessful) return
    val body = runCatching { peekBody(4096).string() }.getOrDefault("")
    val said = runCatching { JSONObject(body).let { it.optJSONObject("error")?.optString("message") ?: it.optString("error_description").ifEmpty { it.optString("error") } } }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(160).takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }
    val detail = "HTTP $code" + (said?.let { ": $it" } ?: "")
    throw when (code) {
        401 -> McpException(McpException.Kind.UNAUTHORIZED, detail, challenge = header("WWW-Authenticate"))
        403 -> McpException(McpException.Kind.FORBIDDEN, detail)
        404 -> McpException(if (session) McpException.Kind.SESSION_GONE else McpException.Kind.NOT_MCP, detail)
        405, 406, 415 -> McpException(McpException.Kind.NOT_MCP, detail)
        408, 504 -> McpException(McpException.Kind.TIMEOUT, detail)
        else -> McpException(McpException.Kind.PROTOCOL, detail)
    }
}

/** Server-Sent Events from [source], each as its name and data; reading stops when [onEvent] says false. */
internal fun readEvents(source: BufferedSource, onEvent: (event: String, data: String) -> Boolean) {
    var event = ""
    val data = StringBuilder()
    while (true) {
        val line = source.readUtf8Line() ?: break
        if (line.isEmpty()) {
            if (data.isNotEmpty() && !onEvent(event.ifEmpty { "message" }, data.removeSuffix("\n").toString())) return
            event = ""
            data.setLength(0)
            continue
        }
        if (line.startsWith(":")) continue
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> data.append(value).append('\n')
        }
    }
    if (data.isNotEmpty()) onEvent(event.ifEmpty { "message" }, data.removeSuffix("\n").toString())
}

/** One JSON-RPC message or a batch of them, as a list; anything else is no messages. */
internal fun messages(text: String): List<JSONObject> = runCatching {
    val t = text.trim()
    if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } else listOf(JSONObject(t))
}.getOrDefault(emptyList())

internal fun JSONObject.isResponseTo(id: Long): Boolean = !has("method") && opt("id")?.toString() == id.toString()

internal fun rpcMessage(method: String, params: JSONObject? = null, id: Long? = null): JSONObject = JSONObject().put("jsonrpc", "2.0")
    .apply { if (id != null) put("id", id) }.put("method", method).apply { if (params != null) put("params", params) }

/** How the messages go back and forth. Server requests and notifications go to the session's handler. */
internal interface RpcTransport {
    val kind: Transport
    val alive: Boolean
    var protocolVersion: String?

    /** Sends request [id] and returns the server's response to it, result or error. */
    suspend fun request(id: Long, message: JSONObject): JSONObject

    /** Sends a notification, or the response to a server's request. */
    suspend fun send(message: JSONObject)

    fun close()
}

/** What answers a server's own requests and takes its notifications: a response to send, or null. */
internal typealias Inbound = suspend (JSONObject) -> JSONObject?

/**
 * The Streamable HTTP transport: each message is a POST, answered with JSON or with an event stream that carries the
 * response and whatever the server says on the way.
 */
internal class StreamableHttp(
    private val url: HttpUrl,
    private val http: OkHttpClient,
    private val headers: suspend () -> Map<String, String>,
    private val inbound: Inbound,
) : RpcTransport {
    override val kind = Transport.STREAMABLE

    @Volatile
    private var session: String? = null

    @Volatile
    override var protocolVersion: String? = null

    @Volatile
    private var closed = false

    // The credentials last sent, for the DELETE that ends the session.
    @Volatile
    private var lastHeaders: Map<String, String> = emptyMap()

    override val alive: Boolean get() = !closed

    private suspend fun post(message: JSONObject): Request = Request.Builder().url(url)
        .post(message.toString().toRequestBody(JSON))
        .header("Accept", "application/json, text/event-stream")
        .apply {
            session?.let { header("Mcp-Session-Id", it) }
            protocolVersion?.let { header("MCP-Protocol-Version", it) }
            headers().also { lastHeaders = it }.forEach { (k, v) -> header(k, v) }
        }
        .build()

    override suspend fun request(id: Long, message: JSONObject): JSONObject = coroutineScope {
        // The server's own messages on the way are answered while the stream is still being read.
        val aside = Channel<JSONObject>(Channel.UNLIMITED)
        val handler = launch { for (m in aside) inbound(m)?.let { runCatching { send(it) } } }
        try {
            val sent = session != null
            http.newCall(post(message)).fetch { response ->
                response.requireSuccess(sent)
                response.header("Mcp-Session-Id")?.let { session = it }
                val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
                var reply: JSONObject? = null
                when (type) {
                    "text/event-stream" -> readEvents(response.body.source()) { _, data ->
                        for (m in messages(data)) if (m.isResponseTo(id)) reply = m else aside.trySend(m)
                        reply == null
                    }
                    "application/json" -> for (m in messages(response.body.string())) if (m.isResponseTo(id)) reply = m else aside.trySend(m)
                    else -> throw McpException(McpException.Kind.NOT_MCP, "the server answered with ${type.ifEmpty { "nothing" }}, not JSON")
                }
                reply ?: throw McpException(McpException.Kind.PROTOCOL, "the server ended its answer without a result")
            }
        } finally {
            aside.close()
            handler.join()
        }
    }

    override suspend fun send(message: JSONObject) {
        val sent = session != null
        http.newCall(post(message)).fetch { it.requireSuccess(sent) }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Ending the session is a courtesy: nothing waits for it.
        val id = session ?: return
        val request = Request.Builder().url(url).delete().header("Mcp-Session-Id", id)
            .apply {
                protocolVersion?.let { header("MCP-Protocol-Version", it) }
                lastHeaders.forEach { (k, v) -> header(k, v) }
            }.build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }
}

/**
 * The older HTTP+SSE transport (2024-11-05): one long GET whose first event names where to POST, and every response
 * coming back on that stream.
 */
internal class LegacySse(
    private val url: HttpUrl,
    private val http: OkHttpClient,
    private val stream: OkHttpClient,
    private val headers: suspend () -> Map<String, String>,
    private val inbound: Inbound,
    parent: CoroutineScope,
) : RpcTransport {
    override val kind = Transport.SSE

    @Volatile
    override var protocolVersion: String? = null

    @Volatile
    private var closed = false

    override val alive: Boolean get() = !closed

    private val job = Job(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val aside = Channel<JSONObject>(Channel.UNLIMITED)
    private var endpoint: HttpUrl? = null
    private var call: Call? = null

    /** Opens the stream and waits for the endpoint event. */
    suspend fun open() {
        val ready = CompletableDeferred<HttpUrl>()
        val request = Request.Builder().url(url).get().header("Accept", "text/event-stream")
            .apply { headers().forEach { (k, v) -> header(k, v) } }.build()
        val c = stream.newCall(request)
        call = c
        scope.launch { for (m in aside) inbound(m)?.let { runCatching { send(it) } } }
        scope.launch {
            var why: McpException = McpException(McpException.Kind.UNREACHABLE, "the server closed its event stream")
            try {
                c.execute().use { response ->
                    response.requireSuccess(false)
                    val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
                    if (type != "text/event-stream") throw McpException(McpException.Kind.NOT_MCP, "the server answered with ${type ?: "nothing"}, not an event stream")
                    readEvents(response.body.source()) { event, data ->
                        if (event == "endpoint") {
                            url.resolve(data.trim())?.let(ready::complete)
                        } else {
                            for (m in messages(data)) {
                                if (m.has("method")) aside.trySend(m) else m.opt("id")?.toString()?.let { pending.remove(it) }?.complete(m)
                            }
                        }
                        !closed
                    }
                }
            } catch (error: IOException) {
                why = error.asMcp()
            } finally {
                closed = true
                ready.completeExceptionally(why)
                pending.values.forEach { it.completeExceptionally(why) }
                pending.clear()
            }
        }
        endpoint = withTimeoutOrNull(OPEN_MS) { ready.await() }
            ?: throw McpException(McpException.Kind.NOT_MCP, "the event stream never said where to send messages").also { close() }
    }

    private suspend fun postTo(message: JSONObject) {
        val to = endpoint ?: throw McpException(McpException.Kind.PROTOCOL, "not open")
        val request = Request.Builder().url(to).post(message.toString().toRequestBody(JSON))
            .apply { headers().forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(request).fetch { it.requireSuccess(false) }
    }

    override suspend fun request(id: Long, message: JSONObject): JSONObject {
        val answer = CompletableDeferred<JSONObject>()
        pending[id.toString()] = answer
        try {
            postTo(message)
            return answer.await()
        } finally {
            pending.remove(id.toString())
        }
    }

    override suspend fun send(message: JSONObject) = postTo(message)

    override fun close() {
        closed = true
        call?.cancel()
        aside.close()
        job.cancel()
    }

    private companion object {
        const val OPEN_MS = 10_000L
    }
}
