package com.macedonga.visor.core.assistant.mcp

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** A tool a server offers. */
data class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val schema: JSONObject,
    // It only reads: what it did is not worth showing under the answer.
    val readOnly: Boolean,
)

/** An icon a server declares for itself (MCP 2025-11-25). */
data class IconRef(val src: String, val mimeType: String, val sizes: String, val theme: String)

/** Who the server says it is, and how it was reached. */
data class ServerInfo(
    val name: String,
    val version: String,
    val protocol: String,
    val instructions: String,
    val transport: Transport,
    val icons: List<IconRef> = emptyList(),
    val website: String = "",
)

/** What a tool gave back, as text for the model. */
data class ToolResult(val text: String, val isError: Boolean)

/** The credentials for each request, and what to do when the server refuses them. */
internal interface Authorizer {
    suspend fun headers(): Map<String, String>

    /** After a 401: true when there are new credentials worth one more try. */
    suspend fun recover(challenge: String?): Boolean
}

/** A JSON value or "" for a missing key or a JSON null, which org.json would turn into "null". */
internal fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

/**
 * One server's session: the transport (found by trying), the initialize handshake, and requests that survive a
 * forgotten session, a stale connection and an expired token.
 */
internal class McpConnection(
    private val url: HttpUrl,
    private val hint: Transport,
    private val http: OkHttpClient,
    private val stream: OkHttpClient,
    private val auth: Authorizer,
    private val scope: CoroutineScope,
    private val version: String,
    private val onNotice: (JSONObject) -> Unit,
) {
    private val ids = AtomicLong()
    private val lock = Mutex()

    @Volatile
    private var transport: RpcTransport? = null

    @Volatile
    private var closed = false

    @Volatile
    var info: ServerInfo? = null
        private set

    @Volatile
    var lastUsed = now()
        private set

    suspend fun request(method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
        lastUsed = now()
        var retried = false
        while (true) {
            var used: RpcTransport? = null
            var fresh = true
            try {
                val (t, new) = connected()
                used = t
                fresh = new
                return exchange(t, method, params, timeoutMs)
            } catch (error: McpException) {
                if (error.kind in DROPPING) drop(used)
                val again = !retried && when (error.kind) {
                    McpException.Kind.SESSION_GONE -> true
                    McpException.Kind.UNAUTHORIZED -> auth.recover(error.challenge)
                    // A connection kept from earlier can have gone stale; a new one that fails at once has not.
                    McpException.Kind.UNREACHABLE -> used != null && !fresh
                    else -> false
                }
                if (!again) throw error
                retried = true
            }
        }
    }

    /** Every tool, across pages. */
    suspend fun tools(): List<McpTool> {
        val out = ArrayList<McpTool>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val result = request("tools/list", cursor?.let { JSONObject().put("cursor", it) }, LIST_MS)
            val list = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until list.length()) list.optJSONObject(i)?.let(::toolOf)?.let(out::add)
            cursor = result.str("nextCursor").ifEmpty { return out }
        }
        return out
    }

    suspend fun call(name: String, arguments: JSONObject, timeoutMs: Long): ToolResult {
        val result = try {
            request("tools/call", JSONObject().put("name", name).put("arguments", arguments), timeoutMs)
        } catch (error: McpException) {
            // Bad arguments or an unknown tool: the model can read that and try again.
            if (error.kind == McpException.Kind.ERROR) return ToolResult("Error: ${error.message}", true)
            throw error
        }
        return ToolResult(render(result), result.optBoolean("isError"))
    }

    fun close() {
        closed = true
        transport?.close()
        transport = null
    }

    private suspend fun connected(): Pair<RpcTransport, Boolean> = lock.withLock {
        transport?.takeIf { it.alive }?.let { return@withLock it to false }
        val t = open()
        // Closed while it was opening: nothing would close this one.
        if (closed) {
            t.close()
            throw McpException(McpException.Kind.UNREACHABLE, "closed")
        }
        transport = t
        t to true
    }

    private fun drop(t: RpcTransport?) {
        t ?: return
        if (transport === t) transport = null
        t.close()
    }

    /** The transport this server speaks: the learned one first, then the other when the first is plainly wrong. */
    private suspend fun open(): RpcTransport {
        val sseFirst = hint == Transport.SSE || (hint == Transport.AUTO && url.encodedPath.trimEnd('/').endsWith("/sse"))
        val order = if (sseFirst) listOf(Transport.SSE, Transport.STREAMABLE) else listOf(Transport.STREAMABLE, Transport.SSE)
        var first: McpException? = null
        for (kind in order) {
            val t: RpcTransport = if (kind == Transport.SSE) LegacySse(url, http, stream, { auth.headers() }, ::inbound, scope) else StreamableHttp(url, http, { auth.headers() }, ::inbound)
            try {
                if (t is LegacySse) t.open()
                initialize(t)
                return t
            } catch (error: Throwable) {
                t.close()
                if (error !is McpException || error.kind != McpException.Kind.NOT_MCP) throw error
                if (first == null) first = error
            }
        }
        throw McpException(McpException.Kind.NOT_MCP, "this address does not answer as an MCP server (${first?.message})")
    }

    private suspend fun initialize(t: RpcTransport) {
        val result = exchange(t, "initialize", initializeParams(version), INIT_MS)
        val protocol = result.str("protocolVersion").ifEmpty { LATEST }
        t.protocolVersion = protocol
        t.send(rpcMessage("notifications/initialized"))
        val server = result.optJSONObject("serverInfo") ?: JSONObject()
        info = ServerInfo(
            name = server.str("title").ifEmpty { server.str("name") },
            version = server.str("version"),
            protocol = protocol,
            instructions = result.str("instructions"),
            transport = t.kind,
            icons = server.optJSONArray("icons")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { IconRef(it.str("src"), it.str("mimeType"), it.str("sizes"), it.str("theme")) }
            }.orEmpty().filter { it.src.isNotEmpty() },
            website = server.str("websiteUrl"),
        )
    }

    private suspend fun exchange(t: RpcTransport, method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
        val id = ids.incrementAndGet()
        val response = try {
            withTimeout(timeoutMs) { t.request(id, rpcMessage(method, params, id)) }
        } catch (late: TimeoutCancellationException) {
            // Tell the server to stop working on it; nothing waits for that.
            scope.launch { runCatching { t.send(rpcMessage("notifications/cancelled", JSONObject().put("requestId", id).put("reason", "timed out"))) } }
            throw McpException(McpException.Kind.TIMEOUT, "no answer to $method in ${timeoutMs / 1000} s")
        }
        response.optJSONObject("error")?.let { e ->
            throw McpException(McpException.Kind.ERROR, e.str("message").ifEmpty { "error ${e.optInt("code")}" })
        }
        return response.optJSONObject("result") ?: JSONObject()
    }

    /** The server's own requests: a ping is answered, anything else this client never offered is refused. */
    private suspend fun inbound(message: JSONObject): JSONObject? {
        if (!message.has("id")) {
            onNotice(message)
            return null
        }
        val reply = JSONObject().put("jsonrpc", "2.0").put("id", message.get("id"))
        return if (message.str("method") == "ping") {
            reply.put("result", JSONObject())
        } else {
            reply.put("error", JSONObject().put("code", -32601).put("message", "Visor does not offer ${message.str("method")}"))
        }
    }

    private fun toolOf(o: JSONObject): McpTool? {
        val name = o.str("name").ifEmpty { return null }
        val annotations = o.optJSONObject("annotations") ?: JSONObject()
        val schema = o.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
        if (!schema.has("properties")) schema.put("properties", JSONObject())
        return McpTool(
            name = name,
            title = o.str("title").ifEmpty { annotations.str("title") },
            description = o.str("description"),
            schema = schema,
            readOnly = annotations.optBoolean("readOnlyHint", false),
        )
    }

    /** A tool's content as text: what the model can use of it, cut to a size a turn can carry. */
    private fun render(result: JSONObject): String {
        val parts = ArrayList<String>()
        val content = result.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            when (c.str("type")) {
                "text" -> parts += c.str("text")
                "image", "audio" -> parts += "[${c.str("type")}, ${c.str("mimeType")}]"
                "resource" -> c.optJSONObject("resource")?.let { r -> parts += r.str("text").ifEmpty { "[resource ${r.str("uri")}]" } }
                "resource_link" -> parts += "${c.str("name")} ${c.str("uri")}".trim()
            }
        }
        if (parts.isEmpty()) result.optJSONObject("structuredContent")?.let { parts += it.toString() }
        val text = parts.joinToString("\n").ifEmpty { "Done." }
        return if (text.length > MAX_RESULT) text.take(MAX_RESULT) + "\n[cut: ${text.length - MAX_RESULT} more characters]" else text
    }

    companion object {
        const val LATEST = "2025-11-25"
        private const val INIT_MS = 12_000L
        private const val LIST_MS = 15_000L
        private const val MAX_PAGES = 20
        private const val MAX_RESULT = 16_000
        private val DROPPING = setOf(
            McpException.Kind.SESSION_GONE, McpException.Kind.UNAUTHORIZED, McpException.Kind.UNREACHABLE,
            McpException.Kind.NOT_MCP, McpException.Kind.LOCAL_NETWORK, McpException.Kind.PROTOCOL,
        )

        fun now() = System.currentTimeMillis()

        fun initializeParams(version: String): JSONObject = JSONObject()
            .put("protocolVersion", LATEST)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "visor").put("title", "Visor").put("version", version))
    }
}
