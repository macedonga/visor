package com.macedonga.visor.core.connection

import com.macedonga.visor.core.identity.Diagnosis
import com.macedonga.visor.core.identity.IdentityBundle
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.SessionProbe
import com.macedonga.visor.core.identity.diagnose
import com.macedonga.visor.core.identity.verifyBundle
import com.macedonga.visor.core.session.ConnectFailed
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.LinkStateChanged
import com.macedonga.visor.core.session.ProtocolError
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.core.session.SessionEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one object that decides which glasses, holds one session, and publishes one state. */
class GlassesConnection(
    private val store: IdentityStore,
    private val hosts: SessionHostFactory,
    // Injectable so a test can keep the store reads on its own virtual clock.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

    /** Every service message, undecoded. svc28 and svc100 are also decoded into [state]. */
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

    private var job: Job? = null

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
    private val heldStatus = mutableListOf<ServiceData>()
    private var holdingStatus = true
    private var statusTimer: Job? = null

    // While a lease holds the session on Wi-Fi, the Bluetooth target it came from.
    @Volatile
    private var bluetooth: GlassesTarget? = null

    /** True while the session runs on Wi-Fi for a lease, with Bluetooth to come back to. */
    val onWifi: Boolean get() = bluetooth != null

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

    private suspend fun reopen(target: GlassesTarget) {
        val scope = lastScope ?: return
        if (!_running.value) return
        host?.stop()
        host = null
        job?.cancelAndJoin()
        job = null
        open(scope, target)
    }

    private fun open(scope: CoroutineScope, override: GlassesTarget?) {
        lastScope = scope
        lastOverride = override
        _running.value = true
        job = scope.launch {
            // The reads and the choice move together, not one withContext each: see the class doc for why
            // they leave the caller's dispatcher at all. The pair connected to is the one chosen from now on.
            val bundle = withContext(ioDispatcher) {
                val bundle = if (override != null) store.all().firstOrNull { it.serial == override.serial } else store.chosen()
                if (chooses) (override?.serial ?: bundle?.serial)?.let(store::choose)
                bundle
            }
            val target = override ?: bundle?.let {
                GlassesTarget(GlassesLink.Bluetooth(it.glassesAddress), it.serial)
            }
            if (target == null) {
                _state.value = GlassesState(reason = "No glasses are set up on this phone yet. Set up glasses to start.")
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
    suspend fun pause(why: String) {
        // The session that takes over runs on Wi-Fi of its own; this one comes back on Bluetooth.
        bluetooth?.let { lastOverride = it }
        bluetooth = null
        host?.stop()
        host = null
        job?.cancelAndJoin()
        job = null
        _state.value = _state.value.copy(link = LinkState.DISCONNECTED, reason = why)
    }

    /** Reconnect after [pause], unless [stop] ran in between. */
    fun resume() {
        val scope = lastScope ?: return
        if (_running.value && job == null) open(scope, lastOverride)
    }

    /** Ask svc28 again on the session's own status channel: the glasses answer with a fresh full snapshot. */
    fun refreshDeviceState() {
        val channel = statusChannel ?: return
        submit { subscribeOn(it, channel) }
    }

    // The session's svc28 channel; a new one per refresh would multiply every push.
    @Volatile
    private var statusChannel: Int? = null

    /** Run [work] on the live session with its mux. Dropped when nothing is connected. */
    fun submit(work: suspend (com.macedonga.visor.core.session.DataXConnection) -> Unit) {
        host?.submit(work)
    }

    /**
     * Finish, and do not return until the session has let go of the socket AND the event collector
     * has stopped.
     */
    suspend fun stop() {
        bluetooth = null
        host?.stop()
        host = null
        job?.cancelAndJoin()
        job = null
        _running.value = false
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
                _state.value = _state.value.copy(
                    link = if (refused) LinkState.LOST else event.state,
                    // The session publishes CONNECTING at the top of every attempt, including a
                    // reconnect, so that is where the slate is cleared.
                    reason = if (fresh) "" else event.reason,
                    failure = if (fresh) null else _state.value.failure,
                    // A refused key outlives the retries (newAttempt keeps it); a link that comes up is judged afresh.
                    diagnosis = when {
                        refused -> Diagnosis.EVICTED
                        event.state == LinkState.READY -> null
                        else -> _state.value.diagnosis
                    },
                )
                when {
                    refused -> judge(host, bundle)
                    event.state == LinkState.READY -> onReady(host, bundle, scope)
                    // An attempt that ended without a verdict gets one now.
                    event.state == LinkState.LOST || event.state == LinkState.DISCONNECTED -> judge(host, bundle)
                }
            }

            is ConnectFailed -> _state.value = _state.value.copy(failure = event.failure)

            is ServiceData -> {
                // Whenever it arrives. settleChannels publishes what landed during link setup
                // before READY goes out, so an answer can legitimately come first.
                if (event.serviceId == SVC_PEER_BUILD_INFO &&
                    event.messageType == MT_PEER_DEVICE_INFO
                ) {
                    canaryAnswered = true
                    decodeInto(event) { copy(info = decodePeerDeviceInfo(it.payload)) }
                    heldStatus.forEach(::applyStatus)
                    heldStatus.clear()
                    judge(host, bundle)
                }
                if (event.serviceId == SVC_STATUS && isDeviceStateType(event.messageType)) {
                    // How it is framed depends on the model, which svc100 names: what comes first waits for it.
                    if (holdingStatus && _state.value.info == null && heldStatus.size < HELD_STATUS) heldStatus += event else applyStatus(event)
                }
                // Airship's file chunks and the live stream's frames are their responders' business,
                // and thousands of them would push everything else out of this buffer.
                if (event.serviceId != SVC_AIRSHIP && event.serviceId != SVC_MEDIA_STREAMING_CONTROL) _serviceData.emit(event)
            }

            is ProtocolError -> _warnings.tryEmit(event.detail)

            else -> Unit
        }
    }

    private fun applyStatus(event: ServiceData) = decodeInto(event) {
        val update = decodeDeviceStateUpdate(it.messageType, it.payload, display = isDisplayBuild(info?.build))
        copy(device = mergeDeviceState(device ?: DeviceState(), update.state))
    }

    /** Fold a decoded service message into the state, or report why it would not decode. */
    private fun decodeInto(event: ServiceData, apply: GlassesState.(ServiceData) -> GlassesState) {
        try {
            _state.value = _state.value.apply(event)
        } catch (error: Exception) {
            _warnings.tryEmit("could not decode $event: ${error.message}")
        }
    }

    private fun newAttempt() {
        heldStatus.clear()
        holdingStatus = true
        statusTimer?.cancel()
        statusTimer = null
        canaryAnswered = false
        judged.set(false)
        deadline?.cancel()
        deadline = null
        // A refused key stays said through the reconnect ladder: every try ends the same way until READY.
        _state.value = _state.value.copy(
            diagnosis = _state.value.diagnosis.takeIf { it == Diagnosis.EVICTED },
            device = null,
            info = null,
        )
    }

    /** Ask svc100, then start the clock on the answer. */
    private suspend fun onReady(host: SessionHost, bundle: IdentityBundle?, scope: CoroutineScope) {
        // One piece of work, so they go out in this order every time. The desktop's
        // run_status submits the first two at READY as well; none needs identity auth.
        host.submit { connection ->
            announcePeerAppInfo(connection)
            statusChannel = subscribeOn(connection, null)
            syncClock(connection)
        }
        statusTimer = scope.launch {
            delay(STATUS_HOLD_MILLIS)
            if (canaryAnswered) return@launch
            holdingStatus = false
            heldStatus.forEach(::applyStatus)
            heldStatus.clear()
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

        val trustOk = host.trustComplete
        val answered = canaryAnswered
        val theirs = host.credentialReceived
        if (bundle == null) {
            // Bring-up against a peer with no stored identity. There is a verdict to publish
            // and no bundle whose state could move.
            _state.value = _state.value.copy(diagnosis = diagnose(trustOk, answered, theirs))
            return
        }
        val checked = verifyBundle(bundle) {
            object : SessionProbe {
                override suspend fun runTrust(): Boolean = trustOk
                override suspend fun readCanary(): Boolean = answered
                override suspend fun credentialReceived(): Boolean = theirs
            }
        }
        // A file write, off the caller's dispatcher for the same reason the store reads
        // in start() are: see the class doc. Against what is stored now, not start()'s copy: a
        // refused key is judged on every retry, and a pair forgotten meanwhile must stay gone.
        withContext(ioDispatcher) {
            val stored = store.load(bundle.serial) ?: return@withContext
            if (stored.state != checked.bundle.state) store.save(stored.withState(checked.bundle.state))
        }
        _state.value = _state.value.copy(diagnosis = checked.diagnosis)
    }

    companion object {
        /** 20 seconds, from `__main__.py`'s `_DEFAULT_TIMEOUT_SECONDS`. */
        const val CANARY_TIMEOUT_MILLIS: Long = 20_000

        /** How long svc28 waits for svc100 before it is applied anyway. */
        private const val STATUS_HOLD_MILLIS: Long = 5_000

        private const val SVC_AIRSHIP = 20
        private const val SVC_MEDIA_STREAMING_CONTROL = 51350
        private const val HELD_STATUS = 64
    }
}
