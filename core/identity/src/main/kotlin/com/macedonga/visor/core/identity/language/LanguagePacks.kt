package com.macedonga.visor.core.identity.language

import com.macedonga.visor.core.identity.constellationPost
import com.macedonga.visor.core.identity.number
import com.macedonga.visor.core.identity.path
import com.macedonga.visor.core.identity.text
import com.macedonga.visor.core.identity.token.Token
import com.macedonga.visor.core.identity.token.TokenError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A language pack Meta has for these glasses: the zip, its size and its SHA-256. */
data class PackOffer(
    /** The constellation feature, e.g. `com.meta.constellation.feature.air.language_it_it`. */
    val feature: String,
    /** e.g. `it-language_resource`. */
    val resource: String,
    val version: String,
    val url: String,
    val size: Long,
    val sha256: String,
    /** What the glasses' OTA install names it by, e.g. `7506485332707669`. */
    val appId: String = "",
    /** e.g. `RESOURCE_PACK`. */
    val type: String = "",
)

const val LANGUAGE_FEATURE = "com.meta.constellation.feature.air.language_"

private const val FETCH_UPDATES_DOC = "10649732309712548951633872342"

/**
 * FetchConstellationFeatureUpdates, the Meta app's own query: what Meta has for [features] on the glasses
 * with [serial] running [firmware]. [token] is the AR token from the login.
 */
fun fetchPackOffers(
    token: Token,
    serial: String,
    firmware: String,
    features: List<String>,
    deviceType: String,
): List<PackOffer> {
    val info = buildJsonObject {
        put("device_type", deviceType)
        put("device_serial", serial)
        put("device_firmware_version", firmware)
        put("device_feature_list", buildJsonArray {
            for (f in features) add(buildJsonObject {
                put("feature_name", f)
                put("feature_version", JsonNull)
            })
        })
        put("device_artifact_list", JsonArray(emptyList()))
        put("device_firmware_full_update_only", false)
        put("device_firmware_rollout_token", JsonNull)
        put("device_manage_mode", JsonNull)
    }
    val (status, json) = constellationPost(token, "FetchConstellationFeatureUpdates", FETCH_UPDATES_DOC, info)
    val responses = json.path("data", "xar_constellation_feature_updates")?.get("constellation_updates_response_info") as? JsonArray
        ?: throw TokenError("Meta answered $status without updates")
    return responses.mapNotNull { it as? JsonObject }.flatMap { r ->
        (r["resource_updates_info"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.flatMap { u ->
            val feature = u.path("feature")?.text("feature_name").orEmpty()
            (u["feature_resource_updates_info"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                PackOffer(
                    feature = feature,
                    resource = o.text("resource_name") ?: return@mapNotNull null,
                    version = o.text("resource_version").orEmpty(),
                    url = o.text("file_uri") ?: return@mapNotNull null,
                    size = o.number("file_size") ?: 0,
                    sha256 = o.text("file_checksum").orEmpty(),
                    appId = o.text("app_id").orEmpty(),
                    type = o.text("resource_type").orEmpty(),
                )
            }
        }
    }
}
