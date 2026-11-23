@file:OptIn(ExperimentalForeignApi::class)

package com.macedonga.visor.core.protocol

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.refTo
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun timeZoneId(): String = NSTimeZone.localTimeZone.name

actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    if (size > 0) check(SecRandomCopyBytes(kSecRandomDefault, size.convert(), out.refTo(0)) == errSecSuccess) { "no secure random bytes" }
    return out
}

actual class Monitor actual constructor() {
    private val lock = NSRecursiveLock()

    actual fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
