@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import android.content.Context
import android.net.ConnectivityManager
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.Scope
import com.macedonga.visor.kit.server.Caller
import com.macedonga.visor.kit.server.Dispatcher
import com.macedonga.visor.kit.server.JsonRpcSession
import com.macedonga.visor.kit.server.McpAdapter
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A client of the kit's network door: the name the person gave it, its scopes, and the hash of its token. */
data class NetworkClient(
    val name: String,
    val scopes: Set<Scope>,
    val tokenHash: String,
    /** Where Visor posts the events of [topics], or null for a client that connects itself. */
    val webhook: String? = null,
    val topics: List<String> = emptyList(),
)

/** The network door's switches and clients. A client's token is shown once, when it is made, and kept only as a hash. */
@Singleton
class NetworkClients @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("kit_network", Context.MODE_PRIVATE)

    private val _on = MutableStateFlow(prefs.getBoolean(ON, false))
    val on: StateFlow<Boolean> = _on.asStateFlow()

    private val _lan = MutableStateFlow(prefs.getBoolean(LAN, false))

    /** True when other devices on the local network reach the door too, not only this phone. */
    val lan: StateFlow<Boolean> = _lan.asStateFlow()

    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<NetworkClient>> = _all.asStateFlow()

    fun setOn(on: Boolean) {
        prefs.edit().putBoolean(ON, on).apply()
        _on.value = on
    }

    fun setLan(lan: Boolean) {
        prefs.edit().putBoolean(LAN, lan).apply()
        _lan.value = lan
    }

    /**
     * Makes a client named [name], or replaces the one of that name, and returns its token. With a [webhook], Visor
     * posts the events of [topics] there.
     */
    @Synchronized
    fun add(name: String, scopes: Set<Scope>, webhook: String?, topics: List<String>): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes))
        val client = NetworkClient(name, scopes, hash(token), webhook?.takeIf { it.isNotBlank() }, if (webhook.isNullOrBlank()) emptyList() else topics)
        _all.value = _all.value.filter { it.name != name } + client
        write()
        return token
    }

    @Synchronized
    fun remove(name: String) {
        _all.value = _all.value.filter { it.name != name }
        write()
    }

    /** The client whose token is [token], or null. */
    fun byToken(token: String): NetworkClient? {
        val given = hash(token).toByteArray()
        return _all.value.firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), given) }
    }

    fun scopesOf(name: String): Set<Scope> = _all.value.firstOrNull { it.name == name }?.scopes.orEmpty()

    private fun hash(token: String) =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun read(): List<NetworkClient> {
        val list = JSONArray(prefs.getString(CLIENTS, null) ?: return emptyList())
        return (0 until list.length()).map { i ->
            val c = list.getJSONObject(i)
            val scopes = c.getJSONArray("scopes")
            val topics = c.optJSONArray("topics") ?: JSONArray()
            NetworkClient(
                c.getString("name"), (0 until scopes.length()).mapTo(LinkedHashSet()) { Scope(scopes.getString(it)) }, c.getString("hash"),
                c.optString("webhook").ifEmpty { null }, (0 until topics.length()).map(topics::getString),
            )
        }
    }

    private fun write() {
        val list = JSONArray()
        for (c in _all.value) {
            list.put(
                JSONObject().put("name", c.name).put("hash", c.tokenHash).put("scopes", JSONArray(c.scopes.map { it.id }))
                    .put("webhook", c.webhook ?: "").put("topics", JSONArray(c.topics)),
            )
        }
        prefs.edit().putString(CLIENTS, list.toString()).apply()
    }

    private companion object {
        const val ON = "on"
        const val LAN = "lan"
        const val CLIENTS = "clients"
        const val TOKEN_BYTES = 24
    }
}

/**
 * The kit's network door: JSON-RPC over a WebSocket at `/rpc`, and MCP over HTTP at `/mcp`, on [PORT]. Every request
 * carries a client's token, as `Authorization: Bearer` or, where a browser cannot set headers, as `?token=`.
 */
@Singleton
class KitNetwork @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clients: NetworkClients,
    private val log: EventLog,
) {
    private var server: EmbeddedServer<*, *>? = null

    /** Serves [dispatcher] on this phone only, or with [lan] on the local network too, until [stop]. */
    @Synchronized
    fun start(dispatcher: Dispatcher, mcp: McpAdapter, lan: Boolean) {
        stop()
        val host = if (lan) "0.0.0.0" else "127.0.0.1"
        server = embeddedServer(CIO, port = PORT, host = host) {
            install(WebSockets)
            routing {
                route("/rpc") {
                    // Turned away before the upgrade, so a client with a wrong token sees 401, not a socket that closes.
                    intercept(ApplicationCallPipeline.Plugins) {
                        if (clientOf(call) == null) {
                            call.respond(HttpStatusCode.Unauthorized)
                            finish()
                        }
                    }
                    webSocket {
                        val client = clientOf(call) ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No valid token."))
                        // Messages wait in order here, so a slow reader never makes the kit think the client went away.
                        val out = Channel<String>(Channel.UNLIMITED)
                        val writer = launch { for (message in out) outgoing.send(Frame.Text(message)) }
                        val session = JsonRpcSession(
                            dispatcher, Caller(NETWORK_UID, client.name, network = true),
                            send = { out.trySend(it).isSuccess },
                            close = { out.close() },
                        )
                        try {
                            for (frame in incoming) if (frame is Frame.Text) session.receive(frame.readText())
                        } finally {
                            session.end()
                            out.close()
                            writer.join()
                        }
                    }
                }
                post("/mcp") {
                    val client = clientOf(call) ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val answer = mcp.answer(Caller(NETWORK_UID, client.name, network = true), call.receiveText())
                    if (answer == null) call.respond(HttpStatusCode.Accepted) else call.respondText(answer, ContentType.Application.Json)
                }
            }
        }.also { it.start(wait = false) }
        log.add("Kit: the network door is open on ${address(lan)}")
    }

    @Synchronized
    fun stop() {
        server?.stop(GRACE_MS, STOP_MS)
        server = null
    }

    /** Where clients reach the door: this phone's own address, or its Wi-Fi address with [lan]. */
    fun address(lan: Boolean): String {
        if (!lan) return "127.0.0.1:$PORT"
        val links = context.getSystemService(ConnectivityManager::class.java)?.let { it.getLinkProperties(it.activeNetwork) }
        val ip = links?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress ?: "0.0.0.0"
        return "$ip:$PORT"
    }

    private fun clientOf(call: ApplicationCall): NetworkClient? {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            ?: call.request.queryParameters["token"]
            ?: return null
        return clients.byToken(token)
    }

    companion object {
        const val PORT = 7333

        // No Android uid stands behind a network client.
        private const val NETWORK_UID = -1
        private const val GRACE_MS = 500L
        private const val STOP_MS = 2_000L
    }
}
