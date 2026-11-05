package com.macedonga.visor.core.identity.firmware

import com.macedonga.visor.core.identity.constellationPost
import com.macedonga.visor.core.identity.number
import com.macedonga.visor.core.identity.obj
import com.macedonga.visor.core.identity.path
import com.macedonga.visor.core.identity.text
import com.macedonga.visor.core.identity.token.Token
import com.macedonga.visor.core.identity.token.TokenError
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A firmware image Meta offers for the glasses: a zip of [size] bytes at [url], its SHA-256 [checksum]. The install
 * message carries the fields the Meta app puts in it.
 */
data class FirmwareOffer(
    val baseVersion: String,
    val targetVersion: String,
    /** Meta's name for the build; empty when Meta gives none. */
    val displayName: String,
    val url: String,
    val size: Long,
    val checksum: String,
    /** From the offer's install options: the payload's headers, one per line, and its offset and size inside the zip. */
    val metaHeaders: String,
    val payloadOffset: Long,
    val payloadSize: Long,
    val kekId: Long?,
    val kekHash: String,
    /** Empty when Meta gives none. */
    val releaseChannelId: String,
    /** The session the offer was asked under; the install names its update by it. */
    val sessionId: String,
)

private const val UPDATES_DOC = "155904535318377635033579516369"

/**
 * FetchConstellationUpdates, the Meta app's own query for the glasses' firmware: the image newer than [firmware], or
 * null when there is none. [token] is the AR token from the login. [fullOnly] asks for the full image, not a delta.
 */
fun fetchFirmwareOffer(
    token: Token,
    serial: String,
    deviceType: String,
    firmware: String,
    fullOnly: Boolean = true,
    // A band's query names its glasses: their OTA type, build and serial.
    constellationType: String? = null,
    constellationFirmware: String? = null,
    constellationSerial: String? = null,
): FirmwareOffer? {
    val sessionId = UUID.randomUUID().toString()
    val info = buildJsonObject {
        put("device_type", deviceType)
        put("device_serial", serial)
        put("device_firmware_version", firmware)
        put("device_ota_session_id", sessionId)
        put("device_feature_list", JsonArray(emptyList()))
        put("device_artifact_list", JsonArray(emptyList()))
        put("device_firmware_full_update_only", fullOnly)
        put("device_firmware_rollout_token", JsonNull)
        put("device_manage_mode", JsonNull)
        // The Meta app fills these only in a band's query, with its glasses' values; the glasses' own query sends null.
        put("constellation_device_type", constellationType)
        put("constellation_device_firmware_version", constellationFirmware)
        put("constellation_device_serial", constellationSerial)
    }
    val (status, json) = constellationPost(token, "FetchConstellationUpdates", UPDATES_DOC, info)
    val responses = json.path("data", "xar_constellation_updates")?.get("constellation_updates_response_info") as? JsonArray
        ?: throw TokenError("Meta answered $status without updates")
    val offer = responses.firstNotNullOfOrNull { (it as? JsonObject)?.obj("firmware_update_info") } ?: return null
    val url = offer.text("file_uri") ?: throw TokenError("Meta's firmware offer has no file")
    // The options come as an object or as the JSON text of one.
    val options = when (val raw = offer["extra_install_options"]) {
        is JsonObject -> raw
        is JsonPrimitive -> runCatching { Json.parseToJsonElement(raw.content).jsonObject }.getOrNull()
        else -> null
    }
    val size = offer.number("file_size") ?: 0L
    // headers: the lines of the zip's payload_properties.txt, an array the Meta app joins with newlines.
    val headers = when (val raw = options?.get("headers")) {
        is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
        is JsonPrimitive -> raw.contentOrNull.orEmpty()
        else -> ""
    }
    val fileSize = headers.lineSequence().firstOrNull { it.startsWith("FILE_SIZE=") }?.substringAfter('=')?.trim()?.toLongOrNull()
    return FirmwareOffer(
        baseVersion = offer.text("base_version").orEmpty(),
        targetVersion = offer.text("target_version").orEmpty(),
        displayName = offer.text("build_number_display_name").orEmpty(),
        url = url,
        size = size,
        checksum = offer.text("file_checksum").orEmpty(),
        metaHeaders = headers,
        payloadOffset = options?.number("offset") ?: options?.number("payloadOffset") ?: 0L,
        payloadSize = fileSize ?: options?.number("size") ?: size,
        kekId = offer.number("kek_id")?.takeIf { it != 0L },
        kekHash = offer.text("kek_hash").orEmpty(),
        releaseChannelId = offer.text("release_channel_id")?.takeIf { it != "0" }.orEmpty(),
        sessionId = sessionId,
    )
}
