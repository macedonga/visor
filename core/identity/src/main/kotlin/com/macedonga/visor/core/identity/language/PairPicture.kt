package com.macedonga.visor.core.identity.language

import com.macedonga.visor.core.identity.token.checkSession
import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.pyUrlencode
import com.macedonga.visor.core.identity.token.Token
import com.macedonga.visor.core.identity.token.TokenError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray

/** Where Meta keeps a pair's picture: the frame, colour and lenses as sold, front on, on a clear background. */
data class PairPicture(val light: String, val dark: String?)

private const val GRAPH_URL = "https://ar-genai.graph.meta.com/graphql"
private const val ASSETS_BY_SERIAL_DOC = "256686669512680180239290821066"
// Asset category 2 is the device image the Meta app shows for each pair.
private const val DEVICE_IMAGE = 2

/**
 * WearableDeviceAssetsBySerialNumbers, the Meta app's own query: each pair's picture by serial. The picture for the
 * pair's SKU comes first; the one for its device type, a stock frame, only when there is none.
 */
fun fetchPairPictures(token: Token, serials: List<String>, http: HttpPost = UrlConnectionPost): Map<String, PairPicture> {
    val body = pyUrlencode(
        linkedMapOf(
            "fb_api_req_friendly_name" to "WearableDeviceAssetsBySerialNumbers",
            "client_doc_id" to ASSETS_BY_SERIAL_DOC,
            "variables" to buildJsonObject { putJsonArray("serial_numbers") { serials.forEach { add(JsonPrimitive(it)) } } }.toString(),
            "method" to "post",
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
    val categories = ((json["data"] as? JsonObject)?.get("xar_wearable_device_assets_by_serial_numbers") as? JsonObject)?.get("data") as? JsonArray
        ?: return emptyMap()
    val images = categories.mapNotNull { it as? JsonObject }.firstOrNull { (it["asset_category"] as? JsonPrimitive)?.intOrNull == DEVICE_IMAGE }
        ?: return emptyMap()
    return (images["serial_specific"] as? JsonArray).orEmpty().mapNotNull { entry ->
        val o = entry as? JsonObject ?: return@mapNotNull null
        val serial = o.text("serial_number") ?: return@mapNotNull null
        val picture = o.image("sku_specific") ?: o.image("device_specific") ?: return@mapNotNull null
        serial to picture
    }.toMap()
}

private fun JsonObject.image(group: String): PairPicture? =
    ((this[group] as? JsonObject)?.get("assets") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .firstOrNull { it.text("asset_type") == "IMAGE" && it.text("status") == "ACTIVE" && it.text("asset_manager_url") != null }
        ?.let { PairPicture(it.text("asset_manager_url")!!, it.text("asset_manager_url_dark")) }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
