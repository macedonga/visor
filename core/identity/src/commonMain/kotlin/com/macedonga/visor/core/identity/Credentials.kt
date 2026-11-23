package com.macedonga.visor.core.identity

/** The PEM is not a key, or not a key of the right kind. */
class CredentialsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The owner key of an [IdentityBundle]. */
expect class BundleCredentials(bundle: IdentityBundle) {
    /** The public key as 64 raw bytes, x then y. */
    val appPublicKey: ByteArray

    /** A SHA-256 digest signed as the glasses check signatures: NONEwithECDSA, r then low s, 64 bytes. */
    fun sign(digest: ByteArray): ByteArray
}
