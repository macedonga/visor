package com.macedonga.visor.core.connection

import com.macedonga.visor.core.protocol.Wire

/** svc100 (peer_build_info) device identity: build fingerprint and serial. */
data class DeviceInfo(
    val build: String,
    val serial: String,
    val deviceUuid: String = "",
    val model: String = "",
    /** The wearables release version (field 9), e.g. 129.0.0.218.272: the store asks by it. */
    val wearablesRelease: String = "",
)

private fun uuidShaped(text: String): Boolean =
    text.length == 36 && text.split("-").map { it.length } == listOf(8, 4, 4, 4, 12)

/** Decode a svc100/3 DeviceInfo push. */
fun decodePeerDeviceInfo(payload: ByteArray): DeviceInfo {
    val fields = Wire.parse(payload)
    val deviceUuid = String(fields.bytes(4), Charsets.ISO_8859_1)
    return DeviceInfo(
        build = String(fields.bytes(1), Charsets.ISO_8859_1),
        serial = String(fields.bytes(2), Charsets.ISO_8859_1),
        deviceUuid = if (uuidShaped(deviceUuid)) deviceUuid else "",
        model = String(fields.bytes(8), Charsets.ISO_8859_1),
        wearablesRelease = String(fields.bytes(9), Charsets.ISO_8859_1),
    )
}
