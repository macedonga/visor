@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.macedonga.visor.shared

import com.macedonga.visor.core.transport.Sdu
import com.macedonga.visor.core.transport.Transport
import com.macedonga.visor.core.transport.TransportClosedException
import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBPeripheral
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSRunLoop
import platform.Foundation.NSStream
import platform.Foundation.NSStreamDelegateProtocol
import platform.Foundation.NSStreamEvent
import platform.Foundation.NSStreamEventEndEncountered
import platform.Foundation.NSStreamEventErrorOccurred
import platform.Foundation.NSStreamEventHasBytesAvailable
import platform.Foundation.NSStreamEventHasSpaceAvailable
import platform.darwin.NSObject

/** The glasses' usual PSM; Ray-Ban Meta Gen 2 names 128 in its PSM characteristic. */
const val META_LE_PSM: Int = 0x80

/**
 * The byte pipe under AirShield on an iPhone: an L2CAP channel over LE to the glasses this iPhone knows as
 * [identifier]. The channel is a byte stream both ways; the frames say their own length, as on Android.
 */
class LeTransport(private val identifier: String, private val psm: Int = META_LE_PSM) : Transport {

    @Volatile private var peripheral: CBPeripheral? = null
    @Volatile private var pump: StreamPump? = null
    @Volatile private var encrypted = false

    override suspend fun connect() {
        close()
        encrypted = false
        val target = Bluetooth.peripheral(identifier)
        peripheral = target
        Bluetooth.connect(target, CONNECT_MS) { why -> pump?.fail(why) }
        val channel = Bluetooth.openL2cap(target, psm, OPEN_MS)
        pump = withContext(Dispatchers.Main) { StreamPump(channel) }
    }

    override suspend fun send(frame: ByteArray) = open().write(frame)

    override suspend fun receive(): ByteArray = open().readSdu(encrypted)

    override fun handshakeDone() {
        encrypted = true
    }

    override fun close() {
        pump?.close()
        pump = null
        val target = peripheral ?: return
        peripheral = null
        CoroutineScope(Dispatchers.Main).launch { Bluetooth.disconnect(target) }
    }

    private fun open(): StreamPump = pump ?: throw TransportClosedException("not connected")

    private companion object {
        const val CONNECT_MS = 15_000L
        const val OPEN_MS = 10_000L
    }
}

/** The channel's two streams on the main run loop: bytes in to [readSdu], whole frames out from [write]. */
private class StreamPump(private val channel: CBL2CAPChannel) : NSObject(), NSStreamDelegateProtocol {

    private val input: NSInputStream = checkNotNull(channel.inputStream)
    private val output: NSOutputStream = checkNotNull(channel.outputStream)
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val space = Channel<Unit>(Channel.CONFLATED)
    private var buffer = ByteArray(0)

    init {
        for (stream in listOf<NSStream>(input, output)) {
            stream.delegate = this
            stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
            stream.open()
        }
    }

    override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
        when (handleEvent) {
            NSStreamEventHasBytesAvailable -> drain()
            NSStreamEventHasSpaceAvailable -> space.trySend(Unit)
            NSStreamEventEndEncountered -> fail("the glasses closed the link")
            NSStreamEventErrorOccurred -> fail(aStream.streamError?.localizedDescription ?: "the link failed")
        }
    }

    private fun drain() {
        val chunk = ByteArray(READ_CHUNK)
        while (input.hasBytesAvailable) {
            val read = chunk.usePinned { input.read(it.addressOf(0).reinterpret(), READ_CHUNK.convert()) }
            if (read <= 0) break
            incoming.trySend(chunk.copyOf(read.toInt()))
        }
    }

    fun fail(why: String) {
        incoming.close(TransportClosedException(why))
        space.close(TransportClosedException(why))
    }

    /** Exactly one frame, measured as [Sdu.length] measures it. */
    suspend fun readSdu(encrypted: Boolean): ByteArray {
        while (true) {
            if (buffer.size >= Sdu.MIN_HEAD) {
                val total = Sdu.length(buffer, encrypted)
                if (total != null && buffer.size >= total) {
                    val frame = buffer.copyOfRange(0, total)
                    buffer = buffer.copyOfRange(total, buffer.size)
                    return frame
                }
            }
            buffer += incoming.receiveCatching().getOrElse { throw it ?: TransportClosedException("the link went away while reading") }
        }
    }

    /** All of [frame], in one write when the stream has room for it. */
    suspend fun write(frame: ByteArray) = withContext(Dispatchers.Main) {
        var sent = 0
        while (sent < frame.size) {
            if (!output.hasSpaceAvailable) {
                space.receiveCatching().getOrElse { throw it ?: TransportClosedException("the link went away while sending") }
                continue
            }
            val wrote = frame.usePinned { output.write(it.addressOf(sent).reinterpret(), (frame.size - sent).convert()) }
            if (wrote < 0) throw TransportClosedException(output.streamError?.localizedDescription ?: "the link went away while sending")
            sent += wrote.toInt()
        }
    }

    fun close() {
        fail("closed")
        CoroutineScope(Dispatchers.Main).launch {
            for (stream in listOf<NSStream>(input, output)) {
                stream.close()
                stream.removeFromRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
                stream.delegate = null
            }
        }
    }

}

// Kotlin subclasses of NSObject may not have a companion with fields.
private const val READ_CHUNK = 8192
