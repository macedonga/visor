package com.macedonga.visor.core.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.LinkState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * svc78, nexus: the Display's connectivity service. A request is one message on a channel of its own, its DataX type
 * naming the request; the answer comes back on that channel, a typed message or a bare status (0 is OK).
 */
class Nexus(private val connection: GlassesConnection) {

    sealed interface Reply

    class Message(val type: Int, val payload: ByteArray) : Reply {
        override fun toString(): String = "type $type " + payload.joinToString("") { "%02x".format(it) }
    }

    class Status(val status: Int) : Reply {
        override fun toString(): String = "status 0x%04x".format(java.util.Locale.ROOT, status)
    }

    // One request at a time: an answer is matched to the request by service.
    private val lock = Mutex()

    /** Sends [type] with [payload]; the answer, or null when none came within [millis] or nothing is connected. */
    suspend fun request(type: Int, payload: ByteArray = ByteArray(0), millis: Long = ANSWER_MS): Reply? = lock.withLock {
        if (connection.state.value.link != LinkState.READY) return@withLock null
        val sent = AtomicInteger(-1)
        val replies = Channel<Reply>(Channel.UNLIMITED)
        coroutineScope {
            val watchers = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    connection.serviceData.collect { if (it.serviceId == SERVICE) replies.trySend(Message(it.messageType, it.payload)) }
                },
                // A status on a channel closed with it names no service.
                launch(start = CoroutineStart.UNDISPATCHED) {
                    connection.errors.collect { e ->
                        val channel = sent.get()
                        if (e.serviceId == SERVICE || (channel >= 0 && (e.channelId and 0x7FFF) == (channel and 0x7FFF))) replies.trySend(Status(e.errorId))
                    }
                },
            )
            connection.submit { mux ->
                val channel = mux.openChannel(SERVICE)
                sent.set(channel)
                mux.send(channel, payload, messageType = type, serviceId = SERVICE)
            }
            try {
                withTimeoutOrNull(millis) { replies.receive() }
            } finally {
                watchers.forEach { it.cancel() }
            }
        }
    }

    companion object {
        const val SERVICE = 78

        const val ENTER_PAIRING_MODE = 4128
        const val EXIT_PAIRING_MODE = 4144
        const val CHECK_CONNECTED_TELEPHONY = 8192
        const val CONNECTION_STATUS_TELEPHONY = 8193

        /** Empty: the Display's default classic device. */
        const val CONNECT_TELEPHONY = 8197

        // BluetoothConnectionStatus.status.
        const val CONNECTED = 1
        const val DISCONNECTED = 3

        const val OK = 0
        const val ANSWER_MS = 5_000L

        /** BluetoothConnectionStatus field 1 from a CONNECTION_STATUS_TELEPHONY answer; null for any other reply. */
        fun telephony(reply: Reply?): Int? =
            (reply as? Message)?.takeIf { it.type == CONNECTION_STATUS_TELEPHONY }?.let { runCatching { Wire.parse(it.payload).varint(1).toInt() }.getOrNull() }

        /** Whether [reply] is the bare OK status. */
        fun ok(reply: Reply?): Boolean = (reply as? Status)?.status == OK
    }
}
