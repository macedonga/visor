package com.macedonga.visor.core.settings

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.connection.productId
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
    // This firmware's settingsservice names it.
    val parsed: Boolean,
) {
    // No svc31 field, or one this firmware acks and drops: written into RLDrive, where every key lives.
    val viaDrive: Boolean get() = this.field == null || !parsed

    /** The RLDrive namespace a write goes to: the schema's, else the one the glasses file unknown keys under. */
    val driveNamespace: String get() = namespace ?: "individual_device_settings"
    val writable: Boolean get() = kind in WRITABLE && key !in PROTECTED
}

private val WRITABLE = setOf(Kind.BOOL, Kind.INT, Kind.STR, Kind.FLOAT)

// The one key Visor never writes: the skin-temperature controller uses it to shut hot glasses down.
private val PROTECTED = setOf("sg_disable_thermal_shutdown")

/** Visor will not write [key]. */
fun isProtected(key: String): Boolean = key in PROTECTED

data class SettingsState(
    // Key to raw text, for every setting someone has set. Anything else is at its default.
    val stored: Map<String, String> = emptyMap(),
    val reading: Boolean = false,
    val readAt: Long? = null,
    // Written and not yet seen on a read, key to the value asked for.
    val pending: Map<String, String> = emptyMap(),
    // Written, read back, and still the old value.
    val kept: Set<String> = emptySet(),
    // Written by writeQuietly: how the glasses answer is not shown.
    val quiet: Set<String> = emptySet(),
    // The Meta product id of the glasses last connected (266 for Ray-Ban Meta Gen 2), for what the catalogue offers.
    val product: Int? = null,
    // The serial of the pair these are, so a reader never takes them for another's.
    val pair: String? = null,
)

/** The glasses' settings: read over RLDrive (in full at connect, a delta after each write), written on svc31 or into RLDrive. */
class GlassesSettings(private val connection: GlassesConnection, drive: RlDriveResponder) {

    val catalogue: Map<String, SettingInfo> by lazy { loadCatalogue() }

    /** Label, group and control for each key, from settings_ui.json. */
    val specs: Map<String, SettingSpec> by lazy { loadSpecs() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(SettingsState(pair = connection.pair.value))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** What was read and written, for the event log. */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    // One sync at a time; the versions and stores below only change under it.
    private val reads = Mutex()

    // Swapped whole, so answering the glasses' own syncs reads it without the lock.
    @Volatile
    private var versions: Map<String, Versions> = emptyMap()
    private var byNamespace: Map<String, Map<String, String>> = emptyMap()

    // Syncs the Display started, by request id: answered with round 1, waiting for round 2.
    private class PeerSync(val namespace: String, val tag: String, val types: Set<String>, val at: Long)

    private val peers = ConcurrentHashMap<String, PeerSync>()
    private val unanswered = ConcurrentHashMap.newKeySet<String>()

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
        val push: Map<String, List<PushObject>> = emptyMap(),
    ) {
        val objects = ConcurrentHashMap<String, MutableList<DriveObject>>()
        val versions = ConcurrentHashMap<String, Versions>()
        val done = CompletableDeferred<Unit>()

        // The glasses answered that they serve no RLDrive.
        @Volatile
        var refused = false
    }

    init {
        // The catalogue parses off the main thread.
        scope.launch { catalogue.size + specs.size }
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
                    peers.clear()
                    _state.value = SettingsState(pair = serial, product = productId(connection.state.value.takeIf { it.target?.serial == serial }?.info?.build))
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
        // Glasses without RLDrive (audio-only ones, #80) refuse it: the read ends then, not at its timeout.
        scope.launch {
            connection.errors.collect { e ->
                if (e.serviceId == SVC_RLDRIVE && e.errorId == NOT_SERVED) sync?.let { it.refused = true; it.done.complete(Unit) }
            }
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
        say("Wrote $key = ${shown(key, value)}")
        // The ack starts a read too; this one settles the write if the ack never comes.
        scope.launch {
            delay(SETTLE_MILLIS + 500)
            read()
        }
        return true
    }

    /** A write whose outcome the screens do not show. */
    fun writeQuietly(key: String, value: String): Boolean =
        write(key, value).also { sent -> if (sent) _state.update { it.copy(quiet = it.quiet + key) } }

    /**
     * Write a key that has no svc31 field straight into its RLDrive namespace, as another replica
     * of it would: the object goes out in round 2 of a sync, under our own writer. False when the
     * namespace is not known.
     */
    fun writeDrive(key: String, value: String, namespaceName: String? = null): Boolean {
        if (key in PROTECTED) return false
        val base = namespaceName ?: catalogue[key]?.driveNamespace ?: return false
        val uuid = connection.state.value.info?.deviceUuid.orEmpty()
        if (connection.state.value.link != LinkState.READY) return false
        val namespace = if (base in SCOPED_NAMESPACES && uuid.isNotEmpty()) "$uuid@$base" else base
        writtenAt[key] = System.currentTimeMillis()
        _state.update { it.copy(pending = it.pending + (key to value), kept = it.kept - key, quiet = it.quiet - key) }
        say("Writing $key = ${shown(key, value)} into $namespace")
        scope.launch {
            reads.withLock { readNow(full = false, push = mapOf(namespace to listOf(PushObject(key, TYPE_KV, value.toByteArray())))) }
            delay(1_000)
            read()
        }
        return true
    }

    /**
     * Every object the glasses hold in [namespace], read once, handing them [objects] on the way as
     * another replica would; for namespaces beyond the settings ones (contacts). Null when the glasses do not answer.
     */
    suspend fun readNamespace(namespace: String, objects: List<PushObject> = emptyList()): List<DriveObject>? = reads.withLock {
        if (connection.state.value.link != LinkState.READY) return null
        val id = UUID.randomUUID().toString()
        val s = Sync(ConcurrentHashMap(mapOf(id to namespace)), push = mapOf(namespace to objects))
        sync = s
        connection.submit { it.sendOnService(SVC_RLDRIVE, MT_SYNC_MESSAGE, buildSync(namespace, id, 0, types = listOf(TYPE_KV, TYPE_CONTACT, "contact_ranks", "sync_metadata"))) }
        val finished = withTimeoutOrNull(READ_TIMEOUT_MILLIS) { s.done.await() } != null && !s.refused
        sync = null
        // Round 1 holds every object; without it what they hold is not known.
        if (!finished && !s.versions.containsKey(namespace)) return null
        s.objects[namespace]?.toList().orEmpty()
    }

    private suspend fun readNow(full: Boolean, push: Map<String, List<PushObject>> = emptyMap()) {
        val glasses = connection.state.value
        if (glasses.link != LinkState.READY) return
        val uuid = glasses.info?.deviceUuid.orEmpty()
        val namespaces = namespacesOf(uuid)
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
        if (s.refused) {
            _state.update { it.copy(reading = false) }
            return say("These glasses keep no settings store Visor can read (service $SVC_RLDRIVE refused)")
        }

        val changed = s.objects.values.sumOf { it.size }
        val now = System.currentTimeMillis()
        val objects = s.objects.mapValues { (_, list) -> synchronized(list) { list.toList() } }
        val stored = merge(objects, s.versions, full) {
            it.copy(reading = false, readAt = if (finished || changed > 0) now else it.readAt)
        }
        say(
            (if (full) "Read" else "Re-read") + " settings: $changed objects from ${s.objects.size} of ${namespaces.size} namespaces" +
                if (finished) "" else ", timed out",
        )
        // The glasses leave the factory with Meta AI off and nothing in RLDrive; the Meta app's setup turns it on.
        if (full && finished && uuid.isNotEmpty() && ASSISTANT_ENABLED !in stored) {
            say("Meta AI was never turned on on these glasses; turning it on")
            writeDrive(ASSISTANT_ENABLED, "true")
        }
        // The Meta app turns two-stage stabilization on at every connect for these models; off, video is not stabilized.
        if (full && finished && _state.value.product in TWO_STAGE_EIS_PRODUCTS && !asBool(value(TWO_STAGE_EIS))) {
            say("Turning on two-stage video stabilization, as the Meta app does")
            writeQuietly(TWO_STAGE_EIS, "true")
        }
    }

    /**
     * Folds [objects] (namespace to objects) into the stores, takes [seen] as the versions now known and settles the
     * writes the result answers, [more] adding to the state. Under [reads]. Returns the settings as they now stand.
     */
    private fun merge(
        objects: Map<String, List<DriveObject>>,
        seen: Map<String, Versions>,
        full: Boolean,
        more: (SettingsState) -> SettingsState,
    ): Map<String, String> {
        val stores = byNamespace.toMutableMap()
        for ((namespace, list) in objects) {
            val before = if (full) emptyMap() else stores[namespace].orEmpty()
            stores[namespace] = before.toMutableMap().apply {
                for (o in list) {
                    raw[o.uri] = namespace to o.raw
                    if (o.type != TYPE_KV) continue
                    if (o.deleted) remove(o.uri) else put(o.uri, o.data)
                }
            }
        }
        byNamespace = stores
        versions = versions + seen
        val stored = stores.values.fold(emptyMap<String, String>()) { acc, m -> acc + m }

        val now = System.currentTimeMillis()
        val pending = _state.value.pending
        val settled = pending.filter { (key, want) -> same(catalogue[key]?.kind, stored[key] ?: catalogue[key]?.default, want) }
        val kept = pending.keys.filter { it !in settled && now - (writtenAt[it] ?: now) > SETTLE_MILLIS }
        settled.forEach { (key, want) -> say("$key is now ${shown(key, want)}") }
        kept.forEach { key -> say("The glasses kept $key at ${shown(key, stored[key] ?: catalogue[key]?.default)}") }
        // An svc31 write the settings service took and did not apply goes once more straight into the store.
        val retry = kept.filter { key -> catalogue[key]?.viaDrive == false && retried.add(key) }.associateWith { pending.getValue(it) }
        _state.update {
            more(it.copy(stored = stored, pending = it.pending - settled.keys - kept.toSet(), kept = it.kept + kept))
        }
        retry.forEach { (key, want) -> writeDrive(key, want) }
        return stored
    }

    // Two namespaces are named after the device. Asking for one that does not exist creates it.
    private fun namespacesOf(uuid: String) = SCOPED_NAMESPACES.takeIf { uuid.isNotEmpty() }.orEmpty().map { "$uuid@$it" } + DEVICE_NAMESPACES

    private fun tracked(namespace: String) = namespace in namespacesOf(connection.state.value.info?.deviceUuid.orEmpty())

    /** Round 0 of a sync the Display started: claim what Visor last saw, so round 2 brings what changed since. */
    private suspend fun answerPeer(round0: SyncReply, link: DataXConnection) {
        val namespace = round0.namespace
        if (!tracked(namespace)) {
            if (unanswered.add(namespace)) say("Left the glasses' sync of $namespace unanswered")
            return
        }
        peers[round0.requestId] = PeerSync(namespace, round0.usecaseTag, round0.versions.keys, System.currentTimeMillis())
        val seen = versions[namespace].orEmpty()
        // Never their own vector: equal versions leave nothing to send. Never above it either.
        val claim = round0.versions.mapValues { (type, theirs) ->
            seen[type].orEmpty().mapNotNull { (writer, n) -> theirs[writer]?.let { writer to minOf(n, it) } }.toMap() +
                (LOCAL_WRITER to (theirs[LOCAL_WRITER] ?: 0L))
        }
        val round1 = buildSync(namespace, round0.requestId, 1, claim, round0.versions.keys, usecaseTag = round0.usecaseTag)
        chunks(round1).forEach { link.sendOnService(SVC_RLDRIVE, MT_SYNC_MESSAGE, it) }
        say("Answered the glasses' sync of $namespace")
    }

    /** Round 2 carries the objects newer than the claim: round 3 ends the sync, the merge redraws the screens. */
    private suspend fun finishPeer(peer: PeerSync, round2: SyncReply, link: DataXConnection) {
        if (!round2.terminated) {
            val types = round2.versions.keys.ifEmpty { peer.types }
            val round3 = buildSync(peer.namespace, round2.requestId, 3, round2.versions, types, terminated = true, usecaseTag = peer.tag)
            chunks(round3).forEach { link.sendOnService(SVC_RLDRIVE, MT_SYNC_MESSAGE, it) }
        }
        scope.launch { reads.withLock { mergePeer(peer.namespace, round2) } }
    }

    private fun mergePeer(namespace: String, round2: SyncReply) {
        // Round 2 names only this sync's types; the others stay as last seen.
        val seen = mapOf(namespace to versions[namespace].orEmpty() + round2.versions)
        val before = _state.value.stored
        val now = System.currentTimeMillis()
        val stored = merge(mapOf(namespace to round2.objects), seen, full = false) { it.copy(readAt = now) }
        for (key in round2.objects.filter { it.type == TYPE_KV }.map { it.uri }.distinct()) {
            if (stored[key] != before[key]) say("Glasses changed $key = ${shown(key, stored[key] ?: catalogue[key]?.default)}")
        }
    }

    private suspend fun onReply(reply: SyncReply, link: DataXConnection) {
        // The Display starts syncs of its own after a change on it; Visor answers them as the responder.
        if (isDisplayBuild(connection.state.value.info?.build)) {
            val now = System.currentTimeMillis()
            peers.values.removeIf { now - it.at > PEER_MILLIS }
            if (reply.round == 0L && sync?.waiting?.containsKey(reply.requestId) != true) return answerPeer(reply, link)
            if (reply.round == 2L) peers.remove(reply.requestId)?.let { return finishPeer(it, reply, link) }
        }
        val s = sync ?: return
        val namespace = s.waiting[reply.requestId] ?: return
        if (reply.round == 1L) {
            s.objects.getOrPut(namespace) { java.util.Collections.synchronizedList(ArrayList()) }.addAll(reply.objects)
            s.versions[namespace] = reply.versions
            // Round 2 echoes their versions back. Stopping at round 1 leaves the sync open on their side.
            val round2 = buildSync(
                namespace, reply.requestId, 2, reply.versions, (reply.versions.keys + TYPE_KV).distinct(),
                objects = s.push[namespace].orEmpty(),
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

    // Passwords stay out of the event log.
    private fun shown(key: String, value: String?) = if ((specs[key]?.control as? Control.Text)?.what == "password") "***" else value

    companion object {
        private const val READ_TIMEOUT_MILLIS = 8_000L
        private const val SETTLE_MILLIS = 3_000L
        private const val ASSISTANT_ENABLED = "assistant_enabled"
        private const val TWO_STAGE_EIS = "two_stage_eis"
        private const val PEER_MILLIS = 30_000L
        private const val NOT_SERVED = 0xC001

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
        Kind.STR -> Wire.fieldString(field, value)
        // SettingsSyncData keeps these as double: fixed64, wire type 1.
        Kind.FLOAT -> value.trim().toDoubleOrNull()?.let { Wire.fieldDouble(field, it) }
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
