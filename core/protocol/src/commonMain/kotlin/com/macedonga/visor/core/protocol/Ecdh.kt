package com.macedonga.visor.core.protocol

/** A P-256 private key, as the platform keeps it. */
expect interface EcPrivateKey

/** An ephemeral key pair with its public half already in wire form. */
class EphemeralKeyPair(val privateKey: EcPrivateKey, val publicKey64: ByteArray)

/** ECDH on secp256r1, NIST P-256. A public key on the wire is 64 bytes, `x ‖ y`. */
expect object Ecdh {
    /** Generate an ephemeral secp256r1 key pair. */
    fun generateEphemeral(): EphemeralKeyPair

    /** ECDH key agreement to a 32-byte shared secret, the raw X coordinate of the shared point. */
    fun computeSharedSecret(privateKey: EcPrivateKey, peerPublic64: ByteArray): ByteArray
}
