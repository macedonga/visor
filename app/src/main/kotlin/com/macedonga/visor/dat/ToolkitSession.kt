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
import com.macedonga.visor.kit.protocol.MotionSample
import com.macedonga.visor.kit.protocol.MotionSource
import com.macedonga.visor.kit.protocol.Quaternion
import com.macedonga.visor.kit.protocol.Vector3
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The toolkit session apps share: an app link of Visor's own to the active pair (its app key proved on svc79), a
 * svc7009 session, and Meta's toolkit app on svc6500, which serves motion and draws layouts. It opens on the first
 * app that needs it and closes a little after the last one leaves; the wearer ends it by going back on the glasses.
 * While no layout shows, the toolkit app's screen stays black, which the Display does not light: motion shows nothing,
 * as with DAT.
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

    private val motionUsers = ConcurrentHashMap<String, AtomicInteger>()
    private val owner = MutableStateFlow<String?>(null)

    // The session the shown layout holds open; a layout holds one use of it, whoever shows it.
    private var layoutSession: Live? = null
    private val states = MutableSharedFlow<Pair<String, LayoutState>>(replay = 0, extraBufferCapacity = 16)
    private val clicks = MutableSharedFlow<Pair<String, LayoutClick>>(extraBufferCapacity = 64)

    /** The motion sensors at [rateHz] for [app], while collected. The first app's rate serves the others too. */
    fun motion(app: String, rateHz: Int): Flow<MotionSample> = flow {
        val session = acquire(app)
        motionUsers.getOrPut(app) { AtomicInteger() }.incrementAndGet()
        try {
            if (Dat.IMU !in session.capabilities) {
                session.add(Dat.IMU)
                session.request(Dat.IMU, Dat.motionStart(rateHz))
            }
            emitAll(session.motion.takeWhile { it != null }.map { sample(it!!) })
            throw KitException(ErrorCode.CLOSED, "The wearer left the toolkit app on the glasses.", reason = "back")
        } finally {
            if (motionUsers[app]?.decrementAndGet() == 0) motionUsers.remove(app)
            if (motionUsers.isEmpty() && !session.ended.isCompleted && Dat.IMU in session.capabilities) {
                runCatching { session.request(Dat.IMU, Dat.motionStop()) }
                session.capabilities -= Dat.IMU
            }
            release(session)
        }
    }

    /** Shows [layout] for [app], in place of the one shown before, which goes with the reason `replaced`. */
    suspend fun show(app: String, layout: Layout) {
        if (layout.root.all().any { it.type == LayoutType.VIDEO }) {
            throw KitException(ErrorCode.UNSUPPORTED, "Visor does not play videos on the Display yet.", reason = "video")
        }
        val session = acquire(app)
        try {
            if (Dat.DISPLAY !in session.capabilities) session.add(Dat.DISPLAY)
            session.request(Dat.DISPLAY, Dat.displayContent(DatLayout.payload(layout)))
        } catch (e: KitException) {
            release(session)
            throw e
        }
        val before = owner.value
        layoutSession?.let(::release)
        layoutSession = session
        owner.value = app
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
            runCatching { session.request(Dat.DISPLAY, Dat.displayClear()) }
        }
        release(session)
    }

    fun clicks(app: String): Flow<LayoutClick> = clicks.filter { it.first == app }.map { it.second }

    fun state(app: String): Flow<LayoutState> =
        states.filter { it.first == app }.map { it.second }.onStart { emit(LayoutState(showing = owner.value == app, time = now())) }

    private suspend fun acquire(app: String): Live = opening.withLock {
        closing?.cancel()
        live?.takeIf { !it.ended.isCompleted }?.let { it.users++; return it }
        val ready = toolkit.ready(grants.labelOf(app))
        val session = Live(open(ready))
        live = session
        session.start()
        session.users++
        session
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

    private fun sample(s: Dat.Sample) = MotionSample(
        time = s.timeNs,
        accelerometer = s.accelerometer?.let { Vector3(it.x, it.y, it.z) },
        gyroscope = s.gyroscope?.let { Vector3(it.x, it.y, it.z) },
        magnetometer = s.magnetometer?.let { Vector3(it.x, it.y, it.z) },
        orientation = s.orientation?.let { Quaternion(it.x, it.y, it.z, it.w) },
        source = if (s.band) MotionSource.BAND else MotionSource.GLASSES,
    )

    private fun ended(session: Live, why: String) {
        if (!session.ended.complete(why)) return
        log.add("Toolkit: session ended ($why)")
        session.motion.tryEmit(null)
        if (layoutSession === session) {
            owner.value?.let { states.tryEmit(it to LayoutState(showing = false, reason = LayoutEnd.BACK, time = now())) }
            owner.value = null
            layoutSession = null
        }
        scope.launch { opening.withLock { if (live === session) live = null } }
    }

    /** One open session: the link, the toolkit app's channel, and the requests waiting for their answers. */
    private inner class Live(val link: GlassesSession) {
        var users = 0
        val capabilities: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        val ended = CompletableDeferred<String>()
        val motion = MutableSharedFlow<Dat.Sample?>(extraBufferCapacity = 512)
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
            log.add("Toolkit: session open, toolkit app ${result.dwaVersion}")
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
                    LinkState.READY -> log.add("Toolkit: app link ready, trust ${link.trustResult}").also { send7009(Dat.SESSION_START_REQUEST, Dat.sessionStart(context.packageName, sessionId, "Visor", SDK_VERSION)) }
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
                        Dat.SESSION_STOP -> ended(this, "the wearer left the toolkit app")
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
                Dat.IMU -> Dat.motionSamples(payload).forEach(motion::tryEmit)
                Dat.DISPLAY -> {
                    val app = owner.value ?: return
                    Dat.displayClick(payload)?.let { clicks.tryEmit(app to LayoutClick(it, now())) }
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
            scope.launch {
                delay(STOP_GRACE_MS)
                link.stop()
                events?.cancel()
            }
        }
    }

    private fun LayoutNode.all(): List<LayoutNode> = listOf(this) + children.flatMap { it.all() }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val SDK_VERSION = "1.0.1"
        const val START_MS = 15_000L
        const val ANSWER_MS = 5_000L
        const val ADD_TRIES = 8
        const val ADD_RETRY_MS = 700L
        const val IDLE_MS = 5_000L
        const val STOP_GRACE_MS = 500L
    }
}
