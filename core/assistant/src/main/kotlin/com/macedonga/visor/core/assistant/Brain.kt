package com.macedonga.visor.core.assistant

import android.util.Base64
import com.macedonga.visor.core.assistant.mcp.ExposedTool
import com.macedonga.visor.core.assistant.mcp.McpHub
import com.macedonga.visor.core.assistant.mcp.PHOTO_BASE64
import com.macedonga.visor.core.assistant.mcp.PHOTO_DATA_URL
import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.assistant.mcp.ToolResult
import com.macedonga.visor.core.assistant.mcp.Toolbox
import com.macedonga.visor.core.connection.objects
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One question, its answer and what the tools did for it: the conversation behind the next question. */
data class Exchange(val question: String, val answer: String, val actions: List<ToolAction> = emptyList())

/** The answer as the model reads it later: with Visor's note of what the tools did, so words alone never pass for an action. */
private fun Exchange.told(): String = if (actions.isEmpty()) answer else
    answer + " [Visor: " + actions.joinToString("; ") { "${it.server.ifEmpty { "Phone" }} · ${it.what}, " + if (it.ok) "done" else "failed" } + "]"

/** [past] as the conversation so far: each question and its answer. */
private fun JSONArray.putPast(past: List<Exchange>): JSONArray = apply {
    for (e in past) {
        put(JSONObject().put("role", "user").put("content", e.question))
        put(JSONObject().put("role", "assistant").put("content", e.told()))
    }
}

/**
 * An answer, what the model chose to remember while giving it, and what it did with the person's tools. [ends]: the
 * model ended the conversation, so the glasses should stop listening after the answer.
 */
data class Reply(
    val text: String, val remembered: List<String> = emptyList(), val actions: List<ToolAction> = emptyList(), val ends: Boolean = false,
)

/**
 * What one answer may use and what it did with it; [photo] is the glasses' JPEG that came with the question, [canEnd]
 * whether the model may end the conversation.
 */
private class Turn(
    val box: Toolbox, var photo: ByteArray? = null, val glasses: GlassesToolbox? = null, val canEnd: Boolean = false,
    val capturePhoto: (suspend () -> ByteArray?)? = null,
    val cameraAction: (suspend (CameraAction) -> Boolean)? = null,
    val apps: List<AppTool> = emptyList(),
) {
    val remembered = ArrayList<String>()
    val actions = ArrayList<ToolAction>()
    // Apps' tools by the name the model sees, which [Brain.specs] gives them.
    val appNames = HashMap<String, AppTool>()
    var ends = false
    var cameraRequested = false
    // A tool ran: asking again would run it twice.
    var toolsRan = false
}

/**
 * What an OpenAI-compatible server and model take: reasoning_effort none, max_completion_tokens for max_tokens. [thinks]:
 * the model thinks anyway, and its answer needs room for that.
 */
data class Fields(val reasoningOff: Boolean, val completionTokens: Boolean, val thinks: Boolean = false)

/** Who the answer is for: spoken by the glasses, or read on the phone. */
enum class Audience { GLASSES, PHONE }

/** Why a photo was refused before the glasses were asked: the wearer has not turned on Voice photos. */
const val VOICE_PHOTOS_OFF = "turn on Voice photos in Meta AI settings before taking photos"

enum class CameraAction(val tool: String, val title: String, val help: String, val confirmed: String) {
    TAKE_PHOTO(
        "take_photo", "Take photo",
        "Take a normal photo to keep on the glasses, without analyzing the scene. No image is returned. " +
            "Report failure rather than substituting look_at_view. At most one camera request per question.",
        "photo capture started",
    ),
    RECORD_VIDEO(
        "record_video", "Record video",
        "Start recording a normal video on the glasses. Use for requests to record or film a video. " +
            "The video stays on the glasses, not in image analysis. At most one camera request per question.",
        "video recording started",
    ),
    RECORD_HYPERLAPSE(
        "record_hyperlapse", "Record hyperlapse",
        "Start recording a hyperlapse on the glasses, only when the wearer asks for hyperlapse. " +
            "The video stays on the glasses. At most one camera request per question.",
        "hyperlapse recording started",
    ),
    STOP_RECORDING(
        "stop_recording", "Stop recording",
        "Stop recording video or hyperlapse on the glasses. This does not stop music or end the conversation. " +
            "It is available even when camera use is off. At most one camera request per question.",
        "video recording is stopped",
    ),
}

/** The model behind the assistant. The conversation comes with each question, from the diary; tools from [hub], [phone] and [handset]. */
class Brain(
    private val store: AssistantConfigStore, private val diary: Diary, private val hub: McpHub, phone: Phone, private val appTools: AppTools,
    private val chatGpt: ChatGptPlan, handset: Handset,
) {
    private val phoneTools = PhoneTools(phone)
    private val handsetTools = HandsetTools(handset) { messaging }

    /** The phone's notifications, for the message tools; the assistant sets it. */
    @Volatile
    var messaging: Messaging? = null

    private fun config() = store.config.value

    /**
     * The answer to [question] after [past], with [image] (a JPEG) when the glasses sent one; [canEnd] offers the
     * end_conversation tool. Throws on any failure.
     */
    suspend fun answer(
        question: String,
        locale: Locale,
        past: List<Exchange> = emptyList(),
        image: ByteArray? = null,
        note: String = "",
        audience: Audience = Audience.GLASSES,
        glasses: GlassesToolbox? = null,
        canEnd: Boolean = false,
        capturePhoto: (suspend () -> ByteArray?)? = null,
        cameraAction: (suspend (CameraAction) -> Boolean)? = null,
    ): Reply = withContext(Dispatchers.IO) {
        // With the camera off only a stop goes without the model; the model answers the rest, its camera tools withheld.
        val keyword = if (cameraAction != null && audience == Audience.GLASSES) {
            cameraKeyword(question, locale)?.takeIf { it in cameraTools(config()) }
        } else null
        if (keyword != null) {
            val turn = Turn(Toolbox(emptyList(), ""), cameraAction = cameraAction)
            val result = runTool(keyword.tool, JSONObject(), turn)
            val text = when {
                !result.isError -> ""
                result.text == VOICE_PHOTOS_OFF && locale.language == "vi" -> "Hãy bật Voice photos trong cài đặt Meta AI để chụp ảnh."
                result.text == VOICE_PHOTOS_OFF -> "Turn on Voice photos in Meta AI settings to take photos."
                locale.language == "vi" -> "Kính chưa xác nhận lệnh camera."
                else -> "The glasses did not confirm the camera command."
            }
            return@withContext Reply(text, actions = turn.actions)
        }
        val c = ready()
        // The tools never stand in the way of an answer: without them the model still answers.
        val box = try {
            hub.toolbox()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Toolbox(emptyList(), "")
        }
        val turn = Turn(box, image, glasses?.described(), canEnd, capturePhoto, cameraAction, appTools.tools())
        val system = (if (audience == Audience.PHONE) PHONE_PROMPT else SYSTEM_PROMPT) +
            " It is ${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))}." +
            (if (audience == Audience.GLASSES) " The glasses are set to ${locale.getDisplayLanguage(Locale.ENGLISH)}." else "") +
            memoryPrompt() +
            phoneTools.prompt + handsetTools.prompt +
            (if (canEnd) END_PROMPT else "") +
            turn.box.prompt +
            (if ((image != null || capturePhoto != null) && turn.box.tools.isNotEmpty()) PHOTO_PROMPT else "") +
            (if (note.isNotBlank()) " $note" else "") +
            if (c.instructions.isNotBlank()) " " + c.instructions else ""
        val recent = past.takeLast(MAX_EXCHANGES)
        val tokens = if (audience == Audience.PHONE) PHONE_TOKENS else GLASSES_TOKENS
        val search = c.webSearch && c.provider.search != null && !store.cannotSearch(c)
        val said = try {
            ask(c, system, recent, question, image, tokens, search, turn)
        } catch (error: IllegalStateException) {
            // A model that refuses the search still answers without it, and the settings say so. Not after a tool ran:
            // asking again would run it twice.
            if (!search || turn.toolsRan || !SEARCH_REFUSED.containsMatchIn(error.message.orEmpty())) throw error
            ask(c, system, recent, question, image, tokens, false, turn).also { store.markCannotSearch(c) }
        }
        val text = (if (search) spoken(said) else said).replace(VISOR_NOTE, "").trim()
        check(text.isNotEmpty()) { "the model gave an empty answer" }
        Reply(text, turn.remembered, turn.actions, turn.ends)
    }

    /** One sentence for the wearer, in [locale]'s language, written from [what]; outside the conversation. */
    suspend fun phrase(what: String, locale: Locale): String = withContext(Dispatchers.IO) {
        val c = ready()
        val system = "You write one short sentence that smart glasses speak to their wearer, in " +
            "${locale.getDisplayLanguage(Locale.ENGLISH)}. Answer with that sentence only."
        write(c, c.model, system, what, 200)
    }

    /** A day's lines for the diary, in [locale]'s language, from [exchanges] in order; with the lines' own model if one is set. */
    suspend fun dayLines(exchanges: String, locale: Locale): String = withContext(Dispatchers.IO) {
        val c = ready()
        val system = "You keep the diary of a person who wears smart glasses with a voice assistant. From the day's " +
            "exchanges with the assistant, write the day's entry: one to three short sentences in " +
            "${locale.getDisplayLanguage(Locale.ENGLISH)}, in the second person (\"You asked about...\"), dry and precise, " +
            "naming the actual subjects, places and things looked at, in the order they came. No times, no greeting, no " +
            "judgement, no markdown. Answer with the entry only."
        write(c, c.linesModel.ifBlank { c.model }, system, exchanges, 300)
    }

    private fun ready(): AssistantConfig =
        config().also { check(it.hasModel) { "no API key is set" } }

    /** One answer from the provider, through the API that can [search] when asked to. */
    private suspend fun ask(
        c: AssistantConfig, system: String, past: List<Exchange>, question: String, image: ByteArray?, tokens: Int, search: Boolean, turn: Turn,
    ): String {
        val model = c.answerModel
        return when {
            c.provider == Provider.CLAUDE -> claude(c, model, system, past, question, image, tokens, search, turn)
            c.provider == Provider.CHATGPT -> responses(c, model, system, past, question, image, tokens, search, turn)
            search && c.provider.search == WebSearch.RESPONSES -> responses(c, model, system, past, question, image, tokens, true, turn)
            search && c.provider.search == WebSearch.GEMINI -> gemini(c, model, system, past, question, image, tokens, turn)
            else -> openAi(c, model, system, past, question, image, tokens, turn, if (search) chatSearch(c.provider) else null)
        }
    }

    private suspend fun write(c: AssistantConfig, model: String, system: String, text: String, tokens: Int): String = when (c.provider) {
        Provider.CLAUDE -> claude(c, model.ifBlank { DEFAULT_CLAUDE_MODEL }, system, emptyList(), text, null, tokens, false, null)
        Provider.CHATGPT -> responses(c, model.ifBlank { c.answerModel }, system, emptyList(), text, null, tokens, false, null)
        else -> openAi(c, model, system, emptyList(), text, null, tokens, null)
    }.trim().also { check(it.isNotEmpty()) { "the model gave an empty answer" } }

    /** What the model knows of the wearer, and how to keep it. */
    private fun memoryPrompt(): String {
        val known = diary.memories.value
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
        turn.toolsRan = true
        val cameraAction = CameraAction.entries.firstOrNull { it.tool == name }
        if (name == PHOTO_TOOL || cameraAction != null) {
            val result = if (turn.cameraRequested) ToolResult("a camera request was already made for this question; use that result", true) else {
                turn.cameraRequested = true
                try {
                    if (cameraAction != null) {
                        val perform = checkNotNull(turn.cameraAction) { "camera commands are not available for this question" }
                        if (perform(cameraAction)) ToolResult("the glasses confirmed ${cameraAction.confirmed}; do not describe the scene", false)
                        else ToolResult("the glasses did not confirm ${cameraAction.title}; do not report success", true)
                    } else {
                        val capture = checkNotNull(turn.capturePhoto) { "the glasses camera is not available for this question" }
                        turn.photo = capture()
                        if (turn.photo == null) ToolResult("no photo arrived from the glasses; do not guess what the wearer sees", true)
                        else ToolResult("the glasses photo is attached", false)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    ToolResult(error.message ?: "the glasses could not complete the camera request", true)
                }
            }
            if (cameraAction != null) turn.actions += ToolAction("Glasses", cameraAction.title, !result.isError, kind = cameraAction.tool)
            return result
        }
        if (turn.canEnd && name == END_TOOL) {
            turn.ends = true
            return ToolResult("the glasses stop listening after your answer", false)
        }
        if (name == "remember") {
            val fact = input.optString("fact").trim()
            if (fact.isEmpty()) return ToolResult("nothing to remember", true)
            diary.remember(fact)
            turn.remembered += fact
            return ToolResult("kept", false)
        }
        if (name == "forget") {
            return if (diary.forget(input.optString("id")) != null) ToolResult("forgotten", false) else ToolResult("no such fact", true)
        }
        (phoneTools.run(name, input) ?: handsetTools.run(name, input))?.let { (result, action) ->
            action?.let(turn.actions::add)
            return result
        }
        val glasses = turn.glasses
        if (glasses != null) glasses.tools.find { it.short == name }?.let { return glasses.call(it.full, input) }
        turn.box.find(name)?.let { tool ->
            val (result, action) = hub.call(tool, input, turn.photo)
            action?.let(turn.actions::add)
            return result
        }
        val app = turn.appNames[name] ?: return ToolResult("unknown tool", true)
        val result = app.run(input)
        turn.actions += ToolAction(app.app, app.name, !result.isError)
        return result
    }

    /** A server's tool, described for the model with where it comes from. */
    private fun ExposedTool.help(): String =
        ("[${server.name}] " + (if (tool.title.isNotEmpty()) "${tool.title}. " else "") + tool.description).take(MAX_TOOL_HELP)

    /** The camera actions the model may take: with the camera off, only a stop. */
    private fun cameraTools(c: AssistantConfig) = CameraAction.entries.filter { c.vision || it == CameraAction.STOP_RECORDING }

    /** Every tool [turn] offers the model, as name, help and input schema, in the order it sees them. */
    private fun specs(c: AssistantConfig, turn: Turn): List<Triple<String, String, JSONObject>> = buildList {
        add(Triple("remember", REMEMBER_HELP, schema("fact", FACT_HELP)))
        add(Triple("forget", FORGET_HELP, schema("id", "The fact's id.")))
        if (turn.canEnd) add(Triple(END_TOOL, END_HELP, NO_INPUT))
        if (turn.capturePhoto != null) add(Triple(PHOTO_TOOL, PHOTO_HELP, NO_INPUT))
        if (turn.cameraAction != null) cameraTools(c).forEach { add(Triple(it.tool, it.help, NO_INPUT)) }
        addAll(phoneTools.tools)
        addAll(handsetTools.tools)
        turn.box.tools.forEach { add(Triple(it.name, it.help(), it.tool.schema)) }
        turn.glasses?.tools?.forEach { add(Triple(it.short, it.help, it.schema)) }
        // Apps' tools come last; one named like a tool above gets a "_" more, as two apps' tools do.
        val taken = mapTo(HashSet()) { it.first }
        turn.apps.forEach { app ->
            val name = generateSequence(app.name) { it + "_" }.first(taken::add)
            turn.appNames[name] = app
            add(Triple(name, app.help, app.schema))
        }
    }

    private suspend fun claude(
        c: AssistantConfig, model: String, system: String, past: List<Exchange>, question: String, image: ByteArray?,
        tokens: Int, webSearch: Boolean, turn: Turn?,
    ): String {
        val messages = JSONArray().putPast(past)
        val content = JSONArray()
        if (image != null) content.put(claudeImage(image))
        content.put(JSONObject().put("type", "text").put("text", question))
        messages.put(JSONObject().put("role", "user").put("content", content))
        val tools = JSONArray()
        if (webSearch) tools.put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 2))
        turn?.let { specs(c, it) }.orEmpty().forEach { (name, help, input) ->
            tools.put(JSONObject().put("name", name).put("description", help).put("input_schema", input))
        }
        var said = ""
        repeat(MAX_TOOL_ROUNDS) {
            // Current models think before they answer, and the thinking counts toward max_tokens.
            val body = JSONObject().put("model", model).put("max_tokens", tokens + THINKING_TOKENS).put("system", system).put("messages", messages)
            if (tools.length() > 0) body.put("tools", tools)
            val answer = post("$ANTHROPIC_API/messages", anthropicHeaders(c.apiKey), body)
            val blocks = answer.getJSONArray("content")
            val list = blocks.objects()
            // With web search the answer is split into several text blocks around the citations.
            val text = list.filter { it.optString("type") == "text" }.joinToString("") { it.getString("text") }
            if (text.isNotBlank()) said = text
            val uses = list.filter { it.optString("type") == "tool_use" }
            check(said.isNotBlank() || answer.optString("stop_reason") != "max_tokens") { "the model used its whole answer thinking" }
            if (answer.optString("stop_reason") != "tool_use" || uses.isEmpty() || turn == null) return said
            messages.put(JSONObject().put("role", "assistant").put("content", blocks))
            val results = JSONArray()
            for (use in uses) {
                val name = use.getString("name")
                val out = runTool(name, use.optJSONObject("input") ?: JSONObject(), turn)
                val result: Any = if (name == PHOTO_TOOL && !out.isError) JSONArray()
                    .put(JSONObject().put("type", "text").put("text", out.text))
                    .put(claudeImage(checkNotNull(turn.photo))) else out.text
                results.put(
                    JSONObject().put("type", "tool_result").put("tool_use_id", use.getString("id")).put("content", result)
                        .apply { if (out.isError) put("is_error", true) },
                )
            }
            messages.put(JSONObject().put("role", "user").put("content", results))
        }
        return said
    }

    private suspend fun openAi(
        c: AssistantConfig, model: String, system: String, past: List<Exchange>, question: String, image: ByteArray?,
        tokens: Int, turn: Turn?, searchTool: JSONObject? = null,
    ): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system)).putPast(past)
        val user: Any = if (image == null) question else JSONArray()
            .put(JSONObject().put("type", "text").put("text", question))
            .put(openAiImage(image))
        messages.put(JSONObject().put("role", "user").put("content", user))
        val headers = if (c.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${c.apiKey}")
        val url = c.api.trimEnd('/') + "/chat/completions"
        var tools: JSONArray? = JSONArray().apply {
            searchTool?.let(::put)
            // Some servers behind this API refuse a schema that names its own dialect.
            turn?.let { specs(c, it) }.orEmpty().forEach { (name, help, input) ->
                put(function(name, help, JSONObject(input.toString()).apply { remove("\$schema") }))
            }
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
                // With search on, a refusal may be the search's own: answer() asks again without it.
                if (searchTool != null || tools == null || turn?.box?.tools.orEmpty().isNotEmpty() || !TOOLS_REFUSED.containsMatchIn(error.message.orEmpty())) throw error
                tools = null
                if (turn?.capturePhoto != null) messages.put(JSONObject().put("role", "system").put("content", NO_PHOTO))
                if (turn?.cameraAction != null) messages.put(JSONObject().put("role", "system").put("content",
                    "Camera commands are unavailable for this answer. If the wearer asked for a camera action, explain " +
                        "that this model cannot use camera tools. Otherwise answer normally. Do not claim a camera action succeeded."))
                send(url, headers, body.apply { remove("tools") })
            }
            val choice = answer.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val text = message.optString("content").takeUnless { message.isNull("content") }.orEmpty().replace(THINKING, "")
            check(text.isNotBlank() || choice.optString("finish_reason") != "length") { "the model used its whole answer thinking" }
            if (text.isNotBlank()) said = text
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0 || turn == null) return said
            check(tools != null) { "the model requested a tool after tools were disabled" }
            messages.put(message)
            var photo: ByteArray? = null
            for (call in calls.objects()) {
                val fn = call.getJSONObject("function")
                val input = runCatching { JSONObject(fn.optString("arguments")) }.getOrDefault(JSONObject())
                val out = runTool(fn.getString("name"), input, turn)
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", out.text))
                if (fn.getString("name") == PHOTO_TOOL && !out.isError) photo = turn.photo
            }
            photo?.let {
                messages.put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "The current view from the glasses."))
                    .put(openAiImage(it))))
            }
        }
        return said
    }

    /**
     * The Responses API: OpenAI's and Grok's way to search the web, and the only way to the ChatGPT plan. Nothing is
     * stored on the server, so each round carries the whole exchange, the model's own output items included.
     */
    private suspend fun responses(
        c: AssistantConfig, model: String, system: String, past: List<Exchange>, question: String, image: ByteArray?,
        tokens: Int, search: Boolean, turn: Turn?,
    ): String {
        val input = JSONArray()
        for (e in past) {
            input.put(JSONObject().put("role", "user").put("content", e.question))
            input.put(JSONObject().put("role", "assistant").put("content", e.told()))
        }
        input.put(JSONObject().put("role", "user").put("content", inputContent(question, image)))
        val functions = turn?.let { specs(c, it) }.orEmpty().map { (name, help, schema) ->
            JSONObject().put("type", "function").put("name", name).put("description", help)
                .put("parameters", JSONObject(schema.toString()).apply { remove("\$schema") })
        }
        // The plan takes functions grouped in a namespace; should it refuse that, they go as a plain list.
        val plan = c.provider == Provider.CHATGPT
        fun tools() = JSONArray().apply {
            if (search) put(JSONObject().put("type", "web_search"))
            if (functions.isEmpty()) return@apply
            if (plan && PLAN_FLAT !in refused) {
                put(JSONObject().put("type", "namespace").put("name", NAMESPACE).put("description", "Visor's tools on the glasses and the phone.")
                    .put("tools", JSONArray(functions)))
            } else functions.forEach(::put)
        }
        fun body() = JSONObject().put("model", model).put("instructions", system).put("input", input).put("store", false)
            .apply { tools().takeIf { it.length() > 0 }?.let { put("tools", it) } }
        var said = ""
        repeat(MAX_TOOL_ROUNDS) {
            val answer = try {
                respond(c, body(), tokens)
            } catch (error: IllegalStateException) {
                if (!plan || PLAN_FLAT in refused || functions.isEmpty() || !NAMESPACE_REFUSED.containsMatchIn(error.message.orEmpty())) throw error
                refused += PLAN_FLAT
                respond(c, body(), tokens)
            }
            val items = answer.optJSONArray("output")?.objects().orEmpty()
            val text = items.filter { it.optString("type") == "message" }
                .flatMap { it.optJSONArray("content")?.objects().orEmpty() }
                .filter { it.optString("type") == "output_text" }
                .joinToString("") { it.optString("text") }.replace(THINKING, "")
            if (text.isNotBlank()) said = text
            val calls = items.filter { it.optString("type") == "function_call" }
            check(said.isNotBlank() || calls.isNotEmpty() || answer.optString("status") != "incomplete") { "the model used its whole answer thinking" }
            if (calls.isEmpty() || turn == null) return said
            items.forEach(input::put)
            var photo: ByteArray? = null
            for (call in calls) {
                // A function from the namespace may come back named with it.
                val name = call.getString("name").removePrefix("$NAMESPACE.").removePrefix("${NAMESPACE}__")
                val args = runCatching { JSONObject(call.optString("arguments")) }.getOrDefault(JSONObject())
                val out = runTool(name, args, turn)
                input.put(JSONObject().put("type", "function_call_output").put("call_id", call.getString("call_id")).put("output", out.text))
                if (name == PHOTO_TOOL && !out.isError) photo = turn.photo
            }
            photo?.let { input.put(JSONObject().put("role", "user").put("content", inputContent("The current view from the glasses.", it))) }
        }
        return said
    }

    /**
     * One Responses API call, with a key or paid by the ChatGPT plan. A short think keeps a spoken answer quick, and
     * its encrypted reasoning comes back for the next round; a model that does not reason refuses both, and is then
     * asked without them.
     */
    private suspend fun respond(c: AssistantConfig, body: JSONObject, tokens: Int): JSONObject {
        val plan = c.provider == Provider.CHATGPT
        val url = c.api.trimEnd('/') + "/responses"
        val key = (if (plan) "plan " else "$url ") + body.optString("model")
        // The plan takes no length limit; it sets its own.
        if (!plan) body.put("max_output_tokens", tokens + THINKING_TOKENS)
        val call: suspend (JSONObject) -> JSONObject = if (plan) chatGpt::respond else { b -> post(url, bearerHeaders(c.apiKey), b) }
        if (key in refused) return call(body)
        return try {
            call(reasoned(JSONObject(body.toString())))
        } catch (error: IllegalStateException) {
            if (!REASONING_REFUSED.containsMatchIn(error.message.orEmpty())) throw error
            refused += key
            call(body)
        }
    }

    private fun reasoned(body: JSONObject) = body
        .put("reasoning", JSONObject().put("effort", "low"))
        .put("include", JSONArray().put("reasoning.encrypted_content"))

    /** Responses API content: the text, and the glasses' photo when there is one. */
    private fun inputContent(text: String, image: ByteArray?): JSONArray = JSONArray()
        .put(JSONObject().put("type", "input_text").put("text", text))
        .apply {
            if (image != null) put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP)))
        }

    /**
     * Gemini's own API, the one with Google Search. Search and Visor's tools go together from Gemini 3, the model's
     * turns going back exactly as they came, with their thought signatures; an older model refuses the pair, and
     * answer() then asks without search.
     */
    private suspend fun gemini(
        c: AssistantConfig, model: String, system: String, past: List<Exchange>, question: String, image: ByteArray?,
        tokens: Int, turn: Turn?,
    ): String {
        val id = model.removePrefix("models/")
        val url = "$GEMINI_API/models/$id:generateContent"
        val headers = mapOf("x-goog-api-key" to c.apiKey)
        val contents = JSONArray()
        for (e in past) {
            contents.put(geminiTurn("user", JSONArray().put(JSONObject().put("text", e.question))))
            contents.put(geminiTurn("model", JSONArray().put(JSONObject().put("text", e.told()))))
        }
        val first = JSONArray()
        if (image != null) first.put(geminiImage(image))
        contents.put(geminiTurn("user", first.put(JSONObject().put("text", question))))
        val functions = JSONArray()
        turn?.let { specs(c, it) }.orEmpty().forEach { (name, help, schema) ->
            functions.put(JSONObject().put("name", name).put("description", help).apply { geminiSchema(schema)?.let { put("parameters", it) } })
        }
        val tools = JSONArray().put(JSONObject().put("googleSearch", JSONObject()))
        if (functions.length() > 0) tools.put(JSONObject().put("functionDeclarations", functions))
        // Gemini 2 thinks as it sees fit; from Gemini 3 a level keeps a spoken answer quick.
        val thinkingKey = "gemini $id"
        val levels = !id.startsWith("gemini-2") && thinkingKey !in refused
        var said = ""
        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("tools", tools)
                .put("generationConfig", JSONObject().put("maxOutputTokens", tokens + THINKING_TOKENS))
            if (functions.length() > 0) {
                body.put("toolConfig", JSONObject().put("includeServerSideToolInvocations", true)
                    .put("functionCallingConfig", JSONObject().put("mode", "VALIDATED")))
            }
            val answer = if (!levels || thinkingKey in refused) post(url, headers, body) else try {
                post(url, headers, JSONObject(body.toString()).apply {
                    getJSONObject("generationConfig").put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
                })
            } catch (error: IllegalStateException) {
                if (!REASONING_REFUSED.containsMatchIn(error.message.orEmpty())) throw error
                refused += thinkingKey
                post(url, headers, body)
            }
            val candidate = answer.optJSONArray("candidates")?.optJSONObject(0)
                ?: error("the model gave no answer" + (answer.optJSONObject("promptFeedback")?.optString("blockReason")?.let { " ($it)" } ?: ""))
            val content = candidate.optJSONObject("content") ?: JSONObject().put("role", "model").put("parts", JSONArray())
            val parts = content.optJSONArray("parts")?.objects().orEmpty()
            val text = parts.filter { it.has("text") && !it.optBoolean("thought") }.joinToString("") { it.getString("text") }
            if (text.isNotBlank()) said = text
            val calls = parts.mapNotNull { it.optJSONObject("functionCall") }
            check(said.isNotBlank() || calls.isNotEmpty() || candidate.optString("finishReason") != "MAX_TOKENS") { "the model used its whole answer thinking" }
            if (calls.isEmpty() || turn == null) return said
            contents.put(content)
            val results = JSONArray()
            var photo: ByteArray? = null
            for (call in calls) {
                val name = call.getString("name")
                val out = runTool(name, call.optJSONObject("args") ?: JSONObject(), turn)
                val response = JSONObject().put("name", name).put("response", JSONObject().put(if (out.isError) "error" else "result", out.text))
                call.optString("id").takeIf { it.isNotEmpty() }?.let { response.put("id", it) }
                results.put(JSONObject().put("functionResponse", response))
                if (name == PHOTO_TOOL && !out.isError) photo = turn.photo
            }
            photo?.let { results.put(geminiImage(it)) }
            contents.put(geminiTurn("user", results))
        }
        return said
    }

    private fun geminiTurn(role: String, parts: JSONArray) = JSONObject().put("role", role).put("parts", parts)

    private fun geminiImage(image: ByteArray) = JSONObject().put("inlineData",
        JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(image, Base64.NO_WRAP)))

    /** What a server refused (a model's reasoning setting, the plan's namespaces), left out until Visor restarts. */
    private val refused = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * A reasoning model otherwise spends the whole answer thinking (LM Studio, llama.cpp), or refuses tools on this API
     * (OpenAI's GPT-6), so reasoning_effort goes out as none; a model without reasoning refuses the field. OpenAI's newer
     * models, and servers relaying them (Azure, a proxy), want max_completion_tokens. The first answer finds out which,
     * and that is kept for the address and model.
     */
    private suspend fun send(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val key = url + " " + body.optString("model")
        val known = store.fields(key)
        // Every OpenAI model takes max_completion_tokens.
        var f = known ?: Fields(reasoningOff = true, completionTokens = "api.openai.com" in url)
        while (true) {
            try {
                val answer = post(url, headers, shaped(body, f))
                // A model that cannot stop thinking (Gemini's Pro models) spends a spoken answer's tokens on it.
                if (known == null && !f.reasoningOff && !f.thinks && thoughtOnly(answer)) {
                    f = f.copy(thinks = true)
                    continue
                }
                return answer.also { if (f != known) store.keep(key, f) }
            } catch (error: IllegalStateException) {
                val why = error.message.orEmpty()
                f = when {
                    // Not every server names the field: Google answers "Request contains an invalid argument".
                    known == null && f.reasoningOff && ("reasoning" in why || "answered 400:" in why) -> f.copy(reasoningOff = false)
                    known == null && !f.completionTokens && "max_completion_tokens" in why -> f.copy(completionTokens = true)
                    else -> throw error
                }
            }
        }
    }

    private fun thoughtOnly(answer: JSONObject): Boolean {
        val choice = answer.optJSONArray("choices")?.optJSONObject(0) ?: return false
        val message = choice.optJSONObject("message") ?: return false
        val text = message.optString("content").takeUnless { message.isNull("content") }.orEmpty().replace(THINKING, "")
        return choice.optString("finish_reason") == "length" && text.isBlank() && message.optJSONArray("tool_calls") == null
    }

    private fun shaped(body: JSONObject, f: Fields) = JSONObject(body.toString()).apply {
        if (f.reasoningOff) put("reasoning_effort", "none")
        if (f.thinks) put("max_tokens", getInt("max_tokens") + THINKING_TOKENS)
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

    private fun claudeImage(image: ByteArray): JSONObject = JSONObject().put("type", "image").put("source",
        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", Base64.encodeToString(image, Base64.NO_WRAP)),
    )

    private fun openAiImage(image: ByteArray): JSONObject = JSONObject().put("type", "image_url").put("image_url",
        JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP)),
    )

    private companion object {
        val CAMERA_WORD_SPACES = Regex("[\\s\\p{Z}]+")
        val CAMERA_WAKE_PREFIX = Regex("^hey meta(?:, ?| )")

        fun cameraKeyword(question: String, locale: Locale): CameraAction? {
            if (locale.language != "vi" && locale.language != "en") return null
            val words = Normalizer.normalize(question, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                .trim().trimEnd('.', '!').trim().replace(CAMERA_WORD_SPACES, " ").replace(CAMERA_WAKE_PREFIX, "")
            return when (words) {
                "chụp", "chụp ảnh", "chụp hình", "take photo", "take a photo", "take picture", "take a picture" -> CameraAction.TAKE_PHOTO
                "quay video", "quay phim", "bắt đầu quay video", "bắt đầu quay phim", "record video", "record a video",
                "start recording", "start recording video" -> CameraAction.RECORD_VIDEO
                "quay hyperlapse", "bắt đầu quay hyperlapse", "record hyperlapse", "start hyperlapse",
                "start recording hyperlapse" -> CameraAction.RECORD_HYPERLAPSE
                "dừng quay", "dừng quay video", "dừng quay phim", "dừng quay hyperlapse", "stop recording",
                "stop recording video", "stop recording hyperlapse", "stop hyperlapse" -> CameraAction.STOP_RECORDING
                else -> null
            }
        }

        /** Thinking a server left inline in the answer. */
        val THINKING = Regex("(?s)<think>.*?</think>")
        /** Visor's note on past answers, should the model copy it into its own. */
        val VISOR_NOTE = Regex("""\s*\[Visor:[^\]]*]""")
        val TOOLS_REFUSED = Regex("tool|function", RegexOption.IGNORE_CASE)
        // A request the server turned down as written, not a key, a limit or an outage.
        val SEARCH_REFUSED = Regex("^the model answered (400|404|422):")
        val REASONING_REFUSED = Regex("reasoning|encrypted|include|thinking", RegexOption.IGNORE_CASE)
        val NAMESPACE_REFUSED = Regex("namespace", RegexOption.IGNORE_CASE)
        const val NAMESPACE = "visor"
        const val PLAN_FLAT = "plan flat tools"
        const val GEMINI_API = "https://generativelanguage.googleapis.com/v1beta"

        /** The search tool a server runs inside a Chat Completions request. */
        fun chatSearch(provider: Provider): JSONObject? = when (provider.search) {
            WebSearch.OPENROUTER -> JSONObject().put("type", "openrouter:web_search")
            WebSearch.GROQ -> JSONObject().put("type", "browser_search")
            else -> null
        }

        // A searched answer's citations: "([site](url))", "[1](url)", "[[1]](url)", then any link or address left.
        private val CITED = Regex("""\s*\(\s*\[[^\]]*]\([^)]*\)\s*\)|\s*\[\[?\d+]?]\([^)]*\)""")
        private val LINK = Regex("""\[([^\]]+)]\(https?://[^)]*\)""")
        private val ADDRESS = Regex("""\s*\(?https?://[^\s)]+\)?""")

        /** A searched answer as words to say: the sources it cites go, the words stay. */
        fun spoken(text: String): String = text.replace(CITED, "").replace(LINK, "$1").replace(ADDRESS, "")
            .replace(Regex("[ \\t]{2,}"), " ").replace(Regex(" +([.,;:!?])"), "$1")

        private val GEMINI_KEYS = setOf(
            "type", "format", "description", "nullable", "enum", "properties", "required", "items", "anyOf",
            "minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength", "pattern",
        )

        /**
         * A tool's JSON Schema in the OpenAPI subset Gemini reads: unknown keywords go, a ["x", "null"] type becomes
         * nullable x, a const an enum. Null for a tool without inputs, which Gemini wants with no parameters at all.
         */
        fun geminiSchema(schema: JSONObject, top: Boolean = true): JSONObject? {
            val out = JSONObject()
            val type = schema.opt("type")
            if (type is JSONArray) {
                val kinds = (0 until type.length()).map { type.optString(it) }
                kinds.firstOrNull { it != "null" }?.let { out.put("type", it) }
                if ("null" in kinds) out.put("nullable", true)
            } else if (type is String) out.put("type", type)
            if (schema.has("const")) out.put("enum", JSONArray().put(schema.get("const").toString()))
            for (key in schema.keys()) {
                if (key !in GEMINI_KEYS || key == "type") continue
                val value = schema.get(key)
                when (key) {
                    "properties" -> {
                        val props = JSONObject()
                        (value as? JSONObject)?.let { p -> p.keys().forEach { name -> p.optJSONObject(name)?.let { geminiSchema(it, false) }?.let { props.put(name, it) } } }
                        if (props.length() > 0) out.put("properties", props)
                    }
                    "items" -> (value as? JSONObject)?.let { geminiSchema(it, false) }?.let { out.put("items", it) }
                    "anyOf" -> (value as? JSONArray)?.let { a -> JSONArray().apply { a.objects().forEach { geminiSchema(it, false)?.let(::put) } } }
                        ?.takeIf { it.length() > 0 }?.let { out.put("anyOf", it) }
                    // Gemini's enums are strings.
                    "enum" -> (value as? JSONArray)?.let { a -> out.put("enum", JSONArray().apply { (0 until a.length()).forEach { put(a.get(it).toString()) } }) }
                    else -> out.put(key, value)
                }
            }
            val props = out.optJSONObject("properties")
            out.optJSONArray("required")?.let { r ->
                val kept = JSONArray().apply { (0 until r.length()).map { r.optString(it) }.filter { props?.has(it) == true }.forEach(::put) }
                if (kept.length() > 0) out.put("required", kept) else out.remove("required")
            }
            if (top && props == null) return null
            if (out.optString("type") == "object" && props == null) out.remove("required")
            return out
        }
        const val MAX_EXCHANGES = 12
        // A tool often looks something up, acts, then answers: room for a few of those.
        const val MAX_TOOL_ROUNDS = 6
        const val MAX_TOOL_HELP = 1_024
        const val GLASSES_TOKENS = 400
        const val THINKING_TOKENS = 4_000
        const val PHONE_TOKENS = 900
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
        const val END_TOOL = "end_conversation"
        const val PHOTO_TOOL = "look_at_view"
        const val NO_PHOTO = "This model cannot see what the wearer sees. If the question needs it, say so briefly."
        const val PHOTO_HELP = "Look at what the wearer sees through the glasses. Use it when their question " +
            "needs the current view, such as identifying something, reading text or describing their surroundings. " +
            "Do not use it for general knowledge or a request just to take a photo. The result includes the image; answer from it. " +
            "For a photo to keep, use take_photo instead. At most one camera request per question."
        const val END_HELP = "After each answer the glasses keep listening for another question. Call this when the wearer is " +
            "done: they say no, that's all, thanks, stop or goodbye, or they turn down what you offered. The glasses then " +
            "stop listening after this answer. Still answer, in a few words."
        val NO_INPUT: JSONObject get() = JSONObject().put("type", "object").put("properties", JSONObject())
        const val END_PROMPT = " After each answer the glasses keep listening. When the wearer thanks you, says goodbye, " +
            "says they need nothing more or turns down what you offered, call end_conversation and answer in a few words."

        fun schema(field: String, help: String): JSONObject = JSONObject().put("type", "object")
            .put("properties", JSONObject().put(field, JSONObject().put("type", "string").put("description", help)))
            .put("required", JSONArray().put(field))

        fun function(name: String, help: String, parameters: JSONObject): JSONObject = JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", help).put("parameters", parameters))
    }
}

// A tool takes the glasses' photo by a placeholder; McpHub puts the photo in its place.
private val PHOTO_PROMPT = " When a glasses photo is available, hand it to a tool by writing \"$PHOTO_BASE64\" as " +
    "that argument's value for the JPEG as base64, or \"$PHOTO_DATA_URL\" for it as a data URL; Visor puts the photo there."
