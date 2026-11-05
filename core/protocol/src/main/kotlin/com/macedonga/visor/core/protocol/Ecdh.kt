package com.macedonga.visor.core.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/** ECDH helpers on secp256r1, NIST P-256. */
object Ecdh {

    private const val COORDINATE_BYTES = 32

    private val parameterSpec: ECParameterSpec by lazy {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec(EC_CURVE))
        parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    /** Generate an ephemeral secp256r1 key pair. */
    fun generateEphemeral(): EphemeralKeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(EC_CURVE))
        val pair = generator.generateKeyPair()
        return EphemeralKeyPair(pair.private, rawFromPublicKey(pair.public))
    }

    /** Rebuild a private key from its 32-byte big-endian scalar. */
    fun privateKeyFromScalar(scalar: ByteArray): PrivateKey {
        require(scalar.size == COORDINATE_BYTES) {
            "private scalar must be $COORDINATE_BYTES bytes, got ${scalar.size}"
        }
        return KeyFactory.getInstance("EC")
            .generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), parameterSpec))
    }

    /** Reconstruct a public key from 64 raw bytes of `x ‖ y`. */
    fun publicKeyFromRaw(peerPublic64: ByteArray): PublicKey {
        require(peerPublic64.size == 64) {
            "peer public key must be 64 bytes, got ${peerPublic64.size}"
        }
        val x = BigInteger(1, peerPublic64.copyOfRange(0, COORDINATE_BYTES))
        val y = BigInteger(1, peerPublic64.copyOfRange(COORDINATE_BYTES, 64))
        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), parameterSpec))
    }

    /** The wire form of a public key: 64 raw bytes of `x ‖ y`. */
    fun rawFromPublicKey(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        return fixedWidth(point.affineX) + fixedWidth(point.affineY)
    }

    /** ECDH key agreement to a 32-byte shared secret, the raw X coordinate of the shared point. */
    fun computeSharedSecret(privateKey: PrivateKey, peerPublic64: ByteArray): ByteArray {
        val peer = publicKeyFromRaw(peerPublic64)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peer, true)
        return agreement.generateSecret()
    }

    /** A coordinate as exactly 32 bytes, big endian. */
    private fun fixedWidth(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == COORDINATE_BYTES -> raw
            raw.size > COORDINATE_BYTES -> raw.copyOfRange(raw.size - COORDINATE_BYTES, raw.size)
            else -> ByteArray(COORDINATE_BYTES - raw.size) + raw
        }
    }
}

/** An ephemeral key pair with its public half already in wire form. */
class EphemeralKeyPair(val privateKey: PrivateKey, val publicKey64: ByteArray)
