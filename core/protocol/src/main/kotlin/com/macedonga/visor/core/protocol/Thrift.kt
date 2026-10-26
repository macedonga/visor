package com.macedonga.visor.core.protocol

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

    private val TYPE_NAME = mapOf(
        0 to "STOP", 1 to "TRUE", 2 to "FALSE", 3 to "I8", 4 to "I16", 5 to "I32",
        6 to "I64", 7 to "DOUBLE", 8 to "BIN", 9 to "LIST", 10 to "SET", 11 to "MAP",
        12 to "STRUCT", 13 to "FLOAT",
    )

    fun typeName(code: Int): String = TYPE_NAME[code] ?: "type $code"

    // --- read --------------------------------------------------------------

    /**
     * DIVERGENCE: as with [Wire.decodeVarint], this returns a `Long`, so a varint wider than 64
     * bits wraps where Python would grow the integer.
     */
    private fun uvarint(b: ByteArray, start: Int): Wire.VarintResult {
        var r = 0L
        var s = 0
        var i = start
        while (true) {
            if (i >= b.size) throw ProtocolFormatException("truncated varint")
            val x = b[i].toInt() and 0xFF
            i += 1
            r = r or ((x and 0x7F).toLong() shl s)
            s += 7
            if (x and 0x80 == 0) return Wire.VarintResult(r, i)
        }
    }

    /** Zigzag decode. The varint is unsigned, so the shift is logical, not arithmetic. */
    private fun zz(n: Long): Long = (n ushr 1) xor -(n and 1L)

    private class CollectionHeader(val size: Int, val etype: Int, val offset: Int)

    private fun collectionHeader(b: ByteArray, start: Int): CollectionHeader {
        var i = start
        var size = (b[i].toInt() and 0xFF) shr 4
        val etype = b[i].toInt() and 0x0F
        i += 1
        if (size == 15) {
            val v = uvarint(b, i)
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

    private class Read(val value: Any?, val offset: Int)

    /**
     * DIVERGENCE: `String(bytes, UTF_8)` substitutes replacement characters where Python's
     * `bytes.decode("utf-8")` raises, so a strict decoder is required.
     */
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
            // I8 is NOT sign extended in the reference: it returns b[i], which is 0 to 255.
            I8 -> return Read(b[i].toInt() and 0xFF, i + 1)
            I16, I32, I64 -> {
                val v = uvarint(b, i)
                return Read(zz(v.value), v.offset)
            }

            DOUBLE -> {
                val value = ByteBuffer.wrap(b, i, 8).order(ByteOrder.LITTLE_ENDIAN).double
                return Read(value, i + 8)
            }

            FLOAT -> return Read(ByteBuffer.wrap(b, i, 4).order(ByteOrder.BIG_ENDIAN).float.toDouble(), i + 4)

            BIN -> {
                val len = uvarint(b, i)
                i = len.offset
                // Python's slice yields the bytes that are there and then moves the cursor past the
                // end, so the enclosing loop stops.
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
                val sizeRead = uvarint(b, i)
                i = sizeRead.offset
                if (sizeRead.value !in 0..Int.MAX_VALUE.toLong()) {
                    throw ProtocolFormatException("map size ${sizeRead.value} out of range at $i")
                }
                val size = sizeRead.value.toInt()
                if (size == 0) return Read(LinkedHashMap<Any, Any?>(), i)
                val ktype = (b[i].toInt() and 0xFF) shr 4
                val vtype = b[i].toInt() and 0x0F
                i += 1
                val out = LinkedHashMap<Any, Any?>()   // no capacity hint, see LIST above
                repeat(size) {
                    val k = readElement(b, i, ktype)
                    i = k.offset
                    val v = readElement(b, i, vtype)
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

    /** DIVERGENCE: the reference writes `out[k if isinstance(k, (str, int)) else repr(k)]`. */
    private fun mapKey(value: Any?): Any = when (value) {
        is String, is Int, is Long -> value
        is ByteArray -> "b\"" + value.joinToString("") { "%02x".format(it) } + "\""
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
    } catch (e: java.nio.BufferUnderflowException) {
        throw ProtocolFormatException("truncated $what: ${e.message}")
    }

    /** A parsed struct and the index just past its STOP byte. */
    class StructResult(val fields: Map<Int, Any?>, val offset: Int)

    /** Read one struct, returning its fields by id and the index after STOP. */
    fun readStruct(b: ByteArray, offset: Int = 0): StructResult = guardingFormat("struct") {
        readStructUnguarded(b, offset)
    }

    private fun readStructUnguarded(b: ByteArray, offset: Int): StructResult {
        val out = LinkedHashMap<Int, Any?>()
        var fid = 0
        var i = offset
        while (i < b.size) {
            val h = b[i].toInt() and 0xFF
            i += 1
            if (h == 0) return StructResult(out, i)
            val delta = (h shr 4) and 0x0F
            val t = h and 0x0F
            if (delta == 0) {
                val v = uvarint(b, i)
                i = v.offset
                fid = zz(v.value).toInt()
            } else {
                fid += delta
            }
            val read = readValue(b, i, t)
            i = read.offset
            out[fid] = read.value
        }
        return StructResult(out, i)
    }

    fun loads(b: ByteArray): Map<Int, Any?> = readStruct(b, 0).fields

    /** The bytes of top-level struct field [fid] as written, to pass a sub-struct on unread. */
    fun rawStruct(b: ByteArray, fid: Int): ByteArray? {
        var i = 0
        var cur = 0
        while (i < b.size) {
            val h = b[i].toInt() and 0xFF
            if (h == 0) break
            i += 1
            val delta = (h shr 4) and 0x0F
            val t = h and 0x0F
            if (delta == 0) {
                val v = uvarint(b, i)
                i = v.offset
                cur = zz(v.value).toInt()
            } else {
                cur += delta
            }
            val end = readValue(b, i, t).offset
            if (cur == fid) return if (t == STRUCT) b.copyOfRange(i, end) else null
            i = end
        }
        return null
    }

    // `thrift.py` also has `render`, a debug pretty-printer whose output is Python repr, and
    // a __main__ block that dumps a file from the command line. Neither is ported.

    // --- write -------------------------------------------------------------

    /** An unsigned varint. */
    internal fun wvarint(n: Long): ByteArray {
        val out = ArrayList<Byte>(10)
        var remaining = n
        while (true) {
            val x = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) {
                out.add((x or 0x80).toByte())
            } else {
                out.add(x.toByte())
                return out.toByteArray()
            }
        }
    }

    /** Zigzag a 32-bit value and write it as a varint. */
    fun zigzagVarint32(value: Int): ByteArray =
        wvarint((value.toLong() shl 1) xor (value.toLong() shr 31))

    /** Zigzag a 64-bit value and write it as a varint. `thrift.py` calls this `_wzz(n, 64)`. */
    fun zigzagVarint64(value: Long): ByteArray =
        wvarint((value shl 1) xor (value shr 63))

    /** Zigzag a 16-bit value, used only for the long-form field header. */
    internal fun zigzagVarint16(value: Int): ByteArray =
        wvarint((value.toLong() shl 1) xor (value.toLong() shr 15))

    // --- patching ----------------------------------------------------------

    private class Span(val lengthStart: Int, val dataStart: Int, val dataEnd: Int, val value: ByteArray)

    /**
     * Walk a struct recording the exact byte range of every BIN field, so ranges can be spliced
     * without re-encoding the whole message.
     */
    private fun scanStrings(b: ByteArray, start: Int, out: MutableList<Span>, startFid: Int = 0): Int {
        var i = start
        var fid = startFid
        while (i < b.size) {
            val h = b[i].toInt() and 0xFF
            i += 1
            if (h == 0) return i
            val delta = (h shr 4) and 0x0F
            val t = h and 0x0F
            if (delta == 0) {
                val v = uvarint(b, i)
                i = v.offset
                fid = zz(v.value).toInt()
            } else {
                fid += delta
            }
            i = scanValue(b, i, t, out)
        }
        return i
    }

    private fun scanElement(b: ByteArray, start: Int, t: Int, out: MutableList<Span>): Int =
        if (t == TRUE || t == FALSE) start + 1 else scanValue(b, start, t, out)

    private fun scanValue(b: ByteArray, start: Int, t: Int, out: MutableList<Span>): Int {
        var i = start
        when (t) {
            TRUE, FALSE -> return i
            I8 -> return i + 1
            I16, I32, I64 -> return uvarint(b, i).offset
            DOUBLE -> return i + 8
            FLOAT -> return i + 4
            BIN -> {
                val lengthStart = i
                val len = uvarint(b, i)
                i = len.offset
                // Clamped for the same reason as the reader, and so the scan cursor cannot move
                // backwards.
                val remaining = (b.size - i).toLong()
                val fits = len.value in 0..remaining
                val take = if (fits) len.value.toInt() else remaining.toInt()
                val end = i + take
                out.add(Span(lengthStart, i, end, b.copyOfRange(i, end)))
                return end
            }

            LIST, SET -> {
                val header = collectionHeader(b, i)
                i = header.offset
                repeat(header.size) { i = scanElement(b, i, header.etype, out) }
                return i
            }

            MAP -> {
                val sizeRead = uvarint(b, i)
                i = sizeRead.offset
                val size = sizeRead.value.toInt()
                if (size != 0) {
                    val kt = (b[i].toInt() and 0xFF) shr 4
                    val vt = b[i].toInt() and 0x0F
                    i += 1
                    repeat(size) {
                        i = scanElement(b, i, kt, out)
                        i = scanElement(b, i, vt, out)
                    }
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
                wvarint(replacement.size.toLong()) +
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
        var i = 0
        var cur = 0
        while (i < raw.size) {
            val h = raw[i].toInt() and 0xFF
            if (h == 0) break
            val start = i
            i += 1
            val delta = (h shr 4) and 0x0F
            val t = h and 0x0F
            if (delta == 0) {
                val v = uvarint(raw, i)
                i = v.offset
                cur = zz(v.value).toInt()
            } else {
                cur += delta
            }
            if (cur == fid && (t == TRUE || t == FALSE)) {
                val want = if (value) TRUE else FALSE
                if (t == want) return raw
                return raw.copyOfRange(0, start) +
                    byteArrayOf(((h and 0xF0) or want).toByte()) +
                    raw.copyOfRange(start + 1, raw.size)
            }
            i = readValue(raw, i, t).offset
        }
        return raw
    }

    /** Set a string at a nested field path, for instance `[28, 2]`, splicing in place. */
    fun setPathString(raw: ByteArray, path: List<Int>, value: String): ByteArray = guardingFormat("struct") {
        setPathStringUnguarded(raw, path, value)
    }

    private fun setPathStringUnguarded(raw: ByteArray, path: List<Int>, value: String): ByteArray {
        val span = walkPath(raw, 0, path, 0) ?: return raw
        val replacement = value.toByteArray(Charsets.UTF_8)
        return raw.copyOfRange(0, span.first) +
            wvarint(replacement.size.toLong()) +
            replacement +
            raw.copyOfRange(span.second, raw.size)
    }

    /** The length-varint start and the end of the BIN value at [path], or null. */
    private fun walkPath(b: ByteArray, start: Int, path: List<Int>, depth: Int): Pair<Int, Int>? {
        var i = start
        var fid = 0
        while (i < b.size) {
            val h = b[i].toInt() and 0xFF
            if (h == 0) return null
            i += 1
            val delta = (h shr 4) and 0x0F
            val t = h and 0x0F
            if (delta == 0) {
                val v = uvarint(b, i)
                i = v.offset
                fid = zz(v.value).toInt()
            } else {
                fid += delta
            }
            if (fid == path[depth]) {
                if (depth == path.size - 1) {
                    if (t != BIN) return null
                    val lengthStart = i
                    val len = uvarint(b, i)
                    return lengthStart to (len.offset + len.value.toInt())
                }
                if (t != STRUCT) return null
                return walkPath(b, i, path, depth + 1)
            }
            i = readValue(b, i, t).offset
        }
        return null
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

    fun i8(fid: Int, v: Int): ThriftWriter {
        header(fid, Thrift.I8)
        buf += (v and 0xFF).toByte()
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
        buf += Thrift.wvarint(v.size.toLong()) + v
        return this
    }

    /** Append a nested struct whose body was produced by another writer. */
    fun struct(fid: Int, body: ByteArray): ThriftWriter {
        header(fid, Thrift.STRUCT)
        buf += body
        return this
    }

    fun listOfStructs(fid: Int, bodies: List<ByteArray>): ThriftWriter {
        header(fid, Thrift.LIST)
        val n = bodies.size
        if (n < 15) {
            buf += ((n shl 4) or Thrift.STRUCT).toByte()
        } else {
            buf += (0xF0 or Thrift.STRUCT).toByte()
            buf += Thrift.wvarint(n.toLong())
        }
        bodies.forEach { buf += it }
        return this
    }

    fun listOfBinaries(fid: Int, items: List<ByteArray>): ThriftWriter {
        header(fid, Thrift.LIST)
        val n = items.size
        if (n < 15) {
            buf += ((n shl 4) or Thrift.BIN).toByte()
        } else {
            buf += (0xF0 or Thrift.BIN).toByte()
            buf += Thrift.wvarint(n.toLong())
        }
        items.forEach { buf += Thrift.wvarint(it.size.toLong()) + it }
        return this
    }

    fun done(): ByteArray = buf + byteArrayOf(0)
}
