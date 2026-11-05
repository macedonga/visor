package com.macedonga.visor.connection

import android.content.Context
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.TransportFactory
import com.macedonga.visor.core.transport.BluetoothLeTransport
import com.macedonga.visor.core.transport.BluetoothRfcommTransport
import com.macedonga.visor.core.transport.LeFirstTransport
import com.macedonga.visor.core.transport.LeKeeper
import com.macedonga.visor.core.transport.TcpTransport
import com.macedonga.visor.core.transport.Transport
import com.macedonga.visor.core.transport.WireLog

/** Builds the byte pipe. */
class AndroidTransportFactory(
    private val context: Context,
    private val keeper: LeKeeper,
    private val wireLog: WireLog? = null,
    // Counts what the Wi-Fi link receives, so a stalled live view can tell a silent link from a lost parse.
    private val wifiMeter: WireLog? = null,
    private val kind: LinkKind? = null,
) : TransportFactory {

    // LE first, as the Meta app, so the session lives through their sleep; RFCOMM while there is no LE link.
    override fun create(link: GlassesLink): Transport = when (link) {
        is GlassesLink.Bluetooth -> LeFirstTransport(
            le = BluetoothLeTransport(context, link.address, wireLog, keeper),
            rfcomm = BluetoothRfcommTransport(context = context, address = link.address, wireLog = wireLog),
            keeper = keeper,
            onLe = { kind?.set(it) },
        )
        is GlassesLink.Wifi -> TcpTransport(link.host, link.port, wifiMeter, link.sockets)
    }
}
