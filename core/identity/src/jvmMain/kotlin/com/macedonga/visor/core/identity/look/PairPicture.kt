package com.macedonga.visor.core.identity.look

import com.macedonga.visor.core.identity.graphPost
import com.macedonga.visor.core.identity.nonBlank
import com.macedonga.visor.core.identity.obj
import com.macedonga.visor.core.identity.path
import com.macedonga.visor.core.identity.token.Token
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.putJsonArray

/** Where Meta keeps a pair's picture: the frame, colour and lenses as sold, front on, on a clear background. */
data class PairPicture(val light: String, val dark: String?)

private const val ASSETS_BY_SERIAL_DOC = "256686669512680180239290821066"
// Asset category 2 is the device image the Meta app shows for each pair.
private const val DEVICE_IMAGE = 2

/**
 * WearableDeviceAssetsBySerialNumbers, the Meta app's own query: each pair's picture by serial. The picture for the
 * pair's SKU comes first; the one for its device type, a stock frame, only when there is none.
 */
fun fetchPairPictures(token: Token, serials: List<String>): Map<String, PairPicture> {
    val variables = buildJsonObject { putJsonArray("serial_numbers") { serials.forEach { add(JsonPrimitive(it)) } } }
    val (_, json) = graphPost(token, "WearableDeviceAssetsBySerialNumbers", ASSETS_BY_SERIAL_DOC, variables, locale = null)
    val categories = json.path("data", "xar_wearable_device_assets_by_serial_numbers")?.get("data") as? JsonArray
        ?: return emptyMap()
    val images = categories.mapNotNull { it as? JsonObject }.firstOrNull { (it["asset_category"] as? JsonPrimitive)?.intOrNull == DEVICE_IMAGE }
        ?: return emptyMap()
    return (images["serial_specific"] as? JsonArray).orEmpty().mapNotNull { entry ->
        val o = entry as? JsonObject ?: return@mapNotNull null
        val serial = o.nonBlank("serial_number") ?: return@mapNotNull null
        val picture = o.image("sku_specific") ?: o.image("device_specific") ?: return@mapNotNull null
        serial to picture
    }.toMap()
}

private fun JsonObject.image(group: String): PairPicture? =
    (obj(group)?.get("assets") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstNotNullOfOrNull { a ->
        a.nonBlank("asset_manager_url")?.takeIf { a.nonBlank("asset_type") == "IMAGE" && a.nonBlank("status") == "ACTIVE" }
            ?.let { PairPicture(it, a.nonBlank("asset_manager_url_dark")) }
    }
