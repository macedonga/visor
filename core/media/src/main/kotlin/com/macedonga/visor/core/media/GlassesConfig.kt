package com.macedonga.visor.core.media

import android.content.Context
import com.macedonga.visor.core.connection.GlassesConnection
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** One MobileConfig param from the glasses' params map. type: 1 bool, 2 int64, 3 string, 4 double. */
class McParam(val name: String, val type: Int, val slot: Int, val sessionless: Boolean)

data class ConfigState(
    // The glasses' own values by param name, from their last bug report or from the last write.
    val values: Map<String, Any> = emptyMap(),
    // What the last bug report showed, so a write is only taken as landed once a report says so.
    val reported: Map<String, Any> = emptyMap(),
    val reportedAt: Long? = null,
    // When Visor last sent a table; a report older than this does not tell whether it landed.
    val sentAt: Long? = null,
    val params: Map<String, McParam> = emptyMap(),
    // The values from before Visor's first write, the way back.
    val baseline: Map<String, Any>? = null,
    val readAt: Long? = null,
    // The glasses' boot as the last report named it: a new one means they restarted since.
    val boot: String? = null,
    val busy: String = "",
    val note: String = "",
)

/**
 * The glasses' MobileConfig, Meta's server-side feature switches. They come out of a lite bug report, the directory
 * collectors without dumpstate (mobileconfig/current_mc_values.json and the params map, in seconds where a full report
 * takes minutes over Bluetooth); a change goes back as a full pair of
 * FBMobileConfigTables zipped onto airship namespace "mobileconfig", the way the Meta app syncs them.
 * Most switches are read at boot, so the glasses need a restart after a write.
 */
class GlassesConfig(
    context: Context,
    private val connection: GlassesConnection,
    private val airship: AirshipReceiver,
    private val media: MediaImport,
) {
    private val root = File(context.filesDir, "mobileconfig").apply { mkdirs() }

    // Each pair's own folder: their parameter maps differ with their firmware, and a table built on one pair's values
    // must never reach another.
    @Volatile
    private var dir = File(root, connection.pair.value ?: NO_PAIR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ConfigState())
    val state: StateFlow<ConfigState> = _state.asStateFlow()

    private var schemaHash = ""
    private var counts = IntArray(4)
    private var sessionId = ""
    private var waiting: CompletableDeferred<Unit>? = null
    private var reading: Deferred<Boolean>? = null
    private var boot: String? = null
    private val writes = Mutex()

    init {
        media.bugReportReader = { take(it) }
        scope.launch {
            connection.pair.filterNotNull().distinctUntilChanged().collect { serial ->
                writes.withLock { runCatching { switchTo(serial) } }
            }
        }
    }

    /** The pair's own values, or none yet; what the last pair had stays in its folder. */
    private fun switchTo(serial: String) {
        dir = File(root, serial)
        // Before Visor kept pairs apart, the one pair's files sat in the root.
        val loose = root.listFiles { f -> f.isFile }.orEmpty()
        if (loose.isNotEmpty() && !dir.exists()) {
            dir.mkdirs()
            loose.forEach { it.renameTo(File(dir, it.name)) }
        }
        dir.mkdirs()
        schemaHash = ""
        counts = IntArray(4)
        sessionId = ""
        boot = null
        _state.value = ConfigState()
        load()
    }

    /** Ask the glasses for a bug report and read their configuration out of it. */
    fun read() {
        readAsync()
    }

    /** [read], waited for: true once the report came in. A second ask joins the read on its way. */
    suspend fun readNow(): Boolean = readAsync().await()

    @Synchronized
    private fun readAsync(): Deferred<Boolean> = reading?.takeIf { it.isActive } ?: scope.async {
        val done = CompletableDeferred<Unit>().also { waiting = it }
        _state.update { it.copy(busy = "Asking the glasses for their configuration.", note = "") }
        media.requestBugReport(LITE_REPORT)
        val got = withTimeoutOrNull(READ_TIMEOUT_MS) { done.await() } != null
        waiting = null
        _state.update { it.copy(busy = "", note = if (got) it.note else "The glasses did not send their configuration.") }
        got
    }.also { reading = it }

    /** Write [changes] (param name to value) over the glasses' current values. */
    fun apply(changes: Map<String, Any>) {
        scope.launch { send(changes) }
    }

    /** [apply], waited for: null once the glasses have the table, else why it did not go. */
    suspend fun send(changes: Map<String, Any>): String? = write(changes, "Sent. Restart the glasses to use the new values.")

    /** The zip [apply] would send for [changes], for checking it by hand. */
    fun preview(changes: Map<String, Any>): Pair<String, ByteArray> = tables(state.value.values + changes)

    /** Put back the values from before Visor's first write. */
    fun restore() {
        val base = state.value.baseline ?: return
        scope.launch { write(base, "Original values sent. Restart the glasses to use them.") }
    }

    private suspend fun write(changes: Map<String, Any>, done: String): String? {
        // A read on its way finishes first: the table goes over what it brings.
        reading?.takeIf { it.isActive }?.await()
        return writes.withLock {
            val s = state.value
            if (s.params.isEmpty()) return@withLock "the glasses' configuration has not been read yet"
            val values = s.values + changes
            _state.update { it.copy(busy = "Sending to the glasses…", note = "") }
            val why = runCatching {
                val (name, zip) = tables(values)
                val sent = CompletableDeferred<String?>()
                connection.submit { mux -> sent.complete(airship.send(mux, NAMESPACE, name, zip)) }
                withTimeoutOrNull(SEND_TIMEOUT_MS) { sent.await() } ?: if (sent.isCompleted) null else "no link or no answer"
            }.getOrElse { it.message ?: it.toString() }
            if (why == null) {
                if (s.baseline == null) {
                    File(dir, BASELINE).writeText(valuesJson(s.values))
                }
                File(dir, VALUES).writeText(valuesJson(values))
                val now = System.currentTimeMillis()
                File(dir, SENT).writeText(now.toString())
                _state.update { it.copy(values = values, baseline = it.baseline ?: s.values, sentAt = now, busy = "", note = done) }
            } else {
                _state.update { it.copy(busy = "", note = "Not sent: $why") }
            }
            why
        }
    }

    /** A bug report zip from the glasses: keep its MobileConfig part. */
    private fun take(zip: File) {
        val lost = ZipFile(zip).use { z ->
            fun bytes(path: String) = z.getEntry(path)?.let { e -> z.getInputStream(e).use { it.readBytes() } }
            val map = bytes("mobileconfig/maps/current/merged_params_map_v4_u0.txt") ?: return
            val names = bytes("mobileconfig/maps/current/merged_params_names_v4_u0.txt") ?: return
            val values = bytes("mobileconfig/current_mc_values.json")
            values?.let {
                File(dir, VALUES).writeBytes(it)
                File(dir, REPORTED).writeBytes(it)
            }
            File(dir, MAP).writeBytes(map)
            File(dir, NAMES).writeBytes(names)
            bytes("mobileconfig/session_info.json")?.let { File(dir, SESSION).writeBytes(it) }
            bytes("mobileconfig/current_timestamp.txt")?.let { File(dir, TIMESTAMP).writeText(String(it).trim()) }
            boot = bytes("settingsservice/boot_session_id")?.let { String(it).trim() } ?: boot
            values == null
        }
        load()
        _state.update { it.copy(readAt = System.currentTimeMillis(), boot = boot, note = "Read ${it.values.size} values from the glasses.") }
        // A reset leaves the glasses on built-in defaults, without what Meta's servers had set (dynamic photos among
        // it); the last values Visor saw go back.
        if (lost && state.value.values.isNotEmpty()) scope.launch { write(emptyMap(), "The glasses had lost their configuration; the last one went back. Restart the glasses to use it.") }
        waiting?.complete(Unit)
    }

    private fun load() {
        val map = File(dir, MAP).takeIf { it.isFile } ?: return
        val params = parseMap(map.readBytes(), File(dir, NAMES).readText())
        sessionId = File(dir, SESSION).takeIf { it.isFile }?.let { JSONObject(it.readText()).optString("session_id") }.orEmpty()
        val values = File(dir, VALUES).takeIf { it.isFile }?.let { parseValues(it.readText()) }.orEmpty()
        val baseline = File(dir, BASELINE).takeIf { it.isFile }?.let { parseValues(it.readText()) }
        val reported = File(dir, REPORTED).takeIf { it.isFile }
        val sent = File(dir, SENT).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        _state.update {
            it.copy(
                values = values, params = params, baseline = baseline, readAt = it.readAt ?: map.lastModified(),
                // Before Visor kept reports apart, the values it held were the last report's.
                reported = reported?.let { f -> parseValues(f.readText()) } ?: values,
                reportedAt = reported?.lastModified() ?: File(dir, VALUES).takeIf { f -> f.isFile }?.lastModified(),
                sentAt = sent,
            )
        }
    }

    private fun parseMap(map: ByteArray, namesJson: String): Map<String, McParam> {
        val b = ByteBuffer.wrap(map).order(ByteOrder.LITTLE_ENDIAN)
        counts = IntArray(4) { b.getShort(4 + 2 * (3 + it)).toInt() and 0xffff }
        schemaHash = String(map, 30, 32, Charsets.US_ASCII)
        val names = HashMap<Long, String>()
        val list = JSONArray(namesJson)
        for (i in 0 until list.length()) {
            val p = list.getString(i).split(':')
            val config = p[0].toLong()
            for (k in 2 until p.size - 1 step 2) names[(config shl 12) or p[k].toLong()] = "${p[1]}.${p[k + 1]}"
        }
        val out = HashMap<String, McParam>()
        var at = 62
        while (at + 8 <= map.size) {
            val x = b.getLong(at)
            at += 8
            val name = names[((x ushr 44) shl 12) or ((x ushr 32) and 0xfff)] ?: continue
            out[name] = McParam(name, ((x ushr 11) and 7).toInt(), ((x ushr 16) and 0xffff).toInt(), x and 0x400L != 0L)
        }
        return out
    }

    /**
     * The archive time in the zip's name. The glasses skip, without a word, any archive whose time is not newer than
     * the last one they took, and they may read the name in their local clock or in UTC. So the time is read in
     * whichever of the two runs ahead, and kept past the last one they reported and the last one Visor sent.
     */
    private fun stamp(): String {
        val now = System.currentTimeMillis()
        val local = TimeZone.getDefault()
        val fmt = SimpleDateFormat(STAMP, Locale.US).apply { timeZone = if (local.getOffset(now) > 0) local else TimeZone.getTimeZone("UTC") }
        var at = now
        File(dir, TIMESTAMP).takeIf { it.isFile }?.readText()?.trim()?.let { runCatching { fmt.parse(it) }.getOrNull() }?.let { at = maxOf(at, it.time + STALE_MARGIN_MS) }
        File(dir, LAST_STAMP).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let { at = maxOf(at, it + 1_000) }
        File(dir, LAST_STAMP).writeText(at.toString())
        return fmt.format(Date(at))
    }

    /** Both tables for [values] and the zip the glasses take them in. */
    private fun tables(values: Map<String, Any>): Pair<String, ByteArray> {
        // Per table (sessionless, sessioned): bools, int64s, doubles, strings by slot.
        val t = mapOf(true to List(4) { HashMap<Int, Any>() }, false to List(4) { HashMap<Int, Any>() })
        for ((name, v) in values) {
            val p = state.value.params[name] ?: continue
            val kind = when (p.type) {
                1 -> 0.takeIf { v is Boolean }
                2 -> 1.takeIf { v is Long || v is Int }
                4 -> 2.takeIf { v is Number }
                3 -> 3.takeIf { v is String }
                else -> null
            } ?: continue
            t.getValue(p.sessionless)[kind][p.slot] = v
        }
        // The glasses drop a table with no value in it. One Meta never filled gets a test param nothing reads, at the
        // value both the firmware and Meta's server give it.
        for ((filler, value) in FILLERS) {
            val p = state.value.params[filler]?.takeIf { it.type == 1 } ?: continue
            val table = t.getValue(p.sessionless)
            if (table.all { it.isEmpty() }) table[0][p.slot] = value
        }
        // The name must be 88 characters, so it needs a session uuid; a pair Meta never synced has none to report.
        if (sessionId.length != 36) {
            sessionId = UUID.randomUUID().toString()
            File(dir, SESSION).writeText(JSONObject().put("session_id", sessionId).toString())
        }
        val name = "${stamp()}_${sessionId}_mobileconfig_params_values.zip"
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("sessionless.data/")); z.closeEntry()
                z.putNextEntry(ZipEntry("params.data/")); z.closeEntry()
                for ((sessionless, path) in listOf(true to "sessionless.data/0.mctable", false to "params.data/0.mctable")) {
                    val (bools, ints, doubles, strings) = t.getValue(sessionless)
                    z.putNextEntry(ZipEntry(path))
                    z.write(McTable.container(McTable.build(schemaHash, counts, bools, ints, doubles, strings)))
                    z.closeEntry()
                }
            }
        }.toByteArray()
        return name to zip
    }

    private fun parseValues(json: String): Map<String, Any> {
        val out = HashMap<String, Any>()
        val list = JSONArray(json)
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            if (e.optInt("value_source", -1) != 0) continue
            for (k in e.keys()) {
                if (k == "value_source") continue
                val v = e.get(k)
                if (v != JSONObject.NULL) out[k] = if (v is Int) v.toLong() else v
            }
        }
        return out
    }

    private fun valuesJson(values: Map<String, Any>): String =
        JSONArray(values.map { (k, v) -> JSONObject().put(k, v).put("value_source", 0) }).toString()

    private companion object {
        const val NAMESPACE = "mobileconfig"
        const val VALUES = "values.json"
        const val BASELINE = "baseline.json"
        const val MAP = "params_map.bin"
        const val NAMES = "params_names.json"
        const val SESSION = "session_info.json"
        const val REPORTED = "reported.json"
        const val SENT = "sent_at.txt"
        const val TIMESTAMP = "current_timestamp.txt"
        const val LAST_STAMP = "last_stamp.txt"
        private const val NO_PAIR = "none"
        const val STAMP = "MMM-dd-yyyy-HH-mm-ss"
        const val STALE_MARGIN_MS = 15 * 60_000L
        const val READ_TIMEOUT_MS = 10 * 60_000L
        // BugReportParams.mode 1: 355 KB in 10 s over Bluetooth on 7238, where the default full report was 4.3 MB in 2 min.
        private const val LITE_REPORT = 1
        const val SEND_TIMEOUT_MS = 60_000L
        // One sessionless, one sessioned.
        val FILLERS = mapOf("smartglasses_test_config.test_1" to true, "smartglasses_test_config_sessioned.gw_test_1" to false)
    }
}

/**
 * FBMobileConfigTable as the glasses' readers in libmcdeps.so take it: a flatbuffer with the schema
 * hash in field 0, value and meta vectors per type in 1-10, magic 123456 in 15, everything else empty.
 * A meta of 1 means no value; a true bool is 0x80.
 */
internal object McTable {
    private const val SLOTS = 29
    private const val NULL_META = 1
    private const val MAGIC = 123456

    private class Out {
        var b = ByteArray(1 shl 16)
        var n = 0
        fun room(k: Int) {
            if (n + k > b.size) b = b.copyOf(maxOf(b.size * 2, n + k))
        }
        fun byte(v: Int) { room(1); b[n++] = v.toByte() }
        fun zeros(k: Int) { room(k); n += k }
        fun align(a: Int, extra: Int = 0) { while ((n + extra) % a != 0) byte(0) }
        fun i32(v: Int) { room(4); ByteBuffer.wrap(b, n, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(v); n += 4 }
        fun i64(v: Long) { room(8); ByteBuffer.wrap(b, n, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(v); n += 8 }
        fun f64(v: Double) = i64(v.toRawBits())
        fun u16(v: Int) { byte(v and 0xff); byte(v shr 8) }
        fun putI32(at: Int, v: Int) = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(v)
        fun putI64(at: Int, v: Long) = ByteBuffer.wrap(b, at, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(v)
        fun bytes() = b.copyOf(n)
    }

    fun container(body: ByteArray): ByteArray =
        byteArrayOf(0x3c, 0xf1.toByte(), 1, 8) +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size + 10).array() + body + byteArrayOf(0x3c, 0xf1.toByte())

    fun build(hash: String, counts: IntArray, bools: Map<Int, Any>, ints: Map<Int, Any>, doubles: Map<Int, Any>, strings: Map<Int, Any>): ByteArray {
        val (nb, ni, nd, ns) = counts.toList()
        val vectors: Map<Int, Pair<String, List<Any>>> = mapOf(
            1 to ("i8" to List(nb) { if (bools[it] == true) -128 else 0 }),
            2 to ("i32" to List(nb) { if (it in bools) 0 else NULL_META }),
            3 to ("i64" to List(ni) { (ints[it] as? Number)?.toLong() ?: 0L }),
            4 to ("i32" to List(ni) { if (it in ints) 0 else NULL_META }),
            5 to ("i32" to emptyList()), 6 to ("i32" to emptyList()),
            7 to ("f64" to List(nd) { (doubles[it] as? Number)?.toDouble() ?: 0.0 }),
            8 to ("i32" to List(nd) { if (it in doubles) 0 else NULL_META }),
            9 to ("str" to List(ns) { strings[it] as? String ?: "" }),
            10 to ("i32" to List(ns) { if (it in strings) 0 else NULL_META }),
            11 to ("str" to emptyList()), 14 to ("tab" to emptyList()), 17 to ("tab" to emptyList()),
            18 to ("i32" to emptyList()), 19 to ("str" to emptyList()),
            23 to ("i32" to emptyList()), 24 to ("i32" to emptyList()), 25 to ("i32" to emptyList()), 26 to ("i32" to emptyList()),
        )
        val texts = mapOf(0 to hash, 20 to "", 21 to "", 28 to "")
        val scalars = mapOf(12 to ("i32" to 0L), 15 to ("i32" to MAGIC.toLong()), 16 to ("i64" to 0L), 22 to ("i32" to 0L), 27 to ("i32" to 0L))

        // Table layout: soffset, then the fields in slot order, 8-byte scalars aligned.
        val layout = ArrayList<Triple<Int, Int, String>>()
        var off = 4
        for (slot in 0 until SLOTS) {
            if (slot == 13) continue
            val kind = scalars[slot]?.first
            if (kind == "i64") {
                off = (off + 7) / 8 * 8
                layout += Triple(slot, off, "i64")
                off += 8
            } else {
                layout += Triple(slot, off, kind ?: "ref")
                off += 4
            }
        }
        val tableSize = (off + 7) / 8 * 8

        val out = Out()
        out.zeros(8)
        val vtPos = out.n
        val vt = IntArray(SLOTS)
        for ((slot, o, _) in layout) vt[slot] = o
        out.u16(4 + 2 * SLOTS)
        out.u16(tableSize)
        vt.forEach { out.u16(it) }
        out.align(8)
        val tablePos = out.n
        out.zeros(tableSize)
        out.putI32(0, tablePos)
        "MCTB".forEachIndexed { i, c -> out.b[4 + i] = c.code.toByte() }
        out.putI32(tablePos, tablePos - vtPos)

        fun string(s: String): Int {
            out.align(4)
            val p = out.n
            val raw = s.toByteArray()
            out.i32(raw.size)
            raw.forEach { out.byte(it.toInt()) }
            out.byte(0)
            return p
        }

        fun vector(kind: String, items: List<Any>): Int {
            if (kind == "i64" || kind == "f64") out.align(8, 4) else out.align(4)
            val p = out.n
            out.i32(items.size)
            when (kind) {
                "i8" -> items.forEach { out.byte(it as Int) }
                "i32" -> items.forEach { out.i32(it as Int) }
                "i64" -> items.forEach { out.i64(it as Long) }
                "f64" -> items.forEach { out.f64(it as Double) }
                "str" -> {
                    val slots = out.n
                    out.zeros(4 * items.size)
                    items.forEachIndexed { i, s -> val sp = string(s as String); out.putI32(slots + 4 * i, sp - (slots + 4 * i)) }
                }
            }
            return p
        }

        for ((slot, o, _) in layout) {
            val at = tablePos + o
            val scalar = scalars[slot]
            when {
                scalar != null -> if (scalar.first == "i64") out.putI64(at, scalar.second) else out.putI32(at, scalar.second.toInt())
                slot in texts -> out.putI32(at, string(texts.getValue(slot)) - at)
                else -> {
                    val (kind, items) = vectors.getValue(slot)
                    out.putI32(at, vector(kind, items) - at)
                }
            }
        }
        out.align(4)
        return out.bytes()
    }
}
