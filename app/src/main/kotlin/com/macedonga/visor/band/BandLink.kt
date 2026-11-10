package com.macedonga.visor.band

import android.content.Context
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.ConstellationManifest
import com.macedonga.visor.core.identity.BundleCredentials
import com.macedonga.visor.core.identity.BundleState
import com.macedonga.visor.core.identity.IdentityBundle
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.IdentityTrustExchange
import com.macedonga.visor.core.identity.keyHint
import com.macedonga.visor.core.identity.nowIso
import com.macedonga.visor.core.identity.rawPublicKey
import com.macedonga.visor.core.protocol.Band
import com.macedonga.visor.core.protocol.BtPeer
import com.macedonga.visor.core.protocol.CamControl
import com.macedonga.visor.core.protocol.ServiceId
import com.macedonga.visor.core.session.DataXError
import com.macedonga.visor.core.session.GlassesSession
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.LinkStateChanged
import com.macedonga.visor.core.session.ProtocolError
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.core.session.SessionCommand
import com.macedonga.visor.core.session.SessionEvent
import com.macedonga.visor.core.session.RelayState
import com.macedonga.visor.core.session.SubstreamRelay
import com.macedonga.visor.core.transport.BluetoothLeTransport
import com.macedonga.visor.dat.ToolkitStore
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.setup.NearbyGlasses
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
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

/** How sharing the band goes, for the band settings. */
sealed interface ShareState {
    data object Off : ShareState

    /** Reaching the band, or the Display and the band starting their link through the phone. */
    data object Connecting : ShareState

    /** Shared, with the glasses away: the Display reaches the band once they are back. */
    data object Waiting : ShareState

    /** The Display and the band talk through the phone. */
    data object On : ShareState

    /** Sharing turned itself off, and the band went back to the Display. */
    data class Stopped(val problem: ShareProblem) : ShareState
}

enum class ShareProblem { BAND_UNREACHABLE, DISPLAY_SILENT, KEPT_DROPPING }

/** How an errand on the band ended. */
enum class BandErrand { DONE, NO_ANSWER, NOT_CHANGED, REFUSED }

/**
 * The phone's link to the Neural Band (svc52822), taken from the Display for a lesson, a pairing, an update or one
 * errand, or kept while the band is shared: then the Display reaches the band through the phone's relay.
 */
@Singleton
class BandLink @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val bands: Bands,
    private val store: IdentityStore,
    private val peers: BandPeers,
    private val nearby: NearbyGlasses,
    private val toolkit: ToolkitStore,
    private val log: EventLog,
) {
    // The band the Display let go of for this phone, by its address as the Display names it and its key hint.
    @Volatile private var lent: Pair<ByteArray, ByteArray>? = null
    // A borrow ended while the glasses' link was down: the band still has to go back to the Display.
    @Volatile private var pendingBack = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val seq = AtomicInteger(1)
    // Answers still awaited, by request seq.
    private val replies = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
    private val held = MutableStateFlow(true)
    // Lessons and VDK apps that hold the band's gestures, counted: the band goes back when the last one ends.
    private val lessons = MutableStateFlow(0)
    // Errands under way: the band stays here until the last one ends.
    private val errands = MutableStateFlow(0)
    private val updating = MutableStateFlow(false)
    private val pairing = MutableStateFlow(false)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sharing = MutableStateFlow(prefs.getBoolean(SHARE_KEY, false))

    /** True while the band stays on the phone and the Display reaches it through the relay. */
    val shared: StateFlow<Boolean> = sharing.asStateFlow()
    private val _share = MutableStateFlow<ShareState>(if (sharing.value) ShareState.Connecting else ShareState.Off)

    /** Where sharing is, and why it stopped on its own. */
    val shareState: StateFlow<ShareState> = _share.asStateFlow()
    private val _peer = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    /** What the band says on svc41109, its messages and its error frames. */
    val peer: SharedFlow<SessionEvent> = _peer
    private val _gestures = MutableSharedFlow<Band.Gesture>(extraBufferCapacity = 32)
    private val _motion = MutableSharedFlow<Band.Motion>(extraBufferCapacity = 256)

    /** The band's IMU readings, while an app holds them with [startMotion]. */
    val motion: SharedFlow<Band.Motion> = _motion
    // Apps that read the wrist's motion, counted; the IMU streams run while there is one and the band is up.
    private val motionUsers = MutableStateFlow(0)
    @Volatile private var motionOn = false
    // What the band says on its manifest services.
    private val manifestFrames = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    val gestures: SharedFlow<Band.Gesture> = _gestures

    @Volatile private var session: GlassesSession? = null
    private var job: Job? = null

    private val _state = MutableStateFlow(BandState())
    val state: StateFlow<BandState> = _state.asStateFlow()

    init {
        scope.launch {
            // A lesson or an errand takes the band from the Display; when it ends, the band goes back. Never otherwise:
            // the Display reaches the band on its own, and a phone holding it while the Display restarts keeps it off.
            val wanted = combine(lessons, errands, updating, pairing, sharing) { learning, errand, update, pair, share ->
                learning > 0 || errand > 0 || update || pair || share
            }
            combine(connection.running, connection.pair, bands.all, wanted, held) { running, pair, all, want, hold ->
                pair?.let { p -> all.firstOrNull { it.glasses == p } }?.takeIf { running && hold && want }
            }.distinctUntilChanged().collect { band -> gate.withLock { switchTo(band) } }
        }
        // The Display links its band by a built-in rule that Meta's MobileConfig switches on (cam_sg_to_input); a Display
        // that never had Meta's config links it only by a rule from the phone, a temporary one, gone when the Display
        // restarts. So once a connection, a Display not holding the band a while after it came up gets the band rule.
        // Not while it holds the band: a new rule makes it drop the band and link it again.
        scope.launch {
            connection.state.map { if (it.link == LinkState.READY) it.info?.serial else null }.distinctUntilChanged().collectLatest { serial ->
                val band = serial?.let { s -> bands.all.value.firstOrNull { it.glasses == s } } ?: return@collectLatest
                delay(RULE_CHECK_MS)
                if (session != null) return@collectLatest
                val listed = peers.refresh()?.peers ?: return@collectLatest
                if (listed.any { it.connected }) return@collectLatest
                val hint = band.publicKey?.let(::rawPublicKey)?.let(::keyHint) ?: return@collectLatest
                log.add("Band: the Display does not hold the band; sending it the band rule")
                connection.submit { it.sendOnService(CamControl.SERVICE, CamControl.OVERRIDE_RULE, CamControl.bandRule(hint)) }
            }
        }
        // While shared, the Display's link and the band's are joined whenever both are up. A band out of reach, or a
        // relay the two never use, ends the sharing, so the Display gets its band back rather than none.
        scope.launch {
            combine(sharing, _state.map { it.link }, connection.state.map { it.link }) { share, band, glasses ->
                Triple(share, band, glasses)
            }.distinctUntilChanged().collectLatest { (share, band, glasses) ->
                if (!share) return@collectLatest
                if (glasses != LinkState.READY) {
                    _share.value = ShareState.Waiting
                    return@collectLatest
                }
                if (band != LinkState.READY) {
                    _share.value = ShareState.Connecting
                    delay(REACH_MS)
                    return@collectLatest stopSharing(ShareProblem.BAND_UNREACHABLE)
                }
                val displayLink = connection.link ?: return@collectLatest
                val bandLink = session?.link ?: return@collectLatest
                coroutineScope {
                    val relay = SubstreamRelay(displayLink, bandLink) { log.add(it) }
                    relay.start(this)
                    relay.state.collect { s ->
                        when (s) {
                            RelayState.Opening -> _share.value = ShareState.Connecting
                            RelayState.Up -> {
                                _share.value = ShareState.On
                                // As the Meta app does: told RELAY, the Display stops reaching for its band directly,
                                // which every few minutes took the band off the phone.
                                preferLink(BtPeer.LINK_RELAY)
                            }
                            is RelayState.Failed -> stopSharing(if (s.silent) ShareProblem.DISPLAY_SILENT else ShareProblem.KEPT_DROPPING)
                        }
                    }
                }
            }
        }
        // A band given back while the glasses' link was down goes back once it is up again.
        scope.launch {
            connection.state.collect { glasses ->
                if (glasses.link == LinkState.READY && session == null && pendingBack) gate.withLock { if (session == null) giveBack() }
            }
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

    /**
     * Shares the band between the phone and the Display, or stops: shared, the band stays on the phone, where apps get
     * its gestures and buzz it at once, and the Display reaches it through the phone as on Meta's relay lease.
     */
    fun share(on: Boolean) {
        if (sharing.value != on) log.add(if (on) "Band: shared with the Display through the phone" else "Band: no longer shared")
        _share.value = if (on) ShareState.Connecting else ShareState.Off
        setSharing(on)
    }

    private fun stopSharing(problem: ShareProblem) {
        log.add("Band: sharing stopped (${problem.name.lowercase()}); the band goes back to the Display")
        _share.value = ShareState.Stopped(problem)
        setSharing(false)
    }

    private fun setSharing(on: Boolean) {
        if (!on && sharing.value) preferLink(BtPeer.LINK_DIRECT)
        prefs.edit().putBoolean(SHARE_KEY, on).apply()
        sharing.value = on
    }

    // svc41109 SET_PREFERRED_LINK for the band the Display let go of.
    private fun preferLink(link: Int) {
        val (identity, hint) = lent ?: return
        if (connection.state.value.link != LinkState.READY) return
        connection.submit { it.sendOnService(BtPeer.SERVICE, BtPeer.SET_PREFERRED_LINK, BtPeer.setPreferredLink(link, identity, hint)) }
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

    /** Turns the band's IMU on for one more reader; the band must be shared, so it stays on the phone. */
    @Synchronized
    fun startMotion() {
        motionUsers.value += 1
        if (motionUsers.value == 1 && _state.value.link == LinkState.READY) streams(_state.value.gestures)
    }

    @Synchronized
    fun endMotion() {
        if (motionUsers.value == 0) return
        motionUsers.value -= 1
        if (motionUsers.value == 0 && _state.value.link == LinkState.READY) streams(_state.value.gestures)
    }

    /** A buzz the wearer cannot miss: done once sent, unless the band answers with an error. */
    suspend fun vibrate(): BandErrand = vibrate(BUZZ_MS, FULL_STRENGTH)

    /** A buzz of [millis] at [amplitude], 1 to 255. */
    suspend fun vibrate(millis: Long, amplitude: Int): BandErrand =
        errand("a buzz") { haptic(call(BUZZ_REPLY_MS, { Band.vibrate(it, millis, amplitude) }), "buzz") }

    // The Display never reads the band's haptics answers: none is fine, an error code is not.
    private fun haptic(answer: Band.Response?, what: String): BandErrand {
        val code = answer?.let { it.haptics ?: it.code } ?: return BandErrand.DONE
        // The band answers a buzz it plays with 1, and plays it as it answers.
        if (code == Band.OK || code == HAPTICS_PLAYED) return BandErrand.DONE
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

    /**
     * Installs [manifest] under [authority] on the band, as the Meta app does: the band checks the Display's manifest
     * against its own authority key, and refuses the Display with 0xc004 until it holds the key that signed it. Then
     * the two hand each other newer manifests. The version the band holds afterwards, or null when it did not answer.
     */
    suspend fun installManifest(authority: ByteArray, manifest: ByteArray): Long? = holdFor { install(authority, manifest, salt = null) }

    // As the Meta app does on every band connection: a band holding another manifest than Visor's for its glasses gets
    // Visor's. The band stays here until it is done: half a manifest would leave it with none.
    private suspend fun syncManifest(glasses: String) {
        val pair = toolkit.pair(glasses) ?: return
        val manifest = pair.manifest ?: return
        errands.update { it + 1 }
        try {
            install(pair.authorityPublic, manifest, salt = pair.salt)
        } catch (away: BandAway) {
            log.add("Band: the link went down while its manifest was checked")
        } finally {
            errands.update { it - 1 }
        }
    }

    // svc102 on bands with AirShield 0x11 and up, svc10 before, as the Meta app picks. With [salt], a band already
    // holding that manifest is left alone. The version the band holds afterwards, or null.
    private suspend fun install(authority: ByteArray, manifest: ByteArray, salt: ByteArray?): Long? {
        for (service in MANIFEST_SERVICES) {
            val before = manifestVersion(service) ?: continue
            if (salt != null && before.salt.contentEquals(salt)) return before.version
            log.add("Band: manifest ${before.version} on svc$service; installing Visor's")
            sendOn(service, ConstellationManifest.SET_AUTHORITY_KEY, ConstellationManifest.authorityKey(authority))
            ConstellationManifest.chunks(manifest).forEach { (type, chunk) -> sendOn(service, type, chunk) }
            val after = manifestVersion(service)
            log.add("Band: holds manifest ${after?.version}")
            return after?.version
        }
        log.add("Band: no manifest service answered")
        return null
    }

    private suspend fun manifestVersion(service: Int): ConstellationManifest.Version? = withTimeoutOrNull(REPLY_MS) {
        manifestFrames
            .onSubscription { sendOn(service, ConstellationManifest.GET_VERSION, ByteArray(0)) }
            .first { (it is ServiceData && it.serviceId == service && it.messageType == ConstellationManifest.VERSION_INFO) || (it is DataXError && it.serviceId == service) }
    }?.let { (it as? ServiceData)?.payload }?.let(ConstellationManifest::version)

    private fun sendOn(service: Int, type: Int, payload: ByteArray) {
        val running = session ?: throw BandAway()
        running.submit(SessionCommand { s -> s.connection?.sendOnService(service, type, payload) })
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

    // Gestures as asked, and the IMU while an app reads it: named only while it is on or going off, as the Meta app
    // asks for gestures alone.
    private fun streams(on: Boolean) {
        val motion = motionUsers.value > 0
        val wanted = if (motion || motionOn) motion else null
        val sent = send { listOf(Band.streams(seq.getAndIncrement(), on, wanted, _state.value.protocol)) }
        if (sent) {
            if (_state.value.gestures != on) log.add("Band: gestures ${if (on) "on" else "off"} (protocol ${_state.value.protocol ?: "not read"})")
            if (motionOn != motion) log.add("Band: motion ${if (motion) "on" else "off"}")
            _state.update { it.copy(gestures = on) }
            motionOn = motion
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
        if (band == null) return giveBack()
        val key = band.key ?: store.ownerKeyPem() ?: return
        if (_state.value.serial != band.serial) _state.value = BandState(serial = band.serial)
        // The band takes one central and stops advertising while the Display holds it: the Display lets go first, then
        // the band is heard and reached, as the Meta app does. Pairing finds the band in pairing mode on its own.
        val address = if (pairing.value) band.address else {
            takeFromDisplay(band)
            // The band is reached at the address it advertises; the Display waits about 20 s before it takes it back.
            runCatching { nearby.bandNearby(first = true, millis = HEAR_MS) }.getOrNull() ?: band.address
        }
        val identity = IdentityBundle(key, address, band.serial, nowIso(), "band", BundleState.VERIFIED)
        val opened = GlassesSession(
            transport = BluetoothLeTransport(context, address, defaultPsm = Band.PSM, randomAddress = true),
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
                            band.glasses?.let { glasses -> launch { syncManifest(glasses) } }
                            // The gestures wait for the device info, which picks how to ask for them; not for long.
                            motionOn = false
                            if (lessons.value > 0 || motionUsers.value > 0) launch {
                                delay(INFO_WAIT_MS)
                                if (lessons.value > 0 && !_state.value.gestures || motionUsers.value > 0 && !motionOn) streams(lessons.value > 0)
                            }
                        }
                    }
                    is ServiceData -> when (event.serviceId) {
                        ServiceId.WIRELESS_INPUT.value -> onBand(event)
                        BtPeer.SERVICE -> _peer.tryEmit(event)
                        in MANIFEST_SERVICES -> manifestFrames.tryEmit(event)
                    }
                    is DataXError -> when (event.serviceId) {
                        BtPeer.SERVICE -> _peer.tryEmit(event)
                        in MANIFEST_SERVICES -> manifestFrames.tryEmit(event)
                    }
                    is ProtocolError -> log.add("Band: ${event.detail}")
                    else -> Unit
                }
            }
        }
        log.add("Band: connecting to ${band.serial}")
        opened.start(scope)
    }

    /**
     * Asks the Display on svc41109 to let go of the band it holds, and waits until it says it did. A band the Display
     * lists but does not hold, still on its way back from the last borrow, goes back after this one all the same.
     */
    private suspend fun takeFromDisplay(band: BandRecord) {
        if (connection.state.value.link != LinkState.READY) return
        val listed = peers.refresh()?.peers.orEmpty()
        val held = listed.firstOrNull { it.connected } ?: listed.firstOrNull() ?: return log.add("Band: the Display lists no band")
        val hint = band.publicKey?.let(::rawPublicKey)?.let(::keyHint) ?: return log.add("Band: no key to name the band to the Display")
        lent = held.identity to hint
        if (!held.connected) return log.add("Band: the Display does not hold the band now")
        // Since firmware 7202 the Display takes a band it let go of back within a second. A RELAY rule first makes it
        // wait about 20 s for a way through the phone, time enough to reach the band; the hand-back undoes it.
        connection.submit { it.sendOnService(CamControl.SERVICE, CamControl.OVERRIDE_RULE, CamControl.bandRule(hint, CamControl.SCOPE_RELAY)) }
        log.add("Band: asking the Display to let go of the band")
        val released = withTimeoutOrNull(RELEASE_MS) {
            connection.serviceData
                .onSubscription { connection.submit { it.sendOnService(BtPeer.SERVICE, BtPeer.DISCONNECT, BtPeer.disconnect(held.identity, hint)) } }
                .first {
                    it.serviceId == BtPeer.SERVICE && it.messageType == BtPeer.CONNECTION_EVENT &&
                        BtPeer.connectionEvent(it.payload).let { e -> e.event == BtPeer.DISCONNECTED && BtPeer.sameAddress(e.identity, held.identity) }
                }
        } != null
        log.add(if (released) "Band: the Display let go" else "Band: the Display did not say it let go in ${RELEASE_MS / 1000} s")
    }

    /**
     * Hands the band back to the Display after a borrow: the one it let go of, or, when the borrow began before the
     * Display could be asked, the band it lists but does not hold: a LOW rule in place of the borrow's RELAY one, as the
     * Meta app moves a band to glasses, and CONNECT for firmware before 7202.
     */
    private suspend fun giveBack() {
        if (connection.state.value.link != LinkState.READY) {
            pendingBack = true
            return
        }
        pendingBack = false
        val (identity, hint) = lent ?: listedBand() ?: return
        lent = null
        log.add("Band: handing the band back to the Display")
        connection.submit { it.sendOnService(CamControl.SERVICE, CamControl.OVERRIDE_RULE, CamControl.bandRule(hint)) }
        connection.submit { it.sendOnService(BtPeer.SERVICE, BtPeer.CONNECT, BtPeer.connect(identity, hint)) }
    }

    // The band the Display lists and does not hold, as the Display names it, with its key hint. With no band set up
    // here the Display is not asked: glasses without svc41109 answer 0xc001.
    private suspend fun listedBand(): Pair<ByteArray, ByteArray>? {
        val band = bands.all.value.firstOrNull { it.serial == _state.value.serial } ?: return null
        val hint = band.publicKey?.let(::rawPublicKey)?.let(::keyHint) ?: return null
        val peer = peers.refresh()?.peers?.firstOrNull()?.takeIf { !it.connected } ?: return null
        return peer.identity to hint
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
        if (Band.isMotion(type)) {
            runCatching { Band.motion(type, event.payload) }.getOrNull()?.let { _motion.tryEmit(it) }
            return
        }
        if (type == Band.RESPONSE) {
            runCatching { Band.seqOf(event.payload) }.getOrNull()?.let { replies.remove(it)?.complete(event.payload) }
            runCatching { Band.otaInfo(event.payload) }.getOrNull()?.let { info -> _state.update { it.copy(firmware = info.firmware) } }
        }
        val battery = runCatching {
            when (type) {
                Band.RESPONSE -> Band.response(event.payload).also { r ->
                    if (r.leftHanded != null) _state.update { it.copy(leftHanded = r.leftHanded) }
                    if (r.protocol != null) {
                        _state.update { it.copy(protocol = r.protocol) }
                        if (lessons.value > 0 && !_state.value.gestures || motionUsers.value > 0 && !motionOn) streams(lessons.value > 0)
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
        const val PREFS = "band"
        const val SHARE_KEY = "share_with_display"
        // Taking the band from the Display and reaching it, as a borrow allows, with room to spare.
        const val REACH_MS = 60_000L
        val MANIFEST_SERVICES = listOf(102, ConstellationManifest.SERVICE)
        const val HAPTICS_PLAYED = 1
        const val RELEASE_MS = 10_000L
        const val HEAR_MS = 8_000L
        // Long enough for a Display that links its band itself to have done it.
        const val RULE_CHECK_MS = 30_000L
        const val POLL_MS = 5 * 60_000L
        const val INFO_WAIT_MS = 1_500L
        // The Display letting go (up to 10 s), hearing the band (15 s), then the link.
        const val BORROW_MS = 45_000L
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
