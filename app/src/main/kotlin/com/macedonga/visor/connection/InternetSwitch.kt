package com.macedonga.visor.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * svc41108, internet_switch: how the phone's internet is (type 3): connected, metered, and its DNS servers. The Display
 * asks on a channel of its own (type 6), but takes the state only on the phone's channel, which the phone opens when the
 * Display asks and writes again whenever its network changes. The Display keeps its apps off the phone's internet until
 * it hears the phone is connected.
 */
@Singleton
class InternetSwitch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: Provider<GlassesConnection>,
    private val log: Provider<EventLog>,
) : Responder {
    private val watching = AtomicBoolean(false)

    /** False keeps the Display off the phone's internet: it is told the phone is offline. */
    @Volatile var sharing = true
        set(value) {
            field = value
            push()
        }

    override suspend fun respond(message: DataXMessage, mux: DataXConnection) {
        if (message.info.messageType != MT_LISTEN) return
        val state = phoneInternet()
        log.get().add("Internet: the Display asked; the phone is ${if (state.connected) "online" else "offline"}" + if (state.metered) ", metered" else "")
        mux.send(DataXConnection.mirror(message.info.channelId), encode(state), messageType = MT_COMPANION_STATE)
        mux.sendOnService(SVC, MT_COMPANION_STATE, encode(state))
        watchNetwork()
    }

    /** svc76, wearables_ping: a ping (type 1) is answered with PingResult {1 hasInternet} (type 2). */
    val ping = Responder { message, mux ->
        if (message.info.messageType != MT_PING || message.info.flags.isClosed) return@Responder
        val online = phoneInternet().connected
        mux.send(DataXConnection.mirror(message.info.channelId), Wire.fieldVarint(1, if (online) 1 else 0), messageType = MT_PING_RESULT)
    }

    /** From the first question on, every change of the phone's network goes to the Display while it is connected. */
    private fun watchNetwork() {
        if (!watching.compareAndSet(false, true)) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = push()
            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = push()
            override fun onLost(network: Network) = push()
        })
    }

    private fun push() {
        val glasses = connection.get()
        if (glasses.state.value.link != LinkState.READY) return
        val state = encode(phoneInternet())
        glasses.submit { it.sendOnService(SVC, MT_COMPANION_STATE, state) }
    }

    private data class PhoneInternet(val connected: Boolean, val metered: Boolean, val dns: List<String>)

    private fun phoneInternet(): PhoneInternet {
        if (!sharing) return PhoneInternet(false, false, emptyList())
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return PhoneInternet(false, false, emptyList())
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val dns = network?.let { cm.getLinkProperties(it) }?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
        return PhoneInternet(online, cm.isActiveNetworkMetered, dns)
    }

    private fun encode(s: PhoneInternet): ByteArray =
        Wire.fieldVarint(1, if (s.connected) 1 else 0) + Wire.fieldVarint(2, if (s.metered) 1 else 0) +
            Wire.fieldBytes(3, s.dns.fold(ByteArray(0)) { acc, a -> acc + Wire.fieldBytes(1, a.encodeToByteArray()) })

    companion object {
        const val SVC = 41108
        private const val MT_COMPANION_STATE = 3
        private const val MT_LISTEN = 6
        const val PING_SVC = 76
        private const val MT_PING = 1
        private const val MT_PING_RESULT = 2
    }
}
