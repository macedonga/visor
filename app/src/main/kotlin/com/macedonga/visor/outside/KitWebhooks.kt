@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import com.macedonga.visor.kit.protocol.JsonRpc
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.server.Caller
import com.macedonga.visor.kit.server.Dispatcher
import com.macedonga.visor.kit.server.JsonRpcSession
import com.macedonga.visor.log.EventLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Posts the events of each network client's webhook topics to its URL, while the network door is open. Each client
 * subscribes as itself, with its own scopes, through an ordinary JSON-RPC session. A body is
 * `{"client", "topic", "event", "time"}`, signed in `X-Visor-Signature: sha256=<hex>`: HMAC-SHA256 of the body, keyed
 * by the hex SHA-256 of the client's token, which the receiver can work out and Visor keeps.
 */
@Singleton
class KitWebhooks @Inject constructor(private val clients: NetworkClients, private val log: EventLog) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(dispatcher: Dispatcher) {
        scope.launch {
            combine(clients.on, clients.all) { on, all -> if (on) all.filter { it.webhook != null && it.topics.isNotEmpty() } else emptyList() }
                .distinctUntilChanged()
                .collectLatest { hooks ->
                    val sessions = hooks.map { open(dispatcher, it) }
                    try {
                        awaitCancellation()
                    } finally {
                        sessions.forEach(JsonRpcSession::end)
                    }
                }
        }
    }

    private fun open(dispatcher: Dispatcher, client: NetworkClient): JsonRpcSession {
        val url = checkNotNull(client.webhook)
        // One at a time and in order, so a receiver sees events as they happened.
        val bodies = Channel<String>(Channel.UNLIMITED)
        scope.launch { for (body in bodies) post(client, url, body) }
        val topics = client.topics.withIndex().associate { (i, topic) -> i + 1L to topic }
        val session = JsonRpcSession(
            dispatcher, Caller(NETWORK_UID, client.name, network = true),
            send = { message ->
                val m = JSONObject(message)
                val params = m.optJSONObject("params")
                when (m.optString("method")) {
                    JsonRpc.EVENT -> {
                        val topic = topics[params?.optLong("subscription")]
                        if (topic != null) {
                            val body = JSONObject().put("client", client.name).put("topic", topic)
                                .put("event", params?.opt("event")).put("time", System.currentTimeMillis())
                            bodies.trySend(body.toString())
                        }
                    }
                    JsonRpc.ENDED -> log.add("Kit: ${client.name}'s webhook lost ${topics[params?.optLong("subscription")]}: ${params?.optJSONObject("failure")?.optString("message")}")
                }
                true
            },
            close = { bodies.close() },
        )
        for ((id, topic) in topics) session.receive(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", JsonRpc.SUBSCRIBE).put("params", JSONObject().put("topic", topic)).toString())
        log.add("Kit: ${client.name}'s webhook posts ${client.topics.joinToString()}")
        return session
    }

    private fun post(client: NetworkClient, url: String, body: String) {
        val bytes = body.toByteArray()
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(client.tokenHash.toByteArray(), HMAC)) }
        val signature = mac.doFinal(bytes).joinToString("") { "%02x".format(it) }
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Visor-Signature", "sha256=$signature")
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            connection.disconnect()
            if (code !in 200..299) quietly(client, "answered $code")
        } catch (e: IOException) {
            quietly(client, "failed: ${e.message}")
        }
    }

    // A receiver that is down fails every event: the log says so once a minute, not once an event.
    private val said = ConcurrentHashMap<String, Long>()

    private fun quietly(client: NetworkClient, what: String) {
        val now = System.currentTimeMillis()
        if (now - (said[client.name] ?: 0) < QUIET_MS) return
        said[client.name] = now
        log.add("Kit: ${client.name}'s webhook $what")
    }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val TIMEOUT_MS = 5_000
        const val QUIET_MS = 60_000L

        // No Android uid stands behind a network client.
        const val NETWORK_UID = -1
    }
}
