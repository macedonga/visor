package com.macedonga.visor.core.identity

import com.macedonga.visor.core.protocol.HandshakeBinding
import com.macedonga.visor.core.protocol.IdentityMsgType
import com.macedonga.visor.core.protocol.IdentityStatus
import com.macedonga.visor.core.protocol.RESIGN_PROVISIONING_CAPABILITIES
import com.macedonga.visor.core.protocol.ServiceId
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.protocol.sha256
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.TrustExchange
import com.macedonga.visor.core.session.TrustResult
import kotlinx.coroutines.withTimeoutOrNull

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
    return Wire.fieldBytes(1, sha256(credentials.appPublicKey)) +
        Wire.fieldBytes(2, signChallenge(credentials, txChallenge)) +
        Wire.fieldVarint(3, RESIGN_PROVISIONING_CAPABILITIES.toLong())
}

/** The link's channel binding signed as the glasses check it. */
internal fun signChallenge(credentials: BundleCredentials, challenge: ByteArray): ByteArray = credentials.sign(challenge)

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
