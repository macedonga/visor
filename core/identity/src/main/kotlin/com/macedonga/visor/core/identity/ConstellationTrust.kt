package com.macedonga.visor.core.identity

import com.macedonga.visor.core.protocol.HandshakeBinding
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.TrustExchange
import com.macedonga.visor.core.session.TrustResult
import java.security.PrivateKey
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The svc79 exchange an app's own link runs instead of svc36, as Meta's toolkit does: the app proves a key that the
 * glasses' constellation manifest lists in its app list, which gives the link an App scope. Both sides send an
 * EnableTrust {1 key tag, 2 signature over their channel binding, 3 manifest version, 5 client version} and answer
 * the other's with a DataX status: 0 is OK, 2 an invalid manifest, 3 "send me yours", 4 a key the manifest does not
 * list, 5 an older manifest than theirs.
 */
class ConstellationTrustExchange(
    private val key: PrivateKey,
    /** [key]'s public half, 64 raw bytes. */
    private val publicKey: ByteArray,
    private val manifestVersion: Long,
    private val keySalt: ByteArray,
) : TrustExchange {
    override suspend fun run(connection: DataXConnection, binding: HandshakeBinding): TrustResult {
        require(connection.hasReader) { "the trust exchange bounds its wait, which needs the session's reader" }
        val ours = connection.openChannel(SERVICE)
        val message = Wire.fieldBytes(1, hintHash(publicKey, keySalt)) +
            Wire.fieldBytes(2, signDigest(key, binding.txChallenge())) +
            Wire.fieldVarint(3, manifestVersion) +
            Wire.fieldString(5, CLIENT_VERSION)
        connection.send(ours, message, messageType = ENABLE_TRUST, serviceId = SERVICE)
        val ourStatus = DataXConnection.mirror(ours)

        var txOk = false
        var rxOk = false
        var refusal: Int? = null
        val deferred = mutableListOf<DataXMessage>()
        try {
            withTimeoutOrNull(TIMEOUT_MILLIS) {
                while (!(txOk && rxOk) && refusal == null) {
                    val received = connection.receive()
                    val info = received.info
                    when {
                        info.flags.hasError && info.channelId == ourStatus -> if (info.errorId == OK) txOk = true else refusal = info.errorId
                        DataXConnection.hasOriginatorBit(info.channelId) && info.serviceId == SERVICE && info.messageType == ENABLE_TRUST -> {
                            connection.sendStatus(DataXConnection.mirror(info.channelId), OK)
                            rxOk = true
                        }
                        else -> deferred += received
                    }
                }
            }
        } finally {
            connection.pushback(deferred)
        }
        return TrustResult(txOk = txOk, rxOk = rxOk, refusal = refusal)
    }

    private companion object {
        const val SERVICE = 79
        const val ENABLE_TRUST = 1
        const val OK = 0
        const val TIMEOUT_MILLIS = 5_000L
        const val CLIENT_VERSION = "acdc-android:8.0.0.0.0"
    }
}
