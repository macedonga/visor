package com.macedonga.visor.core.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.protocol.LinkUuid
import com.macedonga.visor.core.protocol.currentTimeMillis
import com.macedonga.visor.core.protocol.timeZoneId
import com.macedonga.visor.core.session.DataXConnection

/** The glasses' device info service, where the phone sets their clock. */
const val SVC_DEVICE_INFO: Int = 22

/** TX: DeviceInfoRequest {1: Timestamp {1: seconds, 2: nanos}, 2: time zone id, 3: request uuid}. */
const val MT_DEVICE_INFO_REQUEST: Int = 1

/**
 * Set the glasses' clock and time zone to the phone's, as the Meta app does on every connect. Nothing else sets it,
 * and every capture is stamped with it: a pair whose clock fell back after a reboot files new photos weeks ago.
 */
suspend fun syncClock(connection: DataXConnection) {
    val nowMillis = currentTimeMillis()
    val requestId = LinkUuid.random()
    val timestamp = Wire.fieldVarint(1, nowMillis.floorDiv(1000L)) + Wire.fieldVarint(2, nowMillis.mod(1000L) * 1_000_000L)
    val payload = Wire.fieldBytes(1, timestamp) + Wire.fieldString(2, timeZoneId()) + Wire.fieldBytes(3, requestId)
    connection.send(connection.openChannel(SVC_DEVICE_INFO), payload, messageType = MT_DEVICE_INFO_REQUEST, serviceId = SVC_DEVICE_INFO)
}
