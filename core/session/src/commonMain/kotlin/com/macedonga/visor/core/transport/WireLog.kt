package com.macedonga.visor.core.transport

/**
 * Every SDU in both directions, so a session that misbehaves can be diffed against one that did
 * not.
 */
fun interface WireLog {

    /** [outbound] is true for a frame the phone sent. */
    fun trace(outbound: Boolean, frame: ByteArray)
}
