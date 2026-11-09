package com.macedonga.visor.core.session

import com.macedonga.visor.core.protocol.FrameType
import com.macedonga.visor.core.protocol.Framing
import com.macedonga.visor.core.protocol.StreamCommand
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** Where a [SubstreamRelay] is. */
sealed interface RelayState {
    /** The sub-streams are opening, or open with nothing across them yet. */
    data object Opening : RelayState

    /** Both devices have sent something across: their link inside is coming up or up. */
    data object Up : RelayState

    /** The relay stopped for good: [silent] when the devices never started, else their link kept dropping. */
    data class Failed(val silent: Boolean) : RelayState
}

/**
 * Joins two devices through this phone, as the Meta app's RelayedConnection does: an AirShield sub-stream on each link,
 * opened by the phone, with every frame of one passed to the other as it came. The two devices run
 * their own link inside, end to end; the phone reads none of it. With both peers on AirShield 0x11 or later the
 * sub-streams open to advertise and the peers' advertisements cross; otherwise both get a plain OPEN and start their
 * link setup at once. A side that loses its sub-stream takes the other's down too, and the phone opens both again until
 * five quick losses in a row (libairshield StreamSecurer::handleCommands, Meta AI 297 RetryingRelayedConnection).
 */
class SubstreamRelay(
    private val central: SecureLink,
    private val peripheral: SecureLink,
    private val log: (String) -> Unit,
) {
    private class Side(val link: SecureLink, val name: String) {
        val frames = Channel<ByteArray>(Channel.UNLIMITED)
        val sent = AtomicInteger(0)
    }

    private val _state = MutableStateFlow<RelayState>(RelayState.Opening)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val a = Side(central, "Display")
    private val b = Side(peripheral, "band")

    /** True when both peers announced AirShield 0x11 or later: their sub-streams open to advertise. */
    val advertising: Boolean get() = central.peerAirshieldVersion >= ADVERTISING_VERSION && peripheral.peerAirshieldVersion >= ADVERTISING_VERSION

    fun start(scope: CoroutineScope): Job {
        a.link.substreams = { a.frames.trySend(it) }
        b.link.substreams = { b.frames.trySend(it) }
        val job = scope.launch {
            val lost = Channel<Unit>(Channel.CONFLATED)
            launch { pass(a, b, lost) }
            launch { pass(b, a, lost) }
            var tries = 0
            while (true) {
                val opened = System.nanoTime()
                a.sent.set(0)
                b.sent.set(0)
                _state.value = RelayState.Opening
                // The other side's close of the last round may still be queued.
                while (lost.tryReceive().isSuccess) Unit
                open()
                // Both devices start their link at once, so silence means they will not.
                if (!firstTraffic(lost)) {
                    closeBoth()
                    val why = "the Display and the band did not start their link through the phone"
                    log("Relay: $why in ${FIRST_TRAFFIC_MS / 1000} s")
                    _state.value = RelayState.Failed(silent = true)
                    return@launch
                }
                lost.receive()
                closeBoth()
                // A pair that held a while starts the count again; five quick losses end the relay.
                tries = if (System.nanoTime() - opened > HELD_NS) 1 else tries + 1
                if (tries >= MAX_OPENS) {
                    log("Relay: gave up after $MAX_OPENS quick losses")
                    _state.value = RelayState.Failed(silent = false)
                    return@launch
                }
                delay(REOPEN_MS)
            }
        }
        job.invokeOnCompletion {
            a.link.substreams = null
            b.link.substreams = null
            a.frames.close()
            b.frames.close()
        }
        return job
    }

    private suspend fun open() {
        val code = if (advertising) StreamCommand.OPEN_TO_ADVERTISE else StreamCommand.OPEN
        log("Relay: opening sub-stream $STREAM on both links (${code.name}; AirShield ${hex(central.peerAirshieldVersion)} and ${hex(peripheral.peerAirshieldVersion)})")
        a.link.sendRaw(command(code))
        b.link.sendRaw(command(code))
    }

    // True once both sides sent something across in time; false on silence or a side that closed first.
    private suspend fun firstTraffic(lost: Channel<Unit>): Boolean = coroutineScope {
        val up = async { _state.first { it is RelayState.Up } }
        val result = withTimeoutOrNull(FIRST_TRAFFIC_MS) {
            select<Boolean> {
                up.onAwait { true }
                lost.onReceive { false }
            }
        } ?: false
        up.cancel()
        result
    }

    private suspend fun closeBoth() {
        runCatching { a.link.sendRaw(command(StreamCommand.LOST)) }
        runCatching { b.link.sendRaw(command(StreamCommand.LOST)) }
    }

    // Every frame on our sub-stream goes across with its header's stream id kept; a closing command ends the pair.
    private suspend fun pass(from: Side, to: Side, lost: Channel<Unit>) {
        for (sdu in from.frames) {
            val header = sdu[0].toInt() and 0xFF
            if (header and Framing.STREAM_MASK != STREAM) continue
            if (header and Framing.TYPE_MASK == FrameType.COMMAND.value && sdu.size > 1) {
                val code = sdu[1].toInt() and 0xFF
                if (code in CLOSING) {
                    log("Relay: the ${from.name} closed its sub-stream (${StreamCommand.entries.firstOrNull { it.value == code }?.name ?: code})")
                    lost.trySend(Unit)
                    continue
                }
            }
            runCatching { to.link.sendRaw(sdu) }.onFailure { lost.trySend(Unit) }
            from.sent.incrementAndGet()
            if (a.sent.get() > 0 && b.sent.get() > 0 && _state.value == RelayState.Opening) {
                _state.value = RelayState.Up
                log("Relay: the Display and the band talk through the phone")
            }
        }
    }

    private fun command(code: StreamCommand): ByteArray =
        byteArrayOf((FrameType.COMMAND.value or STREAM).toByte(), code.value.toByte())

    private fun hex(version: Int) = "0x" + version.toString(16)

    private companion object {
        // The first id of a relay's range (1 to 0x1F, libairshield Substreams::allocate), where the Meta app's first relay
        // lands on each link. The same on both links, so a frame crosses as it came.
        const val STREAM = 1
        const val HELD_NS = 60_000_000_000L
        const val ADVERTISING_VERSION = 0x11
        const val MAX_OPENS = 5
        const val FIRST_TRAFFIC_MS = 30_000L
        const val REOPEN_MS = 2_000L
        val CLOSING = setOf(StreamCommand.LOST.value, StreamCommand.REJECTED.value, StreamCommand.NO_ROUTE.value)
    }
}
