package com.macedonga.visor.connection

import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * svc41111, internet_proxy_remote: the Display's apps reach the internet through the phone. The Display's tunnel sends
 * each IP packet as one message (type 1), framed `1c fb`, its length, its ethertype and two spare bytes; replies go
 * back the same way on the same channel. The phone ends the connections itself: each TCP connection from the Display
 * becomes a socket of the phone's, each UDP flow a datagram socket, so the Display's traffic is the phone's own.
 * Other types go to [PhoneHttpRelay]: a single HTTP request (type 8) answered on its own channel.
 */
@Singleton
class PhoneInternet @Inject constructor(
    private val requests: RequestLog,
    private val log: Provider<EventLog>,
    private val relay: PhoneHttpRelay,
) : Responder {
    // A connection that fails ends alone; nothing in here may take the app down.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> log.get().add("Internet: a connection failed: $e") })
    private val sessions = ConcurrentHashMap<Int, Session>()
    // Tunnel frames waiting for the link. Control frames of the session share its one send lock, so they wait
    // behind at most this many of these.
    private val tunnelSlots = Semaphore(TUNNEL_FRAMES)
    // The connection the tunnels run on; a new one ends the old tunnels.
    private var owner: DataXConnection? = null
    // When the tunnel last carried anything either way; a move to Wi-Fi for a link ends once it has been idle a while.
    private val lastTraffic = AtomicLong(System.currentTimeMillis())

    fun idleMillis(): Long = System.currentTimeMillis() - lastTraffic.get()

    fun touch() = lastTraffic.set(System.currentTimeMillis())

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val info = message.info
        val channel = info.channelId
        touch()
        if (connection !== owner) {
            // A tunnel of an earlier connection would write on this one under the old keys, and keep retransmitting.
            owner = connection
            sessions.values.forEach { it.close() }
            sessions.clear()
        }
        if (info.flags.isClosed && message.payload.isEmpty()) {
            sessions.remove(channel)?.close()
            relay.forget(channel)
            return
        }
        when (info.messageType) {
            MT_START -> {
                sessions.remove(channel)?.close()
                sessions[channel] = Session(connection, DataXConnection.mirror(channel))
                log.get().add("Internet: the Display's apps are using the phone's internet")
            }
            MT_FRAME -> sessions.getOrPut(channel) { Session(connection, DataXConnection.mirror(channel)) }.inbound(message.payload)
            else -> relay.handle(message, connection)
        }
    }

    /** One tunnel from the Display: its TCP and UDP flows, and the way back. */
    private inner class Session(private val mux: DataXConnection, private val reply: Int) {
        private val tcp = ConcurrentHashMap<String, TcpFlow>()
        private val udp = ConcurrentHashMap<String, UdpFlow>()
        private val ids = AtomicInteger(Random.nextInt(0xFFFF))

        fun inbound(frame: ByteArray) {
            if (frame.size < ICEE_HEADER) return
            val head = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            if (head.getShort(0).toInt() and 0xFFFF != ICEE_MAGIC) return
            val length = head.getShort(2).toInt() and 0xFFFF
            val packet = IpPacket.parse(frame.copyOfRange(ICEE_HEADER, (ICEE_HEADER + length).coerceAtMost(frame.size))) ?: return
            when (packet.protocol) {
                IpPacket.TCP -> {
                    val seg = TcpSegment.parse(packet.transport) ?: return
                    val key = key(packet, seg.srcPort, seg.dstPort)
                    val flow = tcp[key] ?: if (seg.syn && !seg.hasAck) TcpFlow(this, packet, seg).also { tcp[key] = it } else {
                        // A segment for a connection the phone does not know: tell the Display it is gone.
                        if (!seg.rst) resetFor(packet, seg)
                        return
                    }
                    flow.inbox.trySend(seg)
                }
                IpPacket.UDP -> {
                    val d = UdpDatagram.parse(packet.transport) ?: return
                    val key = key(packet, d.srcPort, d.dstPort)
                    // A flow the phone cannot reach (IPv6 on a phone without it) is dropped; the Display falls back.
                    val flow = udp[key] ?: runCatching { UdpFlow(this, packet, d.srcPort, d.dstPort, key) }.getOrNull()?.also { udp[key] = it } ?: return
                    flow.send(d.data)
                }
            }
        }

        fun forget(key: String) {
            tcp.remove(key)
            udp.remove(key)
        }

        /** An IP packet to the Display, framed for its tunnel. */
        suspend fun deliver(version: Int, src: ByteArray, dst: ByteArray, protocol: Int, transport: ByteArray) {
            touch()
            val ip = IpPacket.build(version, src, dst, protocol, transport, ids.incrementAndGet())
            val frame = ByteBuffer.allocate(ICEE_HEADER + ip.size).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(ICEE_MAGIC.toShort()).putShort(ip.size.toShort())
                .order(ByteOrder.BIG_ENDIAN).putShort(if (version == 4) ETHER_IPV4 else ETHER_IPV6).putShort(0)
                .put(ip).array()
            tunnelSlots.withPermit { mux.send(reply, frame, messageType = MT_FRAME) }
        }

        private fun resetFor(p: IpPacket, seg: TcpSegment) {
            val ack = (seg.seq + seg.data.size + (if (seg.syn) 1 else 0) + (if (seg.fin) 1 else 0)) and MASK
            val rst = TcpSegment.build(seg.dstPort, seg.srcPort, if (seg.hasAck) seg.ack else 0, ack, TcpSegment.RST or TcpSegment.ACK, 0, ByteArray(0))
            scope.launch { runCatching { deliver(p.version, p.dst, p.src, IpPacket.TCP, rst) } }
        }

        fun close() {
            tcp.values.forEach { it.close() }
            udp.values.forEach { it.close() }
            tcp.clear()
            udp.clear()
        }
    }

    /** One TCP connection from the Display, carried on a socket of the phone's. */
    private inner class TcpFlow(private val s: Session, p: IpPacket, syn: TcpSegment) {
        val inbox = Channel<TcpSegment>(Channel.UNLIMITED)
        private val version = p.version
        private val display = p.src
        private val remote = p.dst
        private val remoteAddress = p.dstAddress
        private val displayPort = syn.srcPort
        private val remotePort = syn.dstPort
        private val key = key(p, syn.srcPort, syn.dstPort)
        private val mss = if (version == 4) MSS_V4 else MSS_V6
        private val lock = Mutex()
        private val progress = MutableStateFlow(0L)
        private val logId = requests.start("tcp://${p.dstAddress.hostAddress}:$remotePort", SOURCE_TUNNEL)

        private var socket: Socket? = null
        private var output: OutputStream? = null
        private val iss = Random.nextLong(0, MASK)
        private var sndUna = iss
        private var sndNxt = iss
        private var rcvNxt = (syn.seq + 1) and MASK
        private var peerWindow = syn.window
        private var pending = ByteArray(0)
        private var established = false
        private var peerFin = false
        private var finSent = false
        private var named = false
        @Volatile private var finished = false
        private var bytes = 0L
        private val job: Job = scope.launch { run() }

        private suspend fun run() {
            val connected = runCatching {
                runInterruptible { Socket().apply { connect(InetSocketAddress(remoteAddress, remotePort), CONNECT_MS); tcpNoDelay = true } }
            }.getOrElse {
                send(TcpSegment.RST or TcpSegment.ACK, 0, ByteArray(0))
                return finish("unreachable", failed = true)
            }
            socket = connected
            output = connected.getOutputStream()
            lock.withLock { synAck() }
            while (true) {
                val next = withTimeoutOrNull(IDLE_MS) { inbox.receiveCatching() } ?: return finish("idle")
                // Closed: the tunnel or the link went, and close() has already let go of everything.
                val seg = next.getOrNull() ?: return
                if (seg.rst) return finish("reset by the Display")
                if (seg.syn) {
                    lock.withLock { synAck() }
                    continue
                }
                if (seg.hasAck) onAck(seg)
                if (seg.data.isNotEmpty() || seg.fin) onData(seg)
                if (peerFin && finSent && sndUna == sndNxt) return finish("closed")
            }
        }

        private suspend fun synAck() {
            sndNxt = (iss + 1) and MASK
            send(TcpSegment.SYN or TcpSegment.ACK, iss, ByteArray(0), mss)
        }

        private suspend fun onAck(seg: TcpSegment) {
            lock.withLock {
                val acked = diff(seg.ack, sndUna)
                if (acked > 0 && diff(seg.ack, sndNxt) <= 0) {
                    val dataAcked = acked.coerceAtMost(pending.size.toLong()).toInt()
                    pending = pending.copyOfRange(dataAcked, pending.size)
                    sndUna = seg.ack
                }
                peerWindow = seg.window
                if (!established && diff(seg.ack, iss) > 0) {
                    established = true
                    scope.launch { pump() }
                }
            }
            progress.value++
        }

        private suspend fun onData(seg: TcpSegment) {
            if (seg.seq != rcvNxt) {
                // Out of order or a repeat: the last good point again, and the Display sends it anew.
                lock.withLock { send(TcpSegment.ACK, sndNxt, ByteArray(0)) }
                return
            }
            if (seg.data.isNotEmpty()) {
                if (!named) {
                    named = true
                    tlsServerName(seg.data)?.let { requests.rename(logId, "tls://$it:$remotePort") }
                }
                val ok = runCatching { runInterruptible { output?.write(seg.data); output?.flush() } }.isSuccess
                if (!ok) {
                    lock.withLock { send(TcpSegment.RST or TcpSegment.ACK, sndNxt, ByteArray(0)) }
                    finish("the server went away", failed = true)
                    return
                }
                bytes += seg.data.size
            }
            lock.withLock {
                rcvNxt = (rcvNxt + seg.data.size) and MASK
                if (seg.fin && !peerFin) {
                    peerFin = true
                    rcvNxt = (rcvNxt + 1) and MASK
                    runCatching { socket?.shutdownOutput() }
                }
                send(TcpSegment.ACK, sndNxt, ByteArray(0))
            }
        }

        /** The server's bytes to the Display, as much as its window lets through, again when it goes unanswered. */
        private suspend fun pump() {
            val input: InputStream = socket?.getInputStream() ?: return
            val buffer = ByteArray(mss)
            try {
                while (scope.isActive) {
                    val n = runInterruptible { input.read(buffer) }
                    if (n < 0) break
                    if (n == 0) continue
                    waitForWindow(n)
                    lock.withLock {
                        val data = buffer.copyOf(n)
                        send(TcpSegment.ACK or TcpSegment.PSH, sndNxt, data)
                        pending += data
                        sndNxt = (sndNxt + n) and MASK
                    }
                    bytes += n
                }
            } catch (_: Exception) {
            }
            waitForWindow(0)
            lock.withLock {
                if (!finSent) {
                    finSent = true
                    send(TcpSegment.FIN or TcpSegment.ACK, sndNxt, ByteArray(0))
                    sndNxt = (sndNxt + 1) and MASK
                }
            }
        }

        private suspend fun waitForWindow(n: Int) {
            while (true) {
                val (inFlight, window, last) = lock.withLock { Triple(pending.size, peerWindow, progress.value) }
                if (inFlight + n <= window.coerceAtLeast(mss) && (n > 0 || inFlight == 0)) return
                if (withTimeoutOrNull(RETRANSMIT_MS) { progress.first { it != last } } == null) retransmit()
                if (!job.isActive) return
            }
        }

        /**
         * Resend the oldest unacked segment only. Resending the whole window each tick queued up to 48 frames per flow
         * every 1.5 s behind a link that was already not keeping up, and held this flow's lock while they waited.
         */
        private suspend fun retransmit() {
            val (seq, data) = lock.withLock { sndUna to pending.copyOf(minOf(pending.size, mss)) }
            if (data.isNotEmpty()) send(TcpSegment.ACK or TcpSegment.PSH, seq, data)
        }

        private suspend fun send(flags: Int, seq: Long, data: ByteArray, mssOption: Int = 0) {
            val t = TcpSegment.build(remotePort, displayPort, seq, rcvNxt, flags, WINDOW, data, mssOption)
            runCatching { s.deliver(version, remote, display, IpPacket.TCP, t) }
        }

        private fun finish(outcome: String, failed: Boolean = false) {
            if (!finished) requests.finish(logId, outcome, failed, bytes)
            finished = true
            close()
        }

        fun close() {
            if (!finished) requests.finish(logId, "the tunnel ended", failed = false, bytes = bytes)
            finished = true
            s.forget(key)
            runCatching { socket?.close() }
            job.cancel()
            inbox.close()
        }
    }

    /** One UDP flow from the Display: a datagram socket, kept while it is used. */
    private inner class UdpFlow(private val s: Session, p: IpPacket, private val displayPort: Int, private val remotePort: Int, private val key: String) {
        private val version = p.version
        private val display = p.src
        private val remote = p.dst
        private val socket = DatagramSocket().apply {
            connect(InetSocketAddress(p.dstAddress, remotePort))
            soTimeout = POLL_MS
        }
        private val logId = requests.start("udp://${p.dstAddress.hostAddress}:$remotePort", SOURCE_TUNNEL)
        @Volatile private var lastUsed = System.currentTimeMillis()
        private var bytes = 0L
        private val job = scope.launch { receive() }

        fun send(data: ByteArray) {
            lastUsed = System.currentTimeMillis()
            bytes += data.size
            scope.launch { runCatching { socket.send(DatagramPacket(data, data.size)) } }
        }

        private suspend fun receive() {
            val buffer = ByteArray(UDP_MAX)
            while (scope.isActive && System.currentTimeMillis() - lastUsed < UDP_IDLE_MS) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    runInterruptible { socket.receive(packet) }
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                lastUsed = System.currentTimeMillis()
                bytes += packet.length
                val u = UdpDatagram.build(remotePort, displayPort, buffer.copyOf(packet.length))
                runCatching { s.deliver(version, remote, display, IpPacket.UDP, u) }
            }
            requests.finish(logId, "done", failed = false, bytes = bytes)
            close()
        }

        fun close() {
            s.forget(key)
            socket.close()
            job.cancel()
        }
    }

    private fun key(p: IpPacket, srcPort: Int, dstPort: Int) = "${p.protocol}/${p.srcAddress.hostAddress}:$srcPort>${p.dstAddress.hostAddress}:$dstPort"

    /** a - b in sequence space, as a signed 32-bit distance. */
    private fun diff(a: Long, b: Long): Long {
        val d = (a - b) and MASK
        return if (d >= 0x80000000L) d - 0x100000000L else d
    }

    companion object {
        const val SVC = 41111
        private const val MT_FRAME = 1
        private const val MT_START = 3
        private const val ICEE_HEADER = 8
        private const val ICEE_MAGIC = 0xFB1C
        private const val ETHER_IPV4: Short = 0x0800
        private const val ETHER_IPV6: Short = 0x86DD.toShort()
        private const val MASK = 0xFFFFFFFFL
        // The Display's tunnel has an MTU of 1400.
        private const val MSS_V4 = 1360
        private const val MSS_V6 = 1340
        private const val TUNNEL_FRAMES = 8
        private const val WINDOW = 65535
        private const val CONNECT_MS = 10_000
        private const val IDLE_MS = 600_000L
        private const val RETRANSMIT_MS = 1_500L
        private const val POLL_MS = 1_000
        private const val UDP_MAX = 2048
        private const val UDP_IDLE_MS = 60_000L
        // The request log's "source" for traffic of the tunnel rather than of the data fetcher.
        private const val SOURCE_TUNNEL = -1
    }
}
