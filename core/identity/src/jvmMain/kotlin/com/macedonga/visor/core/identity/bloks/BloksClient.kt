package com.macedonga.visor.core.identity.bloks

import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Meta AI's Bloks GraphQL queries (screen, action, component), signed with the app's logged-out token. */
class BloksClient(
    private val token: String,
    private val locale: String,
    private val androidRelease: String,
    private val apiLevel: Int,
    private val http: HttpPost = UrlConnectionPost,
) {
    private enum class Kind(val doc: String, val queryName: String) {
        SCREEN("6165507603411378493763367459", "FrlBloksAppRootQuery"),
        ACTION("25388939413601934797287790300", "FrlBloksAsyncActionQuery"),
        COMPONENT("26656874278801481548161588323", "FrlBloksAsyncComponentQuery"),
    }

    fun screen(appId: String, params: String): Payload = Payload.parse(query(Kind.SCREEN, appId, params))

    fun action(appId: String, params: String): Payload = Payload.parse(query(Kind.ACTION, appId, params))

    /** An async component query (screen.Open, component_queries): its first component's payload. */
    fun component(appId: String, params: String): Payload {
        val bundle = Json.parseToJsonElement(query(Kind.COMPONENT, appId, params)).jsonObject
        val response = bundle["component_query_responses"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw BloksException("$appId answered no component_query_responses")
        val inner = response["payload"]?.jsonPrimitive?.content ?: throw BloksException("$appId answered no component payload")
        val first = Json.parseToJsonElement(inner).jsonObject["components"]?.jsonArray?.firstOrNull()?.jsonObject?.get("payload")?.jsonObject
            ?: throw BloksException("$appId answered no components")
        return Payload.of(first)
    }

    private fun query(kind: Kind, appId: String, params: String): String {
        if (token.isEmpty()) throw BloksException("This build has no Meta app token. Add VISOR_META_FRL_TOKEN to local.properties and build again.")
        val variables = buildJsonObject {
            put("params", buildJsonObject {
                put("app_id", appId)
                put("bloks_versioning_id", VERSIONING_ID)
                put("params", params)
            })
            put("bk_context", buildJsonObject {
                put("is_flipper_enabled", false)
                put("debug_tooling_metadata_token", JsonNull)
                put("android_device_performance_class", JsonNull)
                put("android_os_api_level", apiLevel)
                put("gpu_memory_mb", 4096)
                if (kind == Kind.SCREEN) put("pixel_ratio", PIXEL_RATIO)
            })
        }
        val name = "${kind.queryName}-$appId"
        val body = listOf(
            "method" to "post",
            "format" to "json",
            "server_timestamps" to "true",
            "locale" to locale,
            "fb_api_req_friendly_name" to name,
            "client_doc_id" to kind.doc,
            "variables" to variables.toString(),
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val reply = http.post(
            "$ENDPOINT?locale=${URLEncoder.encode(locale, "UTF-8")}",
            mapOf(
                "Authorization" to "OAuth $token",
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to userAgent,
                "X-FB-Friendly-Name" to name,
            ),
            body.toByteArray(),
        )
        val text = String(reply.body, Charsets.UTF_8)
        if (reply.status != 200) throw BloksException("${kind.queryName} for $appId answered HTTP ${reply.status}: ${text.take(300)}")
        val data = Json.parseToJsonElement(text).jsonObject["data"] as? JsonObject
            ?: throw BloksException("${kind.queryName} for $appId answered without data: ${text.take(300)}")
        val bundle = when (kind) {
            Kind.COMPONENT -> data["bloks_component_query"]?.jsonObject?.get("bloks_bundle_component_query")
            Kind.SCREEN -> data["bloks_app"]?.jsonObject?.get("bloks_bundle")?.jsonObject?.get("bloks_bundle_tree")
            Kind.ACTION -> data["bloks_action"]?.jsonObject?.get("action")?.jsonObject?.get("action_bundle")?.jsonObject?.get("bloks_bundle_action")
        }
        return (bundle as? JsonPrimitive)?.content ?: throw BloksException("${kind.queryName} for $appId answered no bundle: ${text.take(300)}")
    }

    companion object {
        private const val ENDPOINT = "https://meta.graph.meta.com/graphql"

        /** The pixel ratio the screen query declares; px sizes in its answers are at this scale. */
        internal const val PIXEL_RATIO = 3f

        // The Bloks runtime version of Meta AI 294; the server shapes its scripts to it.
        private const val VERSIONING_ID = "563d6e0947a986c84a526eed86f47bc9f456d52f386d4757475b045e577e0541"
    }

    private val userAgent =
        "Dalvik/2.1.0 (Linux; U; Android $androidRelease) [FBAN/Stella;FBAV/294.1.0.25.165;FBSN/Android;FBSV/$androidRelease;FBLC/en_US]"
}
