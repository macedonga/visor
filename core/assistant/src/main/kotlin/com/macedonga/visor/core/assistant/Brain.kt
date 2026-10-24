package com.macedonga.visor.core.assistant

import android.util.Base64
import com.macedonga.visor.core.assistant.mcp.ExposedTool
import com.macedonga.visor.core.assistant.mcp.McpHub
import com.macedonga.visor.core.assistant.mcp.PHOTO_BASE64
import com.macedonga.visor.core.assistant.mcp.PHOTO_DATA_URL
import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.assistant.mcp.ToolResult
import com.macedonga.visor.core.assistant.mcp.Toolbox
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Where an answer comes from. */
enum class Provider { CLAUDE, OPENAI_COMPATIBLE }

/** One question, its answer and what the tools did for it: the conversation behind the next question. */
data class Exchange(val question: String, val answer: String, val actions: List<ToolAction> = emptyList())

/** The answer as the model reads it later: with Visor's note of what the tools did, so words alone never pass for an action. */
private fun Exchange.told(): String = if (actions.isEmpty()) answer else
    answer + " [Visor: " + actions.joinToString("; ") { "${it.server} · ${it.what}, " + if (it.ok) "done" else "failed" } + "]"

/** An answer, what the model chose to remember while giving it, and what it did with the person's tools. */
data class Reply(val text: String, val remembered: List<String> = emptyList(), val actions: List<ToolAction> = emptyList())

/** What one answer may use and what it did with it; [photo] is the glasses' JPEG that came with the question. */
private class Turn(val box: Toolbox, val photo: ByteArray? = null, val glasses: GlassesToolbox? = null) {
    val remembered = ArrayList<String>()
    val actions = ArrayList<ToolAction>()
}

/** What the assistant keeps about the wearer across conversations; the model adds and drops entries with its tools. */
interface MemoryBook {
    val memories: StateFlow<List<Memory>>
    fun remember(text: String): Memory
    fun forget(id: String): Memory?
}

/** What an OpenAI-compatible server and model take: reasoning_effort none, max_completion_tokens for max_tokens. */
data class Fields(val reasoningOff: Boolean, val completionTokens: Boolean)

/** Where [Fields] are kept, by address and model, until either changes. */
interface FieldBook {
    fun fields(key: String): Fields?
    fun keep(key: String, fields: Fields)
}

/** Who the answer is for: spoken by the glasses, or read on the phone. */
enum class Audience { GLASSES, PHONE }

/** The model behind the assistant. The conversation comes with each question, from the diary; tools from [hub] and [phone]. */
class Brain(
    private val config: () -> AssistantConfig,
    private val book: MemoryBook? = null,
    private val hub: McpHub? = null,
    private val fields: FieldBook? = null,
    phone: Phone? = null,
) {
    private val phoneTools = phone?.let(::PhoneTools)

    /** The answer to [question] after [past], with [image] (a JPEG) when the glasses sent one. Throws on any failure. */
    suspend fun answer(
        question: String,
        locale: Locale,
        past: List<Exchange> = emptyList(),
        image: ByteArray? = null,
        note: String = "",
        audience: Audience = Audience.GLASSES,
        glasses: GlassesToolbox? = null,
    ): Reply = withContext(Dispatchers.IO) {
        val c = config()
        check(c.apiKey.isNotBlank() || c.provider == Provider.OPENAI_COMPATIBLE) { "no API key is set" }
        // The tools never stand in the way of an answer: without them the model still answers.
        val turn = Turn(hub?.let { runCatching { it.toolbox() }.getOrNull() } ?: Toolbox(emptyList(), ""), image, glasses)
        val system = (if (audience == Audience.PHONE) PHONE_PROMPT else SYSTEM_PROMPT) +
            " It is ${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))}." +
            (if (audience == Audience.GLASSES) " The glasses are set to ${locale.getDisplayLanguage(Locale.ENGLISH)}." else "") +
            memoryPrompt() +
            phoneTools?.prompt.orEmpty() +
            turn.box.prompt +
            (if (image != null && turn.box.tools.isNotEmpty()) PHOTO_PROMPT else "") +
            (if (note.isNotBlank()) " $note" else "") +
            if (c.instructions.isNotBlank()) " " + c.instructions else ""
        val recent = past.takeLast(MAX_EXCHANGES)
        val tokens = if (audience == Audience.PHONE) PHONE_TOKENS else GLASSES_TOKENS
        val text = when (c.provider) {
            Provider.CLAUDE -> claude(c, c.model.ifBlank { DEFAULT_CLAUDE_MODEL }, system, recent, question, image, tokens, c.webSearch, turn)
            Provider.OPENAI_COMPATIBLE -> openAi(c, c.model, system, recent, question, image, tokens, turn)
        }.replace(VISOR_NOTE, "").trim()
        check(text.isNotEmpty()) { "the model gave an empty answer" }
        Reply(text, turn.remembered, turn.actions)
    }

    /** One sentence for the wearer, in [locale]'s language, written from [what]; outside the conversation. */
    suspend fun phrase(what: String, locale: Locale): String = withContext(Dispatchers.IO) {
        val c = config()
        check(c.apiKey.isNotBlank() || c.provider == Provider.OPENAI_COMPATIBLE) { "no API key is set" }
        val system = "You write one short sentence that smart glasses speak to their wearer, in " +
            "${locale.getDisplayLanguage(Locale.ENGLISH)}. Answer with that sentence only."
        write(c, c.model, system, what, 200)
    }

    /** A day's lines for the diary, in [locale]'s language, from [exchanges] in order; with the lines' own model if one is set. */
    suspend fun dayLines(exchanges: String, locale: Locale): String = withContext(Dispatchers.IO) {
        val c = config()
        check(c.apiKey.isNotBlank() || c.provider == Provider.OPENAI_COMPATIBLE) { "no API key is set" }
        val system = "You keep the diary of a person who wears smart glasses with a voice assistant. From the day's " +
            "exchanges with the assistant, write the day's entry: one to three short sentences in " +
            "${locale.getDisplayLanguage(Locale.ENGLISH)}, in the second person (\"You asked about...\"), dry and precise, " +
            "naming the actual subjects, places and things looked at, in the order they came. No times, no greeting, no " +
            "judgement, no markdown. Answer with the entry only."
        write(c, c.linesModel.ifBlank { c.model }, system, exchanges, 300)
    }

    private suspend fun write(c: AssistantConfig, model: String, system: String, text: String, tokens: Int): String = when (c.provider) {
        Provider.CLAUDE -> claude(c, model.ifBlank { DEFAULT_CLAUDE_MODEL }, system, emptyList(), text, null, tokens, false, null)
        Provider.OPENAI_COMPATIBLE -> openAi(c, model, system, emptyList(), text, null, tokens, null)
    }.trim().also { check(it.isNotEmpty()) { "the model gave an empty answer" } }

    /** What the model knows of the wearer, and how to keep it. Empty when there is nowhere to keep it. */
    private fun memoryPrompt(): String {
        val book = book ?: return ""
        val known = book.memories.value
        return " You can keep lasting facts about the wearer with the remember tool, and drop wrong or outdated ones with " +
            "forget. When the wearer mentions something that would help in later conversations (people and their names, " +
            "where they live or work, preferences, plans, what they own), end your answer by asking whether to remember " +
            "it, as in \"Should I remember that you work at the hospital?\". Call remember only once they say yes, or " +
            "when they ask you to remember something, and then say in a few words that it is in your memories. Forget " +
            "when they ask you to, saying so. Never keep the moment's small talk, and always give your answer too." +
            if (known.isEmpty()) " You remember nothing about the wearer yet." else
                " What you remember about the wearer, with each fact's id: " + known.joinToString(" ") { "[${it.id}] ${it.text}" }
    }

    /** A tool run for the model: remember or forget, or one of the person's servers'. What it is told back. */
    private suspend fun runTool(name: String, input: JSONObject, turn: Turn): ToolResult {
        val book = book
        if (book != null && name == "remember") {
            val fact = input.optString("fact").trim()
            if (fact.isEmpty()) return ToolResult("nothing to remember", true)
            book.remember(fact)
            turn.remembered += fact
            return ToolResult("kept", false)
        }
        if (book != null && name == "forget") {
            return if (book.forget(input.optString("id")) != null) ToolResult("forgotten", false) else ToolResult("no such fact", true)
        }
        phoneTools?.run(name, input)?.let { (result, action) ->
            action?.let(turn.actions::add)
            return result
        }
        val glasses = turn.glasses
        if (glasses != null) glasses.tools.find { it.short == name }?.let { return glasses.call(it.full, input) }
        val tool = turn.box.find(name) ?: return ToolResult("unknown tool", true)
        val (result, action) = hub?.call(tool, input, turn.photo) ?: return ToolResult("unavailable", true)
        action?.let(turn.actions::add)
        return result
    }

    /** A server's tool, described for the model with where it comes from. */
    private fun ExposedTool.help(): String =
        ("[${server.name}] " + (if (tool.title.isNotEmpty()) "${tool.title}. " else "") + tool.description).take(MAX_TOOL_HELP)

    private suspend fun claude(
        c: AssistantConfig, model: String, system: String, past: List<Exchange>, question: String, image: ByteArray?,
        tokens: Int, webSearch: Boolean, turn: Turn?,
    ): String {
        val messages = JSONArray()
        for (e in past) {
            messages.put(JSONObject().put("role", "user").put("content", e.question))
            messages.put(JSONObject().put("role", "assistant").put("content", e.told()))
        }
        val content = JSONArray()
        if (image != null) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg")
                        .put("data", Base64.encodeToString(image, Base64.NO_WRAP)),
                ),
            )
        }
        content.put(JSONObject().put("type", "text").put("text", question))
        messages.put(JSONObject().put("role", "user").put("content", content))
        val tools = JSONArray()
        if (webSearch) tools.put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 2))
        if (turn != null && book != null) {
            tools.put(JSONObject().put("name", "remember").put("description", REMEMBER_HELP).put("input_schema", schema("fact", FACT_HELP)))
            tools.put(JSONObject().put("name", "forget").put("description", FORGET_HELP).put("input_schema", schema("id", "The fact's id.")))
        }
        if (turn != null) phoneTools?.tools?.forEach { (name, help, input) -> tools.put(JSONObject().put("name", name).put("description", help).put("input_schema", input)) }
        turn?.box?.tools?.forEach { t -> tools.put(JSONObject().put("name", t.name).put("description", t.help()).put("input_schema", t.tool.schema)) }
        turn?.glasses?.tools?.forEach { t -> tools.put(JSONObject().put("name", t.short).put("description", t.help).put("input_schema", t.schema)) }
        var said = ""
        repeat(MAX_TOOL_ROUNDS) {
            // Current models think before they answer, and the thinking counts toward max_tokens.
            val body = JSONObject().put("model", model).put("max_tokens", tokens + THINKING_TOKENS).put("system", system).put("messages", messages)
            if (tools.length() > 0) body.put("tools", tools)
            val answer = post("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to c.apiKey, "anthropic-version" to "2023-06-01"), body)
            val blocks = answer.getJSONArray("content")
            val list = (0 until blocks.length()).map { blocks.getJSONObject(it) }
            // With web search the answer is split into several text blocks around the citations.
            val text = list.filter { it.optString("type") == "text" }.joinToString("") { it.getString("text") }
            if (text.isNotBlank()) said = text
            val uses = list.filter { it.optString("type") == "tool_use" }
            check(said.isNotBlank() || answer.optString("stop_reason") != "max_tokens") { "the model used its whole answer thinking" }
            if (answer.optString("stop_reason") != "tool_use" || uses.isEmpty() || turn == null) return said
            messages.put(JSONObject().put("role", "assistant").put("content", blocks))
            val results = JSONArray()
            for (use in uses) {
                val out = runTool(use.getString("name"), use.optJSONObject("input") ?: JSONObject(), turn)
                results.put(
                    JSONObject().put("type", "tool_result").put("tool_use_id", use.getString("id")).put("content", out.text)
                        .apply { if (out.isError) put("is_error", true) },
                )
            }
            messages.put(JSONObject().put("role", "user").put("content", results))
        }
        return said
    }

    private suspend fun openAi(
        c: AssistantConfig, model: String, system: String, past: List<Exchange>, question: String, image: ByteArray?,
        tokens: Int, turn: Turn?,
    ): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (e in past) {
            messages.put(JSONObject().put("role", "user").put("content", e.question))
            messages.put(JSONObject().put("role", "assistant").put("content", e.told()))
        }
        val user: Any = if (image == null) question else JSONArray()
            .put(JSONObject().put("type", "text").put("text", question))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP)),
                ),
            )
        messages.put(JSONObject().put("role", "user").put("content", user))
        val headers = if (c.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${c.apiKey}")
        val url = c.baseUrl.trimEnd('/') + "/chat/completions"
        var tools: JSONArray? = JSONArray().apply {
            if (turn != null && book != null) {
                put(function("remember", REMEMBER_HELP, schema("fact", FACT_HELP)))
                put(function("forget", FORGET_HELP, schema("id", "The fact's id.")))
            }
            if (turn != null) phoneTools?.tools?.forEach { (name, help, input) -> put(function(name, help, input)) }
            // Some servers behind this API refuse a schema that names its own dialect.
            turn?.box?.tools?.forEach { t -> put(function(t.name, t.help(), JSONObject(t.tool.schema.toString()).apply { remove("\$schema") })) }
            turn?.glasses?.tools?.forEach { t -> put(function(t.short, t.help, t.schema)) }
        }.takeIf { it.length() > 0 }
        var said = ""
        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject().put("model", model).put("messages", messages).put("max_tokens", tokens)
            tools?.let { body.put("tools", it) }
            val answer = try {
                send(url, headers, body)
            } catch (error: IllegalStateException) {
                // A server without tools refuses the field; then the conversation goes on without memory. The person's
                // own tools are never dropped unseen: their refusal is the answer's error.
                if (tools == null || turn?.box?.tools.orEmpty().isNotEmpty() || !TOOLS_REFUSED.containsMatchIn(error.message.orEmpty())) throw error
                tools = null
                send(url, headers, body.apply { remove("tools") })
            }
            val choice = answer.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val text = message.optString("content").takeUnless { message.isNull("content") }.orEmpty().replace(THINKING, "")
            check(text.isNotBlank() || choice.optString("finish_reason") != "length") { "the model used its whole answer thinking" }
            if (text.isNotBlank()) said = text
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0 || turn == null) return said
            messages.put(message)
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val input = runCatching { JSONObject(fn.optString("arguments")) }.getOrDefault(JSONObject())
                val out = runTool(fn.getString("name"), input, turn)
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", out.text))
            }
        }
        return said
    }

    /**
     * A reasoning model otherwise spends the whole answer thinking (LM Studio, llama.cpp), or refuses tools on this API
     * (OpenAI's GPT-6), so reasoning_effort goes out as none; a model without reasoning refuses the field. OpenAI's newer
     * models, and servers relaying them (Azure, a proxy), want max_completion_tokens. The first answer finds out which,
     * and that is kept for the address and model.
     */
    private suspend fun send(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val key = url + " " + body.optString("model")
        val known = fields?.fields(key)
        // Every OpenAI model takes max_completion_tokens.
        var f = known ?: Fields(reasoningOff = true, completionTokens = "api.openai.com" in url)
        while (true) {
            try {
                return post(url, headers, shaped(body, f)).also { if (f != known) fields?.keep(key, f) }
            } catch (error: IllegalStateException) {
                val why = error.message.orEmpty()
                f = when {
                    known == null && f.reasoningOff && "reasoning" in why -> f.copy(reasoningOff = false)
                    known == null && !f.completionTokens && "max_completion_tokens" in why -> f.copy(completionTokens = true)
                    else -> throw error
                }
            }
        }
    }

    private fun shaped(body: JSONObject, f: Fields) = JSONObject(body.toString()).apply {
        if (f.reasoningOff) put("reasoning_effort", "none")
        if (f.completionTokens) put("max_completion_tokens", remove("max_tokens"))
    }

    /** Cancelled with its turn: a blocking read ignores cancellation, so the connection is closed under it. */
    private suspend fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject = suspendCancellableCoroutine { call ->
        val http = URL(url).openConnection() as HttpURLConnection
        // disconnect can wait on the read it ends, so not on the cancelling thread.
        call.invokeOnCancellation { thread { http.disconnect() } }
        try {
            http.requestMethod = "POST"
            http.connectTimeout = 5_000
            // A local model reading a picture can take a while; each turn still has its own budget above.
            http.readTimeout = 45_000
            http.doOutput = true
            http.setRequestProperty("content-type", "application/json")
            headers.forEach(http::setRequestProperty)
            http.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = http.responseCode
            val text = (if (code in 200..299) http.inputStream else http.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
                error("the model answered $code: $message")
            }
            call.resume(JSONObject(text))
        } catch (error: Exception) {
            call.resumeWithException(error)
        } finally {
            http.disconnect()
        }
    }

    private companion object {
        /** Thinking a server left inline in the answer. */
        val THINKING = Regex("(?s)<think>.*?</think>")
        /** Visor's note on past answers, should the model copy it into its own. */
        val VISOR_NOTE = Regex("""\s*\[Visor:[^\]]*]""")
        val TOOLS_REFUSED = Regex("tool|function", RegexOption.IGNORE_CASE)
        const val MAX_EXCHANGES = 12
        // A tool often looks something up, acts, then answers: room for a few of those.
        const val MAX_TOOL_ROUNDS = 6
        const val MAX_TOOL_HELP = 1_024
        const val GLASSES_TOKENS = 400
        const val THINKING_TOKENS = 4_000
        const val PHONE_TOKENS = 900
        const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-5-5"
        const val SYSTEM_PROMPT =
            "You are the voice assistant in a pair of Meta smart glasses, run by the wearer's own app, Visor. " +
                "Everything you write is spoken aloud through the glasses' speakers, so answer in one to three short, " +
                "natural sentences unless asked for more. No lists, markdown, emoji, URLs or citations. " +
                "Reply in the language the wearer speaks. When an image is attached, it is what the wearer is looking at " +
                "through the glasses' camera right now."
        const val PHONE_PROMPT =
            "You are the assistant of a person who wears Meta smart glasses, run by their own app, Visor. Right now " +
                "they are writing to you in the app on their phone, often to continue what they asked the glasses; your " +
                "answer is shown there as text, not spoken. Be direct and brief: a few short paragraphs at most, plain " +
                "text without markdown or emoji. Reply in the language they write in."
        const val REMEMBER_HELP = "Keep a lasting fact about the wearer for later conversations."
        const val FORGET_HELP = "Drop a fact you remember that is wrong or outdated, or that the wearer asks you to forget."
        const val FACT_HELP = "One short sentence addressed to the wearer, in their language, e.g. \"Your sister is called Giulia.\""

        fun schema(field: String, help: String): JSONObject = JSONObject().put("type", "object")
            .put("properties", JSONObject().put(field, JSONObject().put("type", "string").put("description", help)))
            .put("required", JSONArray().put(field))

        fun function(name: String, help: String, parameters: JSONObject): JSONObject = JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", help).put("parameters", parameters))
    }
}

// A tool takes the glasses' photo by a placeholder; McpHub puts the photo in its place.
private val PHOTO_PROMPT = " The glasses' photo comes with this question. To hand it to a tool, write \"$PHOTO_BASE64\" as " +
    "that argument's value for the JPEG as base64, or \"$PHOTO_DATA_URL\" for it as a data URL; Visor puts the photo there."
