package com.macedonga.visor.core.connection

import com.macedonga.visor.core.identity.Diagnosis
import com.macedonga.visor.core.identity.IdentityBundle
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.diagnose
import com.macedonga.visor.core.identity.stateAfter
import com.macedonga.visor.core.protocol.SVC_AIRSHIP
import com.macedonga.visor.core.protocol.SVC_MEDIA_STREAMING_CONTROL
import com.macedonga.visor.core.session.ConnectFailed
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXError
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.LinkStateChanged
import com.macedonga.visor.core.session.ProtocolError
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.core.session.SessionEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The one object that decides which glasses, holds one session, and publishes one state. */
class GlassesConnection(
    private val store: IdentityStore,
    private val hosts: SessionHostFactory,
    // False for a quick look at another pair, which leaves the choice of pair as it was.
    private val chooses: Boolean = true,
) {

    private val _state = MutableStateFlow(GlassesState())
    val state: StateFlow<GlassesState> = _state.asStateFlow()

    // Re-broadcast, because GlassesSession.events is documented as having exactly one consumer and
    // this is it.
    private val _serviceData = MutableSharedFlow<ServiceData>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every service message but airship's and the live stream's, undecoded; svc28 and svc100 also land in [state]. */
    val serviceData: SharedFlow<ServiceData> = _serviceData.asSharedFlow()

    // Same buffering and the same reason as _serviceData: a slow reader must never stall the
    // session's single event consumer.
    private val _warnings = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * What the session reported as a protocol error, and any service message that would not decode.
     */
    val warnings: SharedFlow<String> = _warnings.asSharedFlow()

    private val _errors = MutableSharedFlow<DataXError>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every DataX status the glasses send but svc36's 0x1000, OK included, by service and error id. */
    val errors: SharedFlow<DataXError> = _errors.asSharedFlow()

    private var job: Job? = null

    // Two stop-then-open runs that interleave leave a session nothing can stop, and two sessions take the glasses in turns.
    private val lifecycle = Mutex()

    private val _pair = MutableStateFlow(runCatching { store.currentSerial() }.getOrNull())

    /** The serial of the pair Visor is for: the one connected, or chosen last. What Visor keeps per pair follows it. */
    val pair: StateFlow<String?> = _pair.asStateFlow()

    private val _running = MutableStateFlow(false)

    /** True from [start] until [stop] has let go of the socket. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile
    private var host: SessionHost? = null

    private var lastScope: CoroutineScope? = null
    private var lastOverride: GlassesTarget? = null

    // Per attempt, and reset on CONNECTING.
    @Volatile
    private var canaryAnswered = false
    private val judged = AtomicBoolean(false)
    private var deadline: Job? = null

    // svc28 state that came before svc100 said which model this is. Let through after a while if svc100 never decodes.
    // Guarded by itself, with holdingStatus: the hold timer runs beside the event collector.
    private val heldStatus = mutableListOf<ServiceData>()
    private var holdingStatus = true
    private var statusTimer: Job? = null

    // While a lease holds the session on Wi-Fi, the Bluetooth target it came from.
    @Volatile
    private var bluetooth: GlassesTarget? = null

    /** True while the session runs on Wi-Fi for a lease, with Bluetooth to come back to. */
    val onWifi: Boolean get() = bluetooth != null

    /** True from [pause] until the session opens again: the Live or a media import has the glasses on Wi-Fi of its own. */
    @Volatile
    var paused = false
        private set

    /** Choose a target, open a session on it, and keep the state current until [stop]. */
    fun start(scope: CoroutineScope, override: GlassesTarget? = null) {
        bluetooth = null
        open(scope, override)
    }

    /**
     * Move the session to [target], a Wi-Fi link, as the Meta app's link manager does for a lease: the glasses take one
     * session at a time, so it moves rather than opening a second, and every service moves with it.
     */
    suspend fun moveToWifi(target: GlassesTarget) {
        val current = _state.value.target ?: return
        if (bluetooth == null) bluetooth = current.takeIf { it.link is GlassesLink.Bluetooth } ?: return
        reopen(target)
    }

    /** Back on the Bluetooth link the session left for [moveToWifi]. */
    suspend fun backToBluetooth() {
        val target = bluetooth ?: return
        bluetooth = null
        reopen(target)
    }

    private suspend fun reopen(target: GlassesTarget) = lifecycle.withLock {
        val scope = lastScope ?: return@withLock
        if (!_running.value) return@withLock
        drop()
        open(scope, target)
    }

    /** [stop], then [start] on [target], as one step; nothing happens when [skip] holds for the state at that time. */
    suspend fun restart(scope: CoroutineScope, target: GlassesTarget?, skip: (GlassesState) -> Boolean = { false }) = lifecycle.withLock {
        if (skip(_state.value)) return@withLock
        halt()
        open(scope, target)
    }

    private fun open(scope: CoroutineScope, override: GlassesTarget?) {
        paused = false
        lastScope = scope
        lastOverride = override
        _running.value = true
        job = scope.launch {
            // Off the caller's dispatcher: the store is files. The pair connected to is the one chosen from now on.
            val bundle = withContext(Dispatchers.IO) {
                val bundle = if (override != null) store.load(override.serial) else store.chosen()
                if (chooses) (override?.serial ?: bundle?.serial)?.let(store::choose)
                bundle
            }
            val target = override ?: bundle?.let {
                GlassesTarget(GlassesLink.Bluetooth(it.glassesAddress), it.serial)
            }
            if (target == null) {
                _state.value = GlassesState(noGlasses = true)
                return@launch
            }

            if (chooses) _pair.value = target.serial
            _state.value = GlassesState(target = target)
            val opened = hosts.create(target, bundle)
            host = opened
            opened.start(this)
            opened.events.collect { event -> onEvent(event, opened, bundle, this) }
        }
    }

    /** Let go of the link without ending the connection, so another session can have the glasses. */
    suspend fun pause(why: String) = lifecycle.withLock {
        // The session that takes over runs on Wi-Fi of its own; this one comes back on Bluetooth.
        bluetooth?.let { lastOverride = it }
        bluetooth = null
        paused = true
        drop()
        _state.update { it.copy(link = LinkState.DISCONNECTED, reason = why) }
    }

    /** Reconnect after [pause], unless [stop] ran in between. A stop or restart under way decides instead. */
    fun resume() {
        val scope = lastScope ?: return
        if (!lifecycle.tryLock()) return
        try {
            if (_running.value && job == null) open(scope, lastOverride)
        } finally {
            lifecycle.unlock()
        }
    }

    /** Ask svc28 again on the session's own status channel: the glasses answer with a fresh full snapshot. */
    fun refreshDeviceState() {
        if (statusChannel == null) return
        submit { mux ->
            // Only on the connection that opened it: on the next one that id belongs to another service.
            val (owner, channel) = statusChannel ?: return@submit
            if (owner === mux) statusChannel = mux to subscribeOn(mux, channel)
        }
    }

    // The session's svc28 channel and its connection; a new one per refresh would multiply every push.
    @Volatile
    private var statusChannel: Pair<DataXConnection, Int>? = null

    /** The current session's link handshake line, for the event log. */
    val handshake: String? get() = host?.handshake

    /** Run [work] on the session with its mux: queued until READY while it reconnects, dropped when there is no session. */
    fun submit(work: suspend (DataXConnection) -> Unit) {
        host?.submit(work)
    }

    /**
     * Finish, and do not return until the session has let go of the socket AND the event collector
     * has stopped.
     */
    suspend fun stop() = lifecycle.withLock { halt() }

    private suspend fun halt() {
        bluetooth = null
        drop()
        _running.value = false
    }

    // Let go of the session and wait for its collector.
    private suspend fun drop() {
        statusChannel = null
        host?.stop()
        host = null
        job?.cancelAndJoin()
        job = null
    }

    private suspend fun onEvent(
        event: SessionEvent,
        host: SessionHost,
        bundle: IdentityBundle?,
        scope: CoroutineScope,
    ) {
        when (event) {
            is LinkStateChanged -> {
                val fresh = event.state == LinkState.CONNECTING
                if (fresh) newAttempt()
                // Glasses can turn the key down and still end link setup, then refuse every service: that is no link.
                val refused = event.state == LinkState.READY && !host.trustComplete && host.credentialReceived
                _state.update {
                    it.copy(
                        link = if (refused) LinkState.LOST else event.state,
                        // The session publishes CONNECTING at the top of every attempt, including a
                        // reconnect, so that is where the slate is cleared.
                        reason = if (fresh) "" else event.reason,
                        failure = if (fresh) null else it.failure,
                        // A refused key outlives the retries (newAttempt keeps it); a link that comes up is judged afresh.
                        diagnosis = when {
                            refused -> Diagnosis.EVICTED
                            event.state == LinkState.READY -> null
                            else -> it.diagnosis
                        },
                    )
                }
                when {
                    refused -> judge(host, bundle)
                    event.state == LinkState.READY -> onReady(host, bundle, scope)
                    // An attempt that ended without a verdict gets one now.
                    event.state == LinkState.LOST || event.state == LinkState.DISCONNECTED -> judge(host, bundle)
                }
            }

            is ConnectFailed -> _state.update { it.copy(failure = event.failure) }

            is ServiceData -> {
                // Whenever it arrives. settleChannels publishes what landed during link setup
                // before READY goes out, so an answer can legitimately come first.
                if (event.serviceId == SVC_PEER_BUILD_INFO &&
                    event.messageType == MT_PEER_DEVICE_INFO
                ) {
                    canaryAnswered = true
                    decodeInto(event) { copy(info = decodePeerDeviceInfo(it.payload)) }
                    releaseHeld()
                    judge(host, bundle)
                }
                if (event.serviceId == SVC_STATUS && isDeviceStateType(event.messageType)) {
                    // How it is framed depends on the model, which svc100 names: what comes first waits for it.
                    val held = synchronized(heldStatus) {
                        holdingStatus && _state.value.info == null && heldStatus.size < HELD_STATUS && heldStatus.add(event)
                    }
                    if (!held) applyStatus(event)
                }
                // Airship's file chunks and the live stream's frames are their responders' business,
                // and thousands of them would push everything else out of this buffer.
                if (event.serviceId != SVC_AIRSHIP && event.serviceId != SVC_MEDIA_STREAMING_CONTROL) _serviceData.emit(event)
            }

            is ProtocolError -> _warnings.tryEmit(event.detail)

            is DataXError -> _errors.tryEmit(event)

            else -> Unit
        }
    }

    private fun applyStatus(event: ServiceData) = decodeInto(event) {
        copy(device = decodeDeviceStatePush(it.payload, isDisplayBuild(info?.build), device))
    }

    // Applies what svc28 held back, in order; [stop] ends the hold in the same step.
    private fun releaseHeld(stop: Boolean = false) = synchronized(heldStatus) {
        if (stop) holdingStatus = false
        heldStatus.forEach(::applyStatus)
        heldStatus.clear()
    }

    /** Fold a decoded service message into the state, or report why it would not decode. */
    private fun decodeInto(event: ServiceData, apply: GlassesState.(ServiceData) -> GlassesState) {
        try {
            _state.update { it.apply(event) }
        } catch (error: Exception) {
            _warnings.tryEmit("could not decode $event: ${error.message}")
        }
    }

    private fun newAttempt() {
        statusChannel = null
        synchronized(heldStatus) {
            heldStatus.clear()
            holdingStatus = true
        }
        statusTimer?.cancel()
        statusTimer = null
        canaryAnswered = false
        judged.set(false)
        deadline?.cancel()
        deadline = null
        // A refused key stays said through the reconnect ladder: every try ends the same way until READY.
        _state.update {
            it.copy(
                diagnosis = it.diagnosis.takeIf { d -> d == Diagnosis.EVICTED },
                device = null,
                info = null,
            )
        }
    }

    /** Ask svc100, then start the clock on the answer. */
    private suspend fun onReady(host: SessionHost, bundle: IdentityBundle?, scope: CoroutineScope) {
        // One piece of work, so they go out in this order.
        host.submit { connection ->
            askPeerDeviceInfo(connection)
            statusChannel = connection to subscribeOn(connection, null)
            syncClock(connection)
        }
        statusTimer = scope.launch {
            delay(STATUS_HOLD_MILLIS)
            if (canaryAnswered) return@launch
            releaseHeld(stop = true)
        }
        if (canaryAnswered) {
            judge(host, bundle)
            return
        }
        deadline = scope.launch {
            delay(CANARY_TIMEOUT_MILLIS)
            // judge cancels the deadline, and this is it: let go first, or its save is cancelled under it.
            deadline = null
            judge(host, bundle)
        }
    }

    /** Decide once per attempt, as soon as the answer cannot change. */
    private suspend fun judge(host: SessionHost, bundle: IdentityBundle?) {
        // Claim, so the deadline firing and the canary answering at the same moment produce
        // one verdict and one store write rather than two of each.
        if (!judged.compareAndSet(false, true)) return
        deadline?.cancel()
        deadline = null

        val diagnosis = diagnose(host.trustComplete, canaryAnswered, host.credentialReceived)
        // Against what is stored now: a pair forgotten meanwhile stays gone.
        if (bundle != null) withContext(Dispatchers.IO) {
            store.update(bundle.serial) { it.copy(state = stateAfter(it.state, diagnosis)) }
        }
        _state.update { it.copy(diagnosis = diagnosis) }
    }

    companion object {
        private const val CANARY_TIMEOUT_MILLIS: Long = 20_000

        /** How long svc28 waits for svc100 before it is applied anyway. */
        private const val STATUS_HOLD_MILLIS: Long = 5_000

        private const val HELD_STATUS = 64
    }
}
