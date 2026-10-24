package com.macedonga.visor.core.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** AirShield key schedule, live-confirmed 2026-06-11 (notes/67, `hci/reproduce_kdf.py`). */
object Kdf {

    val AIRSHIELD_INFO: ByteArray = "AirShield".toByteArray(Charsets.US_ASCII)
    val HMAC_DERIVE_LABEL: ByteArray = "hmac_derive".toByteArray(Charsets.US_ASCII)

    /** EncryptionParameters bit 4: a separate HMAC key. The Neural Band leaves it out (27, the glasses 31). */
    const val MULTIKEY: Int = 4

    /** Derive ONE direction's key set, in the live-confirmed construction. */
    fun deriveDirectionKeys(
        ecdhSecret: ByteArray,
        nonce: ByteArray,
        seed: ByteArray,
        preambleIv: ByteArray,
        info: ByteArray = AIRSHIELD_INFO,
        // Without Multikey on both ends the AES key is the HMAC key too (CipherBuilder::buildFramingKeys).
        multikey: Boolean = true,
    ): SessionKeys {
        require(ecdhSecret.size == 32) { "ECDH secret must be 32 bytes, got ${ecdhSecret.size}" }
        require(preambleIv.size == 16) { "preamble IV must be 16 bytes, got ${preambleIv.size}" }

        val aesSalt = sha256(nonce, seed)
        val aesKey = hkdfExpand(hkdfExtract(aesSalt, ecdhSecret), info, 32)
        if (!multikey) return SessionKeys(aesKey = aesKey, hmacKey = aesKey, iv = preambleIv)
        val hmacSalt = sha256(seed, nonce, HMAC_DERIVE_LABEL)
        val hmacKey = hkdfExpand(hkdfExtract(hmacSalt, ecdhSecret), info, 32)
        return SessionKeys(aesKey = aesKey, hmacKey = hmacKey, iv = preambleIv)
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An all-zero key is legal here and Mac rejects an empty one, not a zero one.
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val out = ArrayList<Byte>(length + 32)
        var t = ByteArray(0)
        var counter = 1
        while (out.size < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            t.forEach(out::add)
            counter += 1
        }
        return out.toByteArray().copyOf(length)
    }

    // `kdf.py` also binds the name `derive_session_keys` to `derive_direction_keys` for
    // backward compatibility. It has no distinct behaviour, so it is not ported.
}

/** One direction's key set: AES-256, HMAC-SHA256, and the initial CBC IV. */
class SessionKeys(
    /** 32 bytes. */
    val aesKey: ByteArray,
    /** 32 bytes. */
    val hmacKey: ByteArray,
    /** 16 bytes, the preamble nonce, which is this direction's initial CBC IV. */
    val iv: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionKeys) return false
        return aesKey.contentEquals(other.aesKey) &&
            hmacKey.contentEquals(other.hmacKey) &&
            iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int {
        var result = aesKey.contentHashCode()
        result = 31 * result + hmacKey.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }

    /** Deliberately says nothing about the key material. */
    override fun toString(): String = "SessionKeys(aes=32B, hmac=32B, iv=${iv.size}B)"
}
