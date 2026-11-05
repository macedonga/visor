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
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.media.PhoneGroup
import com.macedonga.visor.core.media.Screencast
import com.macedonga.visor.core.media.ScreencastPhase
import com.macedonga.visor.core.media.WifiUnavailable
import com.macedonga.visor.core.media.joinGlasses
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** svc41107 leases: the session moves to Wi-Fi (the phone's network, else a Wi-Fi Direct group) and back after [IDLE_MS] idle. */
@Singleton
class WifiLink @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val networks: GlassesWifi,
    private val live: Provider<LiveStream>,
    private val media: Provider<MediaImport>,
    private val mirror: Provider<Screencast>,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> say("failed: $e") })
    private val lock = Mutex()
    private val leases = ConcurrentHashMap.newKeySet<String>()
    private var group: PhoneGroup? = null
    private var idle: Job? = null

    // Guarded by [lock]; a watcher acts only while its move is the latest.
    private var moves = 0

    /** Why the phone cannot give a Wi-Fi link now, or null when it can. */
    fun refusal(): String? = when {
        !wifiIsOn(context) -> "the phone's Wi-Fi is off"
        connection.state.value.link != LinkState.READY -> "the glasses are not connected"
        live.get().running.value -> "the Live view is using Wi-Fi"
        media.get().importing.value -> "a media import is using Wi-Fi"
        // Mirror runs its own Wi-Fi Direct session, which a lease's move would end.
        mirror.get().state.value.phase.let { it == ScreencastPhase.STARTING || it == ScreencastPhase.LIVE } -> "the mirror is using Wi-Fi"
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
            // hold() may cancel the wait, not a return under way.
            lock.withLock { if (leases.isEmpty() && connection.onWifi) withContext(NonCancellable) { back("no lease needs Wi-Fi") } }
        }
    }

    private suspend fun moveToWifi() {
        val serial = connection.state.value.target?.serial ?: return
        val joined = sameNetwork() ?: phoneGroup() ?: return say("staying on Bluetooth")
        say("moving the session to ${joined.address}:${joined.port}")
        connection.moveToWifi(GlassesTarget(GlassesLink.Wifi(checkNotNull(joined.address), joined.port), serial))
        val gen = ++moves
        scope.launch { watch(gen) }
    }

    /** Back on Bluetooth if the session does not come up on Wi-Fi, or when it drops there. */
    private suspend fun watch(gen: Int) {
        val up = withTimeoutOrNull(UP_MS) { connection.state.first { it.target?.link is GlassesLink.Wifi && it.link == LinkState.READY } }
        if (up == null) return lock.withLock { if (gen == moves && connection.onWifi) back("the session did not come up over Wi-Fi") }
        say("the session runs over Wi-Fi")
        connection.state.first { it.link != LinkState.READY || !connection.onWifi }
        lock.withLock {
            if (gen != moves) return@withLock
            when {
                connection.onWifi -> back("the Wi-Fi session dropped")
                // The Live or a media import took the glasses for a Wi-Fi session of their own, and the group with it.
                connection.paused -> forget()
                // The session was opened again on Bluetooth some other way.
                else -> {
                    group?.remove()
                    forget()
                }
            }
        }
    }

    private fun forget() {
        moves++
        group = null
        leases.clear()
    }

    private suspend fun back(why: String) {
        moves++
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
        if (!wifiDirectAllowed(context)) {
            say("Wi-Fi Direct is not allowed")
            return null
        }
        val g = PhoneGroup(context) { say(it) }
        try {
            g.create(BAND_2GHZ)
        } catch (unavailable: WifiUnavailable) {
            say("Wi-Fi Direct did not come up: ${unavailable.message}")
            return null
        }
        group = g
        val joined = join(g.ssid, g.passphrase, BW_20MHZ)
        if (joined == null) {
            g.remove()
            group = null
        }
        return joined
    }

    // Fresh out of a group, or of a Wi-Fi restart, the glasses answer INTERNAL_ERROR for a while.
    private suspend fun join(ssid: String, passphrase: String, bandwidth: Int): JoinResult? {
        for (attempt in 1..JOIN_TRIES) {
            if (attempt > 1) delay(RETRY_MS)
            val result = joinGlasses(connection, ssid, passphrase, bandwidth, JOIN_MS)
            if (result?.ok == true) return result
            say(if (result == null) "the glasses did not answer the join to $ssid" else "the glasses could not join $ssid (${result.statusName})")
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun phoneSsid(): String? =
        context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }

    private fun say(text: String) = log.add("Wi-Fi link: $text")

    private companion object {
        const val IDLE_MS = 30_000L
        const val UP_MS = 20_000L
        const val JOIN_MS = 30_000L
        const val RETRY_MS = 4_000L
        const val JOIN_TRIES = 3
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
