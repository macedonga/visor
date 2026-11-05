package com.macedonga.visor.core.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection

/** The service the canary asks. */
const val SVC_PEER_BUILD_INFO: Int = 100

/** GET_INFO, an empty ask: the phone asks the glasses, and the Display's apps ask the phone. */
const val MT_PEER_GET_INFO: Int = 1

/** APP_INFO, the phone's answer to a GET_INFO. */
const val MT_PEER_APP_INFO: Int = 2

/** RX: the glasses answer with their build info. This arriving is the canary answering. */
const val MT_PEER_DEVICE_INFO: Int = 3

private const val PLATFORM_ANDROID = 2L

/** The canary: ask svc100 for the glasses' build info. */
suspend fun askPeerDeviceInfo(connection: DataXConnection) {
    val channel = connection.openChannel(SVC_PEER_BUILD_INFO)
    connection.send(
        channel,
        ByteArray(0),
        messageType = MT_PEER_GET_INFO,
        serviceId = SVC_PEER_BUILD_INFO,
    )
}

/** AppInfo {1 platform Android, 2 appVersion, 3 osVersion, 4 manufacturer, 5 phoneId, 6 model}; empty strings left out. */
fun encodePeerAppInfo(appVersion: String, osVersion: String, manufacturer: String, phoneId: String, model: String): ByteArray =
    listOf(appVersion, osVersion, manufacturer, phoneId, model).withIndex().fold(Wire.fieldVarint(1, PLATFORM_ANDROID)) { out, (i, s) ->
        if (s.isEmpty()) out else out + Wire.fieldString(i + 2, s)
    }
