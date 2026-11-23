package com.macedonga.visor.core.protocol

/**
 * The P-256 arithmetic a platform without an EC library needs: a private key's public point, and an ECDSA signature
 * as the glasses check it. Plain, not constant-time: it runs once per owner key, never on a secret the link sees.
 */
object P256 {

    private val P = hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF")
    private val N = hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551")
    private val HALF_N = hex("7FFFFFFF800000007FFFFFFFFFFFFFFFDE737D56D38BCF4279DCE5617E3192A8")
    private val GX = hex("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296")
    private val GY = hex("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5")

    /** The public point of a 32-byte big-endian scalar, as 64 bytes `x ‖ y`. */
    fun publicKey(scalar: ByteArray): ByteArray {
        require(scalar.size == 32) { "a P-256 scalar is 32 bytes, got ${scalar.size}" }
        val k = fromBytes(scalar)
        require(!isZero(k) && cmp(k, N) < 0) { "the scalar is not a P-256 private key" }
        var r: Jacobian? = null
        val g = Jacobian(GX, GY, one())
        for (bit in 255 downTo 0) {
            r = r?.let(::double)
            if (testBit(k, bit)) r = if (r == null) g else add(r, g)
        }
        val point = checkNotNull(r)
        val zInv = inverse(point.z)
        val zInv2 = mul(zInv, zInv)
        return toBytes(mul(point.x, zInv2)) + toBytes(mul(point.y, mul(zInv2, zInv)))
    }

    /** A DER ECDSA signature as 64 bytes, r then s, with s in the low half as the glasses require. */
    fun rawSignature(der: ByteArray): ByteArray {
        var at = 0
        fun next(): Int = der[at++].toInt() and 0xFF
        fun length(): Int {
            val first = next()
            if (first < 0x80) return first
            var n = 0
            repeat(first and 0x7F) { n = (n shl 8) or next() }
            return n
        }
        fun integer(): ByteArray {
            require(next() == 0x02) { "not a DER signature" }
            val n = length()
            val value = der.copyOfRange(at, at + n).dropWhile { it == 0.toByte() }.toByteArray()
            at += n
            require(value.size <= 32) { "not a P-256 signature" }
            return ByteArray(32 - value.size) + value
        }
        require(next() == 0x30) { "not a DER signature" }
        length()
        val r = integer()
        val s = fromBytes(integer())
        val low = if (cmp(s, HALF_N) > 0) sub(N, s).first else s
        return r + toBytes(low)
    }

    private class Jacobian(val x: IntArray, val y: IntArray, val z: IntArray)

    // dbl-2001-b, for a = -3.
    private fun double(p: Jacobian): Jacobian? {
        if (isZero(p.y)) return null
        val delta = mul(p.z, p.z)
        val gamma = mul(p.y, p.y)
        val beta = mul(p.x, gamma)
        val t = mul(modSub(p.x, delta), modAdd(p.x, delta))
        val alpha = modAdd(modAdd(t, t), t)
        val beta4 = modAdd(modAdd(beta, beta), modAdd(beta, beta))
        val x3 = modSub(mul(alpha, alpha), modAdd(beta4, beta4))
        val yz = modAdd(p.y, p.z)
        val z3 = modSub(modSub(mul(yz, yz), gamma), delta)
        val gamma2 = mul(gamma, gamma)
        val gamma8 = modAdd(modAdd(modAdd(gamma2, gamma2), modAdd(gamma2, gamma2)), modAdd(modAdd(gamma2, gamma2), modAdd(gamma2, gamma2)))
        val y3 = modSub(mul(alpha, modSub(beta4, x3)), gamma8)
        return Jacobian(x3, y3, z3)
    }

    // add-2007-bl.
    private fun add(p: Jacobian, q: Jacobian): Jacobian? {
        val z1z1 = mul(p.z, p.z)
        val z2z2 = mul(q.z, q.z)
        val u1 = mul(p.x, z2z2)
        val u2 = mul(q.x, z1z1)
        val s1 = mul(mul(p.y, q.z), z2z2)
        val s2 = mul(mul(q.y, p.z), z1z1)
        val h = modSub(u2, u1)
        val rr = modSub(s2, s1)
        if (isZero(h)) return if (isZero(rr)) double(p) else null
        val h2 = modAdd(h, h)
        val i = mul(h2, h2)
        val j = mul(h, i)
        val r = modAdd(rr, rr)
        val v = mul(u1, i)
        val x3 = modSub(modSub(mul(r, r), j), modAdd(v, v))
        val s1j = mul(s1, j)
        val y3 = modSub(mul(r, modSub(v, x3)), modAdd(s1j, s1j))
        val zs = modAdd(p.z, q.z)
        val z3 = mul(modSub(modSub(mul(zs, zs), z1z1), z2z2), h)
        return Jacobian(x3, y3, z3)
    }

    private fun inverse(a: IntArray): IntArray {
        // Fermat: a^(p-2).
        val e = sub(P, fromBytes(ByteArray(31) + 2.toByte())).first
        var result = one()
        for (bit in 255 downTo 0) {
            result = mul(result, result)
            if (testBit(e, bit)) result = mul(result, a)
        }
        return result
    }

    // Numbers are 8 little-endian 32-bit limbs.

    private fun one() = IntArray(8).also { it[0] = 1 }

    private fun isZero(a: IntArray) = a.all { it == 0 }

    private fun testBit(a: IntArray, bit: Int) = (a[bit / 32] ushr (bit % 32)) and 1 == 1

    private fun cmp(a: IntArray, b: IntArray): Int {
        for (i in a.indices.reversed()) {
            val x = a[i].toUInt()
            val y = b[i].toUInt()
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private fun add(a: IntArray, b: IntArray): Pair<IntArray, Long> {
        val out = IntArray(a.size)
        var carry = 0L
        for (i in a.indices) {
            val sum = (a[i].toLong() and MASK) + (b[i].toLong() and MASK) + carry
            out[i] = sum.toInt()
            carry = sum ushr 32
        }
        return out to carry
    }

    private fun sub(a: IntArray, b: IntArray): Pair<IntArray, Long> {
        val out = IntArray(a.size)
        var borrow = 0L
        for (i in a.indices) {
            val diff = (a[i].toLong() and MASK) - (b[i].toLong() and MASK) - borrow
            out[i] = diff.toInt()
            borrow = if (diff < 0) 1 else 0
        }
        return out to borrow
    }

    private fun modAdd(a: IntArray, b: IntArray): IntArray {
        val (sum, carry) = add(a, b)
        return if (carry != 0L || cmp(sum, P) >= 0) sub(sum, P).first else sum
    }

    private fun modSub(a: IntArray, b: IntArray): IntArray {
        val (diff, borrow) = sub(a, b)
        return if (borrow != 0L) add(diff, P).first else diff
    }

    /** a·b mod p: the 512-bit product, then shift-and-subtract. */
    private fun mul(a: IntArray, b: IntArray): IntArray {
        val product = LongArray(16)
        for (i in 0 until 8) {
            var carry = 0L
            val x = a[i].toLong() and MASK
            for (j in 0 until 8) {
                val t = x * (b[j].toLong() and MASK) + product[i + j] + carry
                product[i + j] = t and MASK
                carry = t ushr 32
            }
            product[i + 8] = carry
        }
        // r stays below 2p, so nine limbs hold it.
        val r = IntArray(9)
        val p9 = P.copyOf(9)
        for (bit in 511 downTo 0) {
            var carry = (product[bit / 32] ushr (bit % 32)) and 1
            for (i in 0 until 9) {
                val v = ((r[i].toLong() and MASK) shl 1) or carry
                r[i] = v.toInt()
                carry = v ushr 32
            }
            if (cmp(r, p9) >= 0) {
                val (d, _) = sub(r, p9)
                d.copyInto(r)
            }
        }
        return r.copyOf(8)
    }

    private const val MASK = 0xFFFFFFFFL

    private fun fromBytes(bytes: ByteArray): IntArray = IntArray(8) { limb ->
        var v = 0
        for (k in 0 until 4) v = v or ((bytes[31 - (limb * 4 + k)].toInt() and 0xFF) shl (8 * k))
        v
    }

    private fun toBytes(a: IntArray): ByteArray = ByteArray(32) { i ->
        val pos = 31 - i
        (a[pos / 4] ushr (8 * (pos % 4))).toByte()
    }

    private fun hex(text: String): IntArray = fromBytes(ByteArray(32) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() })
}
