package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.connection.objects
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val ANTHROPIC_API = "https://api.anthropic.com/v1"

internal fun anthropicHeaders(key: String) = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")

internal fun bearerHeaders(key: String) = if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")

/** A model the provider offers: its id, the name it gives it, and when it came out (epoch seconds, 0 unknown). */
data class ModelInfo(val id: String, val name: String, val created: Long)

/** Why a list could not be had: the key was refused, or anything else, with the provider's words. */
class ModelsError(val refused: Boolean, message: String) : Exception(message)

/**
 * The models the configured provider offers, newest first. Asking also proves the key: a refused key throws
 * [ModelsError] with [ModelsError.refused] set.
 */
suspend fun listModels(config: AssistantConfig): List<ModelInfo> = withContext(Dispatchers.IO) {
    when (config.provider) {
        Provider.CLAUDE -> {
            val headers = anthropicHeaders(config.apiKey)
            val all = mutableListOf<ModelInfo>()
            var after: String? = null
            do {
                val page = get("$ANTHROPIC_API/models?limit=1000" + (after?.let { "&after_id=" + URLEncoder.encode(it, "UTF-8") } ?: ""), headers)
                val data = page.optJSONArray("data") ?: JSONArray()
                for (m in data.objects()) {
                    all += ModelInfo(m.getString("id"), m.optString("display_name").ifEmpty { m.getString("id") }, created(m.optString("created_at")))
                }
                after = if (page.optBoolean("has_more")) page.optString("last_id").ifEmpty { null } else null
            } while (after != null)
            all
        }
        Provider.OPENAI_COMPATIBLE -> {
            val page = get(config.baseUrl.trimEnd('/') + "/models", bearerHeaders(config.apiKey))
            val data = page.optJSONArray("data") ?: JSONArray()
            data.objects().map { m -> ModelInfo(m.getString("id"), m.getString("id"), m.optLong("created")) }
        }
    }.sortedByDescending { it.created }
}

private fun created(iso: String): Long = runCatching { java.time.Instant.parse(iso).epochSecond }.getOrDefault(0L)

private fun get(url: String, headers: Map<String, String>): JSONObject {
    val http = URL(url).openConnection() as HttpURLConnection
    try {
        http.connectTimeout = 5_000
        http.readTimeout = 15_000
        headers.forEach(http::setRequestProperty)
        val code = http.responseCode
        val text = (if (code in 200..299) http.inputStream else http.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
            throw ModelsError(refused = code == 401 || code == 403, message = message.ifEmpty { "HTTP $code" })
        }
        return JSONObject(text)
    } finally {
        http.disconnect()
    }
}
