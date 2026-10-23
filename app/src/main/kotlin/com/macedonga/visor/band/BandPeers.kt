package com.macedonga.visor.band

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.LinkState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A band the Display has paired: its address, whether it is connected, and whether directly or through the phone. */
data class PairedBand(val address: String, val connected: Boolean, val direct: Boolean)

/**
 * svc41109, bt_peer_connection: the Display's own pairing with the Neural Band. GET_BLE_PAIRING_INFO (1) is answered
 * with BLE_PAIRING_INFO (2): {3 pairedPeers [ConnectionState {1 peerIdentityAddr BleAddr {2 addr}, 2 isConnected}],
 * 5 activeLink [ActiveLink {1 link (1 relay, 2 direct), 2 peerIdentityAddr}]}.
 */
@Singleton
class BandPeers @Inject constructor(private val connection: GlassesConnection) {
    private val lock = Mutex()
    private val _bands = MutableStateFlow<List<PairedBand>?>(null)

    /** The bands the Display has paired, as it last said; null until it has answered. */
    val bands: StateFlow<List<PairedBand>?> = _bands.asStateFlow()

    /** Asks the Display again; without an answer [bands] stays as it was. */
    suspend fun refresh() = lock.withLock {
        if (connection.state.value.link != LinkState.READY) return@withLock
        val answer = withTimeoutOrNull(ANSWER_MS) {
            connection.serviceData
                .onSubscription { connection.submit { it.sendOnService(SVC, MT_GET_PAIRING_INFO, ByteArray(0)) } }
                .first { it.serviceId == SVC && it.messageType == MT_PAIRING_INFO }
                .payload
        } ?: return@withLock
        runCatching { parse(answer) }.getOrNull()?.let { _bands.value = it }
    }

    private fun parse(payload: ByteArray): List<PairedBand> {
        val info = Wire.parse(payload)
        val links = info.repeatedBytes(5).map(Wire::parse).associate { address(it.bytes(2)) to it.varint(1) }
        return info.repeatedBytes(3).map(Wire::parse).map { peer ->
            val address = address(peer.bytes(1))
            PairedBand(address, peer.varint(2) == 1L, links[address] == LINK_DIRECT)
        }
    }

    /** BleAddr {2 addr}, least significant byte first. */
    private fun address(bleAddr: ByteArray): String =
        Wire.parse(bleAddr).bytes(2).reversed().joinToString(":") { "%02X".format(it) }

    private companion object {
        const val SVC = 41109
        const val MT_GET_PAIRING_INFO = 1
        const val MT_PAIRING_INFO = 2
        const val LINK_DIRECT = 2L
        const val ANSWER_MS = 8_000L
    }
}
