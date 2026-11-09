package com.macedonga.visor.core.media

import com.macedonga.visor.core.protocol.SVC_AIRSHIP
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.protocol.uuidText
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.Adler32
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Photos and videos. The glasses also offer `parfait` metadata and `telemetry` logs. */
const val NAMESPACE_MEDIA = "namespace_media"

/** A bug report zip, sent after a REQUEST_BUG_REPORT on svc7006. */
const val NAMESPACE_BUGREPORT = "bugreport"

/** Shares, and on some pairs the low-res photo for a question, which the Meta app offloads to its assistant. */
const val NAMESPACE_SHARING = "Sharing"

private val ACCEPTED = setOf(NAMESPACE_MEDIA, NAMESPACE_BUGREPORT)

// Refused all the time, so not worth a line each.
private val QUIET = setOf("parfait", "telemetry")

// airship::Receiver::receiveMessage. 12 is the multiscope receiver's second offer type.
private const val MT_OFFER = 1
private const val MT_RESET = 3
private const val MT_DATA = 4
private const val MT_CHECK = 5
private const val MT_COMPLETE = 6
private const val MT_REMOVED = 8
private const val MT_QUEUE_COMPLETE = 10
private const val MT_STOPPED = 11
private const val MT_OFFER_MULTISCOPE = 12

// airship::SendChannel::onResponse reads these two and nothing else.
private const val MT_CURSOR = 2
private const val MT_DROP = 7

private const val ANSWER_MS = 15_000L
private const val CHECK_BYTES = 65_536

/** A file the glasses offered. [uuid] matches the capture list's asset id. */
class Offer(val idBytes: ByteArray, val size: Long, val namespace: String) {
    val uuid: String = uuidText(idBytes)
}

/** A whole file. [verified] is true when our adler32 matches the glasses'. */
class Received(val offer: Offer, val file: File, val verified: Boolean)

/** A file in flight. */
data class Transfer(val uuid: String, val received: Long, val size: Long, val bytesPerSecond: Long)

/** Files for someone other than the media import, such as the assistant's photo. */
interface ExtraFiles {
    /** Whether to take an offer outside the media and bug report namespaces. */
    fun wants(offer: Offer): Boolean

    /** A file this side wanted, whole. The receiver deletes it afterwards. */
    fun take(received: Received)

    /** A media file, before the import has it. */
    fun peek(received: Received) = Unit
}

/** Hosts svc20 and takes the media files the glasses offer, spooling each to [spool]. */
class AirshipReceiver(private val spool: File) : Responder {

    /** Words for an offered asset id, from whoever knows the capture list. */
    @Volatile
    var describe: (String) -> String = { "" }

    /** Takers of other files, asked in order: the first that wants an offer takes it, and each other that still wants it gets it too. */
    val extras = CopyOnWriteArrayList<ExtraFiles>()

    private class Reply(val type: Int, val payload: ByteArray)

    private class Incoming(val offer: Offer, val file: File, val taker: ExtraFiles?) {
        var out = FileOutputStream(file)
        val adler = Adler32()
        var received = 0L
        var startedAt = 0L
        var percent = -1L

        fun append(chunk: ByteArray) {
            if (startedAt == 0L) startedAt = System.nanoTime()
            out.write(chunk)
            adler.update(chunk)
            received += chunk.size
        }

        fun restart() {
            out.close()
            out = FileOutputStream(file)
            adler.reset()
            received = 0
        }

        fun discard() {
            out.close()
            file.delete()
        }
    }

    // The session calls respond from its one coroutine; the lock is for abandon.
    private val lock = Mutex()
    private val incoming = HashMap<Int, Incoming>()

    /** When the glasses last said their send queue is empty, in [System.nanoTime] milliseconds. */
    @Volatile
    var queueEmptyAt = 0L
        private set

    // Some glasses repeat that the queue is empty every few seconds: the log hears it once until an offer.
    @Volatile
    private var saidEmpty = false

    /** When airship last said anything, in [System.nanoTime] milliseconds. */
    @Volatile
    var lastActivity = 0L
        private set

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private val _received = Channel<Received>(Channel.UNLIMITED)

    /** Whole files. One consumer. */
    val received: Flow<Received> = _received.receiveAsFlow()

    private val _notes = Channel<String>(Channel.UNLIMITED)

    /** What happened, in words. One consumer. */
    val notes: Flow<String> = _notes.receiveAsFlow()

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val channel = message.info.channelId
        lastActivity = System.nanoTime() / 1_000_000
        // A cursor or a drop on a channel this side opened answers a file we are sending. Those come without the
        // originator bit; a file the glasses offer meanwhile can come on their own channel of the same number.
        if (channel and 0x8000 == 0) outgoing[channel]?.let {
            it.trySend(Reply(message.info.messageType, message.payload))
            return
        }
        val reply = lock.withLock { handle(channel, message.info.messageType, message.payload) } ?: return
        connection.send(DataXConnection.mirror(channel), reply.payload, messageType = reply.type)
    }

    // Files this side is sending, by channel without the originator bit: their answers go here.
    private val outgoing = ConcurrentHashMap<Int, Channel<Reply>>()

    /**
     * Send [data] to the glasses as [baseName] in [namespace], the way their own sender does it in
     * reverse: offer, wait for a cursor (or a drop, a refusal), data from that offset, a check every
     * 64 KB, then the complete cursor, which they answer with a drop once the file is theirs.
     * Returns null on success, else why it failed.
     */
    suspend fun send(
        connection: DataXConnection,
        namespace: String,
        baseName: String,
        data: ByteArray,
        chunk: Int = 1024,
        uuid: ByteArray? = null,
    ): String? = send(connection, namespace, baseName, data.size.toLong(), chunk, uuid = uuid) { at, n -> data.copyOfRange(at.toInt(), at.toInt() + n) }

    /** A file too large to hold in memory, read as it goes; [onProgress] hears the bytes the glasses have so far. */
    suspend fun send(
        connection: DataXConnection,
        namespace: String,
        baseName: String,
        file: File,
        chunk: Int = 1024,
        uuid: ByteArray? = null,
        onProgress: (Long) -> Unit = {},
    ): String? =
        RandomAccessFile(file, "r").use { raf ->
            send(connection, namespace, baseName, file.length(), chunk, onProgress, uuid) { at, n -> ByteArray(n).also { raf.seek(at); raf.readFully(it) } }
        }

    private suspend fun send(
        connection: DataXConnection,
        namespace: String,
        baseName: String,
        size: Long,
        chunk: Int,
        onProgress: (Long) -> Unit = {},
        // A receiver that announced the file's id beforehand (the app manager) needs that id.
        uuid: ByteArray? = null,
        read: (Long, Int) -> ByteArray,
    ): String? {
        val id = uuid ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
        val channel = connection.openChannel(SVC_AIRSHIP)
        val answers = Channel<Reply>(Channel.UNLIMITED)
        outgoing[channel and 0x7FFF] = answers
        // The checksum of everything before [at], read again from the start when they ask for a rewind.
        fun adlerTo(at: Long) = Adler32().apply {
            var p = 0L
            while (p < at) {
                val n = minOf(CHECK_BYTES.toLong(), at - p).toInt()
                update(read(p, n))
                p += n
            }
        }
        try {
            val offer = Wire.fieldBytes(1, id) + Wire.fieldVarintNonZero(2, size) + Wire.fieldString(3, namespace) +
                Wire.fieldString(4, baseName)
            connection.send(channel, offer, messageType = MT_OFFER, serviceId = SVC_AIRSHIP)
            var answer = withTimeoutOrNull(ANSWER_MS) { answers.receive() } ?: return "no answer to the offer"
            while (true) {
                when (answer.type) {
                    MT_DROP -> return "refused"
                    MT_CURSOR -> Unit
                    else -> return "unexpected answer type ${answer.type}"
                }
                // Resume from where they say they are; a fresh file starts at 0.
                var at = parsed(answer.payload).varint(1).coerceIn(0, size)
                var adler = adlerTo(at)
                var sinceCheck = 0
                while (at < size) {
                    val n = minOf(chunk.toLong(), size - at).toInt()
                    val bytes = read(at, n)
                    connection.send(channel, bytes, messageType = MT_DATA)
                    adler.update(bytes)
                    at += n
                    sinceCheck += n
                    if (sinceCheck >= CHECK_BYTES && at < size) {
                        sinceCheck = 0
                        onProgress(at)
                        connection.send(channel, cursor(at, adler.value), messageType = MT_CHECK)
                    }
                    // Answers to checks are read as they come; a cursor behind us means rewind.
                    val check = answers.tryReceive().getOrNull() ?: continue
                    if (check.type == MT_DROP) return "dropped mid-transfer"
                    val theirs = parsed(check.payload).varint(1)
                    if (check.type == MT_CURSOR && theirs < at) {
                        at = theirs
                        adler = adlerTo(at)
                    }
                }
                connection.send(channel, cursor(size, adler.value), messageType = MT_COMPLETE)
                val end = withTimeoutOrNull(ANSWER_MS) { answers.receive() } ?: return "no answer to the complete"
                if (end.type == MT_DROP) return null
                // A cursor instead of the drop: they want part of it again.
                answer = end
            }
        } finally {
            outgoing.remove(channel and 0x7FFF)
        }
    }

    /** Let go of every half-received file. Called when the link drops. */
    suspend fun abandon() = lock.withLock {
        incoming.values.forEach { it.discard() }
        incoming.clear()
        publish()
    }

    private fun handle(channel: Int, type: Int, payload: ByteArray): Reply? {
        when (type) {
            MT_OFFER, MT_OFFER_MULTISCOPE -> {
                saidEmpty = false
                val f = parsed(payload)
                val offer = Offer(f.bytes(1), f.varint(2), f.bytes(3).decodeToString())
                incoming.remove(channel)?.discard()
                val taker = if (offer.namespace in ACCEPTED) null else extras.firstOrNull { runCatching { it.wants(offer) }.getOrDefault(false) }
                // Refusing still needs the drop, or the glasses' queue stalls behind this offer.
                if (offer.namespace !in ACCEPTED && taker == null) {
                    if (offer.namespace !in QUIET) _notes.trySend("Refused ${sizeWords(offer.size)} in namespace \"${offer.namespace}\"")
                    return Reply(MT_DROP, fileId(offer.idBytes))
                }
                spool.mkdirs()
                incoming[channel] = Incoming(offer, File(spool, offer.uuid), taker)
                _notes.trySend("Receiving ${sizeWords(offer.size)}${describe(offer.uuid)}")
                publish()
                // Offset 0 with the adler32 of nothing: what FileReceiver::acceptOffer sends.
                return Reply(MT_CURSOR, cursor(0, 1))
            }

            MT_DATA -> incoming[channel]?.let { t ->
                t.append(payload)
                val percent = if (t.offer.size > 0) t.received * 100 / t.offer.size else 0
                if (percent != t.percent) {
                    t.percent = percent
                    publish()
                    if (t.offer.size > 5_000_000 && percent % 25 == 0L && percent in 1..99) {
                        _notes.trySend("$percent% of ${sizeWords(t.offer.size)}")
                    }
                }
            }

            MT_RESET -> incoming[channel]?.restart()

            MT_CHECK -> incoming[channel]?.let { return Reply(MT_CURSOR, cursor(it.received, it.adler.value)) }

            MT_COMPLETE -> {
                val t = incoming.remove(channel) ?: return null
                t.out.close()
                val f = parsed(payload)
                val verified = f.varint(1) == t.received && f.varint(2) == t.adler.value
                val whole = Received(t.offer, t.file, verified)
                if (t.offer.namespace in ACCEPTED) {
                    if (t.offer.namespace == NAMESPACE_MEDIA) extras.forEach { x ->
                        runCatching { x.peek(whole) }.onFailure { _notes.trySend("A file taker failed: ${it.message ?: it}") }
                    }
                    _received.trySend(whole)
                } else {
                    t.taker?.let { first ->
                        val takers = listOf(first) + extras.filter { it !== first && runCatching { it.wants(t.offer) }.getOrDefault(false) }
                        takers.forEach { taker ->
                            runCatching { taker.take(whole) }.onFailure { _notes.trySend("A file taker failed: ${it.message ?: it}") }
                        }
                    }
                    t.file.delete()
                }
                publish()
                // FileReceiver::complete answers with a drop, which takes it off their queue.
                return Reply(MT_DROP, fileId(t.offer.idBytes))
            }

            MT_REMOVED -> incoming.remove(channel)?.let {
                it.discard()
                _notes.trySend("The glasses withdrew a file of ${sizeWords(it.offer.size)}")
                publish()
            }

            MT_STOPPED -> {
                val reason = parsed(payload).varint(2)
                incoming.remove(channel)?.let {
                    it.discard()
                    _notes.trySend(
                        "The glasses paused a file of ${sizeWords(it.offer.size)} (reason $reason). " +
                            "Over Bluetooth they usually send it again in about five minutes.",
                    )
                    publish()
                }
            }

            MT_QUEUE_COMPLETE -> {
                queueEmptyAt = System.nanoTime() / 1_000_000
                if (!saidEmpty) _notes.trySend("The glasses have nothing more queued")
                saidEmpty = true
            }
        }
        return null
    }

    private fun publish() {
        _transfers.value = incoming.values.map {
            val seconds = (System.nanoTime() - it.startedAt) / 1e9
            val rate = if (it.startedAt == 0L || seconds <= 0) 0 else (it.received / seconds).toLong()
            Transfer(it.offer.uuid, it.received, it.offer.size, rate)
        }
    }
}

private fun cursor(offset: Long, adler: Long): ByteArray = Wire.fieldVarintNonZero(1, offset) + Wire.fieldVarintNonZero(2, adler)

private fun fileId(uuid: ByteArray): ByteArray = Wire.fieldBytes(1, uuid)

// A malformed payload reads as empty, so the offer or file still gets its drop.
private fun parsed(payload: ByteArray): Wire.Fields = runCatching { Wire.parse(payload) }.getOrElse { Wire.parse(ByteArray(0)) }

/** 14.9 MB, 380 KB, 22 B. */
fun sizeWords(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
