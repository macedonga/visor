@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import android.content.Context
import com.macedonga.visor.connection.WebAppBluetooth
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.Scope
import com.macedonga.visor.kit.server.Caller
import com.macedonga.visor.kit.server.Dispatcher
import com.macedonga.visor.kit.server.JsonRpcSession
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The VDK for the Display's web apps, through the browser's Bluetooth scans (visor-kit's `Visor.connectGlasses`). A
 * scan whose filter holds manufacturer data for company 0xFFFF starting "VDK1" is a message, not a scan: `{v, origin,
 * session, open}` opens a session on that scan's channel, where Visor's answers and events go out as advertisements;
 * `{v, origin, session, rpc}` hands one JSON-RPC message to that session. A frame is "VDK1", a flags byte (1: more
 * follow), a message id (4 bytes, big endian), then the bytes. A session ends with its scan. Only the origins the
 * person allows in Settings > Web apps get in, with the scopes chosen there; the Display lets only those scan at all.
 */
@Singleton
class KitWebApps @Inject constructor(
    @ApplicationContext context: Context,
    private val bluetooth: WebAppBluetooth,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _grants = MutableStateFlow(readGrants())

    /** The scopes each web app's origin holds. */
    val grants: StateFlow<Map<String, Set<Scope>>> = _grants.asStateFlow()

    @Volatile private var dispatcher: Dispatcher? = null
    private val sessions = ConcurrentHashMap<String, Session>()
    private val sessionOfChannel = ConcurrentHashMap<Int, String>()
    // Messages that came in pieces, by id, until their last piece.
    private val pieces = ConcurrentHashMap<Int, ByteArrayOutputStream>()

    /** Called once, with the kit's dispatcher. */
    fun start(dispatcher: Dispatcher) {
        this.dispatcher = dispatcher
    }

    fun scopesOf(origin: String): Set<Scope> = _grants.value[origin].orEmpty()

    fun setScopes(origin: String, scopes: Set<Scope>) {
        _grants.value = (_grants.value + (origin to scopes)).filterValues { it.isNotEmpty() }
        val json = JSONObject()
        _grants.value.forEach { (o, s) -> json.put(o, s.joinToString(",") { it.id }) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    /** The frame a scan start carries for Visor, or null for a real scan. */
    fun frameOf(filters: List<ByteArray>): ByteArray? {
        for (filter in filters) {
            val f = runCatching { Wire.parse(filter) }.getOrNull() ?: continue
            for (entry in f.repeatedBytes(4)) {
                val e = Wire.parse(entry)
                if (e.varint(1).toInt() != COMPANY) continue
                val prefix = Wire.parse(e.bytes(2)).bytes(1)
                if (prefix.size >= HEADER && MAGIC.indices.all { prefix[it] == MAGIC[it] }) return prefix
            }
        }
        return null
    }

    /** A scan that carries [frame]: put the pieces together, then open a session or pass a message on. */
    fun onScan(connection: DataXConnection, channel: Int, requestId: String, frame: ByteArray) {
        val buffer = ByteBuffer.wrap(frame)
        buffer.position(MAGIC.size)
        val more = buffer.get().toInt() and MORE != 0
        val id = buffer.int
        val body = pieces.getOrPut(id) { ByteArrayOutputStream() }.apply { write(frame, HEADER, frame.size - HEADER) }
        if (more) return
        pieces.remove(id)
        val message = runCatching { JSONObject(body.toString(Charsets.UTF_8.name())) }.getOrNull() ?: return
        val origin = message.optString("origin")
        val sessionId = message.optString("session")
        if (!bluetooth.allowed.value.contains(origin)) {
            log.add("VDK: a web app from $origin is not allowed in Settings > Web apps")
            return
        }
        if (message.optBoolean("open")) return open(connection, channel, requestId, origin, sessionId)
        val rpc = message.optJSONObject("rpc") ?: return
        val session = sessions[sessionId]?.takeIf { it.origin == origin } ?: return log.add("VDK: a message for a closed web app session")
        session.rpc.receive(rpc.toString())
    }

    /** The scan on [channel] stopped: a session it carried ends. */
    fun onScanEnd(channel: Int) {
        val id = sessionOfChannel.remove(channel) ?: return
        sessions.remove(id)?.end()
    }

    private fun open(connection: DataXConnection, channel: Int, requestId: String, origin: String, id: String) {
        val dispatcher = dispatcher ?: return log.add("VDK: not started")
        sessions.remove(id)?.end()
        val session = Session(origin, connection, DataXConnection.mirror(channel), requestId, dispatcher)
        sessions[id] = session
        sessionOfChannel[channel] = id
        log.add("VDK: the web app $origin connected through the glasses")
    }

    // One page's session: what Visor says goes out in order, as advertisements on the session's scan.
    private inner class Session(val origin: String, private val mux: DataXConnection, private val reply: Int, private val requestId: String, dispatcher: Dispatcher) {
        private val out = Channel<String>(Channel.UNLIMITED)
        private val messageIds = AtomicInteger(0)
        private val sender = scope.launch {
            for (message in out) {
                val bytes = message.encodeToByteArray()
                val id = messageIds.incrementAndGet()
                var at = 0
                do {
                    val end = minOf(bytes.size, at + CHUNK)
                    val last = end == bytes.size
                    val frame = MAGIC + byteArrayOf(if (last) 0 else MORE.toByte()) + ByteBuffer.allocate(4).putInt(id).array() + bytes.copyOfRange(at, end)
                    runCatching { mux.send(reply, advertisement(frame), messageType = ADVERTISEMENT) }
                    at = end
                } while (!last)
            }
        }
        val rpc = JsonRpcSession(dispatcher, Caller(WEB_UID, origin, network = true), send = { out.trySend(it).isSuccess }, close = { out.close() })

        fun end() {
            rpc.end()
            out.close()
            sender.cancel()
        }

        private fun advertisement(frame: ByteArray): ByteArray =
            Wire.fieldString(1, requestId) + Wire.fieldString(2, ADDRESS) + Wire.fieldString(3, NAME) +
                Wire.fieldVarint(4, RSSI) + Wire.fieldBytes(8, Wire.fieldVarint(1, COMPANY.toLong()) + Wire.fieldBytes(2, frame)) +
                Wire.fieldVarint(10, System.currentTimeMillis()) + Wire.fieldVarint(11, 1)
    }

    private fun readGrants(): Map<String, Set<Scope>> {
        val json = runCatching { JSONObject(prefs.getString(KEY, null) ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().associateWith { o -> json.getString(o).split(",").filter { it.isNotEmpty() }.map(::Scope).toSet() }
    }

    companion object {
        /** The uid web apps call with: no Android app stands behind them. */
        const val WEB_UID = -2
        const val COMPANY = 0xFFFF
        private val MAGIC = byteArrayOf(0x56, 0x44, 0x4B, 0x31)
        private const val MORE = 1
        private const val HEADER = 9
        // Under the 16383-byte DataX message, with the advertisement around it.
        private const val CHUNK = 8_000
        private const val ADDRESS = "56:44:4B:00:00:01"
        private const val NAME = "Visor"
        private const val RSSI = -40L
        private const val ADVERTISEMENT = 18
        private const val PREFS = "kit_web_apps"
        private const val KEY = "grants"
    }
}
