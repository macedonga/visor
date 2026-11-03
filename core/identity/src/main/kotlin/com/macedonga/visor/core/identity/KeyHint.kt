package com.macedonga.visor.core.identity

import java.io.ByteArrayInputStream
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

private fun point64(key: ECPublicKey): ByteArray = fixed32(key.w.affineX.toByteArray()) + fixed32(key.w.affineY.toByteArray())

/**
 * Whether [hint] names [publicKey]: AirShield's key hint is a 4-byte salt then SHA256(SHA256(key) ‖ salt)[:8]
 * (generateKeyHint, research PROTOCOL-REFERENCE).
 */
fun keyHintMatches(hint: ByteArray, publicKey: ByteArray): Boolean {
    if (hint.size != 12 || publicKey.size != 64) return false
    return hintHash(publicKey, hint.copyOfRange(0, 4)).contentEquals(hint.copyOfRange(4, 12))
}

/** A key hint for [publicKey], 64 raw bytes, with a fresh salt: what [keyHintMatches] checks. */
fun keyHint(publicKey: ByteArray): ByteArray {
    require(publicKey.size == 64) { "a key hint needs the 64-byte key, got ${publicKey.size}" }
    val salt = ByteArray(4).also { SecureRandom().nextBytes(it) }
    return salt + hintHash(publicKey, salt)
}

private fun hintHash(publicKey: ByteArray, salt: ByteArray): ByteArray {
    val sha = MessageDigest.getInstance("SHA-256")
    val inner = sha.digest(publicKey)
    sha.update(inner)
    sha.update(salt)
    return sha.digest().copyOfRange(0, 8)
}

// A BigInteger's bytes carry a sign byte or lose leading zeros; a coordinate is always 32.
private fun fixed32(b: ByteArray): ByteArray = when {
    b.size == 32 -> b
    b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
    else -> ByteArray(32 - b.size) + b
}
