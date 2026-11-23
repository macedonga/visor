package com.macedonga.visor.core.connection

import com.macedonga.visor.core.protocol.PeerBuildInfo
import com.macedonga.visor.core.protocol.PhoneOs
import com.macedonga.visor.core.session.DataXConnection

/** The service the canary asks. */
const val SVC_PEER_BUILD_INFO: Int = PeerBuildInfo.SERVICE

/** GET_INFO, an empty ask: the phone asks the glasses, and the Display's apps ask the phone. */
const val MT_PEER_GET_INFO: Int = PeerBuildInfo.GET_INFO

/** APP_INFO, the phone's answer to a GET_INFO. */
const val MT_PEER_APP_INFO: Int = PeerBuildInfo.APP_INFO

/** RX: the glasses answer with their build info. This arriving is the canary answering. */
const val MT_PEER_DEVICE_INFO: Int = PeerBuildInfo.DEVICE_INFO

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

/** This phone's AppInfo: Android. */
fun encodePeerAppInfo(appVersion: String, osVersion: String, manufacturer: String, phoneId: String, model: String): ByteArray =
    PeerBuildInfo.appInfo(PhoneOs.ANDROID, appVersion, osVersion, manufacturer, phoneId, model)
