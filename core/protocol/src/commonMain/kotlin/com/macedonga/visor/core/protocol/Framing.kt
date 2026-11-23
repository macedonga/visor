package com.macedonga.visor.core.protocol

/** AirShield substream framing: the one-byte outer header on each CoC frame. */
object Framing {

    const val STREAM_MASK: Int = 0x3F
    const val TYPE_MASK: Int = 0xC0

    fun pack(frameType: FrameType, streamId: Int, body: ByteArray): ByteArray {
        require(streamId in 0..STREAM_MASK) { "stream id $streamId out of range 0..$STREAM_MASK" }
        return byteArrayOf(((frameType.value and TYPE_MASK) or (streamId and STREAM_MASK)).toByte()) + body
    }

    fun unpack(data: ByteArray): Frame {
        if (data.isEmpty()) throw ProtocolFormatException("empty frame")
        val header = data[0].toInt() and 0xFF
        return Frame(
            type = header and TYPE_MASK,
            streamId = header and STREAM_MASK,
            body = data.copyOfRange(1, data.size),
        )
    }
}

/** One unpacked substream frame. */
class Frame(val type: Int, val streamId: Int, val body: ByteArray)
