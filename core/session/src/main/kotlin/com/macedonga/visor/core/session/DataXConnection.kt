package com.macedonga.visor.core.session

import com.macedonga.visor.core.protocol.AirShieldCipher
import com.macedonga.visor.core.protocol.Header
import com.macedonga.visor.core.protocol.MessageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One decoded DataX message: its header and its application payload. On a close, [closedService] is the channel's service. */
class DataXMessage(val info: MessageInfo, val payload: ByteArray, val closedService: Int = 0)

/** A send on a channel this connection has not opened, or under another service's tag. Nothing went out. */
class ChannelRefused(message: String) : IllegalStateException(message)

/** The channel mux riding on one [SecureLink], whose frames may come from another link's transport after a switch. */
class DataXConnection(
    private val link: SecureLink,
    private val registry: ChannelRegistry,
    frames: FrameSource = FrameSource { link.nextSdu() },
) {

    // Where sealed frames come in from. A link switch moves it once the peer's switch message has come.
    @Volatile
    private var rx: FrameSource = frames

    // Keeps a send from slipping between a switch message and the move of the link's sends.
    private val writeLock = Mutex()

    /** When the reader last got a frame, by [System.nanoTime]. */
    @Volatile
    var heardAt: Long = System.nanoTime()
        private set

    private class RxMove(val next: FrameSource, val trigger: (DataXMessage) -> Boolean, val done: CompletableDeferred<FrameSource>)

    @Volatile
    private var rxMove: RxMove? = null

    // Messages waiting in front of anything the reader has produced.
    private val inbox = ArrayDeque<DataXMessage>()

    // Filled by the reader coroutine when one is running, and by nothing otherwise.
    private val fromReader = Channel<DataXMessage>(
        capacity = Channel.UNLIMITED,
        onUndeliveredElement = { inbox.addFirst(it) },
    )

    @Volatile
    private var reader: Job? = null

    // Completed when the reader stops: the link under this connection has gone.
    private val gone = CompletableDeferred<Unit>()

    // RX messageType delta-decode cache.
    private val rxLastType = HashMap<Int, Int>()

    // The DataX stream after the last whole message: the start of the next one, cut wherever the frame ended.
    private var carry = ByteArray(0)

    /** True once [startReader] has run. */
    val hasReader: Boolean get() = reader != null

    /** Start the one coroutine that reads the link into this connection, and return its job. */
    fun startReader(scope: CoroutineScope): Job {
        check(reader == null) { "this connection already has a reader" }
        val job = scope.launch {
            try {
                while (true) {
                    val plaintext = link.open(rx.receive()) ?: continue
                    heardAt = System.nanoTime()
                    for (message in decode(plaintext)) {
                        fromReader.send(message)
                        val move = rxMove
                        // Switches only if the switch did not give up first: one side wins, never both.
                        if (move != null && move.trigger(message) && move.done.complete(rx)) {
                            rxMove = null
                            rx = move.next
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                fromReader.close()
                throw cancellation
            } catch (error: Throwable) {
                fromReader.close(error)
            } finally {
                gone.complete(Unit)
            }
        }
        reader = job
        return job
    }

    /** Returns once the link under this connection has gone. Sends fail from then on. */
    suspend fun awaitGone() = gone.await()

    /** Allocate a local channel id for [serviceId] and bind it in the registry. */
    fun openChannel(serviceId: Int): Int {
        val channelId = registry.nextLocalChannel()
        registry.bindLocal(channelId, serviceId)
        return channelId
    }

    /** The service of [channelId], a channel this connection opened, or 0 when it is not open. */
    fun localService(channelId: Int): Int = registry.localService(channelId)

    /** Send one DataX message on [channelId]. One with the originator bit must be open here, for [serviceId] if given, or [ChannelRefused]. */
    suspend fun send(
        channelId: Int,
        payload: ByteArray,
        messageType: Int? = null,
        serviceId: Int? = null,
    ) {
        val opened = hasOriginatorBit(channelId)
        val bound = if (opened) registry.localService(channelId) else 0
        if (opened && (bound == 0 || (serviceId != null && serviceId != bound))) {
            val why = "refused a send on channel 0x%04x: tag %s, open for %d".format(channelId, serviceId ?: "none", bound)
            trace?.invoke("TX %08x %s".format(System.identityHashCode(this), why))
            throw ChannelRefused(why)
        }
        val header = Header.build(
            channelId = channelId,
            dataLen = payload.size,
            serviceId = serviceId,
            messageType = messageType,
        )
        write(header + payload)
        val t = trace ?: return
        val service = serviceId ?: if (opened) bound else registry.serviceForInbound(channelId or ORIGINATOR_BIT)
        if (service in TRACED_SENDS) t(sentLine(channelId, service, messageType, serviceId, payload))
    }

    private fun sentLine(channelId: Int, service: Int, messageType: Int?, tag: Int?, payload: ByteArray): String =
        "TX %08x ch=0x%04x svc=%d tag=%s mt=%s payload=%d".format(
            System.identityHashCode(this), channelId, service, tag ?: "-", messageType?.let { "0x%04x".format(it) } ?: "-", payload.size,
        ) + (if (payload.size <= TRACE_PAYLOAD) " data=" + payload.toHexString() else "")

    // The transport under a gone link may already be open again for the next one, under other keys.
    private suspend fun write(plaintext: ByteArray) = writeLock.withLock { writeNow(plaintext) }

    private suspend fun writeNow(plaintext: ByteArray) {
        check(!gone.isCompleted) { "the link under this connection has gone" }
        link.send(plaintext)
    }

    /**
     * Send [payload] for [serviceId] on a channel of its own, then send nothing until a message passes [answer]: from
     * then on frames come from [next], and [move] runs before the next send. False, with nothing moved, when no answer
     * comes in [waitMillis]. The new channel's opening frame names the service, which the glasses' link switch
     * watches for in the raw stream; until their answer, a frame on either link would reach a path they are clearing.
     */
    suspend fun switchLinks(
        serviceId: Int,
        messageType: Int,
        payload: ByteArray,
        next: FrameSource,
        answer: (DataXMessage) -> Boolean,
        waitMillis: Long,
        move: () -> Unit,
    ): Boolean = writeLock.withLock {
        val done = CompletableDeferred<FrameSource>()
        rxMove = RxMove(next, answer, done)
        val header = Header.build(channelId = openChannel(serviceId), dataLen = payload.size, serviceId = serviceId, messageType = messageType)
        writeNow(header + payload)
        if (withTimeoutOrNull(waitMillis) { done.await() } == null && done.completeExceptionally(CancellationException("no answer to the switch"))) {
            rxMove = null
            return@withLock false
        }
        move()
        true
    }

    // This connection's one channel per service. The glasses answer on every open channel of a
    // service, so a channel per request multiplied every reply.
    private val serviceChannels = HashMap<Int, Int>()

    /** Send on this connection's one channel for [serviceId], opening it the first time and again after the glasses close it. */
    suspend fun sendOnService(serviceId: Int, messageType: Int, payload: ByteArray) {
        val known = synchronized(serviceChannels) { serviceChannels[serviceId] }
        if (known != null && registry.localService(known) == serviceId) {
            send(known, payload, messageType = messageType)
            return
        }
        val channel = openChannel(serviceId)
        synchronized(serviceChannels) { serviceChannels[serviceId] = channel }
        send(channel, payload, messageType = messageType, serviceId = serviceId)
    }

    /**
     * Forgets [channelId] as its service's channel, so the next [sendOnService] opens a new one. The glasses answer on
     * the mirrored id, so the originator bit does not count.
     */
    fun forgetServiceChannel(channelId: Int) {
        val id = channelId and (ORIGINATOR_BIT - 1)
        synchronized(serviceChannels) { serviceChannels.entries.removeAll { (it.value and (ORIGINATOR_BIT - 1)) == id } }
    }

    /** Send a bare DataX status frame: an error id tag, zero payload, on [channelId]. */
    suspend fun sendStatus(channelId: Int, errorId: Int) {
        write(Header.build(channelId, 0, errorId = errorId))
    }

    /** The next DataX message, decrypting more link frames as needed. */
    suspend fun receive(): DataXMessage = deliver(nextMessage())

    private suspend fun nextMessage(): DataXMessage {
        inbox.removeFirstOrNull()?.let { return it }
        if (reader != null) return fromReader.receive()
        while (inbox.isEmpty()) {
            link.open(rx.receive())?.let { inbox.addAll(decode(it)) }
        }
        return inbox.removeFirst()
    }

    private fun deliver(message: DataXMessage): DataXMessage {
        // A close pushed back keeps the service its first delivery found.
        if (!message.info.flags.isClosed || message.closedService != 0) return message
        return DataXMessage(message.info, message.payload, registry.closeInbound(message.info.channelId))
    }

    /** Return messages to the front of the inbox, in their original order. */
    fun pushback(messages: List<DataXMessage>) {
        for (message in messages.asReversed()) {
            inbox.addFirst(message)
        }
    }

    /** Read whatever is already decoded, without waiting on the peer at all. */
    fun poll(): DataXMessage? {
        inbox.removeFirstOrNull()?.let { return deliver(it) }
        return fromReader.tryReceive().getOrNull()?.let { deliver(it) }
    }

    private fun decode(plaintext: ByteArray): List<DataXMessage> {
        val messages = splitMessages(plaintext).map { DataXMessage(applyTypeCache(it.info), it.payload) }
        trace?.invoke(traceLines(plaintext, messages))
        return messages
    }

    private fun traceLines(plaintext: ByteArray, messages: List<DataXMessage>): String = buildString {
        append("FRAME %08x %d pad=%d head=%s tail=%s".format(
            System.identityHashCode(this@DataXConnection), plaintext.size, plaintext.size - AirShieldCipher.stripPad(plaintext).size,
            plaintext.toHexString(0, minOf(16, plaintext.size)), plaintext.toHexString(maxOf(0, plaintext.size - 8), plaintext.size),
        ))
        for (m in messages) {
            append("\n   msg ch=0x%04x svc=%d mt=0x%04x len=%d hdr=%d payload=%d".format(
                m.info.channelId, m.info.serviceId, m.info.messageType, m.info.length, m.info.headerLen, m.payload.size,
            ))
            // Small ones whole: what a new service says is read from here.
            if (m.payload.size <= TRACE_PAYLOAD) append(" data=").append(m.payload.toHexString())
        }
        if (carry.isNotEmpty()) append("\n   CARRY %d".format(carry.size))
    }

    /** Update or apply the per-channel messageType cache. */
    private fun applyTypeCache(info: MessageInfo): MessageInfo {
        val channel = info.channelId
        // A type tag names the new type, 0 included; only a frame without one takes the last.
        if (info.flags.setType || info.messageType != 0) {
            rxLastType[channel] = info.messageType
            return info
        }
        if (!info.flags.hasError) {
            val cached = rxLastType[channel]
            if (cached != null) return info.copy(messageType = cached)
        }
        return info
    }

    /** Pad off first (the glasses never end a frame's data in pad-like bytes), then messages of `4 + length`, which may run across frames. */
    private fun splitMessages(plaintext: ByteArray): List<DataXMessage> {
        val stream = carry + AirShieldCipher.stripPad(plaintext)
        val messages = mutableListOf<DataXMessage>()
        var pos = 0
        while (stream.size - pos >= 4) {
            val wireTotal = Header.parse(stream.copyOfRange(pos, pos + 4)).wireTotal
            if (stream.size - pos < wireTotal) break
            val whole = stream.copyOfRange(pos, pos + wireTotal)
            val info = Header.parse(whole)
            messages += DataXMessage(info, whole.copyOfRange(info.headerLen, wireTotal))
            pos += wireTotal
        }
        carry = stream.copyOfRange(pos, stream.size)
        return messages
    }

    companion object {
        /** Debug builds: each decrypted frame and the messages walked out of it. */
        @Volatile
        var trace: ((String) -> Unit)? = null

        private const val TRACE_PAYLOAD = 512

        // What [trace] logs of what Visor sends: svc28, where a type the Display does not know kills its system process.
        private val TRACED_SENDS = setOf(28)

        const val ORIGINATOR_BIT = 0x8000

        fun hasOriginatorBit(channelId: Int): Boolean = (channelId and ORIGINATOR_BIT) != 0

        /** The ack channel for a peer-opened channel: the originator bit cleared. */
        fun mirror(channelId: Int): Int = channelId and (ORIGINATOR_BIT - 1)

        /** The reply channel for a message: the originator bit flipped, so Visor's own channels get it back and the glasses' channels get their mirror. */
        fun own(channelId: Int): Int = channelId xor ORIGINATOR_BIT
    }
}
