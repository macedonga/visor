package com.macedonga.visor.core.identity.token

import com.macedonga.visor.core.identity.BuildConfig
import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.pyUrlencode
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** FRLA to ARAe, the exchange confirmed live on 2026-09-15. */

/** The Meta app's own token, so not a user secret. The build reads it from local.properties. */
private fun arAppToken(): String = BuildConfig.META_AR_TOKEN.ifEmpty {
    throw TokenError("This build has no Meta app token. Add VISOR_META_AR_TOKEN to local.properties and build again.")
}

const val DEFAULT_AR_HOST = "ar.graph.meta.com"

private fun post(url: String, fields: Map<String, String>, http: HttpPost): JsonObject {
    val reply = try {
        http.post(
            url,
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to META_USER_AGENT,
            ),
            pyUrlencode(fields).toByteArray(Charsets.US_ASCII),
        )
    } catch (error: Exception) {
        // The interesting answers arrive as 4xx/5xx with a JSON body and are read, not thrown;
        // only failing to reach the server at all lands here.
        throw TokenError("$url: ${error.javaClass.simpleName}: ${error.message}", unreachable = true)
    }
    return try {
        Json.parseToJsonElement(String(reply.body, Charsets.UTF_8)).jsonObject
    } catch (error: Exception) {
        throw TokenError("$url: response was not JSON")
    }
}

/** The FRLA to ARAe call itself, split out so a web login can reuse it verbatim. */
fun exchangeFrla(frla: String, host: String = DEFAULT_AR_HOST, http: HttpPost = UrlConnectionPost): Token {
    val payload = post(
        "https://$host/login",
        linkedMapOf(
            "access_token" to arAppToken(),
            "frl_access_token" to frla,
            "logging_session_id" to UUID.randomUUID().toString(),
            "device_id" to UUID.randomUUID().toString(),
            "format" to "json",
            "generate_session_cookies" to "1",
            "generate_analytics_claim" to "1",
        ),
        http,
    )
    payload["error"]?.let { error ->
        val message = (error as? JsonObject)?.get("message")
            ?.let { (it as? JsonPrimitive)?.content ?: it.toString() }
            ?: (error as? JsonPrimitive)?.content
            ?: error.toString()
        throw TokenError("exchange refused: $message")
    }
    val value = payload.text("access_token")
    if (value.isNullOrEmpty()) {
        throw TokenError("no access_token in the response; keys: ${payload.sortedKeys()}")
    }
    return Token(value, "ar")
}
