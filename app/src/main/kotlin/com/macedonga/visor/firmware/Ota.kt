package com.macedonga.visor.firmware

import com.macedonga.visor.core.connection.SnApp
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.ServiceData

/** svc29's OTA app, as firmware and resource packs both start it, and the message types they share. */
internal object Ota {
    const val MT_INSTALL = 1
    const val MT_PROGRESS = 2
    const val MT_ERROR = 3
    const val MT_FILE_STATUS = 4
    const val MT_INSTALL_COMPLETE = 7
    // The OTA app's id in svc29 starts.
    const val APP = 1L

    /** StartApp for the OTA app, asking the glasses for [megabytes] of room. */
    fun start(otaType: String, attemptId: String, megabytes: Long, autoTransfer: Boolean): ByteArray {
        val data = listOf(SnApp.data("ota_type", otaType), SnApp.data("attempt_id", attemptId))
        return SnApp.app(
            APP,
            if (autoTransfer) data + SnApp.data("app_transfer_auto", true) else data,
            policy = Wire.fieldBytes(1, Wire.fieldVarint(1, megabytes)),
        )
    }

    /** Throws unless the start's [answer] says the app runs; already started, its workflow still takes the request. */
    fun checkStarted(answer: ServiceData) {
        val code = runCatching { Wire.parse(answer.payload).varint(1) }.getOrDefault(0L)
        if (!SnApp.started(code)) error("the glasses would not start an install (code $code)")
    }
}
