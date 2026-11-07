package com.macedonga.visor.band

import android.content.Context
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.BundleCredentials
import com.macedonga.visor.core.identity.BundleState
import com.macedonga.visor.core.identity.IdentityBundle
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.IdentityTrustExchange
import com.macedonga.visor.core.identity.nowIso
import com.macedonga.visor.core.protocol.Band
import com.macedonga.visor.core.protocol.BtPeer
import com.macedonga.visor.core.protocol.ServiceId
import com.macedonga.visor.core.session.DataXError
import com.macedonga.visor.core.session.GlassesSession
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.LinkStateChanged
import com.macedonga.visor.core.session.ProtocolError
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.core.session.SessionCommand
import com.macedonga.visor.core.session.SessionEvent
import com.macedonga.visor.core.transport.BluetoothLeTransport
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The band as last heard from. */
data class BandState(
    val serial: String? = null,
    val link: LinkState = LinkState.DISCONNECTED,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val leftHanded: Boolean? = null,
    // The band's RPC protocol, from its device info: 2 and up turn streams on by a map.
    val protocol: Int? = null,
    // A lesson has the band's gestures coming to the phone.
    val gestures: Boolean = false,
    // The firmware's version, from the band's OTA device info.
    val firmware: Long? = null,
)

/** How an errand on the band ended. */
enum class BandErrand { DONE, NO_ANSWER, NOT_CHANGED, REFUSED }

/** The phone's link to the Neural Band (svc52822), taken from the Display only for a lesson, a pairing, an update or one errand. */
@Singleton
class BandLink @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val bands: Bands,
    private val store: IdentityStore,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val seq = AtomicInteger(1)
    // Answers still awaited, by request seq.
    private val replies = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
    private val held = MutableStateFlow(true)
    // Lessons and kit apps that hold the band's gestures, counted: the band goes back when the last one ends.
    private val lessons = MutableStateFlow(0)
    // Errands under way: the band stays here until the last one ends.
    private val errands = MutableStateFlow(0)
    private val updating = MutableStateFlow(false)
    private val pairing = MutableStateFlow(false)
    private val _peer = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    /** What the band says on svc41109, its messages and its error frames. */
    val peer: SharedFlow<SessionEvent> = _peer
    private val _answers = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    /** Every RPC answer while the band is up, raw: a firmware update reads its chunk acks here. */
    val answers: SharedFlow<ByteArray> = _answers
    private val _gestures = MutableSharedFlow<Band.Gesture>(extraBufferCapacity = 32)
    val gestures: SharedFlow<Band.Gesture> = _gestures

    @Volatile private var session: GlassesSession? = null
    private var job: Job? = null

    private val _state = MutableStateFlow(BandState())
    val state: StateFlow<BandState> = _state.asStateFlow()

    init {
        scope.launch {
            // A lesson or an errand takes the band from the Display; when it ends, the band goes back. Never otherwise:
            // the Display reaches the band on its own, and a phone holding it while the Display restarts keeps it off.
            val wanted = combine(lessons, errands, updating, pairing) { learning, errand, update, pair -> learning > 0 || errand > 0 || update || pair }
            combine(connection.running, connection.pair, bands.all, wanted, held) { running, pair, all, want, hold ->
                pair?.let { p -> all.firstOrNull { it.glasses == p } }?.takeIf { running && hold && want }
            }.distinctUntilChanged().collect { band -> gate.withLock { switchTo(band) } }
        }
    }

    /** Let go of the band for a while, so another session can have it: setting it up again. */
    suspend fun release() {
        held.value = false
        gate.withLock { switchTo(null) }
    }

    fun hold() {
        held.value = true
    }

    /** Holds the band for [block], which needs it for minutes, then gives it back. */
    suspend fun <T> holdFor(block: suspend () -> T): T {
        updating.value = true
        try {
            if (!reached()) throw BandAway()
            return block()
        } finally {
            updating.value = false
        }
    }

    /** Takes the band for pairing it with the Display; false when its link is not up in time. */
    suspend fun startPairing(): Boolean {
        pairing.value = true
        return reached()
    }

    /** Ends the pairing hold; before [hold], or the band comes back here while the Display bonds it. */
    fun endPairing() {
        pairing.value = false
    }

    /** A svc41109 message on the band's own channel, which Visor opens; false when the band is not up. */
    fun sendPeer(type: Int, payload: ByteArray): Boolean {
        val running = session ?: return false
        if (_state.value.link != LinkState.READY) return false
        running.submit(SessionCommand { s -> s.connection?.sendOnService(BtPeer.SERVICE, type, payload) })
        return true
    }

    /** The next seq for a request built by hand. */
    fun nextSeq(): Int = seq.getAndIncrement()

    /** Sends RPC requests on the band now; false when the band is not up. */
    fun sendRaw(requests: List<ByteArray>): Boolean = send { requests }

    /** Have the band's gestures come here: the band is taken from the Display for as long as it lasts. */
    @Synchronized
    fun startLesson() {
        lessons.value += 1
        if (lessons.value > 1) return
        log.add("Band: a lesson takes the band")
        if (_state.value.link == LinkState.READY) streams(true)
    }

    @Synchronized
    fun endLesson() {
        if (lessons.value == 0) return
        // The streams stop before the count lets the band go back.
        if (lessons.value == 1 && _state.value.link == LinkState.READY) streams(false)
        lessons.value -= 1
        if (lessons.value == 0) log.add("Band: the lesson gives the band back")
    }

    /** A buzz the wearer cannot miss: done once sent, unless the band answers with an error. */
    suspend fun vibrate(): BandErrand = vibrate(BUZZ_MS, FULL_STRENGTH)

    /** A buzz of [millis] at [amplitude], 1 to 255. */
    suspend fun vibrate(millis: Long, amplitude: Int): BandErrand =
        errand("a buzz") { haptic(call(BUZZ_REPLY_MS, { Band.vibrate(it, millis, amplitude) }), "buzz") }

    // The Display never reads the band's haptics answers: none is fine, an error code is not.
    private fun haptic(answer: Band.Response?, what: String): BandErrand {
        val code = answer?.let { it.haptics ?: it.code } ?: return BandErrand.DONE
        if (code == Band.OK) return BandErrand.DONE
        log.add("Band: the $what was refused, code $code")
        return BandErrand.REFUSED
    }

    /**
     * Puts the band on the [left] or the right wrist and reads it back, three tries a second apart.
     * The wrist shown changes only with the band's answer.
     */
    suspend fun setWrist(left: Boolean): BandErrand = errand("the wrist") {
        var outcome = BandErrand.NO_ANSWER
        for (attempt in 1..WRIST_TRIES) {
            if (attempt > 1) delay(RETRY_MS)
            val back = call(REPLY_MS, { Band.configRequest(it, left) }, { Band.configRequest(it) })
            if (back?.leftHanded == left) return@errand BandErrand.DONE
            if (back != null) outcome = BandErrand.NOT_CHANGED
        }
        log.add("Band: the wrist " + if (outcome == BandErrand.NOT_CHANGED) "did not change" else "got no answer")
        outcome
    }

    /** Takes the band from the Display for [block], then gives it back; NO_ANSWER when its link does not come up in time. */
    private suspend fun errand(what: String, block: suspend () -> BandErrand): BandErrand {
        errands.update { it + 1 }
        try {
            if (!reached()) {
                log.add("Band: not reached for $what in ${BORROW_MS / 1000} s")
                return BandErrand.NO_ANSWER
            }
            return block()
        } catch (away: BandAway) {
            log.add("Band: the link went down during $what")
            return BandErrand.NO_ANSWER
        } finally {
            scope.launch {
                delay(ERRAND_MS)
                errands.update { it - 1 }
            }
        }
    }

    private suspend fun reached(): Boolean = withTimeoutOrNull(BORROW_MS) { _state.first { it.link == LinkState.READY } } != null

    /** Sends [requests] in order, each with its own seq, and waits for the raw answer to the last; null when none comes. */
    suspend fun request(wait: Long, vararg requests: (Int) -> ByteArray): ByteArray? {
        val seqs = requests.map { seq.getAndIncrement() }
        val answer = CompletableDeferred<ByteArray>()
        replies[seqs.last()] = answer
        try {
            if (!send { requests.zip(seqs) { build, n -> build(n) } }) throw BandAway()
            return withTimeoutOrNull(wait) { answer.await() }
        } finally {
            replies.remove(seqs.last())
        }
    }

    private suspend fun call(wait: Long, vararg requests: (Int) -> ByteArray): Band.Response? =
        request(wait, *requests)?.let { runCatching { Band.response(it) }.getOrNull() }

    internal class BandAway : Exception("the band is not up")

    private fun streams(on: Boolean) {
        val sent = send { listOf(Band.gestureStreams(seq.getAndIncrement(), on, _state.value.protocol)) }
        if (sent) {
            _state.update { it.copy(gestures = on) }
            log.add("Band: gestures ${if (on) "on" else "off"} (protocol ${_state.value.protocol ?: "not read"})")
        }
    }

    /** Requests on the band's RPC, in order; false when the band is not connected. */
    private fun send(requests: () -> List<ByteArray>): Boolean {
        val running = session ?: return false
        if (_state.value.link != LinkState.READY) return false
        running.submit(
            SessionCommand { s ->
                val mux = s.connection ?: return@SessionCommand
                for (r in requests()) mux.sendOnService(ServiceId.WIRELESS_INPUT.value, Band.REQUEST_TYPE, r)
            },
        )
        return true
    }

    private suspend fun switchTo(band: BandRecord?) {
        if (band != null && _state.value.serial == band.serial && session != null) return
        session?.let {
            log.add("Band: link down")
            it.stop()
        }
        job?.cancel()
        session = null
        job = null
        _state.update { it.copy(link = LinkState.DISCONNECTED, gestures = false) }
        if (band == null) return
        val key = band.key ?: store.ownerKeyPem() ?: return
        if (_state.value.serial != band.serial) _state.value = BandState(serial = band.serial)
        val identity = IdentityBundle(key, band.address, band.serial, nowIso(), "band", BundleState.VERIFIED)
        val opened = GlassesSession(
            transport = BluetoothLeTransport(context, band.address, defaultPsm = Band.PSM),
            trust = IdentityTrustExchange(BundleCredentials(identity)),
        )
        session = opened
        job = scope.launch {
            launch { poll(opened) }
            opened.events.collect { event ->
                when (event) {
                    is LinkStateChanged -> {
                        _state.update { it.copy(link = event.state, gestures = if (event.state == LinkState.READY) it.gestures else false) }
                        if (event.state == LinkState.READY) {
                            log.add("Band: link up")
                            ask(opened, info = true)
                            // The gestures wait for the device info, which picks how to ask for them; not for long.
                            if (lessons.value > 0) launch {
                                delay(INFO_WAIT_MS)
                                if (lessons.value > 0 && !_state.value.gestures) streams(true)
                            }
                        }
                    }
                    is ServiceData -> if (event.serviceId == ServiceId.WIRELESS_INPUT.value) onBand(event) else if (event.serviceId == BtPeer.SERVICE) _peer.tryEmit(event)
                    is DataXError -> if (event.serviceId == BtPeer.SERVICE) _peer.tryEmit(event)
                    is ProtocolError -> log.add("Band: ${event.detail}")
                    else -> Unit
                }
            }
        }
        log.add("Band: connecting to ${band.serial}")
        opened.start(scope)
    }

    // The band pushes battery changes too; a slow read keeps the reading fresh when it does not.
    private suspend fun poll(opened: GlassesSession) {
        while (true) {
            delay(POLL_MS)
            if (_state.value.link == LinkState.READY && session === opened) ask(opened)
        }
    }

    private fun ask(opened: GlassesSession, info: Boolean = false) = opened.submit(
        SessionCommand { s ->
            val mux = s.connection ?: return@SessionCommand
            if (info) mux.sendOnService(ServiceId.WIRELESS_INPUT.value, Band.REQUEST_TYPE, Band.deviceInfoRequest(seq.getAndIncrement()))
            if (info) mux.sendOnService(ServiceId.WIRELESS_INPUT.value, Band.REQUEST_TYPE, Band.otaDeviceInfo(seq.getAndIncrement()))
            mux.sendOnService(ServiceId.WIRELESS_INPUT.value, Band.REQUEST_TYPE, Band.batteryRequest(seq.getAndIncrement()))
            mux.sendOnService(ServiceId.WIRELESS_INPUT.value, Band.REQUEST_TYPE, Band.configRequest(seq.getAndIncrement()))
        },
    )

    private fun onBand(event: ServiceData) {
        val type = event.messageType and 0xFF
        if (Band.isGesture(type)) return onGesture(type, event.payload)
        if (type == Band.RESPONSE) {
            runCatching { Band.seqOf(event.payload) }.getOrNull()?.let { replies.remove(it)?.complete(event.payload) }
            _answers.tryEmit(event.payload)
            runCatching { Band.otaInfo(event.payload) }.getOrNull()?.let { info -> _state.update { it.copy(firmware = info.firmware) } }
        }
        val battery = runCatching {
            when (type) {
                Band.RESPONSE -> Band.response(event.payload).also { r ->
                    if (r.leftHanded != null) _state.update { it.copy(leftHanded = r.leftHanded) }
                    if (r.protocol != null) {
                        _state.update { it.copy(protocol = r.protocol) }
                        if (lessons.value > 0 && !_state.value.gestures) streams(true)
                    }
                }.battery
                Band.STREAM_UPDATE -> Band.streamBattery(event.payload)
                else -> null
            }
        }.onFailure { log.add("Band: unreadable message type ${event.messageType}: ${it.message}") }.getOrNull() ?: return
        _state.update { it.copy(battery = battery.percent, charging = battery.charging) }
    }

    // Every gesture is acked.
    private fun onGesture(type: Int, payload: ByteArray) {
        val g = runCatching { Band.gesture(type, payload) }.getOrElse {
            log.add("Band: unreadable gesture type $type: ${it.message}")
            return
        }
        send { listOf(Band.gestureAck(seq.getAndIncrement(), g.seq)) }
        _gestures.tryEmit(g)
    }

    private companion object {
        const val POLL_MS = 5 * 60_000L
        const val INFO_WAIT_MS = 1_500L
        const val BORROW_MS = 20_000L
        const val ERRAND_MS = 1_500L
        const val REPLY_MS = 5_000L
        // The Display's own buzzes are 20 and 50 ms at 255, the most; 400 ms is one the wearer cannot miss.
        const val BUZZ_MS = 400L
        const val FULL_STRENGTH = 255
        const val BUZZ_REPLY_MS = 1_500L
        const val WRIST_TRIES = 3
        const val RETRY_MS = 1_000L
    }
}
