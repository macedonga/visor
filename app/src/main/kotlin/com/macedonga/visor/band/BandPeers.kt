package com.macedonga.visor.band

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.protocol.BtPeer
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
 * with BLE_PAIRING_INFO (2), which names the Display's own addresses, what it supports and the bands it has paired.
 */
@Singleton
class BandPeers @Inject constructor(private val connection: GlassesConnection) {
    private val lock = Mutex()
    private val _bands = MutableStateFlow<List<PairedBand>?>(null)

    /** The bands the Display has paired, as it last said; null until it has answered. */
    val bands: StateFlow<List<PairedBand>?> = _bands.asStateFlow()

    /** Asks the Display again; without an answer [bands] stays as it was and the answer is null. */
    suspend fun refresh(): BtPeer.PairingInfo? = lock.withLock {
        if (connection.state.value.link != LinkState.READY) return@withLock null
        val answer = withTimeoutOrNull(ANSWER_MS) {
            connection.serviceData
                .onSubscription { connection.submit { it.sendOnService(BtPeer.SERVICE, BtPeer.GET_BLE_PAIRING_INFO, BtPeer.getPairingInfo()) } }
                .first { it.serviceId == BtPeer.SERVICE && it.messageType == BtPeer.BLE_PAIRING_INFO }
                .payload
        } ?: return@withLock null
        val (info, found) = runCatching {
            val parsed = BtPeer.pairingInfo(answer)
            val direct = parsed.links.filter { it.link == BtPeer.LINK_DIRECT }.map { address(it.identity) }.toSet()
            parsed to parsed.peers.map { peer -> address(peer.identity).let { PairedBand(it, peer.connected, it in direct) } }
        }.getOrNull() ?: return@withLock null
        _bands.value = found
        info
    }

    /** BleAddr {2 addr}, least significant byte first. */
    private fun address(bleAddr: ByteArray): String =
        BtPeer.address(bleAddr).reversed().joinToString(":") { "%02X".format(it) }

    private companion object {
        const val ANSWER_MS = 8_000L
    }
}
