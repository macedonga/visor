package com.macedonga.visor.core.protocol

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Thrift compact protocol reader and writer. */
object Thrift {

    const val STOP = 0
    const val TRUE = 1
    const val FALSE = 2
    const val I8 = 3
    const val I16 = 4
    const val I32 = 5
    const val I64 = 6
    const val DOUBLE = 7
    const val BIN = 8
    const val LIST = 9
    const val SET = 10
    const val MAP = 11
    const val STRUCT = 12
    // fbthrift's compact protocol only: 4 bytes, big-endian.
    const val FLOAT = 13

    // --- read --------------------------------------------------------------

    /** Zigzag decode. The varint is unsigned, so the shift is logical, not arithmetic. */
    private fun zz(n: Long): Long = (n ushr 1) xor -(n and 1L)

    private class FieldHead(val fid: Int, val type: Int, val offset: Int)

    /** The struct field header at [at], or null at STOP or the end of [b]. */
    private fun fieldHead(b: ByteArray, at: Int, lastFid: Int): FieldHead? {
        if (at >= b.size) return null
        val h = b[at].toInt() and 0xFF
        if (h == STOP) return null
        val delta = h shr 4
        val type = h and 0x0F
        if (delta != 0) return FieldHead(lastFid + delta, type, at + 1)
        val v = Wire.decodeVarint(b, at + 1)
        return FieldHead(zz(v.value).toInt(), type, v.offset)
    }

    private class CollectionHeader(val size: Int, val etype: Int, val offset: Int)

    private fun collectionHeader(b: ByteArray, start: Int): CollectionHeader {
        var i = start
        var size = (b[i].toInt() and 0xFF) shr 4
        val etype = b[i].toInt() and 0x0F
        i += 1
        if (size == 15) {
            val v = Wire.decodeVarint(b, i)
            // A count above 2^31 narrows to a negative Int, and `repeat` on a negative count
            // silently does nothing, which would read a malformed frame as an empty collection.
            if (v.value !in 0..Int.MAX_VALUE.toLong()) {
                throw ProtocolFormatException("collection size ${v.value} out of range at ${v.offset}")
            }
            size = v.value.toInt()
            i = v.offset
        }
        return CollectionHeader(size, etype, i)
    }

    private class MapHeader(val size: Int, val ktype: Int, val vtype: Int, val offset: Int)

    private fun mapHeader(b: ByteArray, start: Int): MapHeader {
        val v = Wire.decodeVarint(b, start)
        if (v.value !in 0..Int.MAX_VALUE.toLong()) {
            throw ProtocolFormatException("map size ${v.value} out of range at ${v.offset}")
        }
        val size = v.value.toInt()
        if (size == 0) return MapHeader(0, 0, 0, v.offset)
        val kv = b[v.offset].toInt() and 0xFF
        return MapHeader(size, kv shr 4, kv and 0x0F, v.offset + 1)
    }

    private class Read(val value: Any?, val offset: Int)

    /** Strict UTF-8, or null so the caller keeps the bytes. */
    private fun decodeUtf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** One Thrift value of type [t], and the index just past it. */
    private fun readValue(b: ByteArray, start: Int, t: Int): Read {
        var i = start
        when (t) {
            TRUE -> return Read(true, i)
            FALSE -> return Read(false, i)
            // I8 reads unsigned, 0 to 255.
            I8 -> return Read(b[i].toInt() and 0xFF, i + 1)
            I16, I32, I64 -> {
                val v = Wire.decodeVarint(b, i)
                return Read(zz(v.value), v.offset)
            }

            DOUBLE -> {
                val value = ByteBuffer.wrap(b, i, 8).order(ByteOrder.LITTLE_ENDIAN).double
                return Read(value, i + 8)
            }

            FLOAT -> return Read(ByteBuffer.wrap(b, i, 4).order(ByteOrder.BIG_ENDIAN).float.toDouble(), i + 4)

            BIN -> {
                val len = Wire.decodeVarint(b, i)
                i = len.offset
                // A length past the end takes what is there.
                val remaining = (b.size - i).toLong()
                val fits = len.value in 0..remaining
                val take = if (fits) len.value.toInt() else remaining.toInt()
                val v = b.copyOfRange(i, i + take)
                i = if (fits) i + take else b.size
                return Read(decodeUtf8OrNull(v) ?: v, i)
            }

            LIST, SET -> {
                val header = collectionHeader(b, i)
                i = header.offset
                // No capacity hint: a declared count is peer controlled, and reserving it turns a
                // malformed frame into an OutOfMemoryError.
                val out = ArrayList<Any?>()
                repeat(header.size) {
                    val read = readElement(b, i, header.etype)
                    i = read.offset
                    out.add(read.value)
                }
                return Read(out, i)
            }

            MAP -> {
                val header = mapHeader(b, i)
                i = header.offset
                val out = LinkedHashMap<Any, Any?>()   // no capacity hint, see LIST above
                repeat(header.size) {
                    val k = readElement(b, i, header.ktype)
                    i = k.offset
                    val v = readElement(b, i, header.vtype)
                    i = v.offset
                    out[mapKey(k.value)] = v.value
                }
                return Read(out, i)
            }

            STRUCT -> {
                val read = readStructUnguarded(b, i)
                return Read(read.fields, read.offset)
            }
        }
        throw ProtocolFormatException("unknown thrift type $t at offset $start")
    }

    /**
     * A value inside a list, set or map. A bool there is a byte of its own (1 true, 2 false), not folded
     * into a field header as in a struct.
     */
    private fun readElement(b: ByteArray, start: Int, t: Int): Read =
        if (t == TRUE || t == FALSE) Read(b[start].toInt() == TRUE, start + 1) else readValue(b, start, t)

    /** Binary keys that are not UTF-8 become hex. */
    private fun mapKey(value: Any?): Any = when (value) {
        is String, is Int, is Long -> value
        is ByteArray -> value.toHexString()
        else -> value.toString()
    }

    /**
     * Run a read and convert the ways a truncated buffer fails into the one exception this module
     * declares for malformed wire data.
     */
    private inline fun <T> guardingFormat(what: String, body: () -> T): T = try {
        body()
    } catch (e: IndexOutOfBoundsException) {
        throw ProtocolFormatException("truncated $what: ${e.message}")
    } catch (e: BufferUnderflowException) {
        throw ProtocolFormatException("truncated $what: ${e.message}")
    }

    /** A parsed struct and the index just past its STOP byte. */
    private class StructResult(val fields: Map<Int, Any?>, val offset: Int)

    private fun readStructUnguarded(b: ByteArray, offset: Int): StructResult {
        val out = LinkedHashMap<Int, Any?>()
        var fid = 0
        var i = offset
        while (true) {
            val head = fieldHead(b, i, fid) ?: return StructResult(out, if (i < b.size) i + 1 else i)
            fid = head.fid
            val read = readValue(b, head.offset, head.type)
            i = read.offset
            out[fid] = read.value
        }
    }

    fun loads(b: ByteArray): Map<Int, Any?> = guardingFormat("struct") { readStructUnguarded(b, 0).fields }

    /** The bytes of top-level struct field [fid] as written, to pass a sub-struct on unread. */
    fun rawStruct(b: ByteArray, fid: Int): ByteArray? = guardingFormat("struct") { rawStructUnguarded(b, fid) }

    private fun rawStructUnguarded(b: ByteArray, fid: Int): ByteArray? {
        var at = 0
        var cur = 0
        while (true) {
            val head = fieldHead(b, at, cur) ?: return null
            cur = head.fid
            val end = readValue(b, head.offset, head.type).offset
            if (cur == fid) return if (head.type == STRUCT) b.copyOfRange(head.offset, end) else null
            at = end
        }
    }

    // --- write -------------------------------------------------------------

    /** Zigzag a 32-bit value and write it as a varint. */
    internal fun zigzagVarint32(value: Int): ByteArray =
        Wire.encodeVarintUnsigned((value.toLong() shl 1) xor (value.toLong() shr 31))

    /** Zigzag a 64-bit value and write it as a varint. */
    internal fun zigzagVarint64(value: Long): ByteArray =
        Wire.encodeVarintUnsigned((value shl 1) xor (value shr 63))

    /** Zigzag a 16-bit value, used only for the long-form field header. */
    internal fun zigzagVarint16(value: Int): ByteArray =
        Wire.encodeVarintUnsigned((value.toLong() shl 1) xor (value.toLong() shr 15))

    // --- patching ----------------------------------------------------------

    private class Span(val lengthStart: Int, val dataEnd: Int, val value: ByteArray)

    /**
     * Walk a struct recording the exact byte range of every BIN field, so ranges can be spliced
     * without re-encoding the whole message.
     */
    private fun scanStrings(b: ByteArray, start: Int, out: MutableList<Span>): Int {
        var i = start
        var fid = 0
        while (true) {
            val head = fieldHead(b, i, fid) ?: return if (i < b.size) i + 1 else i
            fid = head.fid
            i = scanValue(b, head.offset, head.type, out)
        }
    }

    private fun scanElement(b: ByteArray, start: Int, t: Int, out: MutableList<Span>): Int =
        if (t == TRUE || t == FALSE) start + 1 else scanValue(b, start, t, out)

    private fun scanValue(b: ByteArray, start: Int, t: Int, out: MutableList<Span>): Int {
        var i = start
        when (t) {
            TRUE, FALSE -> return i
            I8 -> return i + 1
            I16, I32, I64 -> return Wire.decodeVarint(b, i).offset
            DOUBLE -> return i + 8
            FLOAT -> return i + 4
            BIN -> {
                val lengthStart = i
                val len = Wire.decodeVarint(b, i)
                i = len.offset
                // Clamped for the same reason as the reader, and so the scan cursor cannot move
                // backwards.
                val remaining = (b.size - i).toLong()
                val fits = len.value in 0..remaining
                val take = if (fits) len.value.toInt() else remaining.toInt()
                val end = i + take
                out.add(Span(lengthStart, end, b.copyOfRange(i, end)))
                return end
            }

            LIST, SET -> {
                val header = collectionHeader(b, i)
                i = header.offset
                repeat(header.size) { i = scanElement(b, i, header.etype, out) }
                return i
            }

            MAP -> {
                val header = mapHeader(b, i)
                i = header.offset
                repeat(header.size) {
                    i = scanElement(b, i, header.ktype, out)
                    i = scanElement(b, i, header.vtype, out)
                }
                return i
            }

            STRUCT -> return scanStrings(b, i, out)
        }
        throw ProtocolFormatException("bad element type $t at $start")
    }

    /**
     * Return [raw] with every BIN field whose exact value is a key of [mapping] replaced by the
     * corresponding value, with the length varints fixed up.
     */
    fun replaceStrings(raw: ByteArray, mapping: Map<String, ByteArray>): ByteArray = guardingFormat("struct") {
        val spans = ArrayList<Span>()
        scanStrings(raw, 0, spans)
        var out = raw
        for (span in spans.sortedByDescending { it.lengthStart }) {
            val key = String(span.value, Charsets.UTF_8)
            val replacement = mapping[key] ?: continue
            out = out.copyOfRange(0, span.lengthStart) +
                Wire.encodeVarintUnsigned(replacement.size.toLong()) +
                replacement +
                out.copyOfRange(minOf(span.dataEnd, out.size), out.size)
        }
        out
    }

    /** Flip a top-level boolean field in place. */
    fun setBool(raw: ByteArray, fid: Int, value: Boolean): ByteArray = guardingFormat("struct") {
        setBoolUnguarded(raw, fid, value)
    }

    private fun setBoolUnguarded(raw: ByteArray, fid: Int, value: Boolean): ByteArray {
        var at = 0
        var cur = 0
        while (true) {
            val head = fieldHead(raw, at, cur) ?: return raw
            cur = head.fid
            if (cur == fid && (head.type == TRUE || head.type == FALSE)) {
                val want = if (value) TRUE else FALSE
                if (head.type == want) return raw
                return raw.copyOfRange(0, at) +
                    byteArrayOf(((raw[at].toInt() and 0xF0) or want).toByte()) +
                    raw.copyOfRange(at + 1, raw.size)
            }
            at = readValue(raw, head.offset, head.type).offset
        }
    }
}

/** A minimal compact-protocol struct writer. */
class ThriftWriter {

    private var buf = ByteArray(0)
    private var last = 0

    private fun header(fid: Int, t: Int) {
        val delta = fid - last
        if (delta in 1..15) {
            buf += ((delta shl 4) or t).toByte()
        } else {
            buf += t.toByte()
            buf += Thrift.zigzagVarint16(fid)
        }
        last = fid
    }

    fun bool(fid: Int, v: Boolean): ThriftWriter {
        header(fid, if (v) Thrift.TRUE else Thrift.FALSE)
        return this
    }

    fun i32(fid: Int, v: Int): ThriftWriter {
        header(fid, Thrift.I32)
        buf += Thrift.zigzagVarint32(v)
        return this
    }

    fun i64(fid: Int, v: Long): ThriftWriter {
        header(fid, Thrift.I64)
        buf += Thrift.zigzagVarint64(v)
        return this
    }

    fun str(fid: Int, v: String): ThriftWriter = str(fid, v.toByteArray(Charsets.UTF_8))

    fun str(fid: Int, v: ByteArray): ThriftWriter {
        header(fid, Thrift.BIN)
        buf += Wire.encodeVarintUnsigned(v.size.toLong()) + v
        return this
    }

    /** Append a nested struct whose body was produced by another writer. */
    fun struct(fid: Int, body: ByteArray): ThriftWriter {
        header(fid, Thrift.STRUCT)
        buf += body
        return this
    }

    fun listOfBinaries(fid: Int, items: List<ByteArray>): ThriftWriter {
        header(fid, Thrift.LIST)
        val n = items.size
        if (n < 15) {
            buf += ((n shl 4) or Thrift.BIN).toByte()
        } else {
            buf += (0xF0 or Thrift.BIN).toByte()
            buf += Wire.encodeVarintUnsigned(n.toLong())
        }
        items.forEach { buf += Wire.encodeVarintUnsigned(it.size.toLong()) + it }
        return this
    }

    fun done(): ByteArray = buf + byteArrayOf(0)
}
