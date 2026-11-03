package com.macedonga.visor.core.protocol

import java.nio.ByteBuffer
import java.util.UUID

/** AirShield link-setup handshake messages (notes/63). */

private val EMPTY = ByteArray(0)

/** First handshake message: the initiator proposes ECDH parameters. */
class RequestEncryption(
    val publicKey: ByteArray,
    val challenge: ByteArray,
    val ellipticCurve: Int = 0,
    val supportedParameters: Int = 31,
    val keyHint: List<ByteArray> = emptyList(),
    val quirks: Int = 0,
    val airshieldVersion: Int = 16,
) {
    fun encode(): ByteArray {
        var parts = Wire.fieldBytes(1, publicKey)
        parts += Wire.fieldBytes(2, challenge)
        parts += Wire.fieldVarint(3, ellipticCurve.toLong())
        parts += Wire.fieldVarint(4, supportedParameters.toLong())
        for (hint in keyHint) {
            parts += Wire.fieldBytes(5, hint)
        }
        if (quirks != 0) {   // the official app omits field 6 when it is zero (pair_full.log)
            parts += Wire.fieldVarint(6, quirks.toLong())
        }
        parts += Wire.fieldVarint(7, airshieldVersion.toLong())
        return parts
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RequestEncryption) return false
        return publicKey.contentEquals(other.publicKey) &&
            challenge.contentEquals(other.challenge) &&
            ellipticCurve == other.ellipticCurve &&
            supportedParameters == other.supportedParameters &&
            keyHint.size == other.keyHint.size &&
            keyHint.indices.all { keyHint[it].contentEquals(other.keyHint[it]) } &&
            quirks == other.quirks &&
            airshieldVersion == other.airshieldVersion
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + challenge.contentHashCode()
        result = 31 * result + ellipticCurve
        result = 31 * result + supportedParameters
        result = 31 * result + keyHint.sumOf { it.contentHashCode() }
        result = 31 * result + quirks
        result = 31 * result + airshieldVersion
        return result
    }

    override fun toString(): String =
        "RequestEncryption(curve=$ellipticCurve, params=$supportedParameters, " +
            "hints=${keyHint.size}, quirks=$quirks, version=$airshieldVersion)"

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
            )
        }
    }
}

/** Second handshake message: the responder accepts and provides the seed and IV. */
class EnableEncryption(
    val publicKey: ByteArray,
    val seed: ByteArray,
    val iv: ByteArray,
    /**
     * The sender's preamble sequence counter, an UNSIGNED 32 bit value: `handshake.py` draws it as
     * `int.from_bytes(os.urandom(4), "little")`.
     */
    val base: Long = 0,
    /** 0x1F, the wire-confirmed value from pair_full.log. */
    val parameters: Int = 31,
    val quirks: Int = 0,
    val phasedLinkSetupSupported: Int = 0,
    val supportedLinkSetupServices: Long = 0,
    val linkSwitchVersionSupported: Int = 0,
) {
    /**
     * Matches the official app's exact field set (pair_full.log): 1 to 5 always, and 6, 7, 8 and 9
     * only when non-zero.
     */
    fun encode(): ByteArray {
        var parts = Wire.fieldBytes(1, publicKey)
        parts += Wire.fieldBytes(2, seed)
        parts += Wire.fieldBytes(3, iv)
        parts += Wire.fieldVarint(4, base)
        parts += Wire.fieldVarint(5, parameters.toLong())
        if (quirks != 0) parts += Wire.fieldVarint(6, quirks.toLong())
        if (phasedLinkSetupSupported != 0) parts += Wire.fieldVarint(7, phasedLinkSetupSupported.toLong())
        // Field 8 is a u64 bitmask, so a Long with the high bit set means 2^63 or more.
        if (supportedLinkSetupServices != 0L) parts += Wire.fieldVarintUnsigned(8, supportedLinkSetupServices)
        if (linkSwitchVersionSupported != 0) parts += Wire.fieldVarint(9, linkSwitchVersionSupported.toLong())
        return parts
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnableEncryption) return false
        return publicKey.contentEquals(other.publicKey) &&
            seed.contentEquals(other.seed) &&
            iv.contentEquals(other.iv) &&
            base == other.base &&
            parameters == other.parameters &&
            quirks == other.quirks &&
            phasedLinkSetupSupported == other.phasedLinkSetupSupported &&
            supportedLinkSetupServices == other.supportedLinkSetupServices &&
            linkSwitchVersionSupported == other.linkSwitchVersionSupported
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + seed.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + base.hashCode()
        result = 31 * result + parameters
        result = 31 * result + quirks
        result = 31 * result + phasedLinkSetupSupported
        result = 31 * result + supportedLinkSetupServices.hashCode()
        result = 31 * result + linkSwitchVersionSupported
        return result
    }

    override fun toString(): String =
        "EnableEncryption(base=$base, parameters=$parameters, quirks=$quirks, " +
            "phased=$phasedLinkSetupSupported, services=$supportedLinkSetupServices, " +
            "linkSwitch=$linkSwitchVersionSupported)"

    companion object {
        fun decode(data: ByteArray): EnableEncryption {
            val f = Wire.parse(data)
            return EnableEncryption(
                publicKey = f.bytes(1),
                seed = f.bytes(2),
                iv = f.bytes(3),
                base = f.varint(4),
                // Left out, the glasses mean 31: Multikey on.
                parameters = if (f.has(5)) f.varint(5).toInt() else 31,
                quirks = f.varint(6).toInt(),
                phasedLinkSetupSupported = f.varint(7).toInt(),
                supportedLinkSetupServices = f.varint(8),
                linkSwitchVersionSupported = f.varint(9).toInt(),
            )
        }
    }
}

/** Final handshake message: the encrypted stream is ready. */
class EndLinkSetup(
    /** Field 1, state or asMain. */
    val state: Int? = 0,
    val uuid: ByteArray = EMPTY,
    val linkUuid: ByteArray = EMPTY,
    val userData: Map<Int, ByteArray> = emptyMap(),
) {
    /** DIVERGENCE: `messages.py` does not check the uuid fields. */
    fun encode(): ByteArray {
        require(uuid.isEmpty() || LinkUuid.isValid(uuid)) {
            "EndLinkSetup uuid must be an RFC 4122 v4 UUID: the firmware checks " +
                "(bytes[6] & 0xf0) == 0x40 and (bytes[8] & 0xc0) == 0x80, and tears the " +
                "channel down silently when they are wrong. Use LinkUuid.random()."
        }
        require(linkUuid.isEmpty() || LinkUuid.isValid(linkUuid)) {
            "EndLinkSetup linkUuid must be an RFC 4122 v4 UUID: the firmware checks " +
                "(bytes[6] & 0xf0) == 0x40 and (bytes[8] & 0xc0) == 0x80, and tears the " +
                "channel down silently when they are wrong. Use LinkUuid.random()."
        }
        var parts = EMPTY
        if (state != null) parts += Wire.fieldVarint(1, state.toLong())
        if (uuid.isNotEmpty()) parts += Wire.fieldBytes(2, uuid)
        if (linkUuid.isNotEmpty()) parts += Wire.fieldBytes(3, linkUuid)
        // A map of int to bytes encodes as repeated field 4, each entry a sub-message with field 1
        // the key varint and field 2 the value bytes.
        for ((k, v) in userData) {
            parts += Wire.fieldBytes(4, Wire.fieldVarint(1, k.toLong()) + Wire.fieldBytes(2, v))
        }
        return parts
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EndLinkSetup) return false
        if (state != other.state) return false
        if (!uuid.contentEquals(other.uuid)) return false
        if (!linkUuid.contentEquals(other.linkUuid)) return false
        if (userData.keys != other.userData.keys) return false
        return userData.all { (k, v) -> v.contentEquals(other.userData.getValue(k)) }
    }

    override fun hashCode(): Int {
        var result = state ?: -1
        result = 31 * result + uuid.contentHashCode()
        result = 31 * result + linkUuid.contentHashCode()
        result = 31 * result + userData.entries.sumOf { it.key * 31 + it.value.contentHashCode() }
        return result
    }

    override fun toString(): String = "EndLinkSetup(state=$state, userData=${userData.keys})"

    companion object {
        fun decode(data: ByteArray): EndLinkSetup {
            val f = Wire.parse(data)
            val userData = LinkedHashMap<Int, ByteArray>()
            for (entryBytes in f.repeatedBytes(4)) {
                val entry = Wire.parse(entryBytes)
                userData[entry.varint(1).toInt()] = entry.bytes(2)
            }
            return EndLinkSetup(
                state = f.varint(1).toInt(),
                uuid = f.bytes(2),
                linkUuid = f.bytes(3),
                userData = userData,
            )
        }
    }
}

/** `com.oculus.atc.LinkSetupConfig`, the phased-link-setup service-activation message. */
class LinkSetupConfig(val requiredLinkSetupServices: Long = 0) {

    // Field 1 is a u64 bitmask, so a Long with the high bit set means 2^63 or more.
    fun encode(): ByteArray = Wire.fieldVarintUnsigned(1, requiredLinkSetupServices)

    override fun equals(other: Any?): Boolean =
        other is LinkSetupConfig && requiredLinkSetupServices == other.requiredLinkSetupServices

    override fun hashCode(): Int = requiredLinkSetupServices.hashCode()

    override fun toString(): String = "LinkSetupConfig(services=$requiredLinkSetupServices)"

    companion object {
        fun decode(data: ByteArray): LinkSetupConfig =
            LinkSetupConfig(Wire.parse(data).varint(1))
    }
}

/** The 16-byte UUIDs EndLinkSetup carries, and the firmware check they have to pass. */
object LinkUuid {

    /** 16 bytes from [UUID.randomUUID], which is version 4 and variant 10xx by construction. */
    fun random(): ByteArray {
        val uuid = UUID.randomUUID()
        val bytes = ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        check(isValid(bytes)) { "UUID.randomUUID produced a UUID the firmware would reject" }
        return bytes
    }

    fun isValid(bytes: ByteArray): Boolean =
        bytes.size == 16 &&
            (bytes[6].toInt() and 0xF0) == 0x40 &&
            (bytes[8].toInt() and 0xC0) == 0x80
}
