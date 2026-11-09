package com.macedonga.visor.core.session

import com.macedonga.visor.core.protocol.FrameType
import com.macedonga.visor.core.protocol.Framing
import com.macedonga.visor.core.protocol.StreamCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    }

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
                open()
                lost.receive()
                closeBoth()
                // A pair that held a while starts the count again; five quick losses end the relay.
                tries = if (System.nanoTime() - opened > HELD_NS) 1 else tries + 1
                if (tries >= MAX_OPENS) return@launch log("Relay: gave up after $MAX_OPENS quick losses")
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
        const val REOPEN_MS = 2_000L
        val CLOSING = setOf(StreamCommand.LOST.value, StreamCommand.REJECTED.value, StreamCommand.NO_ROUTE.value)
    }
}
