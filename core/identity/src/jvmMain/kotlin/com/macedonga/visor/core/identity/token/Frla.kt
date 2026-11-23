package com.macedonga.visor.core.identity.token

import com.macedonga.visor.core.identity.BuildConfig
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.pyUrlencode
import com.macedonga.visor.core.identity.text
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The Meta app's own token, so not a user secret. The build reads it from local.properties. */
private fun arAppToken(): String = BuildConfig.META_AR_TOKEN.ifEmpty {
    throw TokenError("This build has no Meta app token. Add VISOR_META_AR_TOKEN to local.properties and build again.")
}

internal const val AR_HOST = "ar.graph.meta.com"

/** Meta turned the FRLA down. */
internal class LoginRefused(message: String) : TokenError(message)

/** The Meta app's user agent, sent on Meta's auth calls. */
private const val META_USER_AGENT =
    "Dalvik/2.1.0 (Linux; U; Android 15) " +
        "[FBAN/Stella;FBAV/289.0.0.25.162;FBSN/Android;FBSV/15;FBLC/en_US]"

/** A form POST to one of Meta's auth endpoints, the answer as JSON. */
internal fun authPost(url: String, fields: Map<String, String>, authorization: String? = null): JsonObject {
    val headers = buildMap<String, String> {
        authorization?.let { put("Authorization", it) }
        put("Content-Type", "application/x-www-form-urlencoded")
        put("User-Agent", META_USER_AGENT)
    }
    val reply = try {
        UrlConnectionPost.post(url, headers, pyUrlencode(fields).toByteArray(Charsets.US_ASCII))
    } catch (error: Exception) {
        // Refusals come back as replies; only a server never reached lands here.
        throw TokenError("Meta could not be reached: ${error.message}", unreachable = true)
    }
    return try {
        Json.parseToJsonElement(String(reply.body, Charsets.UTF_8)).jsonObject
    } catch (error: Exception) {
        throw TokenError("$url: HTTP ${reply.status}, not JSON")
    }
}

/** Trade an FRLA for an AR access token. */
fun exchangeFrla(frla: String): Token {
    val payload = authPost(
        "https://$AR_HOST/login",
        linkedMapOf(
            "access_token" to arAppToken(),
            "frl_access_token" to frla,
            "logging_session_id" to UUID.randomUUID().toString(),
            "device_id" to UUID.randomUUID().toString(),
            "format" to "json",
            "generate_session_cookies" to "1",
            "generate_analytics_claim" to "1",
        ),
    )
    payload.errorMessage()?.let { throw LoginRefused("exchange refused: $it") }
    val value = payload.text("access_token")
    if (value.isNullOrEmpty()) {
        throw LoginRefused("no access_token in the response; keys: ${payload.sortedKeys()}")
    }
    return Token(value)
}
