package com.macedonga.visor.core.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.protocol.toBytes
import com.macedonga.visor.core.session.DataXConnection
import java.util.TimeZone
import java.util.UUID

/** The glasses' device info service, where the phone sets their clock. */
const val SVC_DEVICE_INFO: Int = 22

/** TX: DeviceInfoRequest {1: Timestamp {1: seconds, 2: nanos}, 2: time zone id, 3: request uuid}. */
const val MT_DEVICE_INFO_REQUEST: Int = 1

/**
 * Set the glasses' clock and time zone to the phone's, as the Meta app does on every connect. Nothing else sets it,
 * and every capture is stamped with it: a pair whose clock fell back after a reboot files new photos weeks ago.
 */
suspend fun syncClock(connection: DataXConnection) {
    val nowMillis = System.currentTimeMillis()
    val requestId = UUID.randomUUID().toBytes()
    val timestamp = Wire.fieldVarint(1, Math.floorDiv(nowMillis, 1000L)) + Wire.fieldVarint(2, Math.floorMod(nowMillis, 1000L) * 1_000_000L)
    val payload = Wire.fieldBytes(1, timestamp) + Wire.fieldString(2, TimeZone.getDefault().id) + Wire.fieldBytes(3, requestId)
    connection.send(connection.openChannel(SVC_DEVICE_INFO), payload, messageType = MT_DEVICE_INFO_REQUEST, serviceId = SVC_DEVICE_INFO)
}
