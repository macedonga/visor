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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
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
}
