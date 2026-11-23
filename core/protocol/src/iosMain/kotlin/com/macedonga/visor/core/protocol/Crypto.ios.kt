@file:OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)

package com.macedonga.visor.core.protocol

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

private fun ByteArray.ref() = if (isEmpty()) null else refTo(0)

internal actual fun sha256(vararg parts: ByteArray): ByteArray {
    val data = parts.fold(ByteArray(0), ByteArray::plus)
    val out = UByteArray(CC_SHA256_DIGEST_LENGTH)
    CC_SHA256(data.ref(), data.size.convert(), out.refTo(0))
    return out.asByteArray()
}

internal actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    CCHmac(kCCHmacAlgSHA256.convert(), key.ref(), key.size.convert(), data.ref(), data.size.convert(), out.refTo(0))
    return out
}

internal actual fun aesCbc(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
    require(data.size % AirShieldCipher.BLOCK == 0) { "AES-CBC input must be whole blocks, got ${data.size} bytes" }
    if (data.isEmpty()) return data
    val out = ByteArray(data.size)
    memScoped {
        val moved = alloc<size_tVar>()
        // No options: CBC, no padding.
        val status = CCCrypt(
            (if (encrypt) kCCEncrypt else kCCDecrypt).convert(), kCCAlgorithmAES.convert(), 0.convert(),
            key.refTo(0), key.size.convert(), iv.refTo(0),
            data.refTo(0), data.size.convert(), out.refTo(0), out.size.convert(), moved.ptr,
        )
        check(status == kCCSuccess) { "CCCrypt failed with $status" }
    }
    return out
}
