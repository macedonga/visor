package com.macedonga.visor.core.assistant

/** The Claude model asked when none is set. */
const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-5-5"

internal const val OPENAI_API = "https://api.openai.com/v1"

/** Where a ChatGPT account sees and limits what apps use of its plan. */
const val CHATGPT_USAGE = "https://chatgpt.com/settings/usage"

/** How a provider searches the web for an answer. */
enum class WebSearch {
    /** Anthropic's own web_search tool. */
    CLAUDE,

    /** The Responses API's web_search tool. */
    RESPONSES,

    /** Gemini's own API with its google_search tool. */
    GEMINI,

    /** A tool the server runs inside a Chat Completions request. */
    OPENROUTER,
    GROQ,
}

/**
 * Where an answer comes from: a known service with its API address, the page where a key is made, and how it
 * searches the web; or [OPENAI_COMPATIBLE], any server that speaks Chat Completions at the address the person gives.
 * [CHATGPT] is the person's own ChatGPT plan, signed in instead of a key. [billing] is where credit is added and
 * [limits] where the account's limits show, for a [Refusal] to point at.
 */
enum class Provider(
    val title: String, val api: String, val keys: String, val search: WebSearch?, val defaultModel: String = "",
    val billing: String = "", val limits: String = billing,
) {
    CLAUDE(
        "Claude", ANTHROPIC_API, "https://console.anthropic.com/settings/keys", WebSearch.CLAUDE, DEFAULT_CLAUDE_MODEL,
        "https://console.anthropic.com/settings/billing", "https://console.anthropic.com/settings/limits",
    ),
    CHATGPT("ChatGPT", OPENAI_API, "", WebSearch.RESPONSES, billing = CHATGPT_USAGE),
    OPENAI(
        "OpenAI", OPENAI_API, "https://platform.openai.com/api-keys", WebSearch.RESPONSES,
        billing = "https://platform.openai.com/settings/organization/billing/overview",
        limits = "https://platform.openai.com/settings/organization/limits",
    ),
    // Gemini's billing is set per project, from the key's page.
    GEMINI(
        "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey", WebSearch.GEMINI,
        billing = "https://aistudio.google.com/apikey", limits = "https://aistudio.google.com/rate-limit",
    ),
    GROK("Grok", "https://api.x.ai/v1", "https://console.x.ai", WebSearch.RESPONSES, billing = "https://console.x.ai/team/default/billing"),
    OPENROUTER(
        "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/settings/keys", WebSearch.OPENROUTER,
        billing = "https://openrouter.ai/settings/credits",
    ),
    GROQ("Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys", WebSearch.GROQ, billing = "https://console.groq.com/settings/billing"),
    OPENAI_COMPATIBLE("", "", "", null);

    /** Answers come over Chat Completions (when they do not search). */
    val chat: Boolean get() = this != CLAUDE && this != CHATGPT

    companion object {
        /** The service that serves [url], for an address typed in before there were presets. */
        fun at(url: String): Provider? =
            entries.firstOrNull { it.chat && it != OPENAI_COMPATIBLE && url.trim().trimEnd('/').equals(it.api, ignoreCase = true) }
    }
}
