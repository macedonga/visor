package com.macedonga.visor.core.protocol

/** DataX 4-byte fixed header plus extension-tag codec. */
object Header {

    private const val TAG_SERVICE_ID = 1
    private const val TAG_MESSAGE_TYPE = 2
    private const val TAG_ERROR_ID = 3
    private const val TAG_MESSAGE_TYPE_CLOSED = 4

    /** Parse a DataX wire header from [buf]. */
    fun parse(buf: ByteArray): MessageInfo {
        if (buf.size < 4) {
            throw ProtocolFormatException("Buffer too short for fixed header: ${buf.size} < 4")
        }

        val b0 = buf[0].toInt() and 0xFF
        val b1 = buf[1].toInt() and 0xFF
        val b2 = buf[2].toInt() and 0xFF
        val b3 = buf[3].toInt() and 0xFF

        val hasExtension = (b0 and 0x80) != 0
        val payloadLen = ((b0 and 0x3F) shl 8) or b1
        val channelId = (b2 shl 8) or b3

        var serviceId = 0
        var messageType = 0
        var errorId = 0
        var setType = false
        var hasError = false
        var isClosed = false

        var headerLen = 4

        if (hasExtension) {
            var offset = 4
            val available = minOf(buf.size, 4 + payloadLen)
            while (offset + 4 <= available) {
                val tagByte = buf[offset].toInt() and 0xFF
                val continuation = (tagByte shr 7) and 1
                val tagId = tagByte and 0x7F
                // 16-bit big-endian value from bytes 2 and 3 of the tag.
                val value16 = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)

                when (tagId) {
                    TAG_SERVICE_ID -> {
                        serviceId = value16
                        setType = true
                        if (value16 == 0) isClosed = true
                    }

                    TAG_MESSAGE_TYPE -> {
                        messageType = value16
                        setType = true
                    }

                    TAG_ERROR_ID -> {
                        errorId = value16
                        hasError = true
                    }

                    TAG_MESSAGE_TYPE_CLOSED -> {
                        messageType = value16
                        isClosed = true
                    }
                    // else: unknown tag, skip
                }

                headerLen += 4
                offset += 4

                if (continuation == 0) break
            }
        }

        return MessageInfo(
            flags = HeaderFlags(setType = setType, hasError = hasError, isClosed = isClosed),
            length = payloadLen,
            channelId = channelId,
            serviceId = serviceId,
            messageType = messageType,
            errorId = errorId,
            headerLen = headerLen,
        )
    }

    /** Serialise a DataX header: the fixed 4 bytes plus extension tags. */
    fun build(
        channelId: Int,
        dataLen: Int,
        serviceId: Int? = null,
        messageType: Int? = null,
        errorId: Int? = null,
    ): ByteArray {
        // (tagId, value16), in order.
        val tags = ArrayList<Pair<Int, Int>>(3)

        if (serviceId != null) tags.add(TAG_SERVICE_ID to (serviceId and 0xFFFF))
        if (messageType != null) tags.add(TAG_MESSAGE_TYPE to (messageType and 0xFFFF))
        if (errorId != null) tags.add(TAG_ERROR_ID to (errorId and 0xFFFF))

        val extLen = tags.size * 4
        val payloadLen = extLen + dataLen

        require(payloadLen <= DATAX_MAX_MESSAGE) {
            "payload length $payloadLen exceeds $DATAX_MAX_MESSAGE"
        }

        val hasExtension = if (tags.isNotEmpty()) 1 else 0

        val out = ByteArray(4 + extLen)
        out[0] = ((hasExtension shl 7) or ((payloadLen shr 8) and 0x3F)).toByte()
        out[1] = (payloadLen and 0xFF).toByte()
        out[2] = ((channelId shr 8) and 0xFF).toByte()
        out[3] = (channelId and 0xFF).toByte()

        // Continuation bit on all but the last; each tag's second byte stays 0.
        tags.forEachIndexed { i, (tagId, value16) ->
            val at = 4 + 4 * i
            val continuation = if (i < tags.size - 1) 1 else 0
            out[at] = ((continuation shl 7) or (tagId and 0x7F)).toByte()
            out[at + 2] = ((value16 shr 8) and 0xFF).toByte()
            out[at + 3] = (value16 and 0xFF).toByte()
        }

        return out
    }
}

/** The header flags callers act on. */
data class HeaderFlags(
    val setType: Boolean = false,
    val hasError: Boolean = false,
    val isClosed: Boolean = false,
)

/** A parsed DataX message header. */
data class MessageInfo(
    val flags: HeaderFlags,
    /** The 14-bit payload length. It includes the extension tags. */
    val length: Int,
    /** The 16-bit channel id. Its high bit says who opened the channel. */
    val channelId: Int,
    val serviceId: Int = 0,
    val messageType: Int = 0,
    val errorId: Int = 0,
    /** 4 plus four per extension tag. */
    val headerLen: Int = 4,
) {
    /** Bytes this message occupies on the wire. It is `4 + length`, never `headerLen + length`. */
    val wireTotal: Int get() = 4 + length
}
