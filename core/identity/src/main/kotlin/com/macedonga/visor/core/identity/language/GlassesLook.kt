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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * How a pair looks, as Meta's records have it: the frame's style and colour and the lenses, each a code
 * (`WAYFARER`, `SHINYBLACK`, `G15GREENTRANSITIONS`) and the name Meta shows for it.
 */
data class GlassesLook(
    val style: String?,
    val styleName: String?,
    val color: String?,
    val colorName: String?,
    val lens: String?,
    val lensName: String?,
    val size: String?,
)

private const val GRAPH_URL = "https://ar-genai.graph.meta.com/graphql"
private const val SKU_BY_SERIAL_DOC = "42027378965141777947317603455"

/**
 * WearableDeviceSKUBySerialNumber, the Meta app's own query: the look of the glasses with [serial]. Null when Meta
 * has no record of them. [token] is the AR token from the login.
 */
fun fetchGlassesLook(token: Token, serial: String, locale: String = "en_US", http: HttpPost = UrlConnectionPost): GlassesLook? {
    val body = pyUrlencode(
        linkedMapOf(
            "fb_api_req_friendly_name" to "WearableDeviceSKUBySerialNumber",
            "client_doc_id" to SKU_BY_SERIAL_DOC,
            "variables" to buildJsonObject { put("serial_number", serial) }.toString(),
            "method" to "post",
            "locale" to locale,
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
    val edges = (json["data"] as? JsonObject)?.get("xar_ar_ent_wearable_device_sku_query_by_serial_number")
        ?.let { it as? JsonObject }?.get("edges") as? JsonArray ?: return null
    val node = (edges.firstOrNull() as? JsonObject)?.get("node") as? JsonObject ?: return null
    val style = node["frame_style_new"] as? JsonObject
    val color = node["frame_color_new"] as? JsonObject
    val lens = node["lens_color_new"] as? JsonObject
    return GlassesLook(
        style = style?.text("frame_style"),
        styleName = style?.text("frame_style_display_name"),
        color = color?.text("frame_color"),
        colorName = color?.text("frame_color_display_name"),
        lens = lens?.text("lens_color"),
        lensName = lens?.text("lens_color_display_name"),
        size = node.text("device_size"),
    )
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
