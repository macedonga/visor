package com.macedonga.visor.core.connection

import com.macedonga.visor.core.identity.Diagnosis
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.transport.ConnectFailure

/** Where the glasses are reached. */
sealed interface GlassesLink {

    /** An address as `BluetoothAdapter.checkBluetoothAddress` reads it. */
    data class Bluetooth(val address: String) : GlassesLink

    /**
     * The glasses' socket on Wi-Fi: on a Wi-Fi Direct group this phone owns, or on the glasses' own network, which is
     * not the phone's default one, so its socket comes from [sockets].
     */
    data class Wifi(val host: String, val port: Int, val sockets: javax.net.SocketFactory? = null) : GlassesLink
}

/** Which glasses, and how to reach them. */
data class GlassesTarget(val link: GlassesLink, val serial: String)

/** Everything the app knows about the connection, in one value. */
data class GlassesState(
    /** Null when no glasses are claimed on this phone, or before the store is read. */
    val target: GlassesTarget? = null,
    /** True once the store was read and holds no glasses. */
    val noGlasses: Boolean = false,
    val link: LinkState = LinkState.DISCONNECTED,
    /** Why the link last dropped, or why it paused: the session's own words. */
    val reason: String = "",
    /** What a person could do about a connect that failed. Null when none has. */
    val failure: ConnectFailure? = null,
    /** The verdict, once one has been reached on this attempt. Null before that. */
    val diagnosis: Diagnosis? = null,
    /** svc28's device state on this attempt: the 2005 snapshot with every later push merged in. */
    val device: DeviceState? = null,
    /** svc100's answer on this attempt, the canary's reply decoded. Null until it arrives. */
    val info: DeviceInfo? = null,
)
