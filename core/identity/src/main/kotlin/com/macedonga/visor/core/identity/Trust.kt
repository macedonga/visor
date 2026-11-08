package com.macedonga.visor.core.identity

import com.macedonga.visor.core.protocol.HandshakeBinding
import com.macedonga.visor.core.protocol.IdentityMsgType
import com.macedonga.visor.core.protocol.IdentityStatus
import com.macedonga.visor.core.protocol.RESIGN_PROVISIONING_CAPABILITIES
import com.macedonga.visor.core.protocol.ServiceId
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.TrustExchange
import com.macedonga.visor.core.session.TrustResult
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Signature
import kotlinx.coroutines.withTimeoutOrNull

/** The secp256r1 group order. */
private val SECP256R1_ORDER = BigInteger(
    "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16,
)

private val HALF_ORDER: BigInteger = SECP256R1_ORDER.shiftRight(1)

/** The trust credential the glasses push at us, by message type. */
private val TRUST_PUSH_TYPES = setOf(
    IdentityMsgType.ENABLE_TRUST_EC.value,
    IdentityMsgType.ENABLE_TRUST.value,
)

/** How long the trust exchange waits for both directions. */
private const val TRUST_TIMEOUT_MILLIS: Long = 5_000

/** ENABLE_TRUST, message type 0x1000. */
private fun buildEnableTrust(credentials: BundleCredentials, txChallenge: ByteArray): ByteArray {
    require(txChallenge.size == 32) {
        "the channel binding is a SHA-256 digest and must be 32 bytes, got ${txChallenge.size}"
    }
    return Wire.fieldBytes(1, MessageDigest.getInstance("SHA-256").digest(credentials.appPublicKey)) +
        Wire.fieldBytes(2, signChallenge(credentials, txChallenge)) +
        Wire.fieldVarint(3, RESIGN_PROVISIONING_CAPABILITIES.toLong())
}

/** The link's channel binding signed as the glasses check it: NONEwithECDSA over the digest, r then low s, 64 bytes. */
internal fun signChallenge(credentials: BundleCredentials, challenge: ByteArray): ByteArray {
    val signer = Signature.getInstance("NONEwithECDSA")
    signer.initSign(credentials.appEcKey)
    signer.update(challenge)
    val (r, s) = decodeDerSignature(signer.sign())
    val canonical = if (s > HALF_ORDER) SECP256R1_ORDER.subtract(s) else s
    return r.to32() + canonical.to32()
}

/** A DER ECDSA signature: a SEQUENCE of two INTEGERs, r then s. */
private fun decodeDerSignature(der: ByteArray): Pair<BigInteger, BigInteger> = try {
    val sequence = DerReader(DerReader(der).read(DerReader.SEQUENCE))
    sequence.readInteger() to sequence.readInteger()
} catch (error: DerException) {
    throw CredentialsException("the signature is not in the expected format", error)
}

/** The svc36 exchange, filling `:core:session`'s seam. */
class IdentityTrustExchange(private val credentials: BundleCredentials) : TrustExchange {

    override suspend fun run(
        connection: DataXConnection,
        binding: HandshakeBinding,
    ): TrustResult {
        require(connection.hasReader) {
            "the trust exchange bounds its wait, and a bounded wait needs the session's " +
                "reader. Without one, giving up would abandon a read that carries on."
        }

        val ourChannel = connection.openChannel(ServiceId.IDENTITY.value)
        connection.send(
            ourChannel,
            buildEnableTrust(credentials, binding.txChallenge()),
            messageType = IdentityMsgType.ENABLE_TRUST.value,
            serviceId = ServiceId.IDENTITY.value,
        )
        val ourStatusChannel = DataXConnection.mirror(ourChannel)

        var txOk = false
        var rxOk = false
        var refusal: Int? = null
        // Anything this loop does not consume belongs to the caller.
        val deferred = mutableListOf<DataXMessage>()
        try {
            withTimeoutOrNull(TRUST_TIMEOUT_MILLIS) {
                while (!(txOk && rxOk)) {
                    val message = connection.receive()
                    val info = message.info
                    when {
                        info.flags.hasError && info.channelId == ourStatusChannel -> {
                            if (info.errorId == IdentityStatus.SUCCESS.value) txOk = true else refusal = info.errorId
                        }

                        DataXConnection.hasOriginatorBit(info.channelId) &&
                            info.serviceId == ServiceId.IDENTITY.value &&
                            info.messageType in TRUST_PUSH_TYPES -> {
                            connection.sendStatus(
                                DataXConnection.mirror(info.channelId),
                                IdentityStatus.SUCCESS.value,
                            )
                            rxOk = true
                        }

                        else -> deferred += message
                    }
                }
            }
        } finally {
            connection.pushback(deferred)
        }

        return TrustResult(txOk = txOk, rxOk = rxOk, refusal = refusal)
    }
}
