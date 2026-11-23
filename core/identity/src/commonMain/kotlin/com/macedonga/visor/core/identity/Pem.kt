package com.macedonga.visor.core.identity

import kotlin.io.encoding.Base64

// PEM and the DER inside it, as both platforms read owner keys.

internal const val PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----"
internal const val PKCS8_FOOTER = "-----END PRIVATE KEY-----"
internal const val SEC1_HEADER = "-----BEGIN EC PRIVATE KEY-----"
internal const val SEC1_FOOTER = "-----END EC PRIVATE KEY-----"
internal const val ENCRYPTED_HEADER = "-----BEGIN ENCRYPTED PRIVATE KEY-----"

/** The base64 between a PEM's two markers, decoded. */
internal fun derBody(text: String, header: String, footer: String): ByteArray {
    val start = text.indexOf(header) + header.length
    val end = text.indexOf(footer, start)
    if (end < 0) throw CredentialsException("this is not a PEM private key")
    val body = text.substring(start, end).filterNot { it.isWhitespace() }
    return try {
        Base64.decode(body)
    } catch (error: IllegalArgumentException) {
        throw CredentialsException("this is not a PEM private key", error)
    }
}

/** The private scalar out of a SEC1 `ECPrivateKey`, RFC 5915 section 3. */
internal fun sec1Scalar(der: ByteArray): ByteArray = try {
    val sequence = DerReader(DerReader(der).read(DerReader.SEQUENCE))
    sequence.read(DerReader.INTEGER)          // version, always 1
    val scalar = sequence.read(DerReader.OCTET_STRING)
    // RFC 5915 fixes the length at the curve's, but a stripped leading zero has been seen in the
    // wild from encoders that treat it as an integer, and privateKeyFromScalar requires exactly 32.
    if (scalar.size > 32) throw CredentialsException("the owner key is not a P-256 key")
    ByteArray(32 - scalar.size) + scalar
} catch (error: DerException) {
    throw CredentialsException("the owner key is not in the expected format", error)
}

/** The private scalar out of a PKCS#8 `PrivateKeyInfo` holding a P-256 key, RFC 5208 and RFC 5915. */
internal fun pkcs8Scalar(der: ByteArray): ByteArray = try {
    val info = DerReader(DerReader(der).read(DerReader.SEQUENCE))
    info.read(DerReader.INTEGER)                          // version, 0
    val algorithm = info.read(DerReader.SEQUENCE)
    if (!algorithm.containsRun(PRIME256V1)) throw CredentialsException("the owner key is not a P-256 key")
    sec1Scalar(info.read(DerReader.OCTET_STRING))
} catch (error: DerException) {
    throw CredentialsException("the owner key is not in the expected format", error)
}

/** The OID 1.2.840.10045.3.1.7, as its DER contents. */
private val PRIME256V1 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)

private fun ByteArray.containsRun(run: ByteArray): Boolean =
    (0..size - run.size).any { at -> run.indices.all { this[at + it] == run[it] } }
