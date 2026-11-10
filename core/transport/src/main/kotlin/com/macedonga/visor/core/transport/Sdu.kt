package com.macedonga.visor.core.transport

/** How long the message starting at these bytes is. */
object Sdu {

    /** The DataX fixed header: two bytes of flags and length, two of channel id. */
    const val DATAX_HEADER: Int = 4

    /** The AirShield DATA header: the frame byte, an 8 byte tag, and the block count. */
    const val AIRSHIELD_HEADER: Int = 10

    /** A long COMMAND's head: the frame byte, the code with bit 7 set, a two byte length and a zero byte. */
    const val LONG_COMMAND_HEADER: Int = 5

    const val AES_BLOCK: Int = 16

    /** Below this there is nothing to read the framing out of; also a short COMMAND's whole length. */
    const val MIN_HEAD: Int = 2

    /** Set on the first byte of a DataX message, because it always carries a tag, and of a COMMAND frame. */
    const val DATAX_BIT: Int = 0x80

    const val TYPE_MASK: Int = 0xC0

    /** A sub-stream's PLAINTEXT frame, the inner link setup of a relay: `[stream][length - 1]` and length bytes. */
    private const val PLAINTEXT_HEADER: Int = 3

    private const val LENGTH_HIGH_MASK: Int = 0x3F

    private const val LONG_COMMAND: Int = 0x80

    /**
     * The total length of the message starting at [head], or null if [head] is too short to say yet. Bit 7 means a
     * bare DataX message until the handshake is done, and a COMMAND frame after it, when only those and DATA cross.
     * A long COMMAND, a relay's advertisement, is `[0x80|stream][0x80][length, 2 bytes][0x00]` and length bytes, as
     * the Display and the band send them; the length's byte order past 255 is not measured. Type 00 is a sub-stream's
     * PLAINTEXT frame, as libairshield's Substreams::frameSize measures it.
     */
    fun length(head: ByteArray, encrypted: Boolean = false): Int? {
        if (head.size < MIN_HEAD) return null
        val first = head[0].toInt() and 0xFF
        if (first and DATAX_BIT != 0) {
            if (!encrypted) return DATAX_HEADER + (((first and LENGTH_HIGH_MASK) shl 8) or (head[1].toInt() and 0xFF))
            if (head[1].toInt() and LONG_COMMAND == 0) return MIN_HEAD
            if (head.size < LONG_COMMAND_HEADER) return null
            return LONG_COMMAND_HEADER + (((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF))
        }
        if (first and TYPE_MASK == 0) return PLAINTEXT_HEADER + (head[1].toInt() and 0xFF)
        if (head.size < AIRSHIELD_HEADER) return null
        return AIRSHIELD_HEADER + AES_BLOCK * ((head[9].toInt() and 0xFF) + 1)
    }

    /** How many bytes [length] needs to measure the message starting at [head]. */
    fun headSize(head: ByteArray): Int =
        if ((head[0].toInt() and DATAX_BIT) != 0) LONG_COMMAND_HEADER else AIRSHIELD_HEADER
}
