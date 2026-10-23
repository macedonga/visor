package com.macedonga.visor.core.identity.firmware

import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.language.constellationPost
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A full firmware image Meta offers for the glasses. The glasses fetch [url] themselves over their own Wi-Fi; the
 * install message carries the fields the Meta app puts in it.
 */
data class FirmwareOffer(
    val baseVersion: String,
    val targetVersion: String,
    /** Meta's name for the build; empty when Meta gives none. */
    val displayName: String,
    val url: String,
    val size: Long,
    val checksum: String,
    /** From the offer's install options: the payload's headers and offset inside the image. */
    val metaHeaders: String,
    val payloadOffset: Long,
    val kekId: Long?,
    val kekHash: String,
    /** The session the offer was asked under; the install names its update by it. */
    val sessionId: String,
)

private const val UPDATES_DOC = "155904535318377635033579516369"

/**
 * FetchConstellationUpdates, the Meta app's own query for the glasses' firmware: the full image newer than
 * [firmware], or null when there is none. [token] is the AR token from the login.
 */
fun fetchFirmwareOffer(
    token: Token,
    serial: String,
    deviceType: String,
    firmware: String,
    http: HttpPost = UrlConnectionPost,
): FirmwareOffer? {
    val sessionId = UUID.randomUUID().toString()
    val info = buildJsonObject {
        put("device_type", deviceType)
        put("device_serial", serial)
        put("device_firmware_version", firmware)
        put("device_ota_session_id", sessionId)
        put("device_feature_list", JsonArray(emptyList()))
        put("device_artifact_list", JsonArray(emptyList()))
        put("device_firmware_full_update_only", true)
        put("device_firmware_rollout_token", JsonNull)
        put("device_manage_mode", JsonNull)
        // No constellation device is paired, so the app sends all three as null.
        put("constellation_device_type", JsonNull)
        put("constellation_device_firmware_version", JsonNull)
        put("constellation_device_serial", JsonNull)
    }
    val (status, json) = constellationPost(token, "FetchConstellationUpdates", UPDATES_DOC, info, http)
    val responses = json.path("data", "xar_constellation_updates")?.get("constellation_updates_response_info") as? JsonArray
        ?: throw TokenError("Meta answered $status without updates")
    val offer = responses.firstNotNullOfOrNull { (it.jsonObject["firmware_update_info"] as? JsonObject) } ?: return null
    val url = offer.text("file_uri") ?: throw TokenError("Meta's firmware offer has no file")
    // The options come as an object or as the JSON text of one.
    val options = when (val raw = offer["extra_install_options"]) {
        is JsonObject -> raw
        is JsonPrimitive -> runCatching { Json.parseToJsonElement(raw.content).jsonObject }.getOrNull()
        else -> null
    }
    return FirmwareOffer(
        baseVersion = offer.text("base_version").orEmpty(),
        targetVersion = offer.text("target_version").orEmpty(),
        displayName = offer.text("build_number_display_name").orEmpty(),
        url = url,
        size = (offer["file_size"] as? JsonPrimitive)?.longOrNull ?: offer.text("file_size")?.toLongOrNull() ?: 0,
        checksum = offer.text("file_checksum").orEmpty(),
        metaHeaders = options?.text("headers").orEmpty(),
        payloadOffset = (options?.get("payloadOffset") as? JsonPrimitive)?.longOrNull ?: 0,
        kekId = (offer["kek_id"] as? JsonPrimitive)?.longOrNull,
        kekHash = offer.text("kek_hash").orEmpty(),
        sessionId = sessionId,
    )
}

private fun JsonObject.path(vararg keys: String): JsonObject? =
    keys.fold(this as JsonObject?) { o, k -> o?.get(k) as? JsonObject }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
