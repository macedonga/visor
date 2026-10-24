package com.macedonga.visor.core.assistant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** How the assistant answers. The key stays in this app's private storage. */
data class AssistantConfig(
    val enabled: Boolean = true,
    val provider: Provider = Provider.CLAUDE,
    val apiKey: String = "",
    val model: String = "",
    // The model that writes each day's lines in the diary; empty, the answers' model.
    val linesModel: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val webSearch: Boolean = false,
    // Let a visual question use the glasses' camera.
    val vision: Boolean = true,
    // Answers in the glasses' own voice, the one that reads notifications; off, the phone's voice streamed to them.
    val glassesVoice: Boolean = true,
    // The language the wearer speaks, as a BCP 47 tag, when the glasses have no pack for it; empty, the glasses' own.
    // The phone then hears every question, the model answers in it, and the phone's voice speaks.
    val speechLanguage: String = "",
    val instructions: String = "",
    // How long a conversation is remembered after its last question.
    val memoryMinutes: Int = 10,
    // How long the glasses wait for an answer before they give up, written to them on connect.
    val answerWaitSeconds: Int = 45,
)

/** Languages the wearer can speak that the glasses have no pack for, heard and spoken by the phone (issue #38). */
val SPEECH_LANGUAGES = listOf(
    "ru-RU", "uk-UA", "pl-PL", "cs-CZ", "ro-RO", "hu-HU", "el-GR", "sv-SE", "da-DK", "nb-NO", "fi-FI",
    "he-IL", "ar-SA", "vi-VN", "id-ID", "th-TH",
)

/** The waits offered: 20 is the glasses' own; their watchdog ends a turn still processing at 60. */
val ANSWER_WAITS = listOf(20, 30, 45, 55)

class AssistantConfigStore(context: Context) : FieldBook {

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AssistantConfig> = _config.asStateFlow()

    override fun fields(key: String): Fields? = runCatching {
        JSONObject(prefs.getString(FIELDS, null) ?: return null).optJSONObject(key)
            ?.let { Fields(it.getBoolean("reasoning_off"), it.getBoolean("completion_tokens")) }
    }.getOrNull()

    override fun keep(key: String, fields: Fields) {
        val all = runCatching { JSONObject(prefs.getString(FIELDS, null) ?: "{}") }.getOrDefault(JSONObject())
        all.put(key, JSONObject().put("reasoning_off", fields.reasoningOff).put("completion_tokens", fields.completionTokens))
        prefs.edit().putString(FIELDS, all.toString()).apply()
    }

    fun update(change: (AssistantConfig) -> AssistantConfig) {
        val before = _config.value
        val next = change(before)
        // What a server took holds for the address and models it was found with.
        val moved = next.provider != before.provider || next.baseUrl != before.baseUrl || next.model != before.model ||
            next.linesModel != before.linesModel
        prefs.edit()
            .apply { if (moved) remove(FIELDS) }
            .putBoolean("enabled", next.enabled)
            .putString("provider", next.provider.name)
            .putString("api_key", next.apiKey)
            .putString("model", next.model)
            .putString("lines_model", next.linesModel)
            .putString("base_url", next.baseUrl)
            .putBoolean("web_search", next.webSearch)
            .putBoolean("vision", next.vision)
            .putBoolean("glasses_voice", next.glassesVoice)
            .putString("speech_language", next.speechLanguage)
            .putString("instructions", next.instructions)
            .putInt("memory_minutes", next.memoryMinutes)
            .putInt("answer_wait_s", next.answerWaitSeconds)
            .apply()
        _config.value = next
    }

    private fun load(): AssistantConfig {
        val d = AssistantConfig()
        return AssistantConfig(
            enabled = prefs.getBoolean("enabled", d.enabled),
            provider = runCatching { Provider.valueOf(prefs.getString("provider", null).orEmpty()) }.getOrDefault(d.provider),
            apiKey = prefs.getString("api_key", d.apiKey).orEmpty(),
            model = prefs.getString("model", d.model).orEmpty(),
            linesModel = prefs.getString("lines_model", d.linesModel).orEmpty(),
            baseUrl = prefs.getString("base_url", d.baseUrl).orEmpty(),
            webSearch = prefs.getBoolean("web_search", d.webSearch),
            vision = prefs.getBoolean("vision", d.vision),
            glassesVoice = prefs.getBoolean("glasses_voice", d.glassesVoice),
            speechLanguage = prefs.getString("speech_language", d.speechLanguage).orEmpty(),
            instructions = prefs.getString("instructions", d.instructions).orEmpty(),
            memoryMinutes = prefs.getInt("memory_minutes", d.memoryMinutes),
            answerWaitSeconds = prefs.getInt("answer_wait_s", d.answerWaitSeconds),
        )
    }

    private companion object {
        const val FIELDS = "server_fields"
    }
}
