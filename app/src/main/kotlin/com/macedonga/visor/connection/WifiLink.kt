package com.macedonga.visor.connection

import android.content.Context
import android.net.wifi.WifiManager
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.connection.GlassesWifi
import com.macedonga.visor.core.media.BAND_2GHZ
import com.macedonga.visor.core.media.BW_20MHZ
import com.macedonga.visor.core.media.BW_UNSPECIFIED
import com.macedonga.visor.core.media.JoinResult
import com.macedonga.visor.core.media.MT_JOIN_ACCESS_POINT_RESULT
import com.macedonga.visor.core.media.PhoneGroup
import com.macedonga.visor.core.media.SVC_ATC
import com.macedonga.visor.core.media.WifiUnavailable
import com.macedonga.visor.core.media.joinAccessPoint
import com.macedonga.visor.core.media.parseJoinResult
import com.macedonga.visor.core.media.wifiDirectAllowed
import com.macedonga.visor.core.media.wifiIsOn
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Wi-Fi link the Display asks for with a link lease (svc41107), for WhatsApp, Instagram and other heavy traffic.
 * As the Meta app's link manager does, the one session moves to Wi-Fi while a lease holds it and comes back after: on
 * the network the phone is on when the glasses have it saved (they stay on it, with their own internet), else on a
 * Wi-Fi Direct group of the phone's. Back on Bluetooth when no lease has held Wi-Fi for [IDLE_MS], or when the Wi-Fi
 * session fails.
 */
@Singleton
class WifiLink @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val networks: GlassesWifi,
    private val log: Provider<EventLog>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> say("failed: $e") })
    private val lock = Mutex()
    private val leases = ConcurrentHashMap.newKeySet<String>()
    private var group: PhoneGroup? = null
    private var idle: Job? = null

    /** Why the phone cannot give a Wi-Fi link now, or null when it can. */
    fun refusal(): String? = when {
        !wifiIsOn(context) -> "the phone's Wi-Fi is off"
        connection.state.value.link != LinkState.READY -> "the glasses are not connected"
        else -> null
    }

    /** A granted lease that needs Wi-Fi: the session moves there unless it is already. */
    fun hold(id: String) {
        leases += id
        idle?.cancel()
        scope.launch { lock.withLock { if (!connection.onWifi && leases.isNotEmpty()) moveToWifi() } }
    }

    /** A lease ended: with none left, back to Bluetooth after [IDLE_MS]. */
    fun release(id: String) {
        if (!leases.remove(id) || leases.isNotEmpty()) return
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_MS)
            lock.withLock { if (leases.isEmpty() && connection.onWifi) back("no lease needs Wi-Fi") }
        }
    }

    private suspend fun moveToWifi() {
        val serial = connection.state.value.target?.serial ?: return
        val joined = sameNetwork() ?: phoneGroup() ?: return say("staying on Bluetooth")
        say("moving the session to ${joined.address}:${joined.port}")
        connection.moveToWifi(GlassesTarget(GlassesLink.Wifi(checkNotNull(joined.address), joined.port), serial))
        scope.launch { watch() }
    }

    /** Back on Bluetooth if the session does not come up on Wi-Fi, or when it drops there. */
    private suspend fun watch() {
        val up = withTimeoutOrNull(UP_MS) { connection.state.first { it.target?.link is GlassesLink.Wifi && it.link == LinkState.READY } }
        if (up == null) return lock.withLock { if (connection.onWifi) back("the session did not come up over Wi-Fi") }
        say("the session runs over Wi-Fi")
        connection.state.first { it.link != LinkState.READY || !connection.onWifi }
        lock.withLock {
            // Live or a media import took the glasses for a Wi-Fi session of their own, and their group with it.
            if (!connection.onWifi) group = null else back("the Wi-Fi session dropped")
        }
    }

    private suspend fun back(why: String) {
        say("back to Bluetooth: $why")
        leases.clear()
        connection.backToBluetooth()
        group?.remove()
        group = null
    }

    /** The glasses joined to the network the phone is on, when they keep its passphrase. */
    private suspend fun sameNetwork(): JoinResult? {
        val ssid = phoneSsid() ?: return null
        val key = networks.keyOf(ssid) ?: return null
        return join(ssid, key, BW_UNSPECIFIED)?.also { say("the glasses are on $ssid with the phone") }
    }

    /** The glasses joined to a Wi-Fi Direct group of the phone's. */
    private suspend fun phoneGroup(): JoinResult? {
        if (!wifiDirectAllowed(context)) return null.also { say("Wi-Fi Direct is not allowed") }
        val g = PhoneGroup(context) { say(it) }
        try {
            g.create(BAND_2GHZ)
        } catch (unavailable: WifiUnavailable) {
            return null.also { say("Wi-Fi Direct did not come up: ${unavailable.message}") }
        }
        group = g
        return join(g.ssid, g.passphrase, BW_20MHZ) ?: null.also {
            g.remove()
            group = null
        }
    }

    // Fresh out of a group, or of a Wi-Fi restart, the glasses answer INTERNAL_ERROR for a while.
    private suspend fun join(ssid: String, passphrase: String, bandwidth: Int): JoinResult? {
        for (attempt in 1..JOIN_TRIES) {
            if (attempt > 1) delay(RETRY_MS)
            val result = withTimeoutOrNull(JOIN_MS) {
                connection.serviceData
                    .onSubscription { connection.submit { joinAccessPoint(it, ssid, passphrase, bandwidth) } }
                    .first { it.serviceId == SVC_ATC && it.messageType == MT_JOIN_ACCESS_POINT_RESULT }
                    .let { parseJoinResult(it.payload) }
            }
            if (result?.ok == true) return result
            say(if (result == null) "the glasses did not answer the join to $ssid" else "the glasses could not join $ssid (${result.statusName})")
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun phoneSsid(): String? =
        context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }

    private fun say(text: String) = log.get().add("Wi-Fi link: $text")

    private companion object {
        const val IDLE_MS = 30_000L
        const val UP_MS = 20_000L
        const val JOIN_MS = 30_000L
        const val RETRY_MS = 4_000L
        const val JOIN_TRIES = 3
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
