package com.macedonga.visor.debug

import com.macedonga.visor.core.protocol.HandshakeBinding
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.TrustExchange
import com.macedonga.visor.core.session.TrustResult
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug only: [com.macedonga.visor.core.identity.ConstellationTrustExchange] with its key tag and signing input picked
 * per run, to find what the Display checks. [tag] 0 is SHA256(SHA256(key) ‖ salt), 1 is SHA256(SHA256(key) ‖
 * SHA256(salt)); [sign] 0 signs the tx binding as a digest, 1 hashes it first, 2 signs the binding with its halves
 * swapped (the glasses' own tx). [said] gets what happened, for the log.
 */
internal class ProbeConstellationTrust(
    private val key: PrivateKey,
    private val publicKey: ByteArray,
    private val version: Long,
    private val salt: ByteArray,
    private val tag: Int,
    private val sign: Int,
    private val said: (String) -> Unit,
) : TrustExchange {
    override suspend fun run(connection: DataXConnection, binding: HandshakeBinding): TrustResult {
        val ours = connection.openChannel(SERVICE)
        val keyTag = sha(sha(publicKey), if (tag == 1) sha(salt) else salt).copyOf(8)
        val message = Wire.fieldBytes(1, keyTag) + Wire.fieldBytes(2, signature(challenge(binding))) +
            Wire.fieldVarint(3, version) + Wire.fieldString(5, "acdc-android:8.0.0.0.0")
        said("sent tag ${keyTag.toHexString()} tagMode $tag signMode $sign")
        connection.send(ours, message, messageType = 1, serviceId = SERVICE)
        val ourStatus = DataXConnection.mirror(ours)
        var txOk = false
        var rxOk = false
        var refusal: Int? = null
        val deferred = mutableListOf<DataXMessage>()
        try {
            withTimeoutOrNull(5_000) {
                while (!(txOk && rxOk) && refusal == null) {
                    val received = connection.receive()
                    val info = received.info
                    when {
                        info.flags.hasError && info.channelId == ourStatus -> if (info.errorId == 0) txOk = true else refusal = info.errorId
                        DataXConnection.hasOriginatorBit(info.channelId) && info.serviceId == SERVICE && info.messageType == 1 -> {
                            said("their EnableTrust ${received.payload.toHexString()}")
                            connection.sendStatus(DataXConnection.mirror(info.channelId), 0)
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

    private fun challenge(binding: HandshakeBinding): ByteArray {
        if (sign != 2) return binding.txChallenge()
        fun field(name: String) = HandshakeBinding::class.java.getDeclaredField(name).apply { isAccessible = true }.get(binding) as ByteArray
        return sha(sha(field("mySeed"), field("myPub")), sha(field("glChallenge"), field("glReqPub")))
    }

    private fun signature(challenge: ByteArray): ByteArray {
        val signer = Signature.getInstance(if (sign == 1) "SHA256withECDSA" else "NONEwithECDSA")
        signer.initSign(key)
        signer.update(challenge)
        val der = signer.sign()
        // DER: 30 len 02 lr r 02 ls s.
        val rLen = der[3].toInt()
        val r = BigInteger(1, der.copyOfRange(4, 4 + rLen))
        val sLen = der[5 + rLen].toInt()
        var s = BigInteger(1, der.copyOfRange(6 + rLen, 6 + rLen + sLen))
        if (s > ORDER.shiftRight(1)) s = ORDER.subtract(s)
        return fixed(r) + fixed(s)
    }

    private fun fixed(n: BigInteger): ByteArray = n.toByteArray().let { b -> ByteArray(32 - minOf(32, b.size)) + b.takeLast(32).toByteArray() }

    private fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach(::update)
        digest()
    }

    private companion object {
        const val SERVICE = 79
        val ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    }
}
