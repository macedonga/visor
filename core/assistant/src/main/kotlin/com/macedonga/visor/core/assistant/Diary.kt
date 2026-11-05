package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.connection.objects
import com.macedonga.visor.core.connection.strings
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Where a question came from: "Hey Meta" and its follow-ups, the app's composer, or a Live AI session. */
enum class TurnSource { GLASSES, PHONE, LIVE_AI }

/** One "Hey Meta" turn, for the screen. */
data class TurnRecord(
    val interactionId: String,
    val at: Long,
    val question: String,
    val answer: String = "",
    val problem: String = "",
    val modelMs: Long = 0,
    val sentMs: Long = 0,
    // What the glasses report having played, from their 5005; -1 until it arrives.
    val playedMs: Long = -1,
    // The photo the answer was given, as sent to the model.
    val image: String = "",
    // The conversation it belongs to: questions within the memory window of each other, or a Live AI session.
    val conversation: String = "",
    val source: TurnSource = TurnSource.GLASSES,
    // The Live AI session it was asked in.
    val session: String = "",
    // What the model chose to remember while answering.
    val remembered: List<String> = emptyList(),
    // What it did with the person's tools.
    val actions: List<ToolAction> = emptyList(),
)

/** A day's lines, written by the model from that day's exchanges, and when. */
data class DayLines(val text: String, val writtenAt: Long)

/** Something lasting the assistant keeps about the wearer. */
data class Memory(val id: String, val text: String, val at: Long)

/**
 * Everything asked of the assistant, kept on the phone by day until it is deleted: one file per day with its turns
 * and its lines, the photos the answers were given, and what the assistant remembers.
 */
class Diary(private val dir: File, legacyTurns: File, legacyPhotos: File) {

    val photos = File(dir, "photos")
    private val days = File(dir, "days")
    private val memoryFile = File(dir, "memories.json")
    // One writer at a time: saves share the .tmp file and must land in order.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _turns = MutableStateFlow<List<TurnRecord>>(emptyList())
    /** Every turn, oldest first. */
    val turns: StateFlow<List<TurnRecord>> = _turns.asStateFlow()

    private val _lines = MutableStateFlow<Map<LocalDate, DayLines>>(emptyMap())
    val lines: StateFlow<Map<LocalDate, DayLines>> = _lines.asStateFlow()

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    private val dirty = HashSet<LocalDate>()
    private var saving: Job? = null

    init {
        days.mkdirs()
        photos.mkdirs()
        if (legacyTurns.exists()) adopt(legacyTurns, legacyPhotos)
        load()
    }

    // --- turns ---------------------------------------------------------------

    fun record(turn: TurnRecord) {
        _turns.update { list -> (list.filterNot { it.interactionId == turn.interactionId } + turn).sortedBy { it.at } }
        touch(turn.date)
    }

    fun update(id: String, change: (TurnRecord) -> TurnRecord) {
        var date: LocalDate? = null
        _turns.update { list -> list.map { if (it.interactionId == id) change(it).also { t -> date = t.date } else it } }
        date?.let(::touch)
    }

    fun turn(id: String): TurnRecord? = _turns.value.firstOrNull { it.interactionId == id }

    /** Takes [ids] out of the diary; their photos stay until [forgetPhotos], so an Undo can bring them back whole. */
    fun delete(ids: Set<String>): List<TurnRecord> {
        val gone = _turns.value.filter { it.interactionId in ids }
        if (gone.isEmpty()) return gone
        _turns.update { list -> list.filterNot { it.interactionId in ids } }
        // A day's lines speak of what was deleted: gone with the day, or to be written again.
        val left = _turns.value.map { it.date }.toSet()
        _lines.update { lines ->
            lines.mapNotNull { (date, l) ->
                when {
                    gone.none { it.date == date } -> date to l
                    date in left -> date to l.copy(writtenAt = 0)
                    else -> null
                }
            }.toMap()
        }
        gone.map { it.date }.toSet().forEach(::touch)
        return gone
    }

    fun restore(turns: List<TurnRecord>) {
        if (turns.isEmpty()) return
        val ids = turns.map { it.interactionId }.toSet()
        _turns.update { list -> (list.filterNot { it.interactionId in ids } + turns).sortedBy { it.at } }
        turns.map { it.date }.toSet().forEach(::touch)
    }

    /** The photos of turns deleted for good. */
    fun forgetPhotos(turns: List<TurnRecord>) {
        val kept = _turns.value.map { it.image }.toSet()
        scope.launch { turns.map { it.image }.filter { it.isNotEmpty() && it !in kept }.forEach { File(it).delete() } }
    }

    /**
     * The conversation a question asked [at] belongs to: the last one, while it is within [windowMs] of its last
     * question (what the model still remembers), otherwise a new one.
     */
    fun conversationAt(at: Long, windowMs: Long): String = openConversation(at, windowMs) ?: UUID.randomUUID().toString()

    /** The last conversation, while [at] is within [windowMs] of its last question; null otherwise. */
    fun openConversation(at: Long, windowMs: Long): String? {
        val last = _turns.value.lastOrNull { it.source != TurnSource.LIVE_AI && it.conversation.isNotEmpty() } ?: return null
        return last.conversation.takeIf { at - last.at in 0..windowMs }
    }

    /** The answered turns of [conversation] before [before] (all of them if null), oldest first. */
    fun past(conversation: String, before: String? = null): List<TurnRecord> {
        val all = _turns.value.filter { it.conversation == conversation }
        val upTo = before?.let { id -> all.indexOfFirst { it.interactionId == id }.takeIf { it >= 0 } } ?: all.size
        return all.take(upTo).filter { it.answer.isNotBlank() && it.problem.isEmpty() }
    }

    // --- a day's lines -------------------------------------------------------

    fun setLines(date: LocalDate, text: String) {
        _lines.update { it + (date to DayLines(text, System.currentTimeMillis())) }
        touch(date)
    }

    // --- memories ------------------------------------------------------------

    fun remember(text: String): Memory {
        val memory = Memory("m" + UUID.randomUUID().toString().take(6), text.trim(), System.currentTimeMillis())
        _memories.update { it + memory }
        saveMemories()
        return memory
    }

    fun forget(id: String): Memory? {
        val gone = _memories.value.firstOrNull { it.id == id } ?: return null
        _memories.update { list -> list.filterNot { it.id == id } }
        saveMemories()
        return gone
    }

    fun restoreMemory(memory: Memory) {
        _memories.update { list -> (list.filterNot { it.id == memory.id } + memory).sortedBy { it.at } }
        saveMemories()
    }

    // --- files ---------------------------------------------------------------

    private fun touch(date: LocalDate) {
        synchronized(dirty) { dirty += date }
        saving?.cancel()
        saving = scope.launch {
            delay(SAVE_DELAY_MS)
            flush()
        }
    }

    private fun flush() {
        val dates = synchronized(dirty) { dirty.toList().also { dirty.clear() } }
        val byDay = _turns.value.groupBy { it.date }
        val lines = _lines.value
        for (date in dates) {
            val file = File(days, "$date.json")
            val turns = byDay[date].orEmpty()
            val dayLines = lines[date]
            if (turns.isEmpty() && dayLines == null) {
                file.delete()
                continue
            }
            val json = JSONObject().put("turns", JSONArray().apply { turns.forEach { put(it.toJson()) } })
            dayLines?.let { json.put("lines", it.text).put("linesAt", it.writtenAt) }
            runCatching { write(file, json.toString()) }
        }
    }

    private fun saveMemories() {
        scope.launch {
            val list = _memories.value
            val json = JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at)) } }
            runCatching { write(memoryFile, json.toString()) }
        }
    }

    /** Written beside, then moved over, so a crash never leaves half a day. */
    private fun write(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    private fun load() {
        val turns = ArrayList<TurnRecord>()
        val lines = HashMap<LocalDate, DayLines>()
        for (file in days.listFiles().orEmpty().filter { it.name.endsWith(".json") }) {
            val date = runCatching { LocalDate.parse(file.name.removeSuffix(".json")) }.getOrNull() ?: continue
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
            json.optJSONArray("turns")?.objects()?.forEach { turnFrom(it)?.let(turns::add) }
            json.optString("lines").takeIf { it.isNotEmpty() }?.let { lines[date] = DayLines(it, json.optLong("linesAt")) }
        }
        _turns.value = turns.sortedBy { it.at }
        _lines.value = lines
        _memories.value = runCatching {
            val a = JSONArray(memoryFile.readText())
            a.objects().map { Memory(it.getString("id"), it.getString("text"), it.optLong("at")) }
        }.getOrDefault(emptyList())
    }

    /** The old last-30 file and the photos it pointed at in the cache: moved in, each turn its own conversation by time. */
    private fun adopt(legacy: File, legacyPhotos: File) {
        val old = runCatching { JSONArray(legacy.readText()) }.getOrNull()
        if (old != null) {
            var conversation = ""
            var lastAt = 0L
            val turns = old.objects().mapNotNull { turnFrom(it) }.sortedBy { it.at }.map { t ->
                if (conversation.isEmpty() || t.at - lastAt > LEGACY_WINDOW_MS) conversation = UUID.randomUUID().toString()
                lastAt = t.at
                val image = t.image.takeIf { it.isNotEmpty() }?.let { path ->
                    val from = File(path)
                    val to = File(photos, from.name)
                    // Copied, not moved: a file moved out of the cache would still count as cache.
                    if (from.exists() && (to.exists() || runCatching { from.copyTo(to, true) }.isSuccess)) to.path else ""
                }.orEmpty()
                t.copy(conversation = conversation, image = image)
            }
            for ((date, list) in turns.groupBy { it.date }) {
                val json = JSONObject().put("turns", JSONArray().apply { list.forEach { put(it.toJson()) } })
                runCatching { write(File(days, "$date.json"), json.toString()) }
            }
        }
        legacy.delete()
        legacyPhotos.deleteRecursively()
    }

    private companion object {
        const val SAVE_DELAY_MS = 400L
        const val LEGACY_WINDOW_MS = 10 * 60_000L
    }
}

/** The day a turn was asked, on this phone's clock. */
val TurnRecord.date: LocalDate get() = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()

private fun TurnRecord.toJson(): JSONObject = JSONObject()
    .put("id", interactionId).put("at", at).put("question", question).put("answer", answer)
    .put("problem", problem).put("modelMs", modelMs).put("sentMs", sentMs).put("playedMs", playedMs)
    .put("image", image).put("conversation", conversation).put("source", source.name).put("session", session)
    .put("remembered", JSONArray(remembered))
    .put("actions", JSONArray(actions.map {
        JSONObject().put("server", it.server).put("what", it.what).put("ok", it.ok).put("kind", it.kind).put("subject", it.subject)
    }))

private fun turnFrom(o: JSONObject): TurnRecord? = runCatching {
    TurnRecord(
        interactionId = o.getString("id"), at = o.getLong("at"), question = o.optString("question"), answer = o.optString("answer"),
        problem = o.optString("problem"), modelMs = o.optLong("modelMs"), sentMs = o.optLong("sentMs"),
        playedMs = o.optLong("playedMs", -1), image = o.optString("image"), conversation = o.optString("conversation"),
        source = runCatching { TurnSource.valueOf(o.optString("source")) }.getOrDefault(TurnSource.GLASSES),
        session = o.optString("session"),
        remembered = o.optJSONArray("remembered")?.strings().orEmpty(),
        actions = o.optJSONArray("actions")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                ToolAction(it.optString("server"), it.optString("what"), it.optBoolean("ok", true), it.optString("kind"), it.optString("subject"))
            }
        }.orEmpty(),
    )
}.getOrNull()
