package com.macedonga.visor.core.assistant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    // Let a partner-language voice command take a photo; it turns on the glasses' Robusta switch, not Developer mode.
    val voicePhotos: Boolean = false,
    // Who says the answers: the glasses' own voice, the phone's engine streamed to them, or a cloud service.
    val tts: TtsPath = TtsPath.GLASSES,
    // The cloud speech service, OpenAI compatible; the key stays out of backups like the model's.
    val ttsBaseUrl: String = "https://api.openai.com/v1",
    val ttsApiKey: String = "",
    val ttsModel: String = "",
    val ttsVoice: String = "",
    // No spoken answer when every tool the model used did what was asked, such as music that now plays.
    val quietAfterActions: Boolean = false,
    // The language the wearer speaks, as a BCP 47 tag, when the glasses have no pack for it; empty, the glasses' own.
    // The phone then hears every question, the model answers in it, and the phone's voice speaks.
    val speechLanguage: String = "",
    val instructions: String = "",
    // How long a conversation is remembered after its last question.
    val memoryMinutes: Int = 10,
    // How long the glasses wait for an answer before they give up, written to them on connect.
    val answerWaitSeconds: Int = 45,
    // The ChatGPT account signed in for the ChatGPT provider, by its email; empty when signed out.
    val chatGpt: String = "",
) {
    /** A model can answer: a service needs its key or sign-in, an OpenAI-compatible server may need neither. */
    val hasModel: Boolean get() = when (provider) {
        Provider.OPENAI_COMPATIBLE -> true
        Provider.CHATGPT -> chatGpt.isNotEmpty()
        else -> apiKey.isNotBlank()
    }

    /** The API address: the service's own, or the one typed in for an OpenAI-compatible server. */
    val api: String get() = if (provider == Provider.OPENAI_COMPATIBLE) baseUrl else provider.api

    /** The model asked: the one chosen, or the provider's own default. */
    val answerModel: String get() = model.ifBlank { provider.defaultModel }
}

/** Who says the answer out loud. */
enum class TtsPath { GLASSES, PHONE, CLOUD }

/** Languages the wearer can speak that the glasses have no pack for, heard and spoken by the phone. */
val SPEECH_LANGUAGES = listOf(
    "ru-RU", "uk-UA", "pl-PL", "cs-CZ", "ro-RO", "hu-HU", "el-GR", "sv-SE", "da-DK", "nb-NO", "fi-FI",
    "he-IL", "ar-SA", "vi-VN", "id-ID", "th-TH",
)

/** The waits offered: 20 is the glasses' own; their watchdog ends a turn still processing at 60. */
val ANSWER_WAITS = listOf(20, 30, 45, 55)

class AssistantConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AssistantConfig> = _config.asStateFlow()

    private val _sessionVoices = MutableStateFlow(prefs.getStringSet(SESSION_VOICES, null).orEmpty().toSet())
    /** Voices the glasses spoke session turns in, where the phone speaks. */
    val sessionVoices: StateFlow<Set<String>> = _sessionVoices.asStateFlow()

    fun markSessionVoice(voice: String) = setSessionVoices { it + voice }

    /** A plain turn in [voice]: the glasses speak it again, as after Visor turned their session path off. */
    fun forgetSessionVoice(voice: String) = setSessionVoices { it - voice }

    private fun setSessionVoices(change: (Set<String>) -> Set<String>) {
        _sessionVoices.update(change)
        prefs.edit().putStringSet(SESSION_VOICES, _sessionVoices.value).apply()
    }

    /** The [Fields] found for an address and model, kept until either changes. */
    fun fields(key: String): Fields? = runCatching {
        JSONObject(prefs.getString(FIELDS, null) ?: return null).optJSONObject(key)
            ?.let { Fields(it.getBoolean("reasoning_off"), it.getBoolean("completion_tokens"), it.optBoolean("thinks")) }
    }.getOrNull()

    fun keep(key: String, fields: Fields) {
        val all = runCatching { JSONObject(prefs.getString(FIELDS, null) ?: "{}") }.getOrDefault(JSONObject())
        all.put(key, JSONObject().put("reasoning_off", fields.reasoningOff).put("completion_tokens", fields.completionTokens).put("thinks", fields.thinks))
        prefs.edit().putString(FIELDS, all.toString()).apply()
    }

    private val _noSearch = MutableStateFlow(prefs.getStringSet(NO_SEARCH, null).orEmpty().toSet())
    /** The provider and model pairs that refused a web search, so their answers go without it. */
    val noSearch: StateFlow<Set<String>> = _noSearch.asStateFlow()

    fun cannotSearch(c: AssistantConfig): Boolean = searchKey(c) in _noSearch.value

    fun markCannotSearch(c: AssistantConfig) = setNoSearch(_noSearch.value + searchKey(c))

    private fun setNoSearch(keys: Set<String>) {
        _noSearch.value = keys
        prefs.edit().putStringSet(NO_SEARCH, keys).apply()
    }

    /**
     * Another provider, with the key and models it had last time; the ones left keep theirs for the way back. A
     * ChatGPT sign-in stays with ChatGPT.
     */
    fun switchProvider(to: Provider) {
        val c = _config.value
        if (c.provider == to) return
        val slots = runCatching { JSONObject(prefs.getString(SLOTS, null) ?: "{}") }.getOrDefault(JSONObject())
        slots.put(c.provider.name, JSONObject().put("key", c.apiKey).put("model", c.model).put("lines", c.linesModel))
        prefs.edit().putString(SLOTS, slots.toString()).apply()
        val slot = slots.optJSONObject(to.name)
        update {
            it.copy(
                provider = to,
                apiKey = slot?.optString("key").orEmpty(),
                model = slot?.optString("model").orEmpty(),
                linesModel = slot?.optString("lines").orEmpty(),
            )
        }
    }

    fun update(change: (AssistantConfig) -> AssistantConfig) {
        val before = _config.value
        val next = change(before)
        // Turned on again, search is tried again with every model.
        if (next.webSearch && !before.webSearch && _noSearch.value.isNotEmpty()) setNoSearch(emptySet())
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
            .putBoolean("voice_photos", next.voicePhotos)
            .putString("tts", next.tts.name)
            .putString("tts_base_url", next.ttsBaseUrl)
            .putString("tts_api_key", next.ttsApiKey)
            .putString("tts_model", next.ttsModel)
            .putString("tts_voice", next.ttsVoice)
            .putBoolean("quiet_after_actions", next.quietAfterActions)
            .putString("speech_language", next.speechLanguage)
            .putString("instructions", next.instructions)
            .putInt("memory_minutes", next.memoryMinutes)
            .putInt("answer_wait_s", next.answerWaitSeconds)
            .putString("chatgpt", next.chatGpt)
            .apply()
        _config.value = next
    }

    private fun load(): AssistantConfig {
        val d = AssistantConfig()
        val baseUrl = prefs.getString("base_url", d.baseUrl).orEmpty()
        val provider = runCatching { Provider.valueOf(prefs.getString("provider", null).orEmpty()) }.getOrDefault(d.provider)
        return AssistantConfig(
            enabled = prefs.getBoolean("enabled", d.enabled),
            // A known service's address typed in by hand is that service's preset.
            provider = if (provider == Provider.OPENAI_COMPATIBLE) Provider.at(baseUrl) ?: provider else provider,
            apiKey = prefs.getString("api_key", d.apiKey).orEmpty(),
            model = prefs.getString("model", d.model).orEmpty(),
            linesModel = prefs.getString("lines_model", d.linesModel).orEmpty(),
            baseUrl = baseUrl,
            webSearch = prefs.getBoolean("web_search", d.webSearch),
            vision = prefs.getBoolean("vision", d.vision),
            voicePhotos = prefs.getBoolean("voice_photos", d.voicePhotos),
            // Before "tts", a boolean chose the glasses' voice or the phone's.
            tts = prefs.getString("tts", null)?.let { runCatching { TtsPath.valueOf(it) }.getOrNull() }
                ?: if (prefs.getBoolean("glasses_voice", true)) TtsPath.GLASSES else TtsPath.PHONE,
            ttsBaseUrl = prefs.getString("tts_base_url", d.ttsBaseUrl).orEmpty(),
            ttsApiKey = prefs.getString("tts_api_key", d.ttsApiKey).orEmpty(),
            ttsModel = prefs.getString("tts_model", d.ttsModel).orEmpty(),
            ttsVoice = prefs.getString("tts_voice", d.ttsVoice).orEmpty(),
            quietAfterActions = prefs.getBoolean("quiet_after_actions", d.quietAfterActions),
            speechLanguage = prefs.getString("speech_language", d.speechLanguage).orEmpty(),
            instructions = prefs.getString("instructions", d.instructions).orEmpty(),
            memoryMinutes = prefs.getInt("memory_minutes", d.memoryMinutes),
            answerWaitSeconds = prefs.getInt("answer_wait_s", d.answerWaitSeconds),
            chatGpt = prefs.getString("chatgpt", d.chatGpt).orEmpty(),
        )
    }

    private companion object {
        const val FIELDS = "server_fields"
        const val SESSION_VOICES = "session_voices"
        const val NO_SEARCH = "no_search"
        const val SLOTS = "provider_slots"

        fun searchKey(c: AssistantConfig) = "${c.provider.name} ${c.answerModel}"
    }
}
