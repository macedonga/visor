package com.macedonga.visor.core.transport

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The glasses' own socket on Wi-Fi. It carries raw SDUs, framed exactly as on RFCOMM. */
class TcpTransport(
    private val host: String,
    private val port: Int,
    private val wireLog: WireLog? = null,
    // A network other than the phone's default, such as the glasses' own, hands out its own sockets.
    private val sockets: SocketFactory? = null,
) : Transport {

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var stream: SduStream? = null

    /** Counts closes, so a connect that was in flight when one happened does not undo it. */
    private val closes = AtomicInteger(0)

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val before = closes.get()
        val opening = sockets?.createSocket() ?: Socket()
        try {
            opening.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            opening.tcpNoDelay = true
        } catch (error: IOException) {
            opening.close()
            throw TransportConnectException(ConnectFailure.NO_ANSWER, "nothing answered on $host:$port (${error.message})", error)
        }
        if (closes.get() != before) {
            runCatching { opening.close() }
            throw TransportClosedException("the link was closed while it was opening")
        }
        socket = opening
        stream = SduStream(BufferedInputStream(opening.getInputStream(), 1 shl 16), opening.getOutputStream(), wireLog)
    }

    override suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) { open().send(frame) }

    override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) { open().receive() }

    override fun handshakeDone() {
        stream?.encrypted = true
    }

    override fun close() {
        closes.incrementAndGet()
        runCatching { socket?.close() }
        socket = null
        stream = null
    }

    private fun open(): SduStream = stream ?: throw TransportClosedException("not connected")

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
    }
}
