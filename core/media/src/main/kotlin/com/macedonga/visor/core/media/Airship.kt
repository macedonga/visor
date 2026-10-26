package com.macedonga.visor.core.media

import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import java.io.File
import java.io.FileOutputStream
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

/** svc20, airship: Meta's file transfer engine. Ported from lensbreak/readout/airship.py. */
const val SVC_AIRSHIP = 20

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

/** A file the glasses offered. [uuid] matches the capture list's asset id. */
class Offer(val raw: ByteArray, val size: Long, val namespace: String) {
    val uuid: String = formatUuid(raw)
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

    /** Takers of other files, asked in order; the first that wants an offer gets it. */
    val extras = CopyOnWriteArrayList<ExtraFiles>()

    private class Live(val offer: Offer, val file: File, val taker: ExtraFiles?) {
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
    private val live = HashMap<Int, Live>()

    /** When the glasses last said their send queue is empty, in [System.nanoTime] milliseconds. */
    @Volatile
    var queueEmptyAt = 0L
        private set

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
            it.trySend(message.info.messageType to message.payload)
            return
        }
        val reply = lock.withLock { handle(channel, message.info.messageType, message.payload) } ?: return
        connection.send(DataXConnection.mirror(channel), reply.second, messageType = reply.first)
    }

    // Files this side is sending, by channel without the originator bit: their answers go here.
    private val outgoing = java.util.concurrent.ConcurrentHashMap<Int, Channel<Pair<Int, ByteArray>>>()

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
        file: java.io.File,
        chunk: Int = 1024,
        uuid: ByteArray? = null,
        onProgress: (Long) -> Unit = {},
    ): String? =
        java.io.RandomAccessFile(file, "r").use { raf ->
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
        val uuid = uuid ?: ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val channel = connection.openChannel(SVC_AIRSHIP)
        val answers = Channel<Pair<Int, ByteArray>>(Channel.UNLIMITED)
        outgoing[channel and 0x7FFF] = answers
        // The checksum of everything before [at], read again from the start when they ask for a rewind.
        fun adlerTo(at: Long) = Adler32().apply {
            var p = 0L
            while (p < at) {
                val n = minOf(65_536L, at - p).toInt()
                update(read(p, n))
                p += n
            }
        }
        try {
            val offer = ProtoWriter().bytes(1, uuid).varint(2, size).string(3, namespace).string(4, baseName).build()
            connection.send(channel, offer, messageType = MT_OFFER, serviceId = SVC_AIRSHIP)
            var (type, payload) = kotlinx.coroutines.withTimeoutOrNull(15_000) { answers.receive() } ?: return "no answer to the offer"
            while (true) {
                when (type) {
                    MT_DROP -> return "refused"
                    MT_CURSOR -> Unit
                    else -> return "unexpected answer type $type"
                }
                // Resume from where they say they are; a fresh file starts at 0.
                var at = fields(payload).long(1).coerceIn(0, size)
                var adler = adlerTo(at)
                var sinceCheck = 0
                while (at < size) {
                    val n = minOf(chunk.toLong(), size - at).toInt()
                    val bytes = read(at, n)
                    connection.send(channel, bytes, messageType = MT_DATA)
                    adler.update(bytes)
                    at += n
                    sinceCheck += n
                    if (sinceCheck >= 65_536 && at < size) {
                        sinceCheck = 0
                        onProgress(at)
                        connection.send(channel, cursor(at, adler.value), messageType = MT_CHECK)
                    }
                    // Answers to checks are read as they come; a cursor behind us means rewind.
                    val answer = answers.tryReceive().getOrNull() ?: continue
                    if (answer.first == MT_DROP) return "dropped mid-transfer"
                    val theirs = fields(answer.second).long(1)
                    if (answer.first == MT_CURSOR && theirs < at) {
                        at = theirs
                        adler = adlerTo(at)
                    }
                }
                connection.send(channel, cursor(size, adler.value), messageType = MT_COMPLETE)
                val end = kotlinx.coroutines.withTimeoutOrNull(15_000) { answers.receive() } ?: return "no answer to the complete"
                if (end.first == MT_DROP) return null
                // A cursor instead of the drop: they want part of it again.
                type = end.first
                payload = end.second
            }
        } finally {
            outgoing.remove(channel and 0x7FFF)
        }
    }

    /** Let go of every half-received file. Called when the link drops. */
    suspend fun abandon() = lock.withLock {
        live.values.forEach { it.discard() }
        live.clear()
        publish()
    }

    private fun handle(channel: Int, type: Int, payload: ByteArray): Pair<Int, ByteArray>? {
        when (type) {
            MT_OFFER, MT_OFFER_MULTISCOPE -> {
                val f = fields(payload)
                val offer = Offer(f.bytes(1), f.long(2), f.bytes(3).decodeToString())
                live.remove(channel)?.discard()
                val taker = if (offer.namespace in ACCEPTED) null else extras.firstOrNull { runCatching { it.wants(offer) }.getOrDefault(false) }
                // Refusing still needs the drop, or the glasses' queue stalls behind this offer.
                if (offer.namespace !in ACCEPTED && taker == null) {
                    if (offer.namespace !in QUIET) _notes.trySend("Refused ${sizeWords(offer.size)} in namespace \"${offer.namespace}\"")
                    return MT_DROP to fileId(offer.raw)
                }
                spool.mkdirs()
                live[channel] = Live(offer, File(spool, offer.uuid), taker)
                _notes.trySend("Receiving ${sizeWords(offer.size)}${describe(offer.uuid)}")
                publish()
                // Offset 0 with the adler32 of nothing: what FileReceiver::acceptOffer sends.
                return MT_CURSOR to cursor(0, 1)
            }

            MT_DATA -> live[channel]?.let { t ->
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

            MT_RESET -> live[channel]?.restart()

            MT_CHECK -> live[channel]?.let { return MT_CURSOR to cursor(it.received, it.adler.value) }

            MT_COMPLETE -> {
                val t = live.remove(channel) ?: return null
                t.out.close()
                val f = fields(payload)
                val verified = f.long(1) == t.received && f.long(2) == t.adler.value
                val whole = Received(t.offer, t.file, verified)
                if (t.offer.namespace in ACCEPTED) {
                    extras.forEach { runCatching { it.peek(whole) } }
                    _received.trySend(whole)
                } else {
                    t.taker?.let { runCatching { it.take(whole) } }
                    t.file.delete()
                }
                publish()
                // FileReceiver::complete answers with a drop, which takes it off their queue.
                return MT_DROP to fileId(t.offer.raw)
            }

            MT_REMOVED -> live.remove(channel)?.let {
                it.discard()
                _notes.trySend("The glasses withdrew a file of ${sizeWords(it.offer.size)}")
                publish()
            }

            MT_STOPPED -> {
                val reason = fields(payload).long(2)
                live.remove(channel)?.let {
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
                _notes.trySend("The glasses have nothing more queued")
            }
        }
        return null
    }

    private fun publish() {
        _transfers.value = live.values.map {
            val seconds = (System.nanoTime() - it.startedAt) / 1e9
            val rate = if (it.startedAt == 0L || seconds <= 0) 0 else (it.received / seconds).toLong()
            Transfer(it.offer.uuid, it.received, it.offer.size, rate)
        }
    }
}

private fun cursor(offset: Long, adler: Long): ByteArray = ProtoWriter().varint(1, offset).varint(2, adler).build()

private fun fileId(uuid: ByteArray): ByteArray = ProtoWriter().bytes(1, uuid).build()

private fun formatUuid(raw: ByteArray): String {
    val h = raw.joinToString("") { "%02x".format(it) }
    if (raw.size != 16) return h
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}

/** 14.9 MB, 380 KB, 22 B. */
fun sizeWords(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
