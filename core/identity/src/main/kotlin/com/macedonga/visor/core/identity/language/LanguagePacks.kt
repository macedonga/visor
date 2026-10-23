package com.macedonga.visor.core.identity.language

import com.macedonga.visor.core.identity.token.checkSession
import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.pyUrlencode
import com.macedonga.visor.core.identity.token.Token
import com.macedonga.visor.core.identity.token.TokenError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
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

/** Ray-Ban Meta Gen 2 on a user build, as the OTA service names it. */
const val GEN2_DEVICE_TYPE = "ota.hammerhead.user"

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
    deviceType: String = GEN2_DEVICE_TYPE,
    http: HttpPost = UrlConnectionPost,
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
    val (status, json) = constellationPost(token, "FetchConstellationFeatureUpdates", FETCH_UPDATES_DOC, info, http)
    val responses = json.path("data", "xar_constellation_feature_updates")?.get("constellation_updates_response_info") as? JsonArray
        ?: throw TokenError("Meta answered $status without updates")
    return responses.flatMap { r ->
        (r.jsonObject["resource_updates_info"] as? JsonArray).orEmpty().flatMap { u ->
            val feature = u.jsonObject.path("feature")?.text("feature_name").orEmpty()
            (u.jsonObject["feature_resource_updates_info"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                PackOffer(
                    feature = feature,
                    resource = o.text("resource_name") ?: return@mapNotNull null,
                    version = o.text("resource_version").orEmpty(),
                    url = o.text("file_uri") ?: return@mapNotNull null,
                    size = (o["file_size"] as? JsonPrimitive)?.longOrNull ?: o.text("file_size")?.toLongOrNull() ?: 0,
                    sha256 = o.text("file_checksum").orEmpty(),
                    appId = o.text("app_id").orEmpty(),
                    type = o.text("resource_type").orEmpty(),
                )
            }
        }
    }
}

/**
 * One FetchConstellation query as the Meta app sends it, with [info] as its `constellation_updates_request_info`.
 * Returns the HTTP status and the answer's JSON.
 */
internal fun constellationPost(token: Token, friendlyName: String, docId: String, info: JsonObject, http: HttpPost): Pair<Int, JsonObject> {
    val variables = buildJsonObject {
        put("fetch_request", buildJsonObject { put("constellation_updates_request_info", JsonArray(listOf(info))) })
    }
    return graphPost(token, friendlyName, docId, variables, http)
}

/** One persisted query on Meta's AR endpoint, as the Meta app sends it: the HTTP status and the answer's JSON. */
internal fun graphPost(token: Token, friendlyName: String, docId: String, variables: JsonObject, http: HttpPost): Pair<Int, JsonObject> {
    val body = pyUrlencode(
        linkedMapOf(
            "fb_api_req_friendly_name" to friendlyName,
            "client_doc_id" to docId,
            "variables" to variables.toString(),
            "method" to "post",
            "locale" to "en_US",
            "pretty" to "false",
            "format" to "json",
        ),
    )
    val reply = try {
        http.post(
            GRAPH_URL,
            mapOf("Authorization" to "OAuth ${token.value}", "Content-Type" to "application/x-www-form-urlencoded"),
            body.toByteArray(Charsets.US_ASCII),
        )
    } catch (error: Exception) {
        throw TokenError("Meta could not be reached: ${error.message}")
    }
    val json = runCatching { Json.parseToJsonElement(String(reply.body, Charsets.UTF_8)).jsonObject }
        .getOrElse { throw TokenError("Meta answered ${reply.status} with no JSON") }
    checkSession(json)
    json["errors"]?.let { throw TokenError("Meta refused: ${it.toString().take(200)}") }
    return reply.status to json
}

private const val GRAPH_URL = "https://ar-genai.graph.meta.com/graphql"

private fun JsonObject.path(vararg keys: String): JsonObject? =
    keys.fold(this as JsonObject?) { o, k -> o?.get(k) as? JsonObject }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.jsonArray ?: emptyList()
