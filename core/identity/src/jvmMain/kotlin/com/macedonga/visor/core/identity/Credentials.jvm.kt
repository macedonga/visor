package com.macedonga.visor.core.identity

import com.macedonga.visor.core.protocol.Ecdh
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import org.bouncycastle.asn1.x9.ECNamedCurveTable

actual class BundleCredentials actual constructor(private val bundle: IdentityBundle) {

    val appEcKey: PrivateKey by lazy { readPrivateKeyPem(bundle.privateKeyPem) }

    /** The public key as 64 raw bytes, x then y. */
    actual val appPublicKey: ByteArray by lazy { publicKeyOf(appEcKey) }

    actual fun sign(digest: ByteArray): ByteArray = signDigest(appEcKey, digest)

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
