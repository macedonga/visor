package com.macedonga.visor.core.assistant.mcp

import android.content.Context
import com.macedonga.visor.core.connection.strings
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** How Visor proves itself to a server. */
enum class AuthKind { NONE, TOKEN, HEADER, OAUTH }

/** How the messages travel: Streamable HTTP, or the older HTTP with Server-Sent Events. Learned at the first connection. */
enum class Transport { AUTO, STREAMABLE, SSE }

/**
 * An OAuth client and what it holds: the server's endpoints, the client this app registered (or was given), and the
 * tokens. Tokens are refreshed in place.
 */
data class OAuthGrant(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    // none, client_secret_basic or client_secret_post.
    val tokenAuth: String = "none",
    val redirectUri: String = "",
    val scope: String = "",
    val resource: String = "",
    // Typed in by the person, because the server registers no clients by itself.
    val manualClient: Boolean = false,
    val accessToken: String = "",
    val refreshToken: String = "",
    // Epoch milliseconds; 0 when the server did not say.
    val expiresAt: Long = 0,
    // How long the server gave the token, so a short-lived one is not refreshed at every request.
    val lifeMs: Long = 0,
) {
    val signedIn: Boolean get() = accessToken.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("issuer", issuer).put("authorizationEndpoint", authorizationEndpoint).put("tokenEndpoint", tokenEndpoint)
        .put("registrationEndpoint", registrationEndpoint).put("clientId", clientId).put("clientSecret", clientSecret)
        .put("tokenAuth", tokenAuth).put("redirectUri", redirectUri).put("scope", scope).put("resource", resource)
        .put("manualClient", manualClient).put("accessToken", accessToken).put("refreshToken", refreshToken).put("expiresAt", expiresAt).put("lifeMs", lifeMs)

    companion object {
        fun from(o: JSONObject) = OAuthGrant(
            issuer = o.optString("issuer"), authorizationEndpoint = o.optString("authorizationEndpoint"),
            tokenEndpoint = o.optString("tokenEndpoint"), registrationEndpoint = o.optString("registrationEndpoint"),
            clientId = o.optString("clientId"), clientSecret = o.optString("clientSecret"), tokenAuth = o.optString("tokenAuth", "none"),
            redirectUri = o.optString("redirectUri"), scope = o.optString("scope"), resource = o.optString("resource"),
            manualClient = o.optBoolean("manualClient"), accessToken = o.optString("accessToken"),
            refreshToken = o.optString("refreshToken"), expiresAt = o.optLong("expiresAt"), lifeMs = o.optLong("lifeMs"),
        )
    }
}

/** A Model Context Protocol server whose tools the assistant can use. */
data class McpServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val auth: AuthKind = AuthKind.NONE,
    // The token for TOKEN, the header's value for HEADER.
    val secret: String = "",
    // The header's name for HEADER.
    val header: String = "",
    // Tools the person turned off, by name.
    val off: Set<String> = emptySet(),
    val transport: Transport = Transport.AUTO,
    // A client ID typed in by the person, used instead of registering Visor.
    val clientId: String = "",
    val clientSecret: String = "",
    // Where the typed-in client sends the browser back: the app, or a loopback address.
    val redirect: String = "",
    val oauth: OAuthGrant? = null,
) {
    /** What a connection depends on: when it changes, an open session is let go. Tokens refresh without counting. */
    val connectionKey: String get() = listOf(url, auth, secret, header, oauth?.clientId, oauth?.signedIn).joinToString("|")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("enabled", enabled).put("auth", auth.name)
        .put("secret", secret).put("header", header).put("off", JSONArray(off.sorted())).put("transport", transport.name)
        .put("clientId", clientId).put("clientSecret", clientSecret).put("redirect", redirect)
        .apply { oauth?.let { put("oauth", it.toJson()) } }

    companion object {
        fun from(o: JSONObject) = McpServer(
            id = o.getString("id"), name = o.optString("name"), url = o.getString("url"), enabled = o.optBoolean("enabled", true),
            auth = runCatching { AuthKind.valueOf(o.optString("auth")) }.getOrDefault(AuthKind.NONE),
            secret = o.optString("secret"), header = o.optString("header"),
            off = o.optJSONArray("off")?.strings()?.toSet().orEmpty(),
            transport = runCatching { Transport.valueOf(o.optString("transport")) }.getOrDefault(Transport.AUTO),
            clientId = o.optString("clientId"), clientSecret = o.optString("clientSecret"), redirect = o.optString("redirect"),
            oauth = o.optJSONObject("oauth")?.let(OAuthGrant::from),
        )
    }
}

/** The servers, in this app's private storage with their tokens. */
class McpStore(context: Context) {
    private val prefs = context.getSharedPreferences("mcp", Context.MODE_PRIVATE)
    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

    fun get(id: String): McpServer? = _servers.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(server: McpServer) = save(_servers.value.filter { it.id != server.id } + server)

    /** Changes [id] in place; a server removed meanwhile stays removed. */
    @Synchronized
    fun update(id: String, change: (McpServer) -> McpServer) = save(_servers.value.map { if (it.id == id) change(it) else it })

    @Synchronized
    fun remove(id: String) = save(_servers.value.filter { it.id != id })

    private fun save(list: List<McpServer>) {
        prefs.edit().putString(KEY, JSONArray(list.map { it.toJson() }).toString()).apply()
        _servers.value = list
    }

    private fun load(): List<McpServer> = runCatching {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        (0 until a.length()).mapNotNull { runCatching { McpServer.from(a.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "servers"
    }
}
