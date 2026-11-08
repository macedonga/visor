@file:OptIn(KitInternal::class)

package com.macedonga.visor.dat

import android.content.Context
import com.macedonga.visor.core.identity.ConstellationTrustExchange
import com.macedonga.visor.core.protocol.Dat
import com.macedonga.visor.core.session.DataXError
import com.macedonga.visor.core.session.GlassesSession
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.LinkStateChanged
import com.macedonga.visor.core.session.ProtocolError
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.core.session.SessionCommand
import com.macedonga.visor.core.transport.BluetoothLeTransport
import com.macedonga.visor.kit.KitException
import com.macedonga.visor.kit.protocol.ErrorCode
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.Layout
import com.macedonga.visor.kit.protocol.LayoutClick
import com.macedonga.visor.kit.protocol.LayoutEnd
import com.macedonga.visor.kit.protocol.LayoutNode
import com.macedonga.visor.kit.protocol.LayoutState
import com.macedonga.visor.kit.protocol.LayoutType
import com.macedonga.visor.kit.protocol.LayoutVideoEvent
import com.macedonga.visor.kit.protocol.LayoutVideoState
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.outside.KitGrants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The toolkit session apps share: an app link of Visor's own to the active pair (its app key proved on svc79), a
 * svc7009 session, and Meta's toolkit app on svc6500, which draws the layouts. It opens on the first app that needs
 * it and closes a little after the last one leaves; the wearer ends it by going back on the glasses. Every session
 * forwards the wearer's band and touchpad gestures: with no layout to show, one without the toolkit app does, so the
 * screen stays as it was and the band stays on the Display. Motion does not use it:
 * [com.macedonga.visor.outside.GlassesMotion] reads the glasses' sensors with nothing on their screen.
 */
@Singleton
class ToolkitSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toolkit: Toolkit,
    private val grants: KitGrants,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opening = Mutex()
    private var live: Live? = null
    private var closing: Job? = null

    private val owner = MutableStateFlow<String?>(null)

    // The session the shown layout holds open; a layout holds one use of it, whoever shows it.
    private var layoutSession: Live? = null
    private val states = MutableSharedFlow<Pair<String, LayoutState>>(replay = 0, extraBufferCapacity = 16)
    private val clicks = MutableSharedFlow<Pair<String, LayoutClick>>(extraBufferCapacity = 64)
    private val videos = MutableSharedFlow<Pair<String, LayoutVideoEvent>>(extraBufferCapacity = 16)

    // Whether the shown layout is a video, which the toolkit app plays alone on the screen.
    @Volatile private var showingVideo = false

    private val inputs = MutableSharedFlow<Dat.SessionInput>(extraBufferCapacity = 64)
    private var inputUsers = 0
    private var keeper: Job? = null

    // The toolkit app's navigation (nav, select, back, drags), heard while a layout shows; who keeps back for itself.
    private val navigations = MutableSharedFlow<Dat.Navigation>(extraBufferCapacity = 64)
    private var navigationUsers = 0
    private var backKeepers = 0

    /** True while the shown layout's session gives the toolkit app's navigation, which also covers the swipes. */
    val navigating: Boolean get() = layoutSession?.let { !it.ended.isCompleted && it.navigation != null } == true

    /**
     * The toolkit app's navigation while a layout shows: nav, select, back and drags, from the touchpad and the band.
     * With [keepBack] back comes here and the layout stays.
     */
    fun navigation(keepBack: Boolean): Flow<Dat.Navigation> = flow {
        opening.withLock {
            navigationUsers++
            if (keepBack) backKeepers++
        }
        scope.launch { layoutSession?.let { navigate(it) } }
        try {
            navigations.collect { emit(it) }
        } finally {
            withContext(NonCancellable) {
                opening.withLock {
                    navigationUsers--
                    if (keepBack) backKeepers--
                }
                layoutSession?.let { navigate(it) }
            }
        }
    }

    // Gives [session] the input capability while someone listens, with back kept as the listeners want it.
    private suspend fun navigate(session: Live) {
        if (!session.dam || session.ended.isCompleted) return
        val (wanted, keep) = opening.withLock { (navigationUsers > 0) to (backKeepers > 0) }
        val now = session.navigation
        if (!wanted || now == keep) return
        runCatching {
            if (Dat.INPUT !in session.capabilities) session.add(Dat.INPUT)
            session.request(Dat.INPUT, Dat.inputStart(listOf(Dat.NAV_TOUCHPAD, Dat.NAV_BAND, Dat.NAV_BAND_DRAG), keep))
            session.navigation = keep
        }.onFailure { log.add("Toolkit: no navigation from the toolkit app: ${it.message}") }
    }

    /** The wearer's gestures, from the band and the touchpad, while collected; [app] names the request to set up. */
    fun inputs(app: String): Flow<Dat.SessionInput> = flow {
        opening.withLock { if (inputUsers++ == 0) keeper = scope.launch { keep(app) } }
        try {
            inputs.collect { emit(it) }
        } finally {
            withContext(NonCancellable) {
                opening.withLock {
                    if (--inputUsers == 0) {
                        keeper?.cancel()
                        keeper = null
                        live?.takeIf { it.users <= 0 }?.let {
                            it.stop()
                            live = null
                        }
                    }
                }
            }
        }
    }

    // A session without the toolkit app ends when another app comes to the front, and starts only while the glasses
    // are worn: while an app listens, a session is opened again.
    private suspend fun keep(app: String) {
        while (true) {
            val session = runCatching { opening.withLock { live?.takeIf { !it.ended.isCompleted } ?: openSession(app, dam = false) } }
                .onFailure { log.add("Toolkit: no session for gestures yet: ${it.message}") }
                .getOrNull()
            session?.ended?.await()
            delay(REOPEN_MS)
        }
    }

    /**
     * Shows [layout] for [app], in place of the one shown before, which goes with the reason `replaced`. A layout with a
     * video is that video alone, as on DAT: the toolkit app fetches the URL and plays it.
     */
    suspend fun show(app: String, layout: Layout) {
        val video = layout.root.all().firstOrNull { it.type == LayoutType.VIDEO }?.let { node ->
            node.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: throw KitException(ErrorCode.INVALID_PARAMS, "A video needs an https:// URL.", reason = "invalid_url")
        }
        val session = acquire(app)
        try {
            if (Dat.DISPLAY !in session.capabilities) session.add(Dat.DISPLAY)
            if (showingVideo && video == null) session.request(Dat.DISPLAY, Dat.videoStop())
            session.request(Dat.DISPLAY, if (video != null) Dat.videoView(video) else Dat.displayContent(DatLayout.payload(layout)))
        } catch (e: KitException) {
            release(session)
            throw e
        }
        showingVideo = video != null
        if (video != null) videos.tryEmit(app to LayoutVideoEvent(LayoutVideoState.STARTING, time = now()))
        val before = owner.value
        layoutSession?.let(::release)
        layoutSession = session
        owner.value = app
        navigate(session)
        if (before != app) {
            before?.let { states.tryEmit(it to LayoutState(showing = false, reason = LayoutEnd.REPLACED, time = now())) }
            states.tryEmit(app to LayoutState(showing = true, time = now()))
        }
    }

    /** Empties [app]'s layout, back to the toolkit app's black screen. */
    suspend fun close(app: String) {
        if (owner.value != app) return
        owner.value = null
        states.tryEmit(app to LayoutState(showing = false, reason = LayoutEnd.CLOSED, time = now()))
        val session = layoutSession ?: return
        layoutSession = null
        if (!session.ended.isCompleted) {
            if (showingVideo) runCatching { session.request(Dat.DISPLAY, Dat.videoStop()) }
            runCatching { session.request(Dat.DISPLAY, Dat.displayClear()) }
        }
        showingVideo = false
        release(session)
    }

    fun clicks(app: String): Flow<LayoutClick> = clicks.filter { it.first == app }.map { it.second }

    /** The video in [app]'s layout as it starts, ends or fails. */
    fun video(app: String): Flow<LayoutVideoEvent> = videos.filter { it.first == app }.map { it.second }

    fun state(app: String): Flow<LayoutState> =
        states.filter { it.first == app }.map { it.second }.onStart { emit(LayoutState(showing = owner.value == app, time = now())) }

    private suspend fun acquire(app: String): Live = opening.withLock {
        closing?.cancel()
        live?.takeIf { !it.ended.isCompleted && it.dam }?.let { it.users++; return it }
        // A session without the toolkit app cannot draw: it gives way to one with it, which forwards gestures too.
        live?.takeIf { !it.ended.isCompleted }?.stop()
        val session = openSession(app, dam = true)
        session.users++
        session
    }

    // Under [opening].
    private suspend fun openSession(app: String, dam: Boolean): Live {
        val ready = toolkit.ready(grants.labelOf(app))
        val session = Live(open(ready), dam)
        live = session
        session.start()
        return session
    }

    private fun release(session: Live) {
        if (--session.users > 0) return
        // A short pause first, so an app that shows layout after layout does not reopen the session each time.
        closing = scope.launch {
            delay(IDLE_MS)
            opening.withLock {
                if (live === session && session.users <= 0) {
                    session.stop()
                    live = null
                }
            }
        }
    }

    private fun open(ready: ToolkitReady): GlassesSession = GlassesSession(
        BluetoothLeTransport(context, ready.address),
        ConstellationTrustExchange(ready.appKey, ready.appPublicKey, ready.version, ready.salt),
    )

    private fun ended(session: Live, why: String) {
        if (!session.ended.complete(why)) return
        log.add("Toolkit: session ended ($why)")
        if (layoutSession === session) {
            owner.value?.let { states.tryEmit(it to LayoutState(showing = false, reason = LayoutEnd.BACK, time = now())) }
            owner.value = null
            layoutSession = null
        }
        scope.launch { opening.withLock { if (live === session) live = null } }
        // An ended session's link goes too: left up, it reconnects with no session, and the glasses close it each time.
        session.close()
    }

    /** One open session: the link, the toolkit app's channel ([dam]: launched), and the requests waiting for answers. */
    private inner class Live(val link: GlassesSession, val dam: Boolean) {
        var users = 0
        val capabilities: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        // Null until the input capability runs; then whether it keeps back for the app.
        @Volatile
        var navigation: Boolean? = null
        val ended = CompletableDeferred<String>()
        private val started = CompletableDeferred<Dat.SessionStarted>()
        private val answers = ConcurrentHashMap<String, CompletableDeferred<Dat.Response>>()
        private val ids = AtomicInteger()
        private val sessionId = UUID.randomUUID().toString()

        @Volatile
        private var channel: Int? = null
        private var events: Job? = null

        suspend fun start() {
            events = scope.launch { link.events.collect { heard(it) } }
            link.start(scope)
            val result = withTimeoutOrNull(START_MS) { started.await() }
            if (result?.status != Dat.START_OK) {
                stop()
                throw KitException(ErrorCode.FAILED, "The glasses did not start the toolkit session (status ${result?.status}).", reason = "session_failed")
            }
            log.add(if (dam) "Toolkit: session open, toolkit app ${result.dwaVersion}" else "Toolkit: session open without the toolkit app")
        }

        /** Adds [capability], retrying while the toolkit app comes up after its launch. */
        suspend fun add(capability: Int) {
            var last: Throwable? = null
            repeat(ADD_TRIES) {
                val outcome = runCatching { request(capability, null, Dat.addCapability(capability)) }
                if (outcome.isSuccess) {
                    capabilities += capability
                    return
                }
                last = outcome.exceptionOrNull()
                delay(ADD_RETRY_MS)
            }
            throw KitException(ErrorCode.FAILED, "The toolkit app did not take the capability: ${last?.message}", reason = "toolkit_app")
        }

        suspend fun request(capability: Int, payload: ByteArray) = request(capability, payload, null)

        private suspend fun request(capability: Int, payload: ByteArray?, body: ByteArray?) {
            if (ended.isCompleted) throw KitException(ErrorCode.CLOSED, "The toolkit session ended.", reason = "back")
            val id = "v${ids.incrementAndGet()}"
            val answer = CompletableDeferred<Dat.Response>().also { answers[id] = it }
            Dat.requests(id, capability, body, payload, System.currentTimeMillis()).forEach(::send)
            val response = withTimeoutOrNull(ANSWER_MS) { answer.await() }
            answers.remove(id)
            when {
                response == null -> throw KitException(ErrorCode.TIMEOUT, "The toolkit app did not answer.")
                response.status != Dat.STATUS_OK -> throw KitException(
                    ErrorCode.FAILED, "The toolkit app refused: ${response.error ?: response.denied ?: response.status}", reason = "toolkit_app",
                )
            }
        }

        // Only a channel's first frame names its service: a frame that names it again opens it anew.
        private fun send(frame: ByteArray) = link.submit(
            SessionCommand { s ->
                val connection = s.connection ?: return@SessionCommand
                val open = channel
                if (open != null) return@SessionCommand connection.send(open, frame, messageType = Dat.REQUEST)
                val opened = connection.openChannel(Dat.DWA_SERVICE).also { channel = it }
                connection.send(opened, frame, messageType = Dat.REQUEST, serviceId = Dat.DWA_SERVICE)
            },
        )

        private fun heard(event: Any) {
            when (event) {
                is LinkStateChanged -> when (event.state) {
                    LinkState.READY -> log.add("Toolkit: app link ready, trust ${link.trustResult}").also { send7009(Dat.SESSION_START_REQUEST, Dat.sessionStart(context.packageName, sessionId, "Visor", SDK_VERSION, usesDam = dam)) }
                    // Before the session starts, a lost link reconnects on its own: [start] waits for it.
                    LinkState.LOST, LinkState.DISCONNECTED -> if (started.isCompleted) ended(this, "link ${event.state.name.lowercase()}")
                    else -> Unit
                }
                // The toolkit app was not up yet: the next request opens a new channel.
                is DataXError -> if (event.serviceId == Dat.DWA_SERVICE) channel = null
                is ProtocolError -> log.add("Toolkit: app link: ${event.detail}")
                is ServiceData -> when (event.serviceId) {
                    Dat.SESSION_SERVICE -> when (event.messageType) {
                        Dat.SESSION_START_RESPONSE -> started.complete(Dat.sessionStarted(event.payload))
                        Dat.SESSION_STOP -> ended(this, if (dam) "the wearer left the toolkit app" else "the glasses ended it")
                        Dat.SESSION_INPUT -> runCatching { Dat.sessionInput(event.payload) }.getOrNull()?.let(::input)
                    }
                    Dat.DWA_SERVICE -> when (event.messageType) {
                        Dat.RESPONSE -> Dat.response(event.payload).let { answers[it.requestId]?.complete(it) }
                        Dat.EVENT -> event(Dat.event(event.payload))
                    }
                }
            }
        }

        private fun event(e: Dat.Event) {
            val payload = e.payload ?: return
            when (e.capability) {
                Dat.INPUT -> Dat.navigation(payload).forEach { navigations.tryEmit(it) }
                Dat.DISPLAY -> {
                    val app = owner.value ?: return
                    Dat.displayClick(payload)?.let { clicks.tryEmit(app to LayoutClick(it, now())) }
                    Dat.videoEvent(payload)?.let { (type, error) -> videoEvent(type, error)?.let { videos.tryEmit(app to it) } }
                    if (Dat.displayBack(payload) && layoutSession === this) {
                        owner.value = null
                        layoutSession = null
                        states.tryEmit(app to LayoutState(showing = false, reason = LayoutEnd.BACK, time = now()))
                        release(this)
                    }
                }
            }
        }

        private fun send7009(type: Int, payload: ByteArray) =
            link.submit(SessionCommand { s -> s.connection?.sendOnService(Dat.SESSION_SERVICE, type, payload) })

        fun stop() {
            ended.complete("closed")
            send7009(Dat.SESSION_STOP_REQUEST, Dat.sessionStop(sessionId))
            close()
        }

        fun close() {
            scope.launch {
                delay(STOP_GRACE_MS)
                link.stop()
                events?.cancel()
            }
        }
    }

    // One gesture comes as several key codes (the finger's or the touchpad's, and the generic one) within milliseconds.
    @Volatile private var lastInput: Dat.SessionInput? = null

    private fun input(input: Dat.SessionInput) {
        val repeat = lastInput?.let {
            it.source == input.source && it.gesture == input.gesture && it.actuator == input.actuator && input.timeMs - it.timeMs in 0..REPEAT_MS
        } == true
        lastInput = input
        if (!repeat) inputs.tryEmit(input)
    }

    private fun LayoutNode.all(): List<LayoutNode> = listOf(this) + children.flatMap { it.all() }

    // The toolkit app's VideoPlaybackEvent as the VDK names it; stopped means our own stop.
    private fun videoEvent(type: Int, error: Int): LayoutVideoEvent? = when (type) {
        Dat.VIDEO_STARTED -> LayoutVideoEvent(LayoutVideoState.PLAYING, time = now())
        Dat.VIDEO_ENDED, Dat.VIDEO_STOPPED -> LayoutVideoEvent(LayoutVideoState.ENDED, time = now())
        Dat.VIDEO_ERROR -> LayoutVideoEvent(LayoutVideoState.FAILED, VIDEO_ERRORS[error] ?: "playback_failed", now())
        else -> null
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val SDK_VERSION = "1.0.1"
        const val START_MS = 15_000L
        const val ANSWER_MS = 5_000L
        const val ADD_TRIES = 8
        const val ADD_RETRY_MS = 700L
        const val IDLE_MS = 5_000L
        const val STOP_GRACE_MS = 500L
        const val REOPEN_MS = 3_000L
        const val REPEAT_MS = 30L

        // VideoPlaybackEvent errors: 1 URL_INVALID, 2 ALREADY_PLAYING, 3 PLAYBACK_FAILED, 4 INVALID_DIMENSIONS.
        val VIDEO_ERRORS = mapOf(1 to "invalid_url", 2 to "playback_failed", 3 to "playback_failed", 4 to "invalid_dimensions")
    }
}
