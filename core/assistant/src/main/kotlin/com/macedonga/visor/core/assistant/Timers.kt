package com.macedonga.visor.core.assistant

import android.util.Base64
import com.macedonga.visor.core.connection.objects
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject

/** The phone's timers and alarms service ("tar"), which the glasses open when they are asked for a timer. */
const val SVC_TAR = 53210

/**
 * A timer, alarm or reminder the glasses asked the phone to keep (their TAR). [raw] is the message as they sent it,
 * handed back unchanged, so nothing the glasses wrote is lost in a re-encoding.
 */
class Tar(val raw: ByteArray) {
    private val f = Wire.parse(raw)
    val id: String = f.bytes(1).decodeToString()
    val type: TarType = TarType.of(f.varint(2))
    val title: String = f.bytes(6).decodeToString()
    val completed: Boolean = f.varint(8) != 0L

    /** When it goes off, by the wall clock; null for a paused timer or a weekly alarm. */
    val fireAt: Long? = when {
        f.has(TRIGGER_DATE) -> Wire.parse(f.bytes(TRIGGER_DATE)).varint(1)
        f.has(TRIGGER_DURATION) -> Wire.parse(f.bytes(TRIGGER_DURATION)).let { d ->
            d.varint(2).takeIf { it > 0 && d.varint(5) != DURATION_PAUSED }?.plus(d.varint(1))
        }
        else -> null
    }

    /** A timer's full length. */
    val durationMs: Long? = if (f.has(TRIGGER_DURATION)) Wire.parse(f.bytes(TRIGGER_DURATION)).varint(1) else null

    /** Goes off again: an alarm set for days of the week. */
    val repeats: Boolean get() = f.has(TRIGGER_DAYS)

    /** The same TAR under [id]: a later field wins in protobuf, so the id is added at the end. */
    fun withId(id: String): Tar = Tar(raw + Wire.fieldString(1, id))

    private companion object {
        const val TRIGGER_DATE = 100
        const val TRIGGER_DURATION = 101
        const val TRIGGER_DAYS = 102
        const val DURATION_PAUSED = 2L
    }
}

enum class TarType(val number: Long) {
    REMINDER(0), TIMER(1), ALARM(2), OTHER(-1);

    companion object {
        fun of(n: Long) = entries.firstOrNull { it.number == n } ?: OTHER
    }
}

/**
 * The glasses' timers and alarms, kept on the phone as the Meta app keeps them: the glasses send create, update,
 * delete and query requests on svc 53210 and wait a few seconds for each answer; after every change the phone sends
 * them the whole list (TARSyncRequest, type 1100), and they ring each one themselves when its time comes.
 */
class Timers(private val file: File) : Responder {
    // Everything kept, oldest first.
    @Volatile
    private var kept: List<Tar> = load()

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val reply = DataXConnection.mirror(message.info.channelId)
        val f = runCatching { Wire.parse(message.payload) }.getOrNull() ?: return
        when (message.info.messageType) {
            MT_CREATE -> {
                val sent = Tar(f.bytes(1))
                val tar = if (sent.id.isEmpty()) sent.withId(UUID.randomUUID().toString()) else sent
                keep(kept.filterNot { it.id == tar.id } + tar)
                connection.send(reply, result() + Wire.fieldBytes(2, tar.raw), messageType = MT_CREATE + 1)
                note("Glasses created ${words(tar)}")
                sync(connection)
            }
            MT_UPDATE -> {
                val tar = Tar(f.bytes(1))
                val known = kept.any { it.id == tar.id }
                if (known) keep(kept.map { if (it.id == tar.id) tar else it })
                connection.send(reply, if (known) result() else failure(ERROR_UPDATE, "no such id"), messageType = MT_UPDATE + 1)
                note("Glasses updated ${words(tar)}")
                if (known) sync(connection)
            }
            MT_DELETE -> {
                val gone = matching(Wire.parse(f.bytes(1)))
                keep(kept - gone.toSet())
                connection.send(reply, if (gone.isEmpty()) failure(ERROR_NO_MATCHES, "nothing matches") else result(), messageType = MT_DELETE + 1)
                note("Glasses deleted ${gone.size}: ${gone.joinToString { words(it) }}")
                if (gone.isNotEmpty()) sync(connection)
            }
            MT_QUERY -> {
                val found = matching(Wire.parse(f.bytes(1)))
                connection.send(reply, result() + found.fold(ByteArray(0)) { acc, t -> acc + Wire.fieldBytes(2, t.raw) }, messageType = MT_QUERY + 1)
                note("Glasses asked for timers: ${found.size} found")
            }
            MT_ALERT -> {
                // It rang: a timer or a one-off alarm is done with; a weekly alarm stays.
                val id = f.bytes(3).decodeToString()
                val done = kept.filter { it.id == id && !it.repeats }
                if (done.isNotEmpty()) {
                    keep(kept - done.toSet())
                    sync(connection)
                }
                note("Rang on the glasses: ${done.joinToString { words(it) }.ifEmpty { id }}")
            }
            MT_SYNC_ON_DEMAND -> {
                connection.send(reply, result(), messageType = MT_SYNC_ON_DEMAND + 1)
                sync(connection)
            }
            else -> note("message type ${message.info.messageType} not handled")
        }
    }

    /** The whole list to the glasses, without what has long rung. */
    private suspend fun sync(connection: DataXConnection) {
        val now = System.currentTimeMillis()
        keep(kept.filterNot { t -> t.completed || (t.fireAt != null && !t.repeats && t.fireAt < now - STALE_MS) })
        val body = kept.fold(ByteArray(0)) { acc, t -> acc + Wire.fieldBytes(1, t.raw) } +
            Wire.fieldString(2, UUID.randomUUID().toString())
        connection.sendOnService(SVC_TAR, MT_SYNC_TO_GLASSES, body)
    }

    /** What a TARQuery picks out: by ids, else by type and title. */
    private fun matching(q: Wire.Fields): List<Tar> {
        val ids = q.repeatedBytes(1).map { it.decodeToString() }.toSet()
        if (ids.isNotEmpty()) return kept.filter { it.id in ids }
        val type = if (q.has(2)) TarType.of(q.varint(2)) else null
        val title = if (q.has(101)) Wire.parse(q.bytes(101)).bytes(1).decodeToString().trim().lowercase() else ""
        return kept.filter { t -> (type == null || t.type == type) && (title.isEmpty() || title in t.title.lowercase()) }
    }

    private fun result(): ByteArray = Wire.fieldBytes(1, Wire.fieldBytes(1, ByteArray(0)))

    private fun failure(code: Long, why: String): ByteArray =
        Wire.fieldBytes(1, Wire.fieldBytes(2, Wire.fieldVarint(1, code) + Wire.fieldString(2, why)))

    private fun keep(list: List<Tar>) {
        kept = list
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(JSONArray(list.map { JSONObject().put("raw", Base64.encodeToString(it.raw, Base64.NO_WRAP)) }).toString())
        }
    }

    private fun load(): List<Tar> = runCatching {
        JSONArray(file.readText()).objects().map { Tar(Base64.decode(it.getString("raw"), Base64.NO_WRAP)) }
    }.getOrDefault(emptyList())

    private fun words(t: Tar): String = listOfNotNull(
        t.type.name.lowercase(),
        t.title.takeIf { it.isNotEmpty() }?.let { "\"$it\"" },
        t.durationMs?.let { "${it / 1000}s" },
    ).joinToString(" ")

    private fun note(text: String) {
        _notes.tryEmit(text)
    }

    private companion object {
        const val MT_CREATE = 1
        const val MT_UPDATE = 3
        const val MT_DELETE = 5
        const val MT_QUERY = 7
        const val MT_ALERT = 9
        const val MT_SYNC_ON_DEMAND = 10
        const val MT_SYNC_TO_GLASSES = 1100
        const val ERROR_UPDATE = 4L
        const val ERROR_NO_MATCHES = 7L
        const val STALE_MS = 60 * 60_000L
    }
}
