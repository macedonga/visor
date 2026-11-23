@file:OptIn(ExperimentalForeignApi::class)

package com.macedonga.visor.core.protocol

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard
import platform.Security.kSecKeyAlgorithmECDSASignatureDigestX962SHA256
import platform.posix.memcpy

actual interface EcPrivateKey

/** The key as its X9.63 bytes, `04 ‖ x ‖ y ‖ d`. A SecKey is made from them only while it is used. */
internal class X963PrivateKey(val x963: ByteArray) : EcPrivateKey

actual object Ecdh {

    actual fun generateEphemeral(): EphemeralKeyPair {
        val attributes = attributes(keyClass = null)
        val key = SecKeyCreateRandomKey(attributes, null)
        CFRelease(attributes)
        checkNotNull(key) { "could not make a P-256 key" }
        val x963 = try {
            checkNotNull(SecKeyCopyExternalRepresentation(key, null)) { "could not export a P-256 key" }.consume()
        } finally {
            CFRelease(key)
        }
        check(x963.size == 97 && x963[0] == 4.toByte()) { "unexpected P-256 key export, ${x963.size} bytes" }
        return EphemeralKeyPair(X963PrivateKey(x963), x963.copyOfRange(1, 65))
    }

    actual fun computeSharedSecret(privateKey: EcPrivateKey, peerPublic64: ByteArray): ByteArray {
        require(peerPublic64.size == 64) { "peer public key must be 64 bytes, got ${peerPublic64.size}" }
        val mine = key((privateKey as X963PrivateKey).x963, kSecAttrKeyClassPrivate)
        try {
            val peer = key(byteArrayOf(4) + peerPublic64, kSecAttrKeyClassPublic)
            try {
                val parameters = attributes(keyClass = null, sized = false)
                try {
                    return checkNotNull(
                        SecKeyCopyKeyExchangeResult(mine, kSecKeyAlgorithmECDHKeyExchangeStandard, peer, parameters, null),
                    ) { "ECDH failed" }.consume()
                } finally {
                    CFRelease(parameters)
                }
            } finally {
                CFRelease(peer)
            }
        } finally {
            CFRelease(mine)
        }
    }
}

/** ECDSA over a 32-byte digest with the key [scalar], whose public half is [public64]: a DER X9.62 signature. */
fun ecdsaSignDigest(scalar: ByteArray, public64: ByteArray, digest: ByteArray): ByteArray {
    val key = key(byteArrayOf(4) + public64 + scalar, kSecAttrKeyClassPrivate)
    try {
        val data = digest.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), digest.size.convert()) }
        try {
            return checkNotNull(SecKeyCreateSignature(key, kSecKeyAlgorithmECDSASignatureDigestX962SHA256, data, null)) {
                "could not sign"
            }.consume()
        } finally {
            CFRelease(data)
        }
    } finally {
        CFRelease(key)
    }
}

private fun key(x963: ByteArray, keyClass: CFStringRef?): SecKeyRef {
    val attributes = attributes(keyClass)
    val data = x963.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), x963.size.convert()) }
    try {
        return checkNotNull(SecKeyCreateWithData(data, attributes, null)) { "not a P-256 key" }
    } finally {
        CFRelease(data)
        CFRelease(attributes)
    }
}

/** A P-256 key's attributes; [sized] false gives an empty dictionary, for the exchange's parameters. */
private fun attributes(keyClass: CFStringRef?, sized: Boolean = true): CFMutableDictionaryRef {
    val dictionary = checkNotNull(
        CFDictionaryCreateMutable(null, 3.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr),
    )
    if (!sized) return dictionary
    CFDictionarySetValue(dictionary, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
    if (keyClass != null) CFDictionarySetValue(dictionary, kSecAttrKeyClass, keyClass)
    val bits = memScoped {
        val value = alloc<IntVar>()
        value.value = 256
        CFNumberCreate(null, kCFNumberIntType, value.ptr)
    }
    CFDictionarySetValue(dictionary, kSecAttrKeySizeInBits, bits)
    CFRelease(bits)
    return dictionary
}

/** The bytes, and the CFData released. */
private fun CFDataRef.consume(): ByteArray {
    try {
        val out = ByteArray(CFDataGetLength(this).toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), CFDataGetBytePtr(this), out.size.convert()) }
        return out
    } finally {
        CFRelease(this)
    }
}
