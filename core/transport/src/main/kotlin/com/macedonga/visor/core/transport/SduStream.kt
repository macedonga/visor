package com.macedonga.visor.core.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Whole messages over a byte stream. */
class SduStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val wireLog: WireLog? = null,
) {

    fun send(frame: ByteArray) {
        wireLog?.trace(outbound = true, frame = frame)
        try {
            output.write(frame)
            output.flush()
        } catch (error: IOException) {
            throw TransportClosedException("the link went away while sending", error)
        }
    }

    /** Exactly one SDU. */
    fun receive(): ByteArray {
        var head = readExactly(Sdu.MIN_HEAD)
        var total = Sdu.length(head)
        if (total == null) {
            head += readExactly(Sdu.AIRSHIELD_HEADER - head.size)
            total = checkNotNull(Sdu.length(head)) {
                "an ${Sdu.AIRSHIELD_HEADER} byte head still could not be measured"
            }
        }
        val frame = head + readExactly(total - head.size)
        wireLog?.trace(outbound = false, frame = frame)
        return frame
    }

    private fun readExactly(count: Int): ByteArray {
        if (count == 0) return ByteArray(0)
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = try {
                input.read(out, filled, count - filled)
            } catch (error: IOException) {
                throw TransportClosedException("the link went away while reading", error)
            }
            // 0 or -1: the peer closed the link.
            if (read < 1) {
                throw TransportClosedException("the peer closed the link after $filled of $count bytes")
            }
            filled += read
        }
        return out
    }
}
