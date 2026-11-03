package com.macedonga.visor.band

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.keyHint
import com.macedonga.visor.core.identity.rawPublicKey
import com.macedonga.visor.core.protocol.BtPeer
import com.macedonga.visor.core.session.DataXError
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.core.session.SessionEvent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Pairs the Neural Band with a Display set up here, over svc41109 on both links; [BandSetup] runs first when it has no band. */
@Singleton
class BandPair @Inject constructor(
    private val connection: GlassesConnection,
    private val bands: Bands,
    private val link: BandLink,
    private val peers: BandPeers,
    private val setup: BandSetup,
    private val log: EventLog,
) {
    sealed interface Status {
        data object Idle : Status
        data class Running(val step: Step) : Status
        data object Done : Status
        data class Failed(val reason: Reason, val detail: String = "") : Status
    }

    enum class Step { SETUP, BAND, PAIRING, CONNECTING }

    enum class Reason { NOT_CONNECTED, NO_BAND, NO_ANSWER, BAND_REFUSED, WRONG_OWNER, UNSUPPORTED, REFUSED, FAILED }

    private class Stop(val reason: Reason, val detail: String = "") : Exception(detail)

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

    /** Runs the pairing and returns how it ended; a second caller waits for the one running. */
    suspend fun pair(): Status {
        if (!lock.tryLock()) return lock.withLock { _status.value }
        try {
            _status.value = Status.Running(Step.BAND)
            _status.value = try {
                run()
                log.add("Band pairing: done")
                Status.Done
            } catch (stop: Stop) {
                log.add("Band pairing failed: ${stop.reason} ${stop.detail}")
                Status.Failed(stop.reason, stop.detail)
            } catch (cancelled: CancellationException) {
                _status.value = Status.Idle
                throw cancelled
            } catch (error: Exception) {
                log.add("Band pairing failed: $error")
                Status.Failed(Reason.FAILED, error.message ?: error.javaClass.simpleName)
            }
            return _status.value
        } finally {
            lock.unlock()
        }
    }

    private suspend fun run() {
        if (connection.state.value.link != LinkState.READY) throw Stop(Reason.NOT_CONNECTED)
        val glasses = connection.pair.value ?: throw Stop(Reason.NOT_CONNECTED)
        if (bands.forGlasses(glasses) == null) setUp()
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
        peers.refresh()
    }

    private suspend fun steps(display: BtPeer.PairingInfo, hint: ByteArray, fromDisplay: Channel<SessionEvent>, fromBand: Channel<SessionEvent>) {
        if (!link.startPairing()) throw Stop(Reason.NO_ANSWER, "band")
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
            else -> Stop(Reason.FAILED, done.detail.ifEmpty { done.reason.name })
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

    private fun toDisplay(type: Int, payload: ByteArray): Boolean {
        if (connection.state.value.link != LinkState.READY) return false
        connection.submit { it.sendOnService(BtPeer.SERVICE, type, payload) }
        return true
    }

    private fun refused(error: DataXError) = Stop(Reason.REFUSED, "0x%04X".format(Locale.ROOT, error.errorId))

    private fun noAnswer(from: String) =
        Stop(if (connection.state.value.link == LinkState.READY) Reason.NO_ANSWER else Reason.NOT_CONNECTED, from)

    private companion object {
        const val ANSWER_MS = 8_000L
        const val BOND_MS = 60_000L
        const val DATAX_OK = 0
    }
}
