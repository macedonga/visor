package com.macedonga.visor.core.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** svc47, cloud configuration: the Wi-Fi networks the glasses keep. Its answers come on [GlassesConnection.serviceData]. */
const val SVC_CLOUD_CONFIG = 47
private const val MT_REMOVE_NETWORK = 3
private const val MT_REMOVE_NETWORK_RESULT = 4
private const val MT_QUERY_NETWORKS = 5
private const val MT_QUERY_NETWORKS_RESULT = 6
private const val MT_SAVE_NETWORK = 1
private const val MT_SAVE_NETWORK_LIST = 18
private const val MT_SAVE_NETWORK_RESULT = 2

private const val APP_CLOUD_CONFIGURATION = 6L

private const val SECURITY_OPEN = 1L
private const val SECURITY_WPA_PSK = 3L
private const val RANDOMIZATION_PUBLIC = 1L
private const val REQUEST_CONNECT = 2L

private const val ANSWER_MS = 15_000L

/** The status of a save or remove: done. */
const val WIFI_STATUS_OK = 0

/** The status of a save: the glasses could not join with that passphrase. */
const val WIFI_STATUS_WRONG_PASSWORD = 6

/** Lists, saves and removes the Wi-Fi networks the glasses keep. One request at a time. */
class GlassesWifi(private val connection: GlassesConnection) {

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The glasses' cloud configuration app, started before the first request of a connection.
    @Volatile
    private var started = false

    init {
        scope.launch {
            connection.state.map { it.link == LinkState.READY }.distinctUntilChanged().collect { up -> if (!up) started = false }
        }
    }

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** What was asked and answered, for the event log. */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    /** The saved network names, or null without an answer. */
    suspend fun saved(): List<String>? = lock.withLock {
        val answer = ask(MT_QUERY_NETWORKS, ByteArray(0), MT_QUERY_NETWORKS_RESULT)
        if (answer == null) null else readList(answer)
    }

    /** The passphrase the glasses keep for [ssid] (QueryNetworksResult.Result 11), or null. A secret: never logged. */
    suspend fun keyOf(ssid: String): String? = lock.withLock {
        val answer = ask(MT_QUERY_NETWORKS, ByteArray(0), MT_QUERY_NETWORKS_RESULT) ?: return null
        val fields = Wire.parse(answer)
        if (fields.varint(1) != 0L) return null
        fields.repeatedBytes(2).map(Wire::parse).firstOrNull { it.bytes(1).decodeToString() == ssid }?.takeIf { it.has(11) }?.bytes(11)?.decodeToString()
    }

    private fun readList(answer: ByteArray): List<String>? {
        val fields = Wire.parse(answer)
        if (fields.varint(1) != 0L) {
            note("list refused, status ${fields.varint(1)}")
            return null
        }
        return fields.repeatedBytes(2).mapNotNull { entry ->
            val network = Wire.parse(entry)
            if (network.has(1)) network.bytes(1).decodeToString().takeIf { it.isNotBlank() } else null
        }
    }

    /** Save a WPA2 network, or an [open] one, and have the glasses check it. Its status; null without an answer. */
    suspend fun save(ssid: String, passphrase: String, open: Boolean): Int? = lock.withLock {
        val network = Wire.fieldString(1, ssid) +
            (if (open) ByteArray(0) else Wire.fieldString(2, passphrase)) +
            Wire.fieldVarint(3, if (open) SECURITY_OPEN else SECURITY_WPA_PSK) +
            Wire.fieldVarint(5, 1) +
            Wire.fieldVarint(6, RANDOMIZATION_PUBLIC) +
            Wire.fieldVarint(9, REQUEST_CONNECT)
        // The Display's firmware knows only the single SaveNetwork (type 1) and answers the list (18) with 0xD001.
        val answer = ask(MT_SAVE_NETWORK, network, MT_SAVE_NETWORK_RESULT)
            ?: ask(MT_SAVE_NETWORK_LIST, Wire.fieldBytes(1, network), MT_SAVE_NETWORK_RESULT)
        answer?.let { Wire.parse(it).varint(1).toInt().also { status -> note("save: status $status") } }
    }

    /** Forget a saved network. Its status; null without an answer. */
    suspend fun remove(ssid: String): Int? = lock.withLock {
        val answer = ask(MT_REMOVE_NETWORK, Wire.fieldString(1, ssid), MT_REMOVE_NETWORK_RESULT)
        answer?.let { Wire.parse(it).varint(1).toInt().also { status -> note("remove: status $status") } }
    }

    /** Sends one svc47 message and returns the answer's payload, or null after [ANSWER_MS]. */
    private suspend fun ask(type: Int, payload: ByteArray, answer: Int): ByteArray? {
        if (connection.state.value.link != LinkState.READY) return null
        if (!started && !startApp()) return null
        val reply = withTimeoutOrNull(ANSWER_MS) {
            connection.serviceData
                .onSubscription { connection.submit { it.sendOnService(SVC_CLOUD_CONFIG, type, payload) } }
                .first { it.serviceId == SVC_CLOUD_CONFIG && it.messageType == answer }
                .payload
        }
        if (reply == null) {
            // The app may have stopped with the link; the next request starts it again.
            started = false
            note("no answer to type $type")
        }
        return reply
    }

    /** Starts the cloud configuration app on svc28, as the Meta app does first. True when it runs. */
    private suspend fun startApp(): Boolean {
        val code = withTimeoutOrNull(ANSWER_MS) {
            connection.serviceData
                .onSubscription {
                    connection.submit { it.sendOnService(SVC_STATUS, SnApp.MT_START_APP, SnApp.app(APP_CLOUD_CONFIGURATION)) }
                }
                .first { it.serviceId == SVC_STATUS && it.messageType == SnApp.MT_START_APP_RESPONSE }
                .let { Wire.parse(it.payload).varint(1) }
        }
        started = SnApp.started(code)
        if (!started) note("cloud configuration did not start, code ${code ?: "none"}")
        return started
    }

    private fun note(text: String) {
        _notes.tryEmit(text)
    }
}
