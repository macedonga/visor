package com.macedonga.visor.core.protocol

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

actual fun sha256(vararg parts: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach(digest::update)
    return digest.digest()
}

internal actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

internal actual fun aesCbc(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(data)
}
