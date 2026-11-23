package com.macedonga.visor.core.protocol

/** svc100, peer build info. The phone asks the glasses; each Display app asks the phone, and waits 5 s. */
object PeerBuildInfo {
    const val SERVICE = 100

    /** Empty, either way. */
    const val GET_INFO = 1

    /** The phone's answer to a GET_INFO: [appInfo]. */
    const val APP_INFO = 2

    /** The glasses' answer to the phone's GET_INFO. */
    const val DEVICE_INFO = 3

    /**
     * AppInfo {1 platform, 2 appVersion, 3 osVersion, 4 manufacturer, 5 phoneId, 6 model}; empty strings left out.
     * Android sends its SDK level as osVersion; iOS its version string, "Apple" and the model identifier.
     */
    fun appInfo(os: PhoneOs, appVersion: String, osVersion: String, manufacturer: String, phoneId: String, model: String): ByteArray =
        listOf(appVersion, osVersion, manufacturer, phoneId, model).withIndex()
            .fold(Wire.fieldVarint(1, os.peerPlatform.toLong())) { out, (i, s) -> if (s.isEmpty()) out else out + Wire.fieldString(i + 2, s) }
}
