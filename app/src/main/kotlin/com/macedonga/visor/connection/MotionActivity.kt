package com.macedonga.visor.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder

/** svc101: no activity recognition here, so every message gets SETTINGS_NOT_ENABLED, read as "not driving". */
object MotionActivity : Responder {
    const val SVC = 101
    private const val MT_SERVICE_ERROR = 3
    private const val SETTINGS_NOT_ENABLED = 2L

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.flags.isClosed) return
        connection.send(
            DataXConnection.mirror(message.info.channelId),
            Wire.fieldVarint(1, SETTINGS_NOT_ENABLED),
            messageType = MT_SERVICE_ERROR,
        )
    }
}
