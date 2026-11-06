package com.macedonga.visor.core.protocol

import java.nio.ByteBuffer
import java.util.UUID

/** First handshake message: the initiator proposes ECDH parameters. */
class RequestEncryption(
    val publicKey: ByteArray,
    val challenge: ByteArray,
    val ellipticCurve: Int = 0,
    val supportedParameters: Int,
    val keyHint: List<ByteArray> = emptyList(),
    val quirks: Int = 0,
    val airshieldVersion: Int = 16,
    /** Whether field 4 was on the wire. */
    val hasSupportedParameters: Boolean = true,
) {
    fun encode(): ByteArray {
        var parts = Wire.fieldBytes(1, publicKey)
        parts += Wire.fieldBytes(2, challenge)
        parts += Wire.fieldVarint(3, ellipticCurve.toLong())
        parts += Wire.fieldVarint(4, supportedParameters.toLong())
        for (hint in keyHint) {
            parts += Wire.fieldBytes(5, hint)
        }
        parts += Wire.fieldVarintNonZero(6, quirks.toLong())
        parts += Wire.fieldVarint(7, airshieldVersion.toLong())
        return parts
    }

    companion object {
        fun decode(data: ByteArray): RequestEncryption {
            val f = Wire.parse(data)
            return RequestEncryption(
                publicKey = f.bytes(1),
                challenge = f.bytes(2),
                ellipticCurve = f.varint(3).toInt(),
                supportedParameters = f.varint(4).toInt(),
                keyHint = f.repeatedBytes(5),
                quirks = f.varint(6).toInt(),
                airshieldVersion = f.varint(7).toInt(),
                hasSupportedParameters = f.has(4),
            )
        }
    }
}

/** Second handshake message: the responder accepts and provides the seed and IV. */
class EnableEncryption(
    val publicKey: ByteArray,
    val seed: ByteArray,
    val iv: ByteArray,
    /** The sender's preamble seq, unsigned 32-bit. */
    val base: Long = 0,
    val parameters: Int,
    val quirks: Int = 0,
    val phasedLinkSetupSupported: Int = 0,
    val supportedLinkSetupServices: Long = 0,
    val linkSwitchVersionSupported: Int = 0,
    /** Whether field 5 was on the wire. */
    val hasParameters: Boolean = true,
) {
    /** Fields 1 to 5 always; 6 to 9 only when non-zero. */
    fun encode(): ByteArray {
        var parts = Wire.fieldBytes(1, publicKey)
        parts += Wire.fieldBytes(2, seed)
        parts += Wire.fieldBytes(3, iv)
        parts += Wire.fieldVarint(4, base)
        parts += Wire.fieldVarint(5, parameters.toLong())
        parts += Wire.fieldVarintNonZero(6, quirks.toLong())
        parts += Wire.fieldVarintNonZero(7, phasedLinkSetupSupported.toLong())
        parts += Wire.fieldVarintNonZero(8, supportedLinkSetupServices)
        parts += Wire.fieldVarintNonZero(9, linkSwitchVersionSupported.toLong())
        return parts
    }

    companion object {
        fun decode(data: ByteArray): EnableEncryption {
            val f = Wire.parse(data)
            val hasParameters = f.has(5)
            return EnableEncryption(
                publicKey = f.bytes(1),
                seed = f.bytes(2),
                iv = f.bytes(3),
                base = f.varint(4),
                // Left out, the glasses mean 31: Multikey on.
                parameters = if (hasParameters) f.varint(5).toInt() else 31,
                quirks = f.varint(6).toInt(),
                phasedLinkSetupSupported = f.varint(7).toInt(),
                supportedLinkSetupServices = f.varint(8),
                linkSwitchVersionSupported = f.varint(9).toInt(),
                hasParameters = hasParameters,
            )
        }
    }
}

/** Final handshake message: {1 asMain, 2 scope uuid, 3 lease uuid}. */
object EndLinkSetup {
    fun encode(asMain: Int, scopeUuid: ByteArray, leaseUuid: ByteArray): ByteArray {
        // The glasses drop the channel silently on a non-v4 uuid.
        require(LinkUuid.isValid(scopeUuid) && LinkUuid.isValid(leaseUuid)) { "EndLinkSetup uuids must be RFC 4122 v4" }
        return Wire.fieldVarint(1, asMain.toLong()) + Wire.fieldBytes(2, scopeUuid) + Wire.fieldBytes(3, leaseUuid)
    }
}

/** The 16-byte UUIDs EndLinkSetup carries, and the firmware check they have to pass. */
object LinkUuid {

    /** 16 bytes from [UUID.randomUUID], which is version 4 and variant 10xx by construction. */
    fun random(): ByteArray = UUID.randomUUID().toBytes()

    fun isValid(bytes: ByteArray): Boolean =
        bytes.size == 16 &&
            (bytes[6].toInt() and 0xF0) == 0x40 &&
            (bytes[8].toInt() and 0xC0) == 0x80
}

/** The uuid's 16 bytes, most significant first. */
fun UUID.toBytes(): ByteArray = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()

/** 16 bytes as a dashed uuid; any other length as hex. */
fun uuidText(raw: ByteArray): String {
    if (raw.size != 16) return raw.toHexString()
    val b = ByteBuffer.wrap(raw)
    return UUID(b.long, b.long).toString()
}
