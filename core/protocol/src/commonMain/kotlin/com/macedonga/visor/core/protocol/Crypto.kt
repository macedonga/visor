package com.macedonga.visor.core.protocol

// The platform's primitives under AirShield: JCA on Android, CommonCrypto on iOS.

/** SHA-256 over [parts] in order. */
expect fun sha256(vararg parts: ByteArray): ByteArray

internal expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/** AES-CBC without padding: [data] is whole 16-byte blocks. */
internal expect fun aesCbc(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray

/** Equal contents, in a time that does not depend on where they differ. */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
