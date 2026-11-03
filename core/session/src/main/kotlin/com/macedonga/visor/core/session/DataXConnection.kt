package com.macedonga.visor.core.session

import com.macedonga.visor.core.protocol.Header
import com.macedonga.visor.core.protocol.MessageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** What the mux needs from the link below it: plaintext in, plaintext out. */
interface PlaintextLink {

    suspend fun send(plaintext: ByteArray)

    suspend fun receive(): ByteArray
}

/** One decoded DataX message: its header and its application payload. On a close, [closedService] is the channel's service. */
class DataXMessage(val info: MessageInfo, val payload: ByteArray, val closedService: Int = 0)

/** A send on a channel this connection has not opened, or under another service's tag. Nothing went out. */
class ChannelRefused(message: String) : IllegalStateException(message)

/** The channel mux riding on one [PlaintextLink]. */
class DataXConnection(
    private val link: PlaintextLink,
    private val registry: ChannelRegistry,
) {

    // Messages waiting in front of anything the reader has produced.
    private val inbox = ArrayDeque<DataXMessage>()

    // Filled by the reader coroutine when one is running, and by nothing otherwise.
    private val fromReader = Channel<DataXMessage>(
        capacity = Channel.UNLIMITED,
        onUndeliveredElement = { message ->
            queued.decrementAndGet()
            inbox.addFirst(message)
        },
    )

    // How many messages are sitting in fromReader.
    private val queued = java.util.concurrent.atomic.AtomicInteger()

    @Volatile
    private var reader: Job? = null

    // Completed when the reader stops: the link under this connection has gone.
    private val gone = CompletableDeferred<Unit>()

    // RX messageType delta-decode cache.
    private val rxLastType = HashMap<Int, Int>()

    // The DataX stream after the last whole message: the start of the next one, cut wherever the frame ended.
    private var carry = ByteArray(0)

    /** True when [receive] can return a message without waiting on the peer. */
    val buffered: Boolean get() = inbox.isNotEmpty() || queued.get() > 0

    /** True once [startReader] has run. */
    val hasReader: Boolean get() = reader != null

    /** Start the one coroutine that reads the link into this connection, and return its job. */
    fun startReader(scope: CoroutineScope): Job {
        check(reader == null) { "this connection already has a reader" }
        val job = scope.launch {
            try {
                while (true) {
                    for (message in decode(link.receive())) {
                        queued.incrementAndGet()
                        fromReader.send(message)
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
        val opened = (channelId and 0x8000) != 0
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
        val service = serviceId ?: if (opened) bound else registry.serviceForInbound(channelId or 0x8000)
        if (service in TRACED_SENDS) t(sentLine(channelId, service, messageType, serviceId, payload))
    }

    private fun sentLine(channelId: Int, service: Int, messageType: Int?, tag: Int?, payload: ByteArray): String =
        "TX %08x ch=0x%04x svc=%d tag=%s mt=%s payload=%d".format(
            System.identityHashCode(this), channelId, service, tag ?: "-", messageType?.let { "0x%04x".format(it) } ?: "-", payload.size,
        ) + (if (payload.size <= TRACE_PAYLOAD) " data=" + payload.joinToString("") { "%02x".format(it) } else "")

    // The transport under a gone link may already be open again for the next one, under other keys.
    private suspend fun write(plaintext: ByteArray) {
        check(!gone.isCompleted) { "the link under this connection has gone" }
        link.send(plaintext)
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

    /** Send a bare DataX status frame: an error id tag, zero payload, on [channelId]. */
    suspend fun sendStatus(channelId: Int, errorId: Int) {
        write(Header.build(channelId, 0, errorId = errorId))
    }

    /** The next DataX message, decrypting more link frames as needed. */
    suspend fun receive(): DataXMessage = deliver(nextMessage())

    private suspend fun nextMessage(): DataXMessage {
        inbox.removeFirstOrNull()?.let { return it }
        if (reader != null) return fromReader.receive().also { queued.decrementAndGet() }
        while (inbox.isEmpty()) {
            inbox.addAll(decode(link.receive()))
        }
        return inbox.removeFirst()
    }

    private fun deliver(message: DataXMessage): DataXMessage {
        if (!message.info.flags.isClosed) return message
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
        return fromReader.tryReceive().getOrNull()
            ?.also { queued.decrementAndGet() }
            ?.let { deliver(it) }
    }

    private fun decode(plaintext: ByteArray): List<DataXMessage> {
        val messages = splitMessages(plaintext).map { DataXMessage(applyTypeCache(it.info), it.payload) }
        trace?.invoke(traceLines(plaintext, messages))
        return messages
    }

    private fun traceLines(plaintext: ByteArray, messages: List<DataXMessage>): String = buildString {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        append("FRAME %08x %d pad=%d head=%s tail=%s".format(
            System.identityHashCode(this@DataXConnection), plaintext.size, plaintext.size - withoutPad(plaintext).size,
            hex(plaintext.copyOfRange(0, minOf(16, plaintext.size))), hex(plaintext.copyOfRange(maxOf(0, plaintext.size - 8), plaintext.size)),
        ))
        for (m in messages) {
            append("\n   msg ch=0x%04x svc=%d mt=0x%04x len=%d hdr=%d payload=%d".format(
                m.info.channelId, m.info.serviceId, m.info.messageType, m.info.length, m.info.headerLen, m.payload.size,
            ))
            // Small ones whole: what a new service says is read from here.
            if (m.payload.size <= TRACE_PAYLOAD) append(" data=").append(hex(m.payload))
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

    /**
     * Walk [plaintext] into DataX messages by `wireTotal = 4 + length`. The frames' data concatenates into one stream
     * and a message runs on wherever a frame cuts it, header included. The pad comes off first: walking it as data let a
     * message one byte short of the frame's end take the pad for its last byte, and the byte it was owed, at the head of
     * the next frame, was then read as a header. The glasses never end a frame's data in bytes that read as pad.
     */
    private fun splitMessages(plaintext: ByteArray): List<DataXMessage> {
        val stream = carry + withoutPad(plaintext)
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
        /** Debug builds: each decrypted frame and the messages walked out of it, as lensbreak's LENSBREAK_DATAX_TRACE. */
        @Volatile
        var trace: ((String) -> Unit)? = null

        private const val TRACE_PAYLOAD = 512

        // What [trace] logs of what Visor sends: svc28, where a type the Display does not know kills its system process.
        private val TRACED_SENDS = setOf(28)

        /** The ack channel for a peer-originated channel: clear the secure bit, 0x8000. */
        fun mirror(channelId: Int): Int = channelId and 0x7FFF
    }
}

/** A frame's plaintext without its modified PKCS#7 tail. */
internal fun withoutPad(plaintext: ByteArray): ByteArray {
    if (plaintext.isEmpty()) return plaintext
    val last = plaintext[plaintext.size - 1].toInt() and 0xFF
    val padLen = (last + 0x40) and 0xFF
    if (padLen in 1..16 && plaintext.size >= padLen &&
        (plaintext.size - padLen until plaintext.size).all {
            (plaintext[it].toInt() and 0xFF) == last
        }
    ) {
        return plaintext.copyOfRange(0, plaintext.size - padLen)
    }
    return plaintext
}
