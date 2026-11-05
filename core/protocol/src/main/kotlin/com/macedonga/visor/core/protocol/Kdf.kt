package com.macedonga.visor.core.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** AirShield key schedule. */
object Kdf {

    private val AIRSHIELD_INFO: ByteArray = "AirShield".toByteArray(Charsets.US_ASCII)
    private val HMAC_DERIVE_LABEL: ByteArray = "hmac_derive".toByteArray(Charsets.US_ASCII)

    /** EncryptionParameters flag 0x04: a separate HMAC key. The Neural Band offers 27, without it. */
    const val MULTIKEY: Int = 4

    /** One direction's key set. */
    fun deriveDirectionKeys(
        ecdhSecret: ByteArray,
        nonce: ByteArray,
        seed: ByteArray,
        preambleIv: ByteArray,
        // Without Multikey on both ends the AES key is the HMAC key too.
        multikey: Boolean = true,
    ): SessionKeys {
        require(ecdhSecret.size == 32) { "ECDH secret must be 32 bytes, got ${ecdhSecret.size}" }
        require(preambleIv.size == 16) { "preamble IV must be 16 bytes, got ${preambleIv.size}" }

        val aesSalt = sha256(nonce, seed)
        val aesKey = hkdfExpand(hkdfExtract(aesSalt, ecdhSecret))
        if (!multikey) return SessionKeys(aesKey = aesKey, hmacKey = aesKey, iv = preambleIv)
        val hmacSalt = sha256(seed, nonce, HMAC_DERIVE_LABEL)
        val hmacKey = hkdfExpand(hkdfExtract(hmacSalt, ecdhSecret))
        return SessionKeys(aesKey = aesKey, hmacKey = hmacKey, iv = preambleIv)
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    // RFC 5869 T(1): one HMAC-SHA256 block is the whole 32-byte key.
    private fun hkdfExpand(prk: ByteArray): ByteArray = hmacSha256(prk, AIRSHIELD_INFO + byteArrayOf(1))
}

/** One direction's key set: AES-256, HMAC-SHA256, and the initial CBC IV. */
class SessionKeys(
    /** 32 bytes. */
    val aesKey: ByteArray,
    /** 32 bytes. */
    val hmacKey: ByteArray,
    /** 16 bytes, the preamble nonce, which is this direction's initial CBC IV. */
    val iv: ByteArray,
)
