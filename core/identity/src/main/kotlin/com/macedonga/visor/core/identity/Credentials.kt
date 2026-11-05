package com.macedonga.visor.core.identity

import com.macedonga.visor.core.protocol.Ecdh
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.bouncycastle.asn1.x9.ECNamedCurveTable

/** The PEM is not a key, or not a key of the right kind. */
class CredentialsException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** The owner key of an [IdentityBundle]. */
class BundleCredentials(private val bundle: IdentityBundle) {

    val appEcKey: PrivateKey by lazy { readPrivateKeyPem(bundle.privateKeyPem) }

    /** The public key as 64 raw bytes, x then y. */
    val appPublicKey: ByteArray by lazy { publicKeyOf(appEcKey) }

    /** Says which identity, never anything about the key. */
    override fun toString(): String = "BundleCredentials(serial=${bundle.serial})"
}

/** The 64 raw bytes of a private key's public half. */
internal fun publicKeyOf(key: PrivateKey): ByteArray {
    val ec = key as? ECPrivateKey
        ?: throw CredentialsException("the owner key is not an elliptic curve key")
    val point = SECP256R1.g.multiply(ec.s).normalize()
    return point.affineXCoord.encoded + point.affineYCoord.encoded
}

private val SECP256R1 = ECNamedCurveTable.getByName("secp256r1")

/** The EC private key in a PKCS#8 or SEC1 PEM. */
fun readPrivateKeyPem(pem: ByteArray): PrivateKey {
    val text = String(pem, Charsets.US_ASCII)
    return when {
        text.contains(ENCRYPTED_HEADER) -> throw CredentialsException("the owner key is encrypted")

        text.contains(SEC1_HEADER) ->
            Ecdh.privateKeyFromScalar(sec1Scalar(derBody(text, SEC1_HEADER, SEC1_FOOTER)))

        text.contains(PKCS8_HEADER) -> {
            val der = derBody(text, PKCS8_HEADER, PKCS8_FOOTER)
            val key = try {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
            } catch (error: Exception) {
                // An RSA key in a PKCS#8 PEM fails here.
                throw CredentialsException("the owner key is not an elliptic curve key", error)
            }
            key
        }

        else -> throw CredentialsException("this is not a PEM private key")
    }
}

private const val PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----"
private const val PKCS8_FOOTER = "-----END PRIVATE KEY-----"
private const val SEC1_HEADER = "-----BEGIN EC PRIVATE KEY-----"
private const val SEC1_FOOTER = "-----END EC PRIVATE KEY-----"
private const val ENCRYPTED_HEADER = "-----BEGIN ENCRYPTED PRIVATE KEY-----"

/** The base64 between a PEM's two markers, decoded. */
private fun derBody(text: String, header: String, footer: String): ByteArray {
    val start = text.indexOf(header) + header.length
    val end = text.indexOf(footer, start)
    if (end < 0) throw CredentialsException("this is not a PEM private key")
    val body = text.substring(start, end).filterNot { it.isWhitespace() }
    return try {
        Base64.getDecoder().decode(body)
    } catch (error: IllegalArgumentException) {
        throw CredentialsException("this is not a PEM private key", error)
    }
}

/** The private scalar out of a SEC1 `ECPrivateKey`, RFC 5915 section 3. */
private fun sec1Scalar(der: ByteArray): ByteArray = try {
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
