package com.macedonga.visor.core.assistant

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
            val headers = mapOf("x-api-key" to config.apiKey, "anthropic-version" to "2023-06-01")
            val all = mutableListOf<ModelInfo>()
            var after: String? = null
            do {
                val page = get("https://api.anthropic.com/v1/models?limit=1000" + (after?.let { "&after_id=" + URLEncoder.encode(it, "UTF-8") } ?: ""), headers)
                val data = page.optJSONArray("data")
                for (i in 0 until (data?.length() ?: 0)) {
                    val m = data!!.getJSONObject(i)
                    all += ModelInfo(m.getString("id"), m.optString("display_name").ifEmpty { m.getString("id") }, created(m.optString("created_at")))
                }
                after = if (page.optBoolean("has_more")) page.optString("last_id").ifEmpty { null } else null
            } while (after != null)
            all
        }
        Provider.OPENAI_COMPATIBLE, Provider.CLAUDE_SUBSCRIPTION -> {
            val headers = if (config.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${config.apiKey}")
            val page = get(config.baseUrl.trimEnd('/') + "/models", headers)
            val data = page.optJSONArray("data")
            (0 until (data?.length() ?: 0)).map { i ->
                val m = data!!.getJSONObject(i)
                ModelInfo(m.getString("id"), m.getString("id"), m.optLong("created"))
            }
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
