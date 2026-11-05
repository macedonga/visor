package com.macedonga.visor.core.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The blob is not one of ours, or it is damaged. */
class SealingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Seals data with an AES-GCM key kept in the Android Keystore. */
class KeystoreSealer(private val alias: String = DEFAULT_ALIAS) {

    fun seal(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(MAGIC)
        // The keystore picks the IV and refuses one we pick.
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "the keystore chose a ${iv.size} byte IV" }
        return MAGIC + iv + cipher.doFinal(data)
    }

    /** @throws SealingException if the blob is not ours, is damaged, or will not open. */
    fun unseal(blob: ByteArray): ByteArray {
        if (!blob.startsWithMagic()) throw SealingException("not a sealed blob")
        if (blob.size < MAGIC.size + IV_BYTES) {
            throw SealingException("the store is damaged")
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, blob, MAGIC.size, IV_BYTES),
            )
            cipher.updateAAD(MAGIC)
            cipher.doFinal(blob, MAGIC.size + IV_BYTES, blob.size - MAGIC.size - IV_BYTES)
        } catch (error: GeneralSecurityException) {
            throw SealingException("the store will not open", error)
        }
    }

    private fun ByteArray.startsWithMagic(): Boolean = size >= MAGIC.size && MAGIC.indices.all { this[it] == MAGIC[it] }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generate()
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val DEFAULT_ALIAS = "visor.identity.store"

        /** Marks a sealed blob; also the GCM associated data. */
        private val MAGIC: ByteArray = "VKS1".toByteArray(Charsets.US_ASCII)

        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
