package com.macedonga.visor.connection

import java.net.InetAddress
import java.nio.ByteBuffer

/** One IP packet as the Display's tunnel carries it: version 4 or 6, TCP or UDP. */
class IpPacket(
    val version: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val protocol: Int,
    val transport: ByteArray,
) {
    val srcAddress: InetAddress get() = InetAddress.getByAddress(src)
    val dstAddress: InetAddress get() = InetAddress.getByAddress(dst)

    companion object {
        const val TCP = 6
        const val UDP = 17

        fun parse(b: ByteArray): IpPacket? {
            if (b.isEmpty()) return null
            return when (b[0].toInt() ushr 4) {
                4 -> {
                    if (b.size < 20) return null
                    val ihl = (b[0].toInt() and 0x0F) * 4
                    val total = u16(b, 2).coerceAtMost(b.size)
                    if (ihl < 20 || total < ihl) return null
                    // Fragments are not expected inside a 1400-byte tunnel; they are dropped.
                    if (u16(b, 6) and 0x3FFF != 0) return null
                    IpPacket(4, b.copyOfRange(12, 16), b.copyOfRange(16, 20), b[9].toInt() and 0xFF, b.copyOfRange(ihl, total))
                }
                6 -> {
                    if (b.size < 40) return null
                    val length = u16(b, 4)
                    // Extension headers are not followed; such packets are dropped.
                    IpPacket(6, b.copyOfRange(8, 24), b.copyOfRange(24, 40), b[6].toInt() and 0xFF, b.copyOfRange(40, (40 + length).coerceAtMost(b.size)))
                }
                else -> null
            }
        }

        /** An IP packet around [transport], its checksums filled in. */
        fun build(version: Int, src: ByteArray, dst: ByteArray, protocol: Int, transport: ByteArray, id: Int): ByteArray {
            val sum = checksum(pseudoHeader(version, src, dst, protocol, transport.size), transport)
            val at = if (protocol == TCP) 16 else 6
            transport[at] = (sum ushr 8).toByte()
            transport[at + 1] = sum.toByte()
            return if (version == 4) {
                val h = ByteBuffer.allocate(20)
                h.put(0x45).put(0).putShort((20 + transport.size).toShort()).putShort(id.toShort()).putShort(0x4000)
                h.put(64).put(protocol.toByte()).putShort(0).put(src).put(dst)
                val header = h.array()
                val hs = checksum(ByteArray(0), header)
                header[10] = (hs ushr 8).toByte()
                header[11] = hs.toByte()
                header + transport
            } else {
                val h = ByteBuffer.allocate(40)
                h.putInt(0x60000000).putShort(transport.size.toShort()).put(protocol.toByte()).put(64).put(src).put(dst)
                h.array() + transport
            }
        }

        private fun pseudoHeader(version: Int, src: ByteArray, dst: ByteArray, protocol: Int, length: Int): ByteArray =
            if (version == 4) {
                ByteBuffer.allocate(12).put(src).put(dst).put(0).put(protocol.toByte()).putShort(length.toShort()).array()
            } else {
                ByteBuffer.allocate(40).put(src).put(dst).putInt(length).put(0).put(0).put(0).put(protocol.toByte()).array()
            }

        /** The Internet checksum over [a] then [b], the checksum field in [b] taken as zero beforehand. */
        private fun checksum(a: ByteArray, b: ByteArray): Int {
            var sum = 0L
            fun add(x: ByteArray) {
                var i = 0
                while (i + 1 < x.size) {
                    sum += ((x[i].toInt() and 0xFF) shl 8) or (x[i + 1].toInt() and 0xFF)
                    i += 2
                }
                if (i < x.size) sum += (x[i].toInt() and 0xFF) shl 8
            }
            add(a)
            add(b)
            while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
            return (sum.inv() and 0xFFFF).toInt()
        }

        fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

        fun u32(b: ByteArray, at: Int): Long =
            ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
    }
}

/** A TCP segment's header fields and its data. */
class TcpSegment(
    val srcPort: Int,
    val dstPort: Int,
    val seq: Long,
    val ack: Long,
    val flags: Int,
    val window: Int,
    val data: ByteArray,
) {
    val syn get() = flags and SYN != 0
    val fin get() = flags and FIN != 0
    val rst get() = flags and RST != 0
    val hasAck get() = flags and ACK != 0

    companion object {
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10

        fun parse(t: ByteArray): TcpSegment? {
            if (t.size < 20) return null
            val offset = ((t[12].toInt() and 0xF0) ushr 4) * 4
            if (offset < 20 || offset > t.size) return null
            return TcpSegment(
                IpPacket.u16(t, 0), IpPacket.u16(t, 2), IpPacket.u32(t, 4), IpPacket.u32(t, 8),
                t[13].toInt() and 0x3F, IpPacket.u16(t, 14), t.copyOfRange(offset, t.size),
            )
        }

        /** A segment, its checksum left for [IpPacket.build]; [mss] adds the option, for a SYN. */
        fun build(srcPort: Int, dstPort: Int, seq: Long, ack: Long, flags: Int, window: Int, data: ByteArray, mss: Int = 0): ByteArray {
            val header = if (mss > 0) 24 else 20
            val b = ByteBuffer.allocate(header + data.size)
            b.putShort(srcPort.toShort()).putShort(dstPort.toShort()).putInt(seq.toInt()).putInt(ack.toInt())
            b.put(((header / 4) shl 4).toByte()).put(flags.toByte()).putShort(window.toShort()).putShort(0).putShort(0)
            if (mss > 0) b.put(2).put(4).putShort(mss.toShort())
            b.put(data)
            return b.array()
        }
    }
}

/** UDP: ports and data. */
class UdpDatagram(val srcPort: Int, val dstPort: Int, val data: ByteArray) {
    companion object {
        fun parse(u: ByteArray): UdpDatagram? {
            if (u.size < 8) return null
            val length = IpPacket.u16(u, 4).coerceIn(8, u.size)
            return UdpDatagram(IpPacket.u16(u, 0), IpPacket.u16(u, 2), u.copyOfRange(8, length))
        }

        fun build(srcPort: Int, dstPort: Int, data: ByteArray): ByteArray =
            ByteBuffer.allocate(8 + data.size).putShort(srcPort.toShort()).putShort(dstPort.toShort())
                .putShort((8 + data.size).toShort()).putShort(0).put(data).array()
    }
}

/** The server name a TLS ClientHello asks for, if [hello] is one. */
fun tlsServerName(hello: ByteArray): String? = runCatching {
    if (hello.size < 43 || hello[0].toInt() != 0x16 || hello[5].toInt() != 0x01) return null
    var i = 43
    i += 1 + (hello[i].toInt() and 0xFF)
    i += 2 + IpPacket.u16(hello, i)
    i += 1 + (hello[i].toInt() and 0xFF)
    val end = i + 2 + IpPacket.u16(hello, i)
    i += 2
    while (i + 4 <= end && i + 4 <= hello.size) {
        val type = IpPacket.u16(hello, i)
        val len = IpPacket.u16(hello, i + 2)
        if (type == 0) {
            val nameLen = IpPacket.u16(hello, i + 7)
            return String(hello, i + 9, nameLen, Charsets.US_ASCII)
        }
        i += 4 + len
    }
    null
}.getOrNull()
