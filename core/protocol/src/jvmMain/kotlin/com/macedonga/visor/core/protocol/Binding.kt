package com.macedonga.visor.core.protocol

import java.security.MessageDigest

/** The reconnect channel binding svc36 trust signs. */
class HandshakeBinding(
    private val glChallenge: ByteArray,
    private val mySeed: ByteArray,
    /** The glasses' RequestEncryption pubkey, 64 bytes `x ‖ y`. */
    private val glReqPub: ByteArray,
    private val myPub: ByteArray,
) {
    /** `SHA256(SHA256(glChallenge ‖ glReqPub) ‖ SHA256(mySeed ‖ myPub))`. */
    fun txChallenge(): ByteArray = sha(sha(glChallenge, glReqPub), sha(mySeed, myPub))

    private fun sha(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }
}
