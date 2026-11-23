package com.macedonga.visor.core.identity

import com.macedonga.visor.core.protocol.P256
import com.macedonga.visor.core.protocol.ecdsaSignDigest

actual class BundleCredentials actual constructor(private val bundle: IdentityBundle) {

    private val scalar: ByteArray by lazy { privateScalar(bundle.privateKeyPem) }

    actual val appPublicKey: ByteArray by lazy { P256.publicKey(scalar) }

    actual fun sign(digest: ByteArray): ByteArray = P256.rawSignature(ecdsaSignDigest(scalar, appPublicKey, digest))

    /** Says which identity, never anything about the key. */
    override fun toString(): String = "BundleCredentials(serial=${bundle.serial})"
}

/** The 32-byte private scalar of the EC key in a PKCS#8 or SEC1 PEM. */
internal fun privateScalar(pem: ByteArray): ByteArray {
    val text = pem.decodeToString()
    return when {
        text.contains(ENCRYPTED_HEADER) -> throw CredentialsException("the owner key is encrypted")
        text.contains(SEC1_HEADER) -> sec1Scalar(derBody(text, SEC1_HEADER, SEC1_FOOTER))
        text.contains(PKCS8_HEADER) -> pkcs8Scalar(derBody(text, PKCS8_HEADER, PKCS8_FOOTER))
        else -> throw CredentialsException("this is not a PEM private key")
    }
}
