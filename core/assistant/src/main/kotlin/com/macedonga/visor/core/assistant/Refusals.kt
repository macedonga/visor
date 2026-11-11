package com.macedonga.visor.core.assistant

/** What the person can do about a provider refusing an answer. */
enum class Refusal {
    /** The key is wrong or revoked: paste it again, or make another. */
    KEY,

    /** The account has no credit left: add some. */
    CREDIT,

    /** Too many requests, or the quota for now is used up: wait, or raise the limit. */
    LIMIT,

    /** The key cannot use this model: pick another. */
    MODEL,

    /** ChatGPT does not share the person's plan with Visor: allow it in ChatGPT's usage settings. */
    PLAN,
}

/**
 * A provider refused an answer: its status and words as it gave them, and [why] when Visor knows what the person can
 * do about it. The message keeps the "the model answered" form other checks read.
 */
class ModelRefused(val code: Int, val detail: String, val why: Refusal?) : IllegalStateException("the model answered $code: $detail")

/** What a refusal with status [code] and error body [body] means, from the providers' own wording. */
internal fun refusalOf(code: Int, body: String): Refusal? {
    val t = body.lowercase()
    return when {
        // Anthropic and OpenAI answer 401; Gemini answers 400 "API key not valid".
        code == 401 || "api key" in t && ("invalid" in t || "not valid" in t || "incorrect" in t) -> Refusal.KEY
        // OpenAI: 429 insufficient_quota. Anthropic: 400 "credit balance is too low". OpenRouter: 402. xAI: "any credits".
        code == 402 || "insufficient_quota" in t || "credit balance" in t || "insufficient credits" in t || "any credits" in t -> Refusal.CREDIT
        code == 429 -> Refusal.LIMIT
        code == 404 && "model" in t -> Refusal.MODEL
        else -> null
    }
}
