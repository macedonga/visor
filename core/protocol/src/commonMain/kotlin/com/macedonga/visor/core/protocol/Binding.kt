package com.macedonga.visor.core.protocol

/** The reconnect channel binding svc36 trust signs. */
class HandshakeBinding(
    private val glChallenge: ByteArray,
    private val mySeed: ByteArray,
    /** The glasses' RequestEncryption pubkey, 64 bytes `x ‖ y`. */
    private val glReqPub: ByteArray,
    private val myPub: ByteArray,
) {
    /** `SHA256(SHA256(glChallenge ‖ glReqPub) ‖ SHA256(mySeed ‖ myPub))`. */
    fun txChallenge(): ByteArray = sha256(sha256(glChallenge, glReqPub), sha256(mySeed, myPub))
}
