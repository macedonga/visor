package com.macedonga.visor.core.protocol

/** Thrown when bytes that came off the link cannot be read as the format claims. */
class ProtocolFormatException(message: String) : Exception(message)

/**
 * Minimal protobuf-lite primitives: enough to build and parse the link-setup and service messages
 * without a generated stub.
 */
object Wire {

    private val EMPTY = ByteArray(0)

    // --- encode ------------------------------------------------------------

    fun encodeVarint(value: Long): ByteArray {
        require(value >= 0) { "varint must be non-negative, got $value" }
        val out = ArrayList<Byte>(10)
        var remaining = value
        while (true) {
            val b = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            out.add((b or if (remaining != 0L) 0x80 else 0).toByte())
            if (remaining == 0L) return out.toByteArray()
        }
    }

    private fun tag(fieldNumber: Int, wireType: Int): ByteArray =
        encodeVarint(((fieldNumber.toLong() shl 3) or wireType.toLong()))

    /**
     * A varint whose [value] is read as UNSIGNED, so a `Long` with the high bit set means 2^63 or
     * more rather than a negative number.
     */
    fun encodeVarintUnsigned(value: Long): ByteArray {
        val out = ArrayList<Byte>(10)
        var remaining = value
        while (true) {
            val b = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            out.add((b or if (remaining != 0L) 0x80 else 0).toByte())
            if (remaining == 0L) return out.toByteArray()
        }
    }

    fun fieldVarint(fieldNumber: Int, value: Long): ByteArray =
        tag(fieldNumber, 0) + encodeVarint(value)

    /** As [fieldVarint], but [value] is read as unsigned. See [encodeVarintUnsigned]. */
    fun fieldVarintUnsigned(fieldNumber: Int, value: Long): ByteArray =
        tag(fieldNumber, 0) + encodeVarintUnsigned(value)

    fun fieldBytes(fieldNumber: Int, value: ByteArray): ByteArray =
        tag(fieldNumber, 2) + encodeVarint(value.size.toLong()) + value

    /** A float as protobuf writes it: fixed32, little-endian. */
    fun fieldFloat(fieldNumber: Int, value: Float): ByteArray {
        val bits = java.lang.Float.floatToRawIntBits(value)
        return tag(fieldNumber, 5) + ByteArray(4) { i -> (bits ushr (8 * i)).toByte() }
    }

    /** A double as protobuf writes it: fixed64, little-endian. */
    fun fieldDouble(fieldNumber: Int, value: Double): ByteArray {
        val bits = java.lang.Double.doubleToRawLongBits(value)
        return tag(fieldNumber, 1) + ByteArray(8) { i -> (bits ushr (8 * i)).toByte() }
    }

    // --- decode ------------------------------------------------------------

    /** A varint and the offset just past it. */
    data class VarintResult(val value: Long, val offset: Int)

    /**
     * DIVERGENCE: Python integers are arbitrary precision, so `decode_varint` reads a varint of any
     * width exactly.
     */
    fun decodeVarint(data: ByteArray, offset: Int): VarintResult {
        var result = 0L
        var shift = 0
        var at = offset
        while (true) {
            if (at >= data.size) throw ProtocolFormatException("truncated varint")
            val b = data[at].toInt() and 0xFF
            at += 1
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return VarintResult(result, at)
            shift += 7
        }
    }

    /** A parsed protobuf message: field number to the list of values read for it, in wire order. */
    class Fields internal constructor() {

        private val byField = LinkedHashMap<Int, MutableList<Pair<Int, Any>>>()

        internal fun add(fieldNumber: Int, wireType: Int, value: Any) {
            byField.getOrPut(fieldNumber) { mutableListOf() }.add(wireType to value)
        }

        /** The first length-delimited value for [fn], or [default]. */
        fun bytes(fn: Int, default: ByteArray = EMPTY): ByteArray {
            byField[fn]?.forEach { (wireType, value) ->
                if (wireType == 2) return value as ByteArray
            }
            return default
        }

        /** The first varint value for [fn], or [default]. */
        fun varint(fn: Int, default: Long = 0): Long {
            byField[fn]?.forEach { (wireType, value) ->
                if (wireType == 0) return value as Long
            }
            return default
        }

        /** The first fixed32 value for [fn] as a float, or [default]. */
        fun float(fn: Int, default: Float = 0f): Float {
            byField[fn]?.forEach { (wireType, value) ->
                if (wireType == 5) return Float.fromBits(value as Int)
            }
            return default
        }

        /** The first fixed64 value for [fn] as a double, or [default]. */
        fun double(fn: Int, default: Double = 0.0): Double {
            byField[fn]?.forEach { (wireType, value) ->
                if (wireType == 1) return Double.fromBits(value as Long)
            }
            return default
        }

        fun repeatedBytes(fn: Int): List<ByteArray> =
            byField[fn].orEmpty().filter { it.first == 2 }.map { it.second as ByteArray }

        fun has(fn: Int): Boolean = byField.containsKey(fn)

        /** Sorted, which is what the reference returns, not wire order. */
        fun fieldNumbers(): List<Int> = byField.keys.sorted()
    }

    private fun littleEndian(data: ByteArray, at: Int, size: Int): Long {
        var v = 0L
        for (i in size - 1 downTo 0) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
        return v
    }

    /** Parse a flat protobuf message: varint, fixed and length-delimited fields. */
    fun parse(data: ByteArray): Fields {
        val fields = Fields()
        var offset = 0
        val n = data.size
        while (offset < n) {
            val key = decodeVarint(data, offset)
            offset = key.offset
            val fieldNumberLong = key.value ushr 3
            if (fieldNumberLong > Int.MAX_VALUE) {
                // DIVERGENCE: Python carries an arbitrarily large field number.
                throw ProtocolFormatException("field number $fieldNumberLong out of range")
            }
            val fieldNumber = fieldNumberLong.toInt()
            when ((key.value and 0x07L).toInt()) {
                0 -> {
                    val value = decodeVarint(data, offset)
                    offset = value.offset
                    fields.add(fieldNumber, 0, value.value)
                }

                2 -> {
                    val length = decodeVarint(data, offset)
                    offset = length.offset
                    // Python slices with an arbitrary-precision length: one that runs past the end
                    // yields the bytes that are there, and the cursor lands past the end so the
                    // loop stops.
                    val remaining = (n - offset).toLong()
                    val fits = length.value in 0..remaining
                    val take = if (fits) length.value.toInt() else remaining.toInt()
                    fields.add(fieldNumber, 2, data.copyOfRange(offset, offset + take))
                    offset = if (fits) offset + take else n
                }

                5 -> {   // fixed32, little-endian
                    if (offset + 4 <= n) fields.add(fieldNumber, 5, littleEndian(data, offset, 4).toInt())
                    offset += 4
                }
                1 -> {   // fixed64, little-endian
                    if (offset + 8 <= n) fields.add(fieldNumber, 1, littleEndian(data, offset, 8))
                    offset += 8
                }
                else -> throw ProtocolFormatException(
                    "unsupported wire type ${key.value and 0x07L} for field $fieldNumber"
                )
            }
        }
        return fields
    }

    // `wire.py` also has `iter_fields`, a debugging helper that reaches into Fields' private
    // storage to print a capture in the Python REPL.
}
