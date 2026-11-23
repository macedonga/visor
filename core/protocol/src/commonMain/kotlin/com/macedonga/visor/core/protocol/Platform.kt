package com.macedonga.visor.core.protocol

import kotlin.time.TimeSource

// What common code needs from the platform beyond the crypto: a clock, randomness, a lock and a formatter.

private val start = TimeSource.Monotonic.markNow()

/** A monotonic clock in nanoseconds, for intervals only, as System.nanoTime is. */
fun nanoTime(): Long = start.elapsedNow().inWholeNanoseconds

/** Wall-clock time in milliseconds since the epoch. */
expect fun currentTimeMillis(): Long

/** The phone's time zone, as an IANA id such as Europe/Rome. */
expect fun timeZoneId(): String

/** [size] bytes from the platform's secure random source. */
expect fun secureRandomBytes(size: Int): ByteArray

/** A reentrant lock, as a JVM monitor is. */
expect class Monitor() {
    fun <T> locked(block: () -> T): T
}

/**
 * A small `String.format` for common code: `%s`, `%d`, `%x`, `%0Nx` (zero-padded to N) and `%%`. Hex is the two's
 * complement of a negative number, as on the JVM.
 */
fun String.fmt(vararg args: Any?): String {
    val out = StringBuilder()
    var next = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '%' || i + 1 >= length) {
            out.append(c)
            i++
            continue
        }
        var j = i + 1
        if (this[j] == '%') {
            out.append('%')
            i = j + 1
            continue
        }
        val zero = this[j] == '0'
        while (j < length && this[j].isDigit()) j++
        val width = if (j > i + 1) substring(if (zero) i + 2 else i + 1, j).toIntOrNull() ?: 0 else 0
        val text = when (this[j]) {
            'd' -> args[next++].toString()
            'x' -> hex(args[next++])
            else -> args[next++].toString()
        }
        out.append(if (text.length < width) (if (zero) "0" else " ").repeat(width - text.length) + text else text)
        i = j + 1
    }
    return out.toString()
}

private fun hex(value: Any?): String = when (value) {
    is Int -> value.toUInt().toString(16)
    is Long -> value.toULong().toString(16)
    is Short -> value.toUShort().toString(16)
    is Byte -> value.toUByte().toString(16)
    else -> value.toString()
}

/** A flag that one caller raises; every later caller finds it raised, until it is lowered. */
class OnceFlag {
    private val monitor = Monitor()
    private var raised = false

    /** True for the one call that raised it. */
    fun raise(): Boolean = monitor.locked { if (raised) false else true.also { raised = true } }

    fun lower() = monitor.locked { raised = false }
}

/** An Int shared between threads. */
class SharedCounter(initial: Int = 0) {
    private val monitor = Monitor()
    private var value = initial

    fun get(): Int = monitor.locked { value }
    fun set(to: Int) = monitor.locked { value = to }
    fun incrementAndGet(): Int = monitor.locked { ++value }
}

/** Each byte as the character of the same code, ISO 8859-1. */
fun ByteArray.decodeLatin1(): String = CharArray(size) { (this[it].toInt() and 0xFF).toChar() }.concatToString()
