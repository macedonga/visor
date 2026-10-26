package com.macedonga.visor.connection

import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * svc2000, media_capture_state: the glasses open it on connect and send a ConcurrentSignal (type 1) whose signal type
 * is "ready" first, then video, hyperlapse, slow motion or dynamic photo started and stopped. Nothing is asked of the
 * phone, so nothing is answered. A stop refreshes the capture list, and AutoImport takes the new capture from there.
 */
@Singleton
class CaptureState @Inject constructor(private val media: Provider<MediaImport>) : Responder {
    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.flags.isClosed || message.info.messageType != MT_CONCURRENT_SIGNAL) return
        // ConcurrentSignal field 6 is signal_type; field 5 is the source.
        val signalType = Wire.parse(message.payload).bytes(6).decodeToString()
        if (signalType.endsWith("_stopped")) media.get().refresh()
    }

    companion object {
        const val SVC = 2000
        private const val MT_CONCURRENT_SIGNAL = 1
    }
}
