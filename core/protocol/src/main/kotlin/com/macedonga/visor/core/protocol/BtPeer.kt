package com.macedonga.visor.core.protocol

import java.util.UUID

/** svc41109, bt_peer_connection, on a channel the phone opens on the Display and on the band; a BleAddr is {1 type, 2 addr}. */
object BtPeer {
    const val SERVICE = 41109

    const val GET_BLE_PAIRING_INFO = 1
    const val BLE_PAIRING_INFO = 2
    const val ENABLE_PAIRING_MODE = 3
    const val PAIRING_MODE_READY = 4
    const val PAIR = 5
    const val PAIRING_EVENT = 6
    const val CONNECT = 7
    const val CONNECTION_EVENT = 8
    const val TOPOLOGY_EVENT = 13

    // A DataX error id on this service.
    const val ALREADY_PAIRED = 0xEF01

    const val JUST_WORKS = 0
    const val TOPOLOGY_SWITCHING = 0
    const val PAIRED = 0
    const val CONNECTED = 3
    const val TOPOLOGY_START = 1
    const val TOPOLOGY_END = 2
    const val TOPOLOGY_PAIR_AND_CONNECT = 1
    const val TOPOLOGY_CONNECT = 2

    /** ConnectionState {1 peerIdentityAddr, 2 isConnected, 3 peerKeyHint}. */
    class Peer(val identity: ByteArray, val connected: Boolean)

    /** ActiveLink {1 link, 2 peerIdentityAddr, 3 peerKeyHint}. */
    class ActiveLink(val link: Int, val identity: ByteArray)

    /**
     * BLE_PAIRING_INFO {1 bleIdentityAddr, 2 supportedPairingMethods, 3 pairedPeers, 4 supportedCapabilities, 5 activeLink,
     * 7 bleResolvablePrivateAddr}; a missing address is empty.
     */
    class PairingInfo(
        val identity: ByteArray,
        val methods: List<Int>,
        val peers: List<Peer>,
        val capabilities: List<Int>,
        val links: List<ActiveLink>,
        val rpa: ByteArray,
    ) {
        /** Whether [bleAddr] is among the peers this side has paired. */
        fun lists(bleAddr: ByteArray): Boolean = peers.any { BtPeer.sameAddress(it.identity, bleAddr) }
    }

    /** PAIRING_MODE_READY {1 bleIdentityAddr, 2 bleAdvAddr, 3 supportedPairingMethods}. */
    class PairingModeReady(val identity: ByteArray, val advertising: ByteArray)

    /** PAIRING_EVENT {1 event, 2 peerIdentityAddr}. */
    class PairingEvent(val event: Int, val identity: ByteArray)

    /** CONNECTION_EVENT {1 event, 2 peerIdentityAddr, 3 error}. */
    class ConnectionEvent(val event: Int, val identity: ByteArray)

    fun getPairingInfo(): ByteArray = ByteArray(0)

    /** ENABLE_PAIRING_MODE {1 peerIdentityAddr, 2 useForce, 4 peerResolvablePrivateAddr}: both addresses always go, empty or not. */
    fun enablePairingMode(peerIdentity: ByteArray, force: Boolean, peerRpa: ByteArray): ByteArray =
        Wire.fieldBytes(1, peerIdentity) + (if (force) Wire.fieldVarint(2, 1) else ByteArray(0)) + Wire.fieldBytes(4, peerRpa)

    /** PAIR {1 pairingMethod JUST_WORKS (left out), 2 bleIdentityAddr, 3 bleAdvAddr, 4 peerKeyHint}. */
    fun pair(identity: ByteArray, advertising: ByteArray, keyHint: ByteArray): ByteArray =
        Wire.fieldBytes(2, identity) + Wire.fieldBytes(3, advertising) + Wire.fieldBytes(4, keyHint)

    /** CONNECT {1 bleIdentityAddr, 2 peerKeyHint}. */
    fun connect(identity: ByteArray, keyHint: ByteArray): ByteArray = Wire.fieldBytes(1, identity) + Wire.fieldBytes(2, keyHint)

    /** TOPOLOGY_EVENT {1 peerIdentityAddr, 2 peerKeyHint, 3 uuid, 4 event, 5 type}; an empty address or hint is left out. */
    fun topologyEvent(peerIdentity: ByteArray, keyHint: ByteArray, uuid: UUID, event: Int, type: Int): ByteArray {
        val id = uuid.toBytes()
        return (if (peerIdentity.isNotEmpty()) Wire.fieldBytes(1, peerIdentity) else ByteArray(0)) +
            (if (keyHint.isNotEmpty()) Wire.fieldBytes(2, keyHint) else ByteArray(0)) +
            Wire.fieldBytes(3, id) + Wire.fieldVarint(4, event.toLong()) + Wire.fieldVarint(5, type.toLong())
    }

    fun pairingInfo(payload: ByteArray): PairingInfo {
        val f = Wire.parse(payload)
        return PairingInfo(
            identity = f.bytes(1),
            methods = enums(f, 2),
            peers = f.repeatedBytes(3).map(Wire::parse).map { Peer(it.bytes(1), it.varint(2) != 0L) },
            capabilities = enums(f, 4),
            links = f.repeatedBytes(5).map(Wire::parse).map { ActiveLink(it.varint(1).toInt(), it.bytes(2)) },
            rpa = f.bytes(7),
        )
    }

    fun pairingModeReady(payload: ByteArray): PairingModeReady {
        val f = Wire.parse(payload)
        return PairingModeReady(f.bytes(1), f.bytes(2))
    }

    fun pairingEvent(payload: ByteArray): PairingEvent {
        val f = Wire.parse(payload)
        return PairingEvent(f.varint(1).toInt(), f.bytes(2))
    }

    fun connectionEvent(payload: ByteArray): ConnectionEvent {
        val f = Wire.parse(payload)
        return ConnectionEvent(f.varint(1).toInt(), f.bytes(2))
    }

    /** The address bytes of a BleAddr, least significant first. */
    fun address(bleAddr: ByteArray): ByteArray = Wire.parse(bleAddr).bytes(2)

    /** Whether two BleAddr name the same address, whatever type each gives. */
    fun sameAddress(a: ByteArray, b: ByteArray): Boolean = runCatching {
        val x = address(a)
        x.isNotEmpty() && x.contentEquals(address(b))
    }.getOrDefault(false)

    // A repeated enum, packed or not.
    private fun enums(f: Wire.Fields, n: Int): List<Int> =
        f.repeatedVarints(n).map { it.toInt() } + f.repeatedBytes(n).flatMap { packed ->
            buildList<Int> {
                var at = 0
                while (at < packed.size) {
                    val v = Wire.decodeVarint(packed, at)
                    add(v.value.toInt())
                    at = v.offset
                }
            }
        }
}
