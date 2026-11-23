package com.macedonga.visor.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Raised when a frame's MAC or length does not verify. */
class AuthenticationException(message: String) : Exception(message)

/** AirShield per-frame cipher: AES-256-CBC plus HMAC-SHA256, encrypt-then-MAC. */
object AirShieldCipher {

    /** Truncated HMAC-SHA256. */
    const val TAG_SIZE: Int = 8

    /** AES block and CBC block. */
    const val BLOCK: Int = 16

    /** Modified PKCS#7: the pad byte is `pad_len + 0xC0`. */
    const val PAD_OFFSET: Int = 0xC0

    /** The 4-byte little-endian word ahead of the seq in the MAC: 02 02 00, then the link's quirks. */
    const val COUNTER_PREFIX: Int = 0x00000202

    /** Modified PKCS#7. An aligned input gets a whole block, so there is always a pad. */
    fun pad(data: ByteArray): ByteArray {
        val padLen = BLOCK - (data.size % BLOCK)
        val marker = ((padLen + PAD_OFFSET) and 0xFF).toByte()
        return data + ByteArray(padLen) { marker }
    }

    /** [data] without the pad [pad] adds; data that does not end in one comes back whole. */
    fun stripPad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val last = data[data.size - 1].toInt() and 0xFF
        val padLen = (last - PAD_OFFSET) and 0xFF
        if (padLen !in 1..BLOCK || padLen > data.size) return data
        for (i in data.size - padLen until data.size) {
            if ((data[i].toInt() and 0xFF) != last) return data
        }
        return data.copyOfRange(0, data.size - padLen)
    }

    internal fun aesCbc(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )
        return cipher.doFinal(data)
    }

    internal fun le32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}

/** One direction's crypto on AirShield DATA frame bodies: [seal] for tx, [unseal] for rx. */
class FrameCrypto(keys: SessionKeys, initSeq: Int = 0, quirks: Int = 0) {
    private val aesKey: ByteArray
    private val hmacKey: ByteArray
    private var iv: ByteArray
    private var seq: Int = initSeq

    // The quirks byte is the word's top byte: 02 02 00 01 under QCS7 (libairshield Framing, #80).
    private val macPrefix = AirShieldCipher.le32(AirShieldCipher.COUNTER_PREFIX or ((quirks and 0xFF) shl 24))

    init {
        require(keys.aesKey.size == 32 && keys.hmacKey.size == 32 && keys.iv.size == AirShieldCipher.BLOCK) {
            "bad key/iv sizes for AES-256-CBC + HMAC-SHA256"
        }
        aesKey = keys.aesKey
        hmacKey = keys.hmacKey
        iv = keys.iv
    }

    private fun mac(blockCount: Int, ciphertext: ByteArray): ByteArray {
        // MAC input: prefix (LE u32), seq (LE u32), blockCount, ciphertext.
        val macInput = macPrefix + AirShieldCipher.le32(seq) +
            byteArrayOf(blockCount.toByte()) + ciphertext
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        return hmac.doFinal(macInput).copyOf(AirShieldCipher.TAG_SIZE)
    }

    fun seal(plaintext: ByteArray): ByteArray {
        val ciphertext = AirShieldCipher.aesCbc(aesKey, iv, AirShieldCipher.pad(plaintext), encrypt = true)
        val blockCount = (ciphertext.size / AirShieldCipher.BLOCK) - 1
        require(blockCount in 0..0xFF) { "plaintext too large for a single AirShield frame" }
        val tag = mac(blockCount, ciphertext)
        val frame = tag + byteArrayOf(blockCount.toByte()) + ciphertext
        iv = ciphertext.copyOfRange(ciphertext.size - AirShieldCipher.BLOCK, ciphertext.size)
        seq += 1
        return frame
    }

    /** Verify and decrypt one DATA frame body. The pad stays: DataX takes it off with [AirShieldCipher.stripPad]. */
    fun unseal(frame: ByteArray): ByteArray {
        if (frame.size < AirShieldCipher.TAG_SIZE + 1 + AirShieldCipher.BLOCK) {
            throw AuthenticationException("frame too short")
        }
        val tag = frame.copyOfRange(0, AirShieldCipher.TAG_SIZE)
        val blockCount = frame[AirShieldCipher.TAG_SIZE].toInt() and 0xFF
        val ciphertext = frame.copyOfRange(AirShieldCipher.TAG_SIZE + 1, frame.size)
        if (ciphertext.size != (blockCount + 1) * AirShieldCipher.BLOCK) {
            throw AuthenticationException("ciphertext length does not match the block count")
        }
        // Constant-time compare.
        if (!MessageDigest.isEqual(tag, mac(blockCount, ciphertext))) {
            throw AuthenticationException("MAC verification failed")
        }
        val plaintext = AirShieldCipher.aesCbc(aesKey, iv, ciphertext, encrypt = false)
        iv = ciphertext.copyOfRange(ciphertext.size - AirShieldCipher.BLOCK, ciphertext.size)
        seq += 1
        return plaintext
    }
}
