package com.macedonga.visor.core.media

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.connection.SessionHostFactory
import com.macedonga.visor.core.identity.IdentityBundle
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.LinkStateChanged
import com.macedonga.visor.core.session.ProtocolError
import com.macedonga.visor.core.session.ServiceData
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// svc7006 wearables_bug_reporter.
private const val SVC_BUG_REPORTER = 7006
private const val MT_REQUEST_BUG_REPORT = 1
private const val MT_REQUEST_BUG_REPORT_RESPONSE = 2

/**
 * Why an import did not bring [handles] over, for a person to read. It belongs to those captures: it goes once they are
 * saved, and the next import starts without it.
 */
data class ImportProblem(val kind: Kind, val handles: Set<String> = emptySet()) {
    enum class Kind {
        /** Android's Wi-Fi Direct took no call at all: only turning Wi-Fi off and on brings it back. */
        WIFI_STUCK,
        WIFI_FAILED,
        GLASSES_DID_NOT_JOIN,
        NO_WIFI_SESSION,
        WIFI_OFF,
        NOT_ALLOWED,
        FAILED,

        /** The glasses went quiet or away halfway: asleep in the closed case, or out of reach. */
        CUT_OFF,

        /** A file failed its checksum. The glasses count it delivered all the same. */
        DAMAGED,

        /** Everything came, but the phone's gallery would not take it. */
        NOT_SAVED,
    }
}

/**
 * Lists the captures on the glasses, fetches them over Wi-Fi Direct, and saves them to the gallery.
 * Bluetooth is only the fallback when Wi-Fi Direct fails.
 */
class MediaImport(
    private val context: Context,
    private val albums: Albums,
    private val connection: GlassesConnection,
    private val receiver: AirshipReceiver,
    private val store: IdentityStore,
    private val hosts: SessionHostFactory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _captures = MutableStateFlow<List<Capture>>(emptyList())
    val captures: StateFlow<List<Capture>> = _captures.asStateFlow()

    val transfers: StateFlow<List<Transfer>> get() = receiver.transfers

    private val _saved = MutableStateFlow(0)
    val saved: StateFlow<Int> = _saved.asStateFlow()

    private val _problem = MutableStateFlow<ImportProblem?>(null)
    val problem: StateFlow<ImportProblem?> = _problem.asStateFlow()

    private val _overBluetooth = MutableStateFlow(false)

    /** Wi-Fi Direct failed and the captures are coming the slow way: the glasses can take minutes to start. */
    val overBluetooth: StateFlow<Boolean> = _overBluetooth.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _inFlight = MutableStateFlow<Set<String>>(emptySet())

    /** Handles being imported now or waiting their turn. */
    val inFlight: StateFlow<Set<String>> = _inFlight.asStateFlow()

    // Asked for while another import ran: they go next. Guarded by this, with running and inFlight's additions.
    private val queued = LinkedHashSet<String>()

    // Captures that are not media, logged once each.
    private val noticed = Collections.synchronizedSet(HashSet<String>())

    // The import running, and when it last said or received anything.
    @Volatile
    private var running: Job? = null
    @Volatile
    private var lastProgress = 0L

    private val previewDir = File(context.filesDir, "previews")

    /** What each capture brought to this phone became, and its originals. */
    val index = GalleryIndex(context)

    /** What is on the glasses and not on this phone, nor deleted from it: what "import all new" brings. */
    val fresh: StateFlow<List<Capture>> = combine(captures, index.entries, index.dismissed) { list, kept, dismissed ->
        list.filter { isFresh(it, kept, dismissed) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Per capture in flight: its best fraction so far, and the assets seen on the wire (gone from the transfers, one has arrived).
    private val best = HashMap<String, Float>()
    private val seen = HashMap<String, MutableSet<String>>()

    /** Each in-flight capture's import as a fraction that never goes back, by handle; null before it moves. */
    val progress: StateFlow<Map<String, Float?>> = combine(inFlight, receiver.transfers, captures) { flying, transfers, list ->
        best.keys.retainAll(flying)
        seen.keys.retainAll(flying)
        val byHandle = list.associateBy { it.handle }
        flying.associateWith { handle ->
            val capture = byHandle[handle] ?: return@associateWith null
            val total = capture.wanted.sumOf { it.size }.coerceAtLeast(1)
            val ids = capture.wanted.map { it.id }.toSet()
            val moving = transfers.filter { it.uuid in ids }
            val mine = seen.getOrPut(handle) { HashSet() }.apply { addAll(moving.map { it.uuid }) }
            if (mine.isEmpty()) return@associateWith null
            val done = capture.wanted.filter { a -> a.id in mine && moving.none { it.uuid == a.id } }.sumOf { it.size }
            val fraction = ((done + moving.sumOf { it.received }).toFloat() / total).coerceIn(0f, 1f)
            maxOf(fraction, best[handle] ?: 0f).also { best[handle] = it }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    // Deletes asked for on this Bluetooth session, so a second listing does not ask again.
    private val deleteAsked = Collections.synchronizedSet(HashSet<String>())

    /** Where the phone was when the glasses took something. */
    val places = PlaceLog(context)

    /** Where photos are edited from their originals. */
    val darkroom = Darkroom(context, index)

    private val _imported = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Each capture as it lands in the gallery, by handle; [index] says where. */
    val imported: SharedFlow<String> = _imported.asSharedFlow()

    /**
     * Whether a video this size came out of the glasses' own two-stage stabilization; any other is steadied on the phone.
     * The app sets it from the glasses' settings; this is their default output.
     */
    @Volatile
    var steadiedOnGlasses: (Int, Int) -> Boolean = { w, h -> minOf(w, h) == 1512 && maxOf(w, h) == 2016 }

    private val assembly = Assembly(
        context, albums, index, places, ::say,
        onSaved = { handle ->
            _saved.update { it + 1 }
            _imported.tryEmit(handle)
            _inFlight.update { it - handle }
            // A problem about captures that have now arrived is no longer one.
            _problem.update { p -> p?.copy(handles = p.handles - handle)?.takeIf { it.handles.isNotEmpty() } }
            deleteFromGlasses(handle)
        },
        onFailed = { handle, _ -> report(handle, ImportProblem.Kind.NOT_SAVED) },
        steadiedOnGlasses = { w, h -> steadiedOnGlasses(w, h) },
    )

    /** [handle] is no longer coming: it joins the problem on show, or starts one of [kind]. */
    private fun report(handle: String, kind: ImportProblem.Kind) {
        _inFlight.update { it - handle }
        _problem.update { p -> p?.copy(handles = p.handles + handle) ?: ImportProblem(kind, setOf(handle)) }
    }

    private val _previews = MutableStateFlow(
        previewDir.listFiles().orEmpty().associateBy { it.nameWithoutExtension },
    )

    /** Preview JPEGs on this phone, by asset id. */
    val previews: StateFlow<Map<String, File>> = _previews.asStateFlow()

    // Previews asked for on this Bluetooth session, so a second listing does not ask again.
    private val previewsAsked = Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var lastAutoRefresh = 0L

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Every status line, for the event log. */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    // The main files asked for over Wi-Fi and not saved yet: once none are left, Wi-Fi is let go at once.
    @Volatile
    private var awaited: Set<String> = emptySet()

    // True from the join request until Bluetooth is back, so its drop does not abandon Wi-Fi files.
    @Volatile
    private var onWifi = false

    // The pair the capture list came from.
    private var listedFor: String? = null

    init {
        receiver.describe = ::describe
        // Pieces left from a transfer that was cut short, for longer than any transfer runs.
        scope.launch { assembly.saveStragglers(olderThanMs = 10 * 60_000L) }
        scope.launch { receiver.notes.collect(::say) }
        scope.launch { receiver.received.collect(::keep) }
        scope.launch {
            connection.serviceData.collect { data ->
                runCatching<Unit> {
                    when (data.serviceId) {
                        SVC_MEDIA -> onMedia(data)
                        SVC_BUG_REPORTER -> onBugReporter(data)
                    }
                }.onFailure { say("Could not read svc${data.serviceId} type ${data.messageType}: ${it.message}") }
            }
        }
        scope.launch {
            connection.state.map { it.link }.distinctUntilChanged().collect { link ->
                when (link) {
                    LinkState.READY -> {
                        // Another pair's captures are not on these glasses.
                        val serial = connection.state.value.target?.serial
                        if (serial != listedFor) {
                            listedFor = serial
                            _captures.value = emptyList()
                            following = null
                        }
                        previewsAsked.clear()
                        deleteAsked.clear()
                        refresh()
                    }
                    LinkState.LOST, LinkState.DISCONNECTED -> if (!onWifi) receiver.abandon()
                    else -> Unit
                }
            }
        }
    }

    /** Ask for the list again. Captures are updated in place, so nothing flickers. */
    fun refresh() {
        connection.submit { enumerateCaptures(it) }
    }

    /** Ask over Bluetooth for every preview this phone does not have yet. */
    private fun fetchPreviews() {
        val missing = captures.value.mapNotNull { c -> c.preview?.let { c.handle to it.id } }
            .filter { (_, id) -> id !in _previews.value && previewsAsked.add(id) }
        if (missing.isEmpty()) return
        connection.submit { link -> for ((handle, id) in missing) requestAsset(link, handle, id) }
        say("Asked the glasses for ${missing.size} previews")
    }

    private fun describe(assetId: String): String {
        val capture = captures.value.firstOrNull { c -> c.assets.any { it.id == assetId } }
            ?: return " (${assetId.take(8)}, not in the capture list)"
        val asset = capture.assets.first { it.id == assetId }
        val what = when (assetId) {
            capture.main?.id -> if (capture.isVideo) "video" else "photo"
            capture.motion?.id -> "live photo's clip"
            capture.preview?.id -> "preview"
            capture.imu?.id -> "motion log"
            capture.frames?.id -> "frame log"
            else -> "kind ${asset.kind}"
        }
        return " ($what of ${capture.handle.take(8)}, listed as ${sizeWords(asset.size)})"
    }

    // Handles asked for since the person last chose "import all new": what is taken meanwhile follows on its own.
    @Volatile
    private var following: Set<String>? = null

    fun importAllNew() {
        val fresh = captures.value.filter { isFresh(it) }
        following = fresh.map { it.handle }.toSet()
        import(fresh)
    }

    private fun isFresh(c: Capture, kept: Map<String, Kept> = index.entries.value, dismissed: Set<String> = index.dismissed.value) =
        c.isNew && c.isMedia && c.handle !in kept && c.handle !in dismissed

    /**
     * Delete from the glasses what is on this phone, as the Meta app does once it has a capture, and what the person
     * deleted here. Each handle once per Bluetooth session; dropped while Wi-Fi has the glasses, so the listing after
     * catches up.
     */
    private fun deleteFromGlasses(handle: String) {
        if (!deleteAsked.add(handle)) return
        connection.submit { deleteCapture(it, handle) }
    }

    /** The glasses no longer have [handle]: it leaves the list, and its preview this phone. */
    private fun gone(handle: String) {
        val capture = _captures.value.firstOrNull { it.handle == handle } ?: return
        _captures.update { list -> list.filter { it.handle != handle } }
        capture.preview?.let { p -> _previews.value[p.id]?.delete(); _previews.update { it - p.id } }
    }

    /** After an "import all new", what was taken while it ran is imported too, each capture once. */
    private fun followUp() {
        val asked = following ?: return
        if (_importing.value) return
        val more = captures.value.filter { isFresh(it) && it.handle !in asked }
        following = if (more.isEmpty()) null else asked + more.map { it.handle }
        if (more.isEmpty()) return
        say("${more.size} more taken meanwhile")
        import(more)
    }

    fun import(captures: List<Capture>) {
        if (captures.isEmpty()) return
        synchronized(this) {
            _inFlight.update { it + captures.map { c -> c.handle } }
            val busy = running?.isActive == true
            // A silent import gives way after STUCK_MILLIS; one waiting on Bluetooth never does.
            if (busy && (_overBluetooth.value || now() - maxOf(lastProgress, receiver.lastActivity) < STUCK_MILLIS)) {
                queued += captures.map { it.handle }
                return
            }
            val stuck = running.takeIf { busy }
            _importing.value = true
            lastProgress = now()
            val job = scope.launch(start = CoroutineStart.LAZY) { importNow(captures, stuck) }
            running = job
            job.start()
        }
    }

    private suspend fun importNow(captures: List<Capture>, stuck: Job?) {
        if (stuck != null) {
            say("The last import stopped answering: starting again")
            withTimeoutOrNull(STUCK_JOIN_MILLIS) { stuck.cancelAndJoin() }
        }
        val self = currentCoroutineContext()[Job]
        val handles = captures.map { it.handle }.toSet()
        _problem.value = null
        say(permissionLine())
        try {
            when {
                !wifiIsOn(context) -> {
                    say("Turn on Wi-Fi first: media comes over Wi-Fi Direct")
                    _problem.value = ImportProblem(ImportProblem.Kind.WIFI_OFF, handles = handles)
                }
                !wifiDirectAllowed(context) -> {
                    say("Allow Wi-Fi Direct first")
                    _problem.value = ImportProblem(ImportProblem.Kind.NOT_ALLOWED, handles = handles)
                }
                else -> if (!overWifi(captures)) {
                    val missing = handles.filterTo(HashSet()) { it in _inFlight.value && !assembly.hasMain(it) }
                    if (missing.isNotEmpty()) {
                        say("${missing.size} did not come: the glasses went quiet")
                        _problem.update { p -> ImportProblem(ImportProblem.Kind.CUT_OFF, handles = missing + p?.handles.orEmpty()) }
                    }
                }
            }
        } catch (unavailable: WifiUnavailable) {
            say("${unavailable.message}. Falling back to Bluetooth, which is slow")
            // Said only if Bluetooth does not bring them either: while it might, the import is still running.
            val why = ImportProblem(unavailable.kind, handles)
            if (!overBluetooth(captures)) {
                // Glasses that are gone are the thing to fix, whatever Wi-Fi did.
                val gone = connection.state.value.link != LinkState.READY
                _problem.update { p ->
                    (if (gone) ImportProblem(ImportProblem.Kind.CUT_OFF) else why).copy(handles = (handles intersect _inFlight.value) + p?.handles.orEmpty())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            say("Import failed: ${error.message}")
            _problem.value = ImportProblem(ImportProblem.Kind.FAILED, handles)
        } finally {
            _overBluetooth.value = false
            // After STRAGGLER_MILLIS, a capture still missing a piece is saved with what it has.
            scope.launch {
                delay(STRAGGLER_MILLIS)
                assembly.saveStragglers(olderThanMs = STRAGGLER_MILLIS / 2)
            }
            // A capture with pieces here stays in flight until put together; the rest are no longer coming.
            _inFlight.update { it - captures.map { c -> c.handle }.filterNot(assembly::hasMain).toSet() }
            // Only the import still in charge clears the flag and runs the queue.
            val next = synchronized(this) {
                if (running !== self) return@synchronized emptySet<String>()
                running = null
                _importing.value = false
                val queuedNow = queued.toSet().also { queued.clear() }
                if (queuedNow.isEmpty()) _inFlight.update { it.filterTo(HashSet(), assembly::hasMain) }
                queuedNow
            }
            if (next.isNotEmpty()) {
                val more = this@MediaImport.captures.value.filter { it.handle in next }
                _inFlight.update { it - (next - more.map { c -> c.handle }.toSet()) }
                import(more)
            }
        }
    }

    /** Let go of an import in flight, Bluetooth back as it was: the setup needs the glasses to itself. */
    suspend fun stop() {
        synchronized(this) {
            _inFlight.update { it - queued }
            queued.clear()
        }
        running?.cancelAndJoin()
    }

    /** True when everything asked for came. */
    private suspend fun overWifi(captures: List<Capture>): Boolean {
        val target = connection.state.value.target ?: throw WifiUnavailable("no glasses connected")
        val bundle = store.load(target.serial) ?: throw WifiUnavailable("these glasses have no key on this phone")
        val group = PhoneGroup(context, ::say)
        try {
            say("Starting Wi-Fi Direct")
            awaited = captures.flatMap { c -> c.wanted.map { it.id } }.toSet()
            group.create()
            onWifi = true
            say("Asking the glasses to join ${group.ssid}")
            // Ask before the join: on Wi-Fi the glasses stop reading Bluetooth.
            val result = joinGlasses(connection, group.ssid, group.passphrase, timeoutMs = JOIN_TIMEOUT_MILLIS) { link ->
                for (capture in captures) {
                    // The capture request skips a delivered asset, so that one is asked for by itself.
                    for (asset in capture.wanted) if (asset.delivered) requestAsset(link, capture.handle, asset.id)
                    requestCapture(link, capture.handle, LINK_WIFI)
                }
            } ?: throw WifiUnavailable("the glasses did not answer the Wi-Fi join", ImportProblem.Kind.GLASSES_DID_NOT_JOIN)
            if (!result.ok) throw WifiUnavailable("the glasses could not join Wi-Fi Direct (${result.statusName})", ImportProblem.Kind.GLASSES_DID_NOT_JOIN)
            say("The glasses joined at ${result.address}")
            return receive(GlassesTarget(GlassesLink.Wifi(checkNotNull(result.address), result.port), target.serial), bundle)
        } finally {
            awaited = emptySet()
            // Even when cancelled: a group left up or a session left paused would block the next import.
            withContext(NonCancellable) {
                group.remove()
                if (onWifi) {
                    onWifi = false
                    receiver.abandon()
                    connection.resume()
                }
            }
        }
    }

    /** One session over Wi-Fi, held until airship has been quiet for a while. True when everything asked for came. */
    private suspend fun receive(target: GlassesTarget, bundle: IdentityBundle): Boolean = coroutineScope {
        val host = hosts.create(target, bundle)
        val ready = CompletableDeferred<Boolean>()
        var lost = false
        // Connect first and let go of Bluetooth after: the window to connect is short.
        host.start(this)
        val watcher = launch {
            host.events.collect { event ->
                when (event) {
                    is LinkStateChanged -> when (event.state) {
                        LinkState.READY -> ready.complete(true)
                        LinkState.LOST, LinkState.DISCONNECTED -> if (ready.isCompleted) lost = true
                        else -> Unit
                    }
                    is ProtocolError -> _notes.tryEmit("Wi-Fi: ${event.detail}")
                    else -> Unit
                }
            }
        }
        try {
            connection.pause("Paused while media comes over Wi-Fi")
            receiver.abandon()
            if (withTimeoutOrNull(SESSION_TIMEOUT_MILLIS) { ready.await() } != true) {
                throw WifiUnavailable("the glasses did not open a session over Wi-Fi", ImportProblem.Kind.NO_WIFI_SESSION)
            }
            say("Connected over Wi-Fi")
            val readyAt = now()
            val expecting = awaited.isNotEmpty()
            while (!lost) {
                delay(250)
                val idle = receiver.transfers.value.isEmpty()
                // Done once every named file is saved or the glasses report an empty queue.
                if (idle && expecting && awaited.isEmpty()) break
                if (idle && !expecting && receiver.queueEmptyAt > readyAt) break
                val quiet = now() - maxOf(receiver.lastActivity, readyAt)
                if (quiet > IDLE_MILLIS || now() - readyAt > MAX_MILLIS) break
            }
            say(if (lost) "The Wi-Fi link dropped" else "Done over Wi-Fi")
            !expecting || awaited.isEmpty()
        } finally {
            host.stop()
            watcher.cancel()
        }
    }

    /**
     * The rare case: ask for [captures] over Bluetooth and stay on it until they are saved: the glasses stop a big
     * file at once and offer it again about five minutes later. Glasses gone for [GONE_MILLIS] (asleep in the closed
     * case, out of reach) end the wait. True when everything came.
     */
    private suspend fun overBluetooth(captures: List<Capture>): Boolean {
        withTimeoutOrNull(BLUETOOTH_BACK_MILLIS) { connection.state.first { it.link == LinkState.READY } } ?: return false
        _overBluetooth.value = true
        val handles = captures.map { it.handle }.toSet()
        connection.submit { link -> for (capture in captures) requestCapture(link, capture.handle, LINK_BLUETOOTH) }
        val came = _inFlight.filter { (it intersect handles).isEmpty() }.map { true }
        val gone = flow {
            awayFor(GONE_MILLIS)
            say("The glasses are gone: stopped waiting for them over Bluetooth")
            emit(false)
        }
        return withTimeoutOrNull(BLUETOOTH_DELIVERY_MILLIS) { merge(came, gone).first() } == true
    }

    /** Returns once Bluetooth has been down for [millis] in a row. */
    private suspend fun awayFor(millis: Long) {
        while (true) {
            connection.state.first { it.link != LinkState.READY }
            withTimeoutOrNull(millis) { connection.state.first { it.link == LinkState.READY } } ?: return
        }
    }

    private fun onMedia(data: ServiceData) {
        when (data.messageType) {
            MT_CAPTURE -> parseCapture(data.payload)?.let { capture ->
                if (!capture.isMedia && noticed.add(capture.handle)) {
                    say("Skipped a capture of kind ${capture.kindCode}: no photo or video in it, assets ${capture.assets.map { it.kind }}")
                }
                _captures.update { list ->
                    (list.filter { it.handle != capture.handle } + capture).sortedByDescending { it.timestampNs }
                }
            }
            MT_LIST_DONE -> {
                val kept = index.entries.value
                val dismissed = index.dismissed.value
                for (c in captures.value) if (c.handle in kept || c.handle in dismissed) deleteFromGlasses(c.handle)
                fetchPreviews()
                followUp()
            }
            MT_DELETE_OK -> data.payload.decodeToString().trimEnd('\u0000').let { handle ->
                gone(handle)
                say("Deleted ${handle.take(8)} from the glasses")
            }
            // "<handle>:<reason>"; a capture they no longer have is as good as deleted.
            MT_DELETE_FAIL -> data.payload.decodeToString().trimEnd('\u0000').let { text ->
                val handle = text.substringBefore(':')
                val reason = text.substringAfter(':', "").trim()
                if (reason.contains("No such capture")) gone(handle)
                else say("The glasses kept ${handle.take(8)}: ${reason.ifEmpty { "no reason given" }}")
            }
            MT_ASSET_GONE -> say("The glasses could not send a preview")
            MT_LINK_CHANGE_OK -> say("The glasses queued ${data.payload.decodeToString().take(8)}")
            MT_CAPTURE_GONE -> say("The glasses no longer have that capture")
            else -> if (data.messageType >= 0x8000) say("The glasses refused the request (0x%04x)".format(data.messageType))
        }
    }

    /** Gets each bug report zip before it goes to Download/Visor. */
    @Volatile
    var bugReportReader: ((File) -> Unit)? = null

    /** [input] steadied or cut as an import would, into [output], with the logs kept for [handle]: for checking by hand. */
    fun finishAgain(input: File, handle: String, output: File, landscape: Boolean, steadiness: Int): String {
        val logs = index.originals(handle)
        val done = finishVideo(context, input, output, File(logs, Assembly.IMU), File(logs, Assembly.FRAMES), steadiness, landscape, steadiedOnGlasses)
        return done?.let { "steadied ${it.steadied}, landscape ${it.landscape}" } ?: "nothing to do"
    }

    /**
     * Asks the glasses for a bug report on svc7006; the zip comes over airship to Download/Visor.
     * [mode] is BugReportParams.mode (field 6.1), null for the glasses' default.
     */
    fun requestBugReport(mode: Int? = null) {
        val id = UUID.randomUUID().toString()
        fun text(field: Int, value: String) = Wire.fieldString(field, value)
        fun entry(key: String, value: String) = Wire.fieldBytes(5, text(1, key) + text(2, value))
        val request = text(1, "Meta AI") + Wire.fieldVarintNonZero(2, 1) + text(3, "") + text(4, id) +
            entry("bug_category_label", "visor") + entry("is_constellation", "false") + entry("bug_report_id", id) +
            (mode?.let { Wire.fieldBytes(6, Wire.fieldVarintNonZero(1, it.toLong())) } ?: ByteArray(0))
        connection.submit { it.sendOnService(SVC_BUG_REPORTER, MT_REQUEST_BUG_REPORT, request) }
        say("Asked the glasses for a bug report. It comes over airship: minutes over Bluetooth, seconds over Wi-Fi.")
    }

    private fun onBugReporter(data: ServiceData) {
        val f = Wire.parse(data.payload)
        when (data.messageType) {
            // RequestBugReportResponse: 1 status (0 success, 1 failure), 2 error message.
            MT_REQUEST_BUG_REPORT_RESPONSE -> say(
                if (f.varint(1) == 0L) "The glasses are making a bug report" else "The glasses refused the bug report: ${f.bytes(2).decodeToString()}",
            )
            else -> say("Bug reporter: type ${data.messageType}, ${data.payload.size} bytes " + data.payload.toHexString(0, minOf(24, data.payload.size)))
        }
    }

    private fun keepBugReport(received: Received) {
        val name = "Visor_bugreport_" + NAME.format(ZonedDateTime.now()) + ".zip"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Visor")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Downloads would not take it")
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { out -> received.file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        say("Saved the glasses' bug report, ${sizeWords(received.offer.size)}, to Download/Visor/$name" + if (received.verified) "" else " (checksum did not match)")
    }

    private fun keep(received: Received) {
        if (received.offer.namespace == NAMESPACE_BUGREPORT) {
            try {
                bugReportReader?.let { read -> runCatching { read(received.file) }.onFailure { say("Could not read the bug report: ${it.message}") } }
                keepBugReport(received)
            } catch (error: Exception) {
                say("Could not save the bug report: ${error.message}")
            } finally {
                received.file.delete()
            }
            return
        }
        try {
            val capture = captures.value.firstOrNull { c -> c.assets.any { it.id == received.offer.uuid } }
            val ext = extension(received.file)
            // Every capture's small JPEG is kept for the list, whatever else happens to the file.
            if (received.verified && ext == "jpg" && received.offer.size < PREVIEW_MAX_BYTES) {
                keepPreview(received)
                // A preview for a capture not in the list is one just taken.
                if (capture == null && now() - lastAutoRefresh > 3_000) {
                    lastAutoRefresh = now()
                    refresh()
                }
            }
            val asked = capture?.takeIf { c -> c.wanted.any { it.id == received.offer.uuid } }
            when {
                !received.verified -> {
                    say("A file arrived damaged and was not saved${describe(received.offer.uuid)}")
                    if (asked != null) {
                        // Delivered as far as the glasses know: the next import asks for it by name.
                        markDelivered(asked.handle, received.offer.uuid)
                        report(asked.handle, ImportProblem.Kind.DAMAGED)
                    }
                }
                asked != null -> {
                    assembly.take(asked, received.offer.uuid, received.file)
                    markDelivered(asked.handle, received.offer.uuid)
                }
                capture != null -> Unit
                // Not in the list: the full still is always HEIC and the video MP4, the thumbnail JPEG.
                ext == "heic" || ext == "mp4" -> {
                    val where = assembly.saveLoose(received.file, video = ext == "mp4")
                    _saved.update { it + 1 }
                    say(if (ext == "mp4") "Saved a video: $where" else "Saved a photo: $where")
                }
                ext == null -> say("Skipped a ${sizeWords(received.offer.size)} file nothing asked for")
            }
        } catch (error: Exception) {
            say("Could not save a file: ${error.message}")
        } finally {
            received.file.delete()
            awaited = awaited - received.offer.uuid
        }
    }

    private fun keepPreview(received: Received) {
        previewDir.mkdirs()
        val file = File(previewDir, received.offer.uuid + ".jpg")
        received.file.copyTo(file, overwrite = true)
        _previews.update { it + (received.offer.uuid to file) }
    }

    private fun markDelivered(handle: String, assetId: String) = _captures.update { list ->
        list.map { c ->
            if (c.handle != handle) c
            else c.copy(assets = c.assets.map { if (it.id == assetId) it.asDelivered() else it })
        }
    }

    /** What Android has granted that Wi-Fi Direct and the session over it can need, for the event log. */
    private fun permissionLine(): String {
        val wanted = listOf(
            "NEARBY_WIFI_DEVICES", "ACCESS_LOCAL_NETWORK", "ACCESS_FINE_LOCATION", "BLUETOOTH_CONNECT",
        )
        val states = wanted.joinToString(", ") { name ->
            val granted = context.checkSelfPermission("android.permission.$name") == PackageManager.PERMISSION_GRANTED
            "$name ${if (granted) "yes" else "NO"}"
        }
        return "Android ${Build.VERSION.SDK_INT}, Wi-Fi ${if (wifiIsOn(context)) "on" else "off"}: $states"
    }

    private fun say(text: String) {
        lastProgress = now()
        _notes.tryEmit(text)
    }

    private companion object {
        const val JOIN_TIMEOUT_MILLIS = 45_000L
        const val BLUETOOTH_DELIVERY_MILLIS = 9 * 60_000L
        const val STUCK_MILLIS = 90_000L
        const val STUCK_JOIN_MILLIS = 15_000L
        const val PREVIEW_MAX_BYTES = 500_000L
        const val SESSION_TIMEOUT_MILLIS = 45_000L
        const val IDLE_MILLIS = 12_000L
        const val MAX_MILLIS = 30 * 60_000L
        const val BLUETOOTH_BACK_MILLIS = 60_000L
        const val GONE_MILLIS = 30_000L
        const val STRAGGLER_MILLIS = 90_000L

        fun now() = System.nanoTime() / 1_000_000

        val NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val HEIF_BRANDS = setOf("heic", "heix", "hevc", "mif1", "msf1", "heim", "heis")

        /** jpg, heic or mp4 from the first bytes, or null for anything else (the sensor logs). */
        fun extension(file: File): String? {
            val head = ByteArray(12)
            val n = file.inputStream().use { it.read(head) }
            if (n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) return "jpg"
            if (n >= 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp") {
                return if (String(head, 8, 4, Charsets.US_ASCII) in HEIF_BRANDS) "heic" else "mp4"
            }
            return null
        }
    }
}
