package com.macedonga.visor.core.settings

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.LinkState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** svc31, smartglasses_settings: write-only. Its ack is always SUCCESS, so a write is checked by reading back. */
const val SVC_SETTINGS = 31
private const val MT_SYNC_DATA = 2
private const val MT_ACK = 3

enum class Kind { BOOL, INT, STR, FLOAT, OTHER }

/** One setting from the tables: svc31 field number, kind, firmware default and RLDrive namespace. */
data class SettingInfo(
    val key: String,
    val field: Int?,
    val kind: Kind,
    val default: String?,
    val namespace: String?,
    // This firmware's settingsservice names it. A write to one it does not is acked and may do nothing.
    val parsed: Boolean,
) {
    // No svc31 field, or one this firmware's settingsservice does not name (it acks and drops those):
    // written into the glasses' RLDrive store instead, where every key lives.
    val viaDrive: Boolean get() = this.field == null || !parsed

    /** The RLDrive namespace a write goes to: the schema's, else the one the glasses file unknown keys under. */
    val driveNamespace: String get() = namespace ?: "individual_device_settings"
    val writable: Boolean get() = kind in WRITABLE && (this.field != null || viaDrive) && key !in PROTECTED
}

private val WRITABLE = setOf(Kind.BOOL, Kind.INT, Kind.STR, Kind.FLOAT)

// The one key Visor never writes: the skin-temperature controller uses it to shut hot glasses down.
private val PROTECTED = mapOf(
    "sg_disable_thermal_shutdown" to "It turns off the shutdown that stops the glasses getting hot enough to burn skin.",
)

/** Why Visor will not write [key], or null when it will. */
fun protectedReason(key: String): String? = PROTECTED[key]

data class SettingsState(
    // Key to raw text, for every setting someone has set. Anything else is at its default.
    val stored: Map<String, String> = emptyMap(),
    val reading: Boolean = false,
    val readAt: Long? = null,
    // Written and not yet seen on a read, key to the value asked for.
    val pending: Map<String, String> = emptyMap(),
    // Written, read back, and still the old value.
    val kept: Set<String> = emptySet(),
    // Written to tidy up, not asked for on screen: how the glasses answer is not shown.
    val quiet: Set<String> = emptySet(),
    val problem: String = "",
    // The Meta product id of the glasses last connected (266 for Ray-Ban Meta Gen 2), for what the catalogue offers.
    val product: Int? = null,
    // The serial of the pair these are, so a reader never takes them for another's.
    val pair: String? = null,
)

/**
 * Reads the glasses' settings over RLDrive and writes them on svc31. A full read at every connect,
 * then a delta (what moved since the versions last seen) after each write.
 */
class GlassesSettings(private val connection: GlassesConnection, drive: RlDriveResponder) {

    val catalogue: Map<String, SettingInfo> = loadCatalogue()

    /** Label, group and control for each key, from settings_ui.json. */
    val specs: Map<String, SettingSpec> = loadSpecs()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(SettingsState(pair = connection.pair.value))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** What was read and written, for the event log. */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    // One sync at a time; the versions and stores below only change under it.
    private val reads = Mutex()
    private var versions: Map<String, Versions> = emptyMap()
    private var byNamespace: Map<String, Map<String, String>> = emptyMap()

    @Volatile
    private var sync: Sync? = null
    private val writtenAt = ConcurrentHashMap<String, Long>()

    // Keys already sent again over RLDrive after svc31 left them as they were.
    private val retried = ConcurrentHashMap.newKeySet<String>()

    // The last InternalObject seen per key, with the namespace it lives in.
    private val raw = ConcurrentHashMap<String, Pair<String, ByteArray>>()

    /** A key's namespace and InternalObject bytes as last read, for debugging the drive format. */
    fun rawObject(key: String): Pair<String, ByteArray>? = raw[key]

    private class Sync(
        val waiting: MutableMap<String, String>,
        val push: Map<String, Map<String, String>> = emptyMap(),
        val pushObjects: Map<String, List<PushObject>> = emptyMap(),
    ) {
        val objects = ConcurrentHashMap<String, MutableList<DriveObject>>()
        val versions = ConcurrentHashMap<String, Versions>()
        val done = CompletableDeferred<Unit>()
    }

    init {
        drive.handler = ::onReply
        // Another pair's settings are not these: what was read goes, and the next read is a full one.
        scope.launch {
            connection.pair.filterNotNull().distinctUntilChanged().collect { serial ->
                reads.withLock {
                    if (_state.value.pair == serial) return@withLock
                    versions = emptyMap()
                    byNamespace = emptyMap()
                    raw.clear()
                    retried.clear()
                    writtenAt.clear()
                    _state.value = SettingsState(pair = serial)
                }
            }
        }
        scope.launch {
            connection.state.map { it.target?.serial to it.info?.build }.distinctUntilChanged().collect { (_, build) ->
                productId(build)?.let { id -> _state.update { it.copy(product = id) } }
            }
        }
        scope.launch {
            connection.state.map { it.link == LinkState.READY && it.info != null }.distinctUntilChanged().collect { ready ->
                if (ready) {
                    read(full = true)
                } else {
                    _state.update { it.copy(pending = emptyMap()) }
                }
            }
        }
        scope.launch {
            connection.serviceData.collect { if (it.serviceId == SVC_SETTINGS && it.messageType == MT_ACK) read() }
        }
    }

    /** The value in force: what is stored, else the firmware default. Null when neither is known. */
    fun value(key: String): String? = _state.value.stored[key] ?: catalogue[key]?.default

    fun read(full: Boolean = false) {
        scope.launch { reads.withLock { readNow(full) } }
    }

    /** Write [value] as text: "true"/"false", a number, or a string. False when it cannot be sent. */
    fun write(key: String, value: String): Boolean {
        val info = catalogue[key] ?: return false
        if (!info.writable) return false
        if (info.viaDrive) return writeDrive(key, value)
        val payload = encodeWrite(info, value) ?: return false
        if (connection.state.value.link != LinkState.READY) return false
        writtenAt[key] = System.currentTimeMillis()
        retried.remove(key)
        _state.update { it.copy(pending = it.pending + (key to value), kept = it.kept - key, quiet = it.quiet - key) }
        connection.submit { it.sendOnService(SVC_SETTINGS, MT_SYNC_DATA, payload) }
        say("Wrote $key = $value")
        // The ack starts a read too; this one settles the write if the ack never comes.
        scope.launch {
            delay(SETTLE_MILLIS + 500)
            read()
        }
        return true
    }

    /** A write to tidy up what an old build left behind, whose answer nobody needs to see. */
    fun writeQuietly(key: String, value: String): Boolean =
        write(key, value).also { sent -> if (sent) _state.update { it.copy(quiet = it.quiet + key) } }

    /**
     * Write a key that has no svc31 field straight into its RLDrive namespace, as another replica
     * of it would: the object goes out in round 2 of a sync, under our own writer. False when the
     * namespace is not known.
     */
    fun writeDrive(key: String, value: String, namespaceName: String? = null): Boolean {
        val base = namespaceName ?: catalogue[key]?.driveNamespace ?: return false
        val uuid = connection.state.value.info?.deviceUuid.orEmpty()
        if (connection.state.value.link != LinkState.READY) return false
        val namespace = if (base in SCOPED_NAMESPACES && uuid.isNotEmpty()) "$uuid@$base" else base
        writtenAt[key] = System.currentTimeMillis()
        _state.update { it.copy(pending = it.pending + (key to value), kept = it.kept - key, quiet = it.quiet - key) }
        say("Writing $key = $value into $namespace")
        scope.launch {
            reads.withLock { readNow(full = false, push = mapOf(namespace to mapOf(key to value))) }
            delay(1_000)
            read()
        }
        return true
    }

    /**
     * Every object the glasses hold in [namespace], read once, handing them [objects] on the way as
     * another replica would; for namespaces beyond the settings ones (contacts).
     */
    suspend fun readNamespace(namespace: String, objects: List<PushObject> = emptyList()): List<DriveObject> = reads.withLock {
        if (connection.state.value.link != LinkState.READY) return emptyList()
        val id = UUID.randomUUID().toString()
        val s = Sync(ConcurrentHashMap(mapOf(id to namespace)), pushObjects = mapOf(namespace to objects))
        sync = s
        connection.submit { it.sendOnService(SVC_RLDRIVE, MT_SYNC_MESSAGE, buildSync(namespace, id, 0, types = listOf(TYPE_KV, "contact", "contact_ranks", "sync_metadata"))) }
        withTimeoutOrNull(READ_TIMEOUT_MILLIS) { s.done.await() }
        sync = null
        s.objects[namespace]?.toList().orEmpty()
    }

    private suspend fun readNow(full: Boolean, push: Map<String, Map<String, String>> = emptyMap()) {
        val glasses = connection.state.value
        if (glasses.link != LinkState.READY) return
        val uuid = glasses.info?.deviceUuid.orEmpty()
        // Two namespaces are named after the device. Asking for one that does not exist creates it.
        val namespaces = if (uuid.isEmpty()) {
            DEVICE_NAMESPACES
        } else {
            SCOPED_NAMESPACES.map { "$uuid@$it" } + DEVICE_NAMESPACES
        }
        val known = if (full) emptyMap() else versions
        val requests = namespaces.associateBy { UUID.randomUUID().toString() }
        val s = Sync(ConcurrentHashMap(requests), push)
        sync = s
        _state.update { it.copy(reading = true) }
        connection.submit { link ->
            for ((id, namespace) in requests) {
                link.sendOnService(SVC_RLDRIVE, MT_SYNC_MESSAGE, buildSync(namespace, id, 0, known[namespace].orEmpty()))
            }
        }
        val finished = withTimeoutOrNull(READ_TIMEOUT_MILLIS) { s.done.await() } != null
        sync = null

        val stores = byNamespace.toMutableMap()
        for ((namespace, objects) in s.objects) {
            val before = if (full) emptyMap() else stores[namespace].orEmpty()
            stores[namespace] = before.toMutableMap().apply {
                for (o in synchronized(objects) { objects.toList() }) {
                    raw[o.uri] = namespace to o.raw
                    if (o.type != TYPE_KV) continue
                    if (o.deleted) remove(o.uri) else put(o.uri, o.data)
                }
            }
        }
        byNamespace = stores
        versions = versions + s.versions
        val stored = stores.values.fold(emptyMap<String, String>()) { acc, m -> acc + m }
        val changed = s.objects.values.sumOf { it.size }

        val now = System.currentTimeMillis()
        val pending = _state.value.pending
        val settled = pending.filter { (key, want) -> same(catalogue[key]?.kind, stored[key] ?: catalogue[key]?.default, want) }
        val kept = pending.keys.filter { it !in settled && now - (writtenAt[it] ?: now) > SETTLE_MILLIS }
        settled.forEach { (key, want) -> say("$key is now $want") }
        kept.forEach { key -> say("The glasses kept $key at ${stored[key] ?: catalogue[key]?.default}") }
        // An svc31 write the settings service took and did not apply goes once more straight into the store.
        val retry = kept.filter { key -> catalogue[key]?.viaDrive == false && retried.add(key) }.associateWith { pending.getValue(it) }
        _state.update {
            it.copy(
                stored = stored,
                reading = false,
                readAt = if (finished || changed > 0) now else it.readAt,
                pending = it.pending - settled.keys - kept.toSet(),
                kept = it.kept + kept,
                problem = when {
                    !finished && s.objects.isEmpty() -> "The glasses did not answer the settings read."
                    uuid.isEmpty() -> "The glasses did not give their device id, so most settings show their defaults."
                    else -> ""
                },
            )
        }
        say(
            (if (full) "Read" else "Re-read") + " settings: $changed objects from ${s.objects.size} of ${namespaces.size} namespaces" +
                if (finished) "" else ", timed out",
        )
        retry.forEach { (key, want) -> scope.launch { writeDrive(key, want) } }
        // The glasses leave the factory with Meta AI off and nothing in RLDrive; the Meta app's setup turns it on.
        if (full && finished && uuid.isNotEmpty() && ASSISTANT_ENABLED !in stored) {
            say("Meta AI was never turned on on these glasses; turning it on")
            scope.launch { writeDrive(ASSISTANT_ENABLED, "true") }
        }
        // The Meta app turns two-stage stabilization on at every connect for these models; off, video is not stabilized.
        val twoStage = stored[TWO_STAGE_EIS] ?: catalogue[TWO_STAGE_EIS]?.default
        if (full && finished && _state.value.product in TWO_STAGE_EIS_PRODUCTS && !same(Kind.BOOL, twoStage, "true")) {
            say("Turning on two-stage video stabilization, as the Meta app does")
            scope.launch { writeQuietly(TWO_STAGE_EIS, "true") }
        }
    }

    private suspend fun onReply(reply: SyncReply, link: DataXConnection) {
        val s = sync ?: return
        val namespace = s.waiting[reply.requestId] ?: return
        if (reply.round == 1L) {
            s.objects.getOrPut(namespace) { java.util.Collections.synchronizedList(ArrayList()) }.addAll(reply.objects)
            s.versions[namespace] = reply.versions
            // Round 2 echoes their versions back. Stopping at round 1 leaves the sync open on their side.
            val round2 = buildSync(
                namespace, reply.requestId, 2, reply.versions, (reply.versions.keys + TYPE_KV).distinct(),
                s.push[namespace].orEmpty(), s.pushObjects[namespace].orEmpty(),
            )
            chunks(round2).forEach { link.sendOnService(SVC_RLDRIVE, MT_SYNC_MESSAGE, it) }
        } else if (reply.terminated && reply.versions.isNotEmpty()) {
            s.versions[namespace] = reply.versions
        }
        if (reply.terminated) {
            s.waiting.remove(reply.requestId)
            if (s.waiting.isEmpty()) s.done.complete(Unit)
        }
    }

    private fun say(text: String) {
        _notes.tryEmit(text)
    }

    companion object {
        private const val READ_TIMEOUT_MILLIS = 8_000L
        private const val SETTLE_MILLIS = 3_000L
        private const val ASSISTANT_ENABLED = "assistant_enabled"
        private const val TWO_STAGE_EIS = "two_stage_eis"

        // Ray-Ban Meta Optics, Meta glasses, Oakley Meta Vanguard and HSTN, Ray-Ban Meta Gen 2; not Gen 1 or the Display.
        private val TWO_STAGE_EIS_PRODUCTS = setOf(262, 263, 264, 265, 266)

        private val SCOPED_NAMESPACES = listOf("individual_device_settings", "individual_device_sensitive_settings")
        private val DEVICE_NAMESPACES = listOf("local_device_settings", "shared_settings_namespace_1")
    }
}

/** True when the stored text [have] means the same as [want]. RLDrive keeps every value as text. */
fun same(kind: Kind?, have: String?, want: String): Boolean = when {
    have == null -> false
    kind == Kind.BOOL -> asBool(have) == asBool(want)
    kind == Kind.INT -> have.trim().toLongOrNull() == want.trim().toLongOrNull()
    kind == Kind.FLOAT -> have.trim().toDoubleOrNull() == want.trim().toDoubleOrNull()
    else -> have == want
}

fun asBool(text: String?): Boolean = text?.trim()?.lowercase() in setOf("true", "1")

/** SettingsSyncData with one field: the setting's number, and its value as the app encodes it. */
fun encodeWrite(info: SettingInfo, value: String): ByteArray? {
    val field = info.field ?: return null
    return when (info.kind) {
        Kind.BOOL -> Wire.fieldVarint(field, if (asBool(value)) 1 else 0)
        // Unsigned, so a negative int goes out as protobuf's ten-byte two's complement.
        Kind.INT -> value.trim().toLongOrNull()?.let { Wire.fieldVarintUnsigned(field, it) }
        Kind.STR -> Wire.fieldBytes(field, value.toByteArray())
        // SettingsSyncData keeps these as double: fixed64, wire type 1.
        Kind.FLOAT -> value.trim().toDoubleOrNull()?.let {
            Wire.encodeVarint((field.toLong() shl 3) or 1) +
                java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(it).array()
        }
        else -> null
    }
}

private fun loadCatalogue(): Map<String, SettingInfo> {
    val stream = checkNotNull(GlassesSettings::class.java.getResourceAsStream("settings.tsv")) { "settings.tsv is missing" }
    return stream.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val c = line.split('\t')
            val namespace = c[4].ifEmpty { null }
            SettingInfo(
                key = c[0],
                field = c[1].toIntOrNull(),
                kind = when (c[2]) {
                    "bool" -> Kind.BOOL
                    "int" -> Kind.INT
                    "str" -> Kind.STR
                    "float" -> Kind.FLOAT
                    else -> Kind.OTHER
                },
                // No schema means no default, which is different from an empty one.
                default = if (namespace == null) null else c[3],
                namespace = namespace,
                parsed = c[5] == "1",
            )
        }.associateBy { it.key }
    }
}
