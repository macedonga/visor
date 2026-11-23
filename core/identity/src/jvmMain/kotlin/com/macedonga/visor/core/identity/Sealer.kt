package com.macedonga.visor.core.identity

/** Encrypts what the store keeps on disk: the Android Keystore on the phone. */
interface Sealer {
    fun seal(data: ByteArray): ByteArray

    fun unseal(blob: ByteArray): ByteArray
}

/** The sealed blob would not open, or would not seal. */
class SealingException(message: String, cause: Throwable? = null) : Exception(message, cause)
