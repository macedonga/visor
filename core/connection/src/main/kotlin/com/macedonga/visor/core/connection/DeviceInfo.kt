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
    /** The classic (BR/EDR) address (field 6), as AA:BB:CC:DD:EE:FF. */
    val btcAddress: String = "",
)

/** A build fingerprint's parts, "Meta/<product>/<device>:14/<id>/<firmware>:<type>/release-keys"; [device] lowercase. */
data class Fingerprint(val product: String, val device: String, val firmware: String, val type: String) {
    /** The device type Meta's update and pack servers know the glasses by. */
    val otaType: String get() = "ota.$product.$type"
}

/** Null without a fingerprint or for one too short to name the device; a missing firmware part reads empty. */
fun parseBuild(build: String?): Fingerprint? {
    val parts = build?.split('/')?.takeIf { it.size > 2 } ?: return null
    val release = parts.getOrNull(4)
    return Fingerprint(
        product = parts[1],
        device = parts[2].substringBefore(':').lowercase(),
        firmware = release?.substringBefore(':').orEmpty(),
        type = release?.substringAfter(':', "user").orEmpty(),
    )
}

/** The Meta app's product ids by the device codename a build fingerprint names. */
private val PRODUCT_IDS = mapOf(
    "stella" to 257, "hammerhead" to 258, "greatwhite" to 259, "zebra" to 262, "lager" to 263, "mako" to 264,
    "pylades" to 265, "greathammerhead" to 266, "topaz" to 267, "orca" to 268, "oatmeal" to 269, "diamond" to 514,
    "colada" to 770, "tiki" to 771, "swiftlet" to 1537, "ithaca" to 1538, "amethyst" to 1793, "solvang" to 2049,
)

fun productId(build: String?): Int? = parseBuild(build)?.device?.let { PRODUCT_IDS[it] }

// Meta Ray-Ban Display.
private val DISPLAY_PRODUCTS = setOf(259, 270, 271)

fun isDisplayProduct(product: Int?): Boolean = product in DISPLAY_PRODUCTS

/** The name Meta sells a model under, for the product ids Visor has seen named; null for the others. */
fun productName(product: Int?): String? = when {
    product == 257 -> "Ray-Ban Stories"
    isDisplayProduct(product) -> "Meta Ray-Ban Display"
    product == 264 -> "Oakley Meta Vanguard"
    product == 265 -> "Oakley Meta HSTN"
    product == 266 -> "Ray-Ban Meta (Gen 2)"
    else -> null
}

// Audio-only glasses: orca refuses the captures service and Wi-Fi (#80).
private val NO_CAMERA_PRODUCTS = setOf(268)

/** False for glasses with no camera; true when the product is not known. */
fun hasCamera(product: Int?): Boolean = product !in NO_CAMERA_PRODUCTS

/** Whether a build fingerprint is a Meta Ray-Ban Display's. */
fun isDisplayBuild(build: String?): Boolean = isDisplayProduct(productId(build))

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
        btcAddress = String(fields.bytes(6), Charsets.ISO_8859_1),
    )
}
