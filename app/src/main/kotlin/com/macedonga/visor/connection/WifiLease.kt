package com.macedonga.visor.connection

import android.content.Context
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.wifiDirectAllowed
import com.macedonga.visor.core.media.wifiIsOn
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Wi-Fi link the Display asks for on svc41107 (a link lease that needs Wi-Fi). While one is held the phone moves
 * to a Wi-Fi session with the glasses, as [LiveStream.overWifi] does for media, and the internet tunnel (svc41111)
 * runs there with svc41108's state. The move ends when no lease is left and the tunnel has been idle for two minutes.
 */
@Singleton
class WifiLease @Inject constructor(
    @ApplicationContext private val context: Context,
    private val live: Provider<LiveStream>,
    private val internet: Provider<InternetSwitch>,
    private val tunnel: Provider<PhoneInternet>,
    private val log: Provider<EventLog>,
) {
    private val leases = ConcurrentHashMap.newKeySet<String>()
    private val moving = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun add(id: String) {
        leases.add(id)
    }

    fun remove(id: String) {
        leases.remove(id)
    }

    /** Why the phone cannot take a Wi-Fi link now, or null. While a move runs, another lease simply joins it. */
    fun refusal(): String? {
        if (moving.get()) return null
        val glasses = live.get()
        return when {
            glasses.running.value || glasses.wifiSession.value != null -> "the live view is using Wi-Fi"
            !wifiIsOn(context) -> "Wi-Fi is off"
            !wifiDirectAllowed(context) -> "Wi-Fi Direct is not allowed"
            else -> null
        }
    }

    /** Moves to Wi-Fi for the leases held, unless a move is running already. */
    fun hold() {
        if (!moving.compareAndSet(false, true)) return
        scope.launch {
            try {
                log.get().add("Wi-Fi: moving to Wi-Fi Direct, because the Display asked for a link that needs Wi-Fi")
                val failed = live.get().overWifi { mux -> ride(mux) }
                if (failed == null) {
                    log.get().add("Wi-Fi: back on Bluetooth")
                    internet.get().pushWhenReady()
                } else {
                    leases.clear()
                    log.get().add("Wi-Fi: the move did not start: $failed")
                }
            } finally {
                moving.set(false)
            }
        }
    }

    /** Keeps the Wi-Fi session up until no lease is left and the tunnel is idle, or the Live view takes Wi-Fi. */
    private suspend fun ride(mux: DataXConnection): String? {
        internet.get().pushOn(mux)
        tunnel.get().touch()
        while (true) {
            delay(1_000)
            val glasses = live.get()
            if (glasses.running.value) {
                log.get().add("Wi-Fi: leaving Wi-Fi, because the live view started")
                break
            }
            if (glasses.wifiSession.value == null) {
                log.get().add("Wi-Fi: leaving Wi-Fi, because the Wi-Fi link dropped")
                break
            }
            if (leases.isEmpty() && tunnel.get().idleMillis() >= IDLE_MS) {
                log.get().add("Wi-Fi: leaving Wi-Fi, because no link lease is held and the tunnel has been idle for 2 min")
                break
            }
        }
        return null
    }

    companion object {
        private const val IDLE_MS = 120_000L
    }
}
