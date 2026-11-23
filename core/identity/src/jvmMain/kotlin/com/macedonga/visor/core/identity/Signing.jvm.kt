package com.macedonga.visor.core.identity

import java.math.BigInteger
import java.security.PrivateKey
import java.security.Signature

/** The secp256r1 group order. */
private val SECP256R1_ORDER = BigInteger(
    "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16,
)

private val HALF_ORDER: BigInteger = SECP256R1_ORDER.shiftRight(1)

/** A SHA-256 [digest] signed as the glasses check signatures: NONEwithECDSA, r then low s, 64 bytes. */
internal fun signDigest(key: PrivateKey, digest: ByteArray): ByteArray {
    val signer = Signature.getInstance("NONEwithECDSA")
    signer.initSign(key)
    signer.update(digest)
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

/** The next DER element as a non-negative integer. */
internal fun DerReader.readInteger(): BigInteger = BigInteger(1, read(DerReader.INTEGER))
