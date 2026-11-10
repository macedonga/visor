package com.macedonga.visor.debug

import com.macedonga.visor.band.BandPeers
import com.macedonga.visor.band.Bands
import com.macedonga.visor.band.DeviceLease
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.keyHint
import com.macedonga.visor.core.identity.rawPublicKey
import com.macedonga.visor.core.protocol.BtPeer
import com.macedonga.visor.core.protocol.CamControl
import com.macedonga.visor.log.EventLog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug only: whether the Display's context awareness manager takes the band back on its own. The Display lets go
 * of the band (svc41109 DISCONNECT), gets the band rule (svc151), and is watched for [watchMs]; the band is handed
 * back with svc41109 CONNECT when the Display did not take it. Every step goes to the event log.
 */
internal object BandCamProbe {
    suspend fun run(connection: GlassesConnection, bands: Bands, peers: BandPeers, lease: DeviceLease, log: EventLog, watchMs: Long, rule: Boolean) {
        fun note(text: String) = log.add("CAM probe: $text")
        val glasses = connection.pair.value ?: return note("no glasses")
        val band = bands.forGlasses(glasses) ?: return note("no band set up with $glasses")
        val key = band.publicKey?.let(::rawPublicKey) ?: return note("no key for ${band.serial}")
        val listed = peers.refresh()?.peers ?: return note("the Display did not list its bands")
        val held = listed.firstOrNull { it.connected } ?: return note("the Display holds no band now (${listed.size} listed)")
        val hint = keyHint(key)
        note("the Display holds ${held.identity.toHexString()}; asking it to let go")
        val released = withTimeoutOrNull(10_000) {
            connection.serviceData
                .onSubscription { connection.submit { it.sendOnService(BtPeer.SERVICE, BtPeer.DISCONNECT, BtPeer.disconnect(held.identity, hint)) } }
                .first {
                    it.serviceId == BtPeer.SERVICE && it.messageType == BtPeer.CONNECTION_EVENT &&
                        BtPeer.connectionEvent(it.payload).event == BtPeer.DISCONNECTED
                }
        } != null
        note(if (released) "the Display let go" else "the Display did not say it let go")
        if (rule) {
            connection.submit { it.sendOnService(CamControl.SERVICE, CamControl.OVERRIDE_RULE, CamControl.bandRule(keyHint(key))) }
            note("band rule sent on svc151")
        }
        val leaseBefore = lease.holder.value
        val start = System.currentTimeMillis()
        var back = false
        while (System.currentTimeMillis() - start < watchMs) {
            delay(5_000)
            val now = peers.refresh()?.peers
            val connected = now?.any { it.connected } == true
            note("t+${(System.currentTimeMillis() - start) / 1000}s connected=$connected lease=${lease.holder.value}")
            if (connected) {
                back = true
                break
            }
        }
        if (back) return note("the Display took the band back on its own (lease before: $leaseBefore)")
        note("the Display did not take the band in ${watchMs / 1000} s; handing it back")
        connection.submit { it.sendOnService(BtPeer.SERVICE, BtPeer.CONNECT, BtPeer.connect(held.identity, hint)) }
    }

    /**
     * Whether the Display keeps off the band when told to reach it by RELAY: SET_PREFERRED_LINK RELAY, DISCONNECT, a
     * watch of [watchMs], then SET_PREFERRED_LINK DIRECT and another watch, CONNECT last when it still has no band.
     * Every svc41109 message from the Display goes to the log.
     */
    suspend fun topology(connection: GlassesConnection, bands: Bands, peers: BandPeers, log: EventLog, watchMs: Long) = coroutineScope {
        fun note(text: String) = log.add("Topology probe: $text")
        val glasses = connection.pair.value ?: return@coroutineScope note("no glasses")
        val band = bands.forGlasses(glasses) ?: return@coroutineScope note("no band set up with $glasses")
        val key = band.publicKey?.let(::rawPublicKey) ?: return@coroutineScope note("no key for ${band.serial}")
        val listed = peers.refresh()?.peers ?: return@coroutineScope note("the Display did not list its bands")
        val held = listed.firstOrNull { it.connected } ?: listed.firstOrNull() ?: return@coroutineScope note("the Display lists no band")
        val hint = keyHint(key)
        val heard = launch {
            connection.serviceData.collect { if (it.serviceId == BtPeer.SERVICE && it.messageType != BtPeer.BLE_PAIRING_INFO) note("from the Display: type ${it.messageType} ${it.payload.toHexString()}") }
        }
        val errors = launch {
            connection.errors.collect { if (it.serviceId == BtPeer.SERVICE) note("error 0x%04x".format(it.errorId)) }
        }
        suspend fun watch(what: String): Boolean {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < watchMs) {
                delay(5_000)
                val connected = peers.refresh()?.peers?.any { it.connected } == true
                note("$what t+${(System.currentTimeMillis() - start) / 1000}s connected=$connected")
                if (connected) return true
            }
            return false
        }
        note("band ${held.identity.toHexString()} connected=${held.connected}; preferred link RELAY")
        connection.submit { it.sendOnService(BtPeer.SERVICE, BtPeer.SET_PREFERRED_LINK, BtPeer.setPreferredLink(BtPeer.LINK_RELAY, held.identity, hint)) }
        delay(3_000)
        note("asking the Display to let go")
        connection.submit { it.sendOnService(BtPeer.SERVICE, BtPeer.DISCONNECT, BtPeer.disconnect(held.identity, hint)) }
        val retook = watch("after RELAY + DISCONNECT")
        note(if (retook) "the Display took the band back despite RELAY" else "the Display stayed off the band")
        note("preferred link DIRECT")
        connection.submit { it.sendOnService(BtPeer.SERVICE, BtPeer.SET_PREFERRED_LINK, BtPeer.setPreferredLink(BtPeer.LINK_DIRECT, held.identity, hint)) }
        if (!retook && !watch("after DIRECT")) {
            note("no band yet; CONNECT")
            connection.submit { it.sendOnService(BtPeer.SERVICE, BtPeer.CONNECT, BtPeer.connect(held.identity, hint)) }
            watch("after CONNECT")
        }
        heard.cancel()
        errors.cancel()
        note("done")
    }

    /**
     * Whether a band rule with scope [scope] keeps the Display off the band: the rule, a watch of [watchMs], then the
     * LOW rule again and another watch, CONNECT last when it still has no band. svc151 and svc41109 messages from the
     * Display go to the log.
     */
    suspend fun rule(connection: GlassesConnection, bands: Bands, peers: BandPeers, log: EventLog, scope: Int, watchMs: Long, onBody: Boolean, family: Int, disconnect: Boolean) = coroutineScope {
        fun note(text: String) = log.add("Rule probe: $text")
        val glasses = connection.pair.value ?: return@coroutineScope note("no glasses")
        val band = bands.forGlasses(glasses) ?: return@coroutineScope note("no band set up with $glasses")
        val key = band.publicKey?.let(::rawPublicKey) ?: return@coroutineScope note("no key for ${band.serial}")
        val listed = peers.refresh()?.peers ?: return@coroutineScope note("the Display did not list its bands")
        val held = listed.firstOrNull { it.connected } ?: listed.firstOrNull() ?: return@coroutineScope note("the Display lists no band")
        val heard = launch {
            connection.serviceData.collect {
                if (it.serviceId == CamControl.SERVICE || it.serviceId == BtPeer.SERVICE && it.messageType != BtPeer.BLE_PAIRING_INFO) {
                    note("from the Display: svc${it.serviceId} type ${it.messageType} ${it.payload.toHexString()}")
                }
            }
        }
        val errors = launch {
            connection.errors.collect { if (it.serviceId == CamControl.SERVICE || it.serviceId == BtPeer.SERVICE) note("svc${it.serviceId} error 0x%04x".format(it.errorId)) }
        }
        suspend fun watch(what: String, until: Boolean): Boolean {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < watchMs) {
                delay(5_000)
                val connected = peers.refresh()?.peers?.any { it.connected } == true
                note("$what t+${(System.currentTimeMillis() - start) / 1000}s connected=$connected")
                if (connected == until) return true
            }
            return false
        }
        note("band ${held.identity.toHexString()} connected=${held.connected}; rule with scope $scope, onBody $onBody, family $family")
        connection.submit { it.sendOnService(CamControl.SERVICE, CamControl.OVERRIDE_RULE, CamControl.bandRule(keyHint(key), scope, onBody, family)) }
        if (disconnect) {
            delay(2_000)
            note("asking the Display to let go")
            connection.submit { it.sendOnService(BtPeer.SERVICE, BtPeer.DISCONNECT, BtPeer.disconnect(held.identity, keyHint(key))) }
        }
        val dropped = watch("after the rule", until = false)
        note(if (dropped) "the Display let go of the band" else "the Display kept the band")
        if (dropped) {
            val stayed = !watch("staying off", until = true)
            note(if (stayed) "the Display stayed off the band for ${watchMs / 1000} s" else "the Display took the band back")
        }
        note("LOW rule again")
        connection.submit { it.sendOnService(CamControl.SERVICE, CamControl.OVERRIDE_RULE, CamControl.bandRule(keyHint(key), onBody = onBody, glassesFamily = family)) }
        if (!watch("after LOW", until = true)) {
            note("no band yet; CONNECT")
            connection.submit { it.sendOnService(BtPeer.SERVICE, BtPeer.CONNECT, BtPeer.connect(held.identity, keyHint(key))) }
            watch("after CONNECT", until = true)
        }
        heard.cancel()
        errors.cancel()
        note("done")
    }

    /**
     * Whether the band takes the phone while the Display holds it: BandLink reaches the band without asking the Display
     * to let go (its pairing hold), the link is kept for a few seconds, and the Display's own link is read before and
     * after.
     */
    suspend fun direct(link: com.macedonga.visor.band.BandLink, peers: BandPeers, log: EventLog) {
        fun note(text: String) = log.add("Direct probe: $text")
        note("before: the Display ${if (peers.refresh()?.peers?.any { it.connected } == true) "holds" else "does not hold"} the band")
        val reached = link.startPairing()
        note(if (reached) "the phone reached the band beside the Display" else "the phone did not reach the band")
        if (reached) note("during: the Display ${if (peers.refresh()?.peers?.any { it.connected } == true) "still holds" else "lost"} the band")
        delay(5_000)
        link.endPairing()
        delay(10_000)
        note("after: the Display ${if (peers.refresh()?.peers?.any { it.connected } == true) "holds" else "does not hold"} the band")
    }
}
