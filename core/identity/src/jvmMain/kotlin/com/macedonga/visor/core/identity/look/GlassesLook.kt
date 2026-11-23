package com.macedonga.visor.core.identity.look

import com.macedonga.visor.core.identity.graphPost
import com.macedonga.visor.core.identity.nonBlank
import com.macedonga.visor.core.identity.obj
import com.macedonga.visor.core.identity.path
import com.macedonga.visor.core.identity.token.Token
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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

private const val SKU_BY_SERIAL_DOC = "42027378965141777947317603455"

/**
 * WearableDeviceSKUBySerialNumber, the Meta app's own query: the look of the glasses with [serial]. Null when Meta
 * has no record of them. [token] is the AR token from the login.
 */
fun fetchGlassesLook(token: Token, serial: String, locale: String = "en_US"): GlassesLook? {
    val (_, json) = graphPost(token, "WearableDeviceSKUBySerialNumber", SKU_BY_SERIAL_DOC, buildJsonObject { put("serial_number", serial) }, locale)
    val edges = json.path("data", "xar_ar_ent_wearable_device_sku_query_by_serial_number")?.get("edges") as? JsonArray ?: return null
    val node = (edges.firstOrNull() as? JsonObject)?.obj("node") ?: return null
    val style = node.obj("frame_style_new")
    val color = node.obj("frame_color_new")
    val lens = node.obj("lens_color_new")
    return GlassesLook(
        style = style?.nonBlank("frame_style"),
        styleName = style?.nonBlank("frame_style_display_name"),
        color = color?.nonBlank("frame_color"),
        colorName = color?.nonBlank("frame_color_display_name"),
        lens = lens?.nonBlank("lens_color"),
        lensName = lens?.nonBlank("lens_color_display_name"),
        size = node.nonBlank("device_size"),
    )
}
