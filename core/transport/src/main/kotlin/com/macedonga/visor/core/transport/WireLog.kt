package com.macedonga.visor.core.transport

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalTime

/**
 * Every SDU in both directions, so a session that misbehaves can be diffed against one that did
 * not.
 */
fun interface WireLog {

    /** [outbound] is true for a frame the phone sent. */
    fun trace(outbound: Boolean, frame: ByteArray)
}

/** A [WireLog] that appends `direction timestamp length hex` to a file, one line per SDU. */
class FileWireLog(private val file: File) : WireLog, Closeable {

    // Starts over past 64 MB: one imported video is about 35 MB of hex.
    private var written = file.length().takeIf { it < LIMIT } ?: 0L
    private var writer = PrintWriter(BufferedWriter(FileWriter(file, file.length() < LIMIT), 1 shl 16))
    private var flushedAt = 0L

    // It runs on the link's read path, so it formats by hand and flushes at most four times a second.
    @Synchronized
    override fun trace(outbound: Boolean, frame: ByteArray) {
        val now = System.currentTimeMillis()
        writer.append(if (outbound) "tx " else "rx ")
            .append((now / 1000).toString()).append('.').append((now % 1000).toString().padStart(3, '0'))
            .append(' ').append(frame.size.toString()).append(' ').append(frame.toHexString()).append('\n')
        written += frame.size * 2L + 30
        if (written > LIMIT) {
            writer.close()
            writer = PrintWriter(BufferedWriter(FileWriter(file, false), 1 shl 16))
            written = 0
        }
        if (now - flushedAt >= 250) {
            writer.flush()
            flushedAt = now
        }
    }

    @Synchronized
    override fun close() {
        writer.close()
    }

    private companion object {
        const val LIMIT = 64L shl 20
    }
}

/** DataX trace lines, timestamped, in a file that starts over past 32 MB; flushed at most four times a second. */
class FileTrace(private val file: File) : (String) -> Unit {

    private var written = file.length().takeIf { it < LIMIT } ?: 0L
    private var out = PrintWriter(BufferedWriter(FileWriter(file, written > 0), 1 shl 16))
    private var flushedAt = 0L

    @Synchronized
    override fun invoke(line: String) {
        val now = System.currentTimeMillis()
        out.append(LocalTime.now().toString()).append(' ').append(line).append('\n')
        written += line.length + 20L
        if (written > LIMIT) {
            out.close()
            out = PrintWriter(BufferedWriter(FileWriter(file, false), 1 shl 16))
            written = 0
        }
        if (now - flushedAt >= 250) {
            out.flush()
            flushedAt = now
        }
    }

    private companion object {
        const val LIMIT = 32L shl 20
    }
}
