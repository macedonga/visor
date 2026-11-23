package com.macedonga.visor.core.protocol

import java.security.SecureRandom

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun timeZoneId(): String = java.util.TimeZone.getDefault().id

private val random = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

actual class Monitor actual constructor() {
    actual fun <T> locked(block: () -> T): T = synchronized(this, block)
}
