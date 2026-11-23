package com.macedonga.visor.core.transport

import com.macedonga.visor.core.protocol.currentTimeMillis
import kotlin.concurrent.Volatile

/** When a link last received, cheaply enough to sit on the read path of a video stream. */
class LinkMeter : WireLog {

    @Volatile
    var lastRxAt = 0L
        private set

    override fun trace(outbound: Boolean, frame: ByteArray) {
        if (outbound) return
        lastRxAt = currentTimeMillis()
    }
}
