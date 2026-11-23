package com.macedonga.visor.core.identity

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/** The 64 raw bytes, X then Y, of the EC key in a device certificate, as svc36's IdentityResponse carries it. */
fun devicePublicKey(certDer: ByteArray): ByteArray? = runCatching {
    val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certDer))
    point64(cert.publicKey as ECPublicKey)
}.getOrNull()

/** A P-256 public key as 64 raw bytes, from raw, 0x04-prefixed or DER (SubjectPublicKeyInfo) form. */
fun rawPublicKey(encoded: ByteArray): ByteArray? = when {
    encoded.size == 64 -> encoded
    encoded.size == 65 && encoded[0] == 0x04.toByte() -> encoded.copyOfRange(1, 65)
    else -> runCatching {
        point64(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as ECPublicKey)
    }.getOrNull()
}

internal fun point64(key: ECPublicKey): ByteArray = key.w.affineX.to32() + key.w.affineY.to32()

/**
 * Whether [hint] names [publicKey], as AirShield's HintMatcher reads it: a salt as long as the high nibble of its first
 * byte plus one (none when that byte is below 0x10), then the start of SHA256(SHA256(key) ‖ salt).
 */
fun keyHintMatches(hint: ByteArray, publicKey: ByteArray): Boolean {
    if (hint.isEmpty() || publicKey.size != 64) return false
    val first = hint[0].toInt() and 0xFF
    val saltSize = if (first > 0x0F) (first shr 4) + 1 else 0
    val rest = hint.size - saltSize
    if (rest <= 0 || rest > 32) return false
    return hintDigest(publicKey, hint.copyOfRange(0, saltSize)).copyOfRange(0, rest).contentEquals(hint.copyOfRange(saltSize, hint.size))
}

/**
 * A 12-byte key hint for [publicKey], 64 raw bytes: a fresh 4-byte salt, its first byte's high nibble 3 so the Display
 * reads its length, then the first 8 bytes of SHA256(SHA256(key) ‖ salt). A salt with any other nibble matches nothing.
 */
fun keyHint(publicKey: ByteArray): ByteArray {
    require(publicKey.size == 64) { "a key hint needs the 64-byte key, got ${publicKey.size}" }
    val salt = ByteArray(4).also { SecureRandom().nextBytes(it) }
    salt[0] = (0x30 or (salt[0].toInt() and 0x0F)).toByte()
    return salt + hintHash(publicKey, salt)
}

/** SHA256(SHA256(key) ‖ salt)[:8]: a key hint's hash, and the key tag a constellation manifest names a peer by. */
internal fun hintHash(publicKey: ByteArray, salt: ByteArray): ByteArray = hintDigest(publicKey, salt).copyOfRange(0, 8)

private fun hintDigest(publicKey: ByteArray, salt: ByteArray): ByteArray {
    val sha = MessageDigest.getInstance("SHA-256")
    val inner = sha.digest(publicKey)
    sha.update(inner)
    sha.update(salt)
    return sha.digest()
}

/** 32 bytes, big endian, left padded. */
internal fun BigInteger.to32(): ByteArray {
    val bytes = toByteArray()
    return when {
        bytes.size == 32 -> bytes
        bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
        bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
        else -> throw CredentialsException("the value does not fit a P-256 coordinate")
    }
}
