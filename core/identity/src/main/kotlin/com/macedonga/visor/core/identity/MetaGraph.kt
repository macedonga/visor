package com.macedonga.visor.core.identity

import com.macedonga.visor.core.identity.token.Token
import com.macedonga.visor.core.identity.token.TokenError
import com.macedonga.visor.core.identity.token.checkSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

private const val GRAPH_URL = "https://ar-genai.graph.meta.com/graphql"

/** One persisted query as the Meta app sends it: the HTTP status and the answer's JSON. A null [locale] is left out. */
internal fun graphPost(
    authorization: String,
    friendlyName: String,
    docId: String,
    variables: String,
    url: String = GRAPH_URL,
    locale: String? = "en_US",
): Pair<Int, JsonObject> {
    val fields = linkedMapOf(
        "fb_api_req_friendly_name" to friendlyName,
        "client_doc_id" to docId,
        "variables" to variables,
        "method" to "post",
    )
    if (locale != null) fields["locale"] = locale
    fields["pretty"] = "false"
    fields["format"] = "json"
    val body = pyUrlencode(fields).toByteArray(Charsets.US_ASCII)
    val reply = try {
        UrlConnectionPost.post(url, mapOf("Authorization" to authorization, "Content-Type" to "application/x-www-form-urlencoded"), body)
    } catch (error: Exception) {
        throw TokenError("Meta could not be reached: ${error.message}", unreachable = true)
    }
    val json = runCatching { Json.parseToJsonElement(String(reply.body, Charsets.UTF_8)).jsonObject }
        .getOrElse { throw TokenError("Meta answered ${reply.status} with no JSON") }
    checkSession(json)
    json["errors"]?.let { throw TokenError("Meta refused: ${it.toString().take(200)}") }
    return reply.status to json
}

/** [graphPost] on Meta's AR endpoint with the user's AR token. */
internal fun graphPost(token: Token, friendlyName: String, docId: String, variables: JsonObject, locale: String? = "en_US"): Pair<Int, JsonObject> =
    graphPost("OAuth ${token.value}", friendlyName, docId, variables.toString(), locale = locale)

/**
 * One FetchConstellation query as the Meta app sends it, with [info] as its `constellation_updates_request_info`.
 * Returns the HTTP status and the answer's JSON.
 */
internal fun constellationPost(token: Token, friendlyName: String, docId: String, info: JsonObject): Pair<Int, JsonObject> {
    val variables = buildJsonObject {
        put("fetch_request", buildJsonObject { put("constellation_updates_request_info", JsonArray(listOf(info))) })
    }
    return graphPost(token, friendlyName, docId, variables)
}

/** A JSON string or number as text, or null when absent. */
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** [text], with a blank value as null. */
internal fun JsonObject.nonBlank(key: String): String? = text(key)?.takeIf { it.isNotBlank() }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.path(vararg keys: String): JsonObject? = keys.fold(this as JsonObject?) { o, k -> o?.obj(k) }

/** A number Meta sends as a number or as its text. */
internal fun JsonObject.number(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
