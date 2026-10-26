package com.macedonga.visor.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder

/**
 * svc101, motion_activity_service: the Display's driving detection subscribes (type 0) and asks (type 2) whether the
 * phone sees its owner driving. Visor has no activity recognition, so it answers every message as the Meta app does
 * with driving detection off: MotionActivityServiceError {1 SETTINGS_NOT_ENABLED} (type 3), read as "not driving".
 */
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
