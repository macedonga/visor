package com.macedonga.visor.core.protocol

/**
 * svc81, btc_profiles_activation: the phone asks the glasses to start their ANCS client, or their MAP or PBAP client
 * towards the phone. On Gen 2, MAP and PBAP connect only once the iPhone's iAP2 link is up.
 */
object BtcProfiles {
    const val SERVICE = 81

    /** Empty: the glasses enable ANCS, and answer [ANCS_ENABLE_RESPONSE] on success only. */
    const val ANCS_ENABLE_REQUEST = 1

    /** One byte, 0xFF. */
    const val ANCS_ENABLE_RESPONSE = 2

    /** One raw byte: [PROFILE_PBAP] or [PROFILE_MAP]. */
    const val PROFILE_CONNECT_REQUEST = 5

    const val PBAP_STATE_EVENT = 20
    const val MAP_STATE_EVENT = 21
    const val ANCS_STATE_EVENT = 22

    const val PROFILE_PBAP = 0x11
    const val PROFILE_MAP = 0x12

    fun profileConnect(profile: Int): ByteArray = byteArrayOf(profile.toByte())

    fun ancsEnabled(payload: ByteArray): Boolean = payload.size == 1 && payload[0] == 0xFF.toByte()
}

/**
 * svc28 DeviceState field 19 (type 2025): the glasses' own links to the phone. ANCS, MAP and PBAP are 0 UNKNOWN,
 * 1 READY_TO_CONNECT, 2 CONNECTING, 3 CONNECTED, 4 DISCONNECTED; Classic is 0 UNKNOWN, 1 CONNECTED, 2 DISCONNECTED.
 */
data class PhoneProfiles(val ancs: Int, val map: Int, val pbap: Int, val classic: Int) {
    companion object {
        const val DEVICE_STATE_FIELD = 19
        const val CONNECTED = 3
        const val CLASSIC_CONNECTED = 1

        fun parse(field: ByteArray): PhoneProfiles {
            val f = Wire.parse(field)
            return PhoneProfiles(f.varint(1).toInt(), f.varint(2).toInt(), f.varint(3).toInt(), f.varint(4).toInt())
        }
    }
}
