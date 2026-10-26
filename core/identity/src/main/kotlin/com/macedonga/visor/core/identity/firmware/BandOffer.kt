package com.macedonga.visor.core.identity.firmware

import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.language.graphPost
import com.macedonga.visor.core.identity.token.Token
import com.macedonga.visor.core.identity.token.TokenError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A Neural Band firmware image Meta offers: the zip's address, size, SHA-256 and the KEK it is wrapped with. */
data class BandOffer(
    val baseVersion: String,
    val targetVersion: String,
    val displayName: String,
    val url: String,
    val size: Long,
    val checksum: String,
    val kekId: Long?,
)

/** The band's wrapped KEK as Meta gives it, base64, and the algorithm it names. */
data class BandKek(val encrypted: String, val algorithm: String?)

private const val SYSTEM_UPDATES_DOC = "29744781653741296994778085945"
private const val KEK_DOC = "313699925716490204858433749875"
private const val BAND_DEVICE_TYPE = "ota.ceres.user"

/**
 * FetchSystemUpdates, the Meta app's own query for the band's firmware: the full image newer than [firmware] (the
 * band's version as a decimal string), or null when there is none. [token] is the AR token from the login.
 */
fun fetchBandOffer(
    token: Token,
    serial: String,
    firmware: String,
    http: HttpPost = UrlConnectionPost,
): BandOffer? {
    val variables = buildJsonObject {
        put("fetch_request", buildJsonObject {
            put("topology_fetch_update_info", JsonArray(listOf(buildJsonObject {
                put("custom_ota_fetch_type", "CERES_USER_INSIGHTS_NO_DEVICE_IDENTITY")
                put("full_update_only", true)
                put("device_serial_number", serial)
                put("device_type", BAND_DEVICE_TYPE)
                put("fw_version", firmware)
            })))
        })
    }
    val (status, json) = graphPost(token, "FetchSystemUpdates", SYSTEM_UPDATES_DOC, variables, http)
    val root = json["data"]?.jsonObject?.get("xar_fetch_constellation_system_updates") as? JsonObject
        ?: throw TokenError("Meta answered $status without updates")
    val answers = (root["fetch_update_info_list"] as? JsonArray) ?: emptyList()
    val offer = answers.firstNotNullOfOrNull { (it as? JsonObject)?.get("fetch_update_response") as? JsonObject } ?: return null
    val url = offer.text("file_uri") ?: throw TokenError("Meta's band update has no file")
    return BandOffer(
        baseVersion = offer.text("base_version").orEmpty(),
        targetVersion = offer.text("target_version").orEmpty(),
        displayName = offer.text("build_number_display_name").orEmpty(),
        url = url,
        size = offer.long("file_size") ?: 0,
        checksum = offer.text("file_checksum").orEmpty(),
        kekId = offer.long("kek_id"),
    )
}

/** FetchEncryptedKEKInfoByKEKId: the band's KEK for [kekId], wrapped for the band with [serial]. */
fun fetchBandKek(
    token: Token,
    serial: String,
    kekId: Long,
    http: HttpPost = UrlConnectionPost,
): BandKek {
    val variables = buildJsonObject {
        put("request", buildJsonObject {
            put("device_serial_number", serial)
            put("kek_id", kekId)
        })
    }
    val (status, json) = graphPost(token, "FetchEncryptedKEKInfoByKEKId", KEK_DOC, variables, http)
    val node = json["data"]?.jsonObject?.get("xar_fetch_encrypted_kek_info_by_kek_id") as? JsonObject
        ?: throw TokenError("Meta answered $status without a key")
    val encrypted = node.text("encrypted_kek") ?: throw TokenError("Meta's key has no value")
    return BandKek(encrypted, node.text("encryption_algo"))
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
