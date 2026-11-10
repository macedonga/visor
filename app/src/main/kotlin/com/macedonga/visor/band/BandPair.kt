package com.macedonga.visor.band

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.identity.keyHint
import com.macedonga.visor.core.identity.rawPublicKey
import com.macedonga.visor.core.protocol.BtPeer
import com.macedonga.visor.core.protocol.CamControl
import com.macedonga.visor.core.session.DataXError
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.core.session.SessionEvent
import com.macedonga.visor.dat.Manifests
import com.macedonga.visor.kit.KitException
import com.macedonga.visor.log.EventLog
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pairs the Neural Band with a Display set up here; [BandSetup] runs first when it has no band. As the Meta app pairs
 * the two at AirShield 0x11: both on Visor's manifest, and the Display reaches the band itself while worn. Over
 * svc41109 on both links when the Display does not.
 */
@Singleton
class BandPair @Inject constructor(
    private val connection: GlassesConnection,
    private val bands: Bands,
    private val link: BandLink,
    private val peers: BandPeers,
    private val setup: BandSetup,
    private val manifests: Manifests,
    private val lease: DeviceLease,
    private val log: EventLog,
) {
    sealed interface Status {
        data object Idle : Status
        data class Running(val step: Step) : Status
        data object Done : Status
        data class Failed(val reason: Reason) : Status
    }

    enum class Step { SETUP, MANIFEST, WEAR, BAND, PAIRING, CONNECTING }

    enum class Reason { NOT_CONNECTED, NO_BAND, NO_ANSWER, BAND_REFUSED, WRONG_OWNER, UNSUPPORTED, REFUSED, FAILED }

    private class Stop(val reason: Reason, val detail: String = "", val unreached: Boolean = false) : Exception(detail)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun start() {
        scope.launch { pair() }
    }

    fun reset() {
        if (_status.value !is Status.Running) _status.value = Status.Idle
    }

    /** Runs the pairing; a call while one runs does nothing. */
    private suspend fun pair() {
        if (!lock.tryLock()) return
        try {
            _status.value = try {
                run()
                log.add("Band pairing: done")
                Status.Done
            } catch (stop: Stop) {
                log.add("Band pairing failed: ${stop.reason} ${stop.detail}")
                Status.Failed(stop.reason)
            } catch (cancelled: CancellationException) {
                _status.value = Status.Idle
                throw cancelled
            } catch (error: Exception) {
                log.add("Band pairing failed: $error")
                Status.Failed(Reason.FAILED)
            }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun run() {
        if (connection.state.value.link != LinkState.READY) throw Stop(Reason.NOT_CONNECTED)
        val glasses = connection.pair.value ?: throw Stop(Reason.NOT_CONNECTED)
        val fresh = bands.forGlasses(glasses) == null
        if (fresh) setUp()
        var setUpNow = fresh
        var linked = viaManifest(glasses)
        // A band set up here before that does not answer, and that the Display does not list either, has likely
        // forgotten this phone (paired elsewhere, or reset): it is set up again, as a new band is.
        if (linked == Linked.BAND_AWAY && !setUpNow && peers.refresh()?.peers?.isEmpty() == true) {
            log.add("Band pairing: the band did not answer; setting it up again")
            setUp()
            setUpNow = true
            linked = viaManifest(glasses)
        }
        if (linked == Linked.YES) {
            peers.refresh()
            return
        }
        log.add("Band pairing: the glasses did not reach the band; pairing over svc41109")
        try {
            pairWith(glasses)
        } catch (stop: Stop) {
            // A band set up here before that never comes up has likely forgotten this phone (paired elsewhere, or
            // reset): it is set up again, as a new band is, and asked once more.
            if (setUpNow || !stop.unreached) throw stop
            log.add("Band pairing: the band did not come up; setting it up again")
            setUp()
            pairWith(glasses)
        }
        peers.refresh()
    }

    private enum class Linked { YES, NO, BAND_AWAY }

    /**
     * Visor's manifest with the glasses and the band on both, the rule the Meta app sends when a band moves to a pair
     * of glasses, then a wait for the glasses to hold the band: a lease they ask for, or the band connected in their
     * own list. They reach it only while worn.
     */
    private suspend fun viaManifest(glasses: String): Linked {
        val band = bands.forGlasses(glasses) ?: return Linked.NO
        val key = band.publicKey?.let(::rawPublicKey) ?: return Linked.NO
        val state = connection.state.value
        val address = (state.target?.link as? GlassesLink.Bluetooth)?.address ?: return Linked.NO
        val build = state.info?.build ?: return Linked.NO
        // Connected to the Display already: linked, and taking it away to install the manifest again could only lose it.
        if (peers.refresh()?.peers?.any { it.connected } == true) {
            log.add("Band pairing: the glasses already hold the band")
            return Linked.YES
        }
        _status.value = Status.Running(Step.MANIFEST)
        try {
            manifests.receiptKey(address, glasses)
            manifests.install(glasses, address, build)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.add("Band pairing: the manifest did not go in: ${error.message}")
            return if ((error as? KitException)?.reason == Manifests.BAND_AWAY) Linked.BAND_AWAY else Linked.NO
        }
        _status.value = Status.Running(Step.WEAR)
        return coroutineScope {
            val leased = launch(start = CoroutineStart.UNDISPATCHED) { lease.holder.drop(1).first { it == band.serial } }
            if (!toDisplay(CamControl.OVERRIDE_RULE, CamControl.bandRule(keyHint(key)), service = CamControl.SERVICE)) {
                leased.cancel()
                return@coroutineScope Linked.NO
            }
            log.add("Band pairing: the band is on the glasses' manifest; waiting for them to reach it")
            val held = withTimeoutOrNull(LINK_MS) {
                while (!leased.isCompleted && peers.refresh()?.peers?.any { it.connected } != true) delay(POLL_MS)
                true
            } == true
            leased.cancel()
            log.add(if (held) "Band pairing: the glasses hold the band" else "Band pairing: the glasses did not reach the band in ${LINK_MS / 1000} s")
            if (held) Linked.YES else Linked.NO
        }
    }

    private suspend fun pairWith(glasses: String) {
        val band = bands.forGlasses(glasses) ?: throw Stop(Reason.FAILED, "no band set up")
        // The key the Display's lease hints name; the PAIR hint must name it too.
        val key = band.publicKey?.let(::rawPublicKey) ?: throw Stop(Reason.FAILED, "no band key")
        val hint = keyHint(key)
        _status.value = Status.Running(Step.BAND)
        val display = peers.refresh() ?: throw noAnswer("glasses")
        if (BtPeer.JUST_WORKS !in display.methods || BtPeer.TOPOLOGY_SWITCHING !in display.capabilities) {
            throw Stop(Reason.UNSUPPORTED, "methods ${display.methods}, capabilities ${display.capabilities}")
        }
        log.add("Band pairing: ${band.serial} with $glasses")
        coroutineScope {
            val fromDisplay = Channel<SessionEvent>(Channel.UNLIMITED)
            val fromBand = Channel<SessionEvent>(Channel.UNLIMITED)
            launch(start = CoroutineStart.UNDISPATCHED) { connection.serviceData.collect { if (it.serviceId == BtPeer.SERVICE) fromDisplay.trySend(it) } }
            launch(start = CoroutineStart.UNDISPATCHED) { connection.errors.collect { if (it.serviceId == BtPeer.SERVICE) fromDisplay.trySend(it) } }
            launch(start = CoroutineStart.UNDISPATCHED) { link.peer.collect { fromBand.trySend(it) } }
            try {
                steps(display, hint, fromDisplay, fromBand)
            } finally {
                // In this order: holding the band again while still pairing would take it back from the Display.
                link.endPairing()
                link.hold()
                coroutineContext.cancelChildren()
            }
        }
    }

    private suspend fun steps(display: BtPeer.PairingInfo, hint: ByteArray, fromDisplay: Channel<SessionEvent>, fromBand: Channel<SessionEvent>) {
        if (!link.startPairing()) throw Stop(Reason.NO_ANSWER, "band", unreached = true)
        var band = bandInfo(fromBand)
        val session = UUID.randomUUID()
        val known = band.lists(display.identity) && display.lists(band.identity)
        toDisplay(BtPeer.TOPOLOGY_EVENT, BtPeer.topologyEvent(band.identity, hint, session, BtPeer.TOPOLOGY_START, if (known) BtPeer.TOPOLOGY_CONNECT else BtPeer.TOPOLOGY_PAIR_AND_CONNECT))
        if (known) return connect(band, hint, session, fromDisplay)

        // ALREADY_PAIRED: the band still has a bond. Both listing each other only need a connect; else the band is forced.
        var force = false
        var ready: BtPeer.PairingModeReady? = null
        while (ready == null) {
            when (val answer = ask(fromBand, BtPeer.PAIRING_MODE_READY, ANSWER_MS) { link.sendPeer(BtPeer.ENABLE_PAIRING_MODE, BtPeer.enablePairingMode(display.identity, force, display.rpa)) }) {
                is ServiceData -> ready = BtPeer.pairingModeReady(answer.payload)
                is DataXError -> {
                    if (answer.errorId != BtPeer.ALREADY_PAIRED || force) throw refused(answer)
                    log.add("Band pairing: the band is already paired")
                    band = bandInfo(fromBand)
                    if (band.lists(display.identity) && display.lists(band.identity)) return connect(band, hint, session, fromDisplay)
                    force = true
                }
                else -> throw Stop(Reason.NO_ANSWER, "band")
            }
        }
        val mode = checkNotNull(ready)
        if (mode.identity.isEmpty() || mode.advertising.isEmpty()) throw Stop(Reason.FAILED, "the band named no address")
        log.add("Band pairing: the band is in pairing mode")

        // The band takes one host: the phone lets go of it and sends it nothing more.
        _status.value = Status.Running(Step.PAIRING)
        link.release()
        link.endPairing()
        val paired = ask(
            fromDisplay, BtPeer.PAIRING_EVENT, BOND_MS,
            accept = { payload -> BtPeer.pairingEvent(payload).let { it.event == BtPeer.PAIRED && BtPeer.sameAddress(it.identity, mode.identity) } },
        ) { toDisplay(BtPeer.PAIR, BtPeer.pair(mode.identity, mode.advertising, hint)) }
        if (paired is DataXError) throw refused(paired)
        if (paired == null) throw noAnswer("glasses")
        toDisplay(BtPeer.TOPOLOGY_EVENT, BtPeer.topologyEvent(band.identity, hint, session, BtPeer.TOPOLOGY_END, BtPeer.TOPOLOGY_PAIR_AND_CONNECT))
    }

    /** CONNECT for a band the Display has bonded: the phone lets go of it first. */
    private suspend fun connect(band: BtPeer.PairingInfo, hint: ByteArray, session: UUID, fromDisplay: Channel<SessionEvent>) {
        _status.value = Status.Running(Step.CONNECTING)
        log.add("Band pairing: the glasses and the band know each other, connecting")
        link.release()
        link.endPairing()
        val connected = ask(
            fromDisplay, BtPeer.CONNECTION_EVENT, BOND_MS,
            accept = { payload -> BtPeer.connectionEvent(payload).let { it.event == BtPeer.CONNECTED && BtPeer.sameAddress(it.identity, band.identity) } },
        ) { toDisplay(BtPeer.CONNECT, BtPeer.connect(band.identity, hint)) }
        if (connected is DataXError) throw refused(connected)
        if (connected == null) throw noAnswer("glasses")
        toDisplay(BtPeer.TOPOLOGY_EVENT, BtPeer.topologyEvent(band.identity, hint, session, BtPeer.TOPOLOGY_END, BtPeer.TOPOLOGY_CONNECT))
    }

    private suspend fun bandInfo(fromBand: Channel<SessionEvent>): BtPeer.PairingInfo {
        val answer = ask(fromBand, BtPeer.BLE_PAIRING_INFO, ANSWER_MS) { link.sendPeer(BtPeer.GET_BLE_PAIRING_INFO, BtPeer.getPairingInfo()) }
        if (answer is DataXError) throw refused(answer)
        return (answer as? ServiceData)?.let { BtPeer.pairingInfo(it.payload) } ?: throw Stop(Reason.NO_ANSWER, "band")
    }

    private suspend fun setUp() {
        _status.value = Status.Running(Step.SETUP)
        setup.start()
        val done = setup.status.first { it is BandSetup.Status.Done || it is BandSetup.Status.Failed }
        if (done is BandSetup.Status.Failed) throw when (done.reason) {
            BandSetup.Reason.NOT_PAIRED -> Stop(Reason.NO_BAND)
            BandSetup.Reason.NO_ANSWER -> Stop(Reason.NO_ANSWER, "band")
            BandSetup.Reason.REFUSED -> Stop(Reason.BAND_REFUSED)
            BandSetup.Reason.WRONG_OWNER -> Stop(Reason.WRONG_OWNER)
            BandSetup.Reason.NO_GLASSES -> Stop(Reason.NOT_CONNECTED)
            BandSetup.Reason.BLUETOOTH_OFF, BandSetup.Reason.FAILED -> Stop(Reason.FAILED, done.reason.name)
        }
    }

    /** Drops what came before, sends, then waits for a [type] message [accept] takes, or an error frame. */
    private suspend fun ask(
        events: Channel<SessionEvent>,
        type: Int,
        millis: Long,
        accept: (ByteArray) -> Boolean = { true },
        send: () -> Boolean,
    ): SessionEvent? {
        while (events.tryReceive().isSuccess) {}
        if (!send()) return null
        return withTimeoutOrNull(millis) {
            var found: SessionEvent? = null
            while (found == null) {
                val event = events.receive()
                found = when {
                    event is ServiceData && event.messageType == type && runCatching { accept(event.payload) }.getOrDefault(false) -> event
                    event is DataXError && event.errorId != DATAX_OK -> event
                    else -> null
                }
            }
            found
        }
    }

    private fun toDisplay(type: Int, payload: ByteArray, service: Int = BtPeer.SERVICE): Boolean {
        if (connection.state.value.link != LinkState.READY) return false
        connection.submit { it.sendOnService(service, type, payload) }
        return true
    }

    private fun refused(error: DataXError) = Stop(Reason.REFUSED, "0x%04X".format(Locale.ROOT, error.errorId))

    private fun noAnswer(from: String) =
        Stop(if (connection.state.value.link == LinkState.READY) Reason.NO_ANSWER else Reason.NOT_CONNECTED, from)

    private companion object {
        const val ANSWER_MS = 8_000L
        const val BOND_MS = 60_000L
        // Long enough to put the glasses on; the Display scans for the band once it is worn.
        const val LINK_MS = 90_000L
        const val POLL_MS = 3_000L
        const val DATAX_OK = 0
    }
}
