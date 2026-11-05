package com.macedonga.visor.core.identity

import java.math.BigInteger

/** Just enough DER to walk the two structures this module meets, and no more. */
internal class DerReader(private val data: ByteArray) {

    private var position = 0

    /** The contents of the next element, which must carry [expectedTag]. */
    fun read(expectedTag: Int): ByteArray {
        if (position >= data.size) throw DerException("the encoding is truncated")
        val tag = data[position++].toInt() and 0xFF
        if (tag != expectedTag) throw DerException("the encoding is not in the expected format")
        var length = nextByte()
        if (length and 0x80 != 0) {
            val count = length and 0x7F
            // A definite length of more than four bytes is a value no structure here has,
            // and an indefinite length, count 0, is not DER at all.
            if (count == 0 || count > 4) {
                throw DerException("the encoding is not in the expected format")
            }
            length = 0
            repeat(count) { length = (length shl 8) or nextByte() }
        }
        if (length < 0 || position + length > data.size) {
            throw DerException("the encoding is truncated")
        }
        return data.copyOfRange(position, position + length).also { position += length }
    }

    /** The next element as a non-negative integer. */
    fun readInteger(): BigInteger = BigInteger(1, read(INTEGER))

    private fun nextByte(): Int {
        if (position >= data.size) throw DerException("the encoding is truncated")
        return data[position++].toInt() and 0xFF
    }

    companion object {
        const val INTEGER = 0x02
        const val OCTET_STRING = 0x04
        const val SEQUENCE = 0x30
    }
}

internal class DerException(message: String) : Exception(message)
