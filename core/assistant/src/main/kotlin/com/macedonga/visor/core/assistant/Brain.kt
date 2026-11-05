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

/** Where an answer comes from. */
enum class Provider { CLAUDE, OPENAI_COMPATIBLE }

/** The Claude model asked when none is set. */
const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-5-5"

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
) {
    val remembered = ArrayList<String>()
    val actions = ArrayList<ToolAction>()
    var ends = false
    var cameraRequested = false
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

/** The model behind the assistant. The conversation comes with each question, from the diary; tools from [hub] and [phone]. */
class Brain(private val store: AssistantConfigStore, private val diary: Diary, private val hub: McpHub, phone: Phone) {
    private val phoneTools = PhoneTools(phone)

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
        val turn = Turn(box, image, glasses, canEnd, capturePhoto, cameraAction)
        val system = (if (audience == Audience.PHONE) PHONE_PROMPT else SYSTEM_PROMPT) +
            " It is ${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))}." +
            (if (audience == Audience.GLASSES) " The glasses are set to ${locale.getDisplayLanguage(Locale.ENGLISH)}." else "") +
            memoryPrompt() +
            phoneTools.prompt +
            (if (canEnd) END_PROMPT else "") +
            turn.box.prompt +
            (if ((image != null || capturePhoto != null) && turn.box.tools.isNotEmpty()) PHOTO_PROMPT else "") +
            (if (note.isNotBlank()) " $note" else "") +
            if (c.instructions.isNotBlank()) " " + c.instructions else ""
        val recent = past.takeLast(MAX_EXCHANGES)
        val tokens = if (audience == Audience.PHONE) PHONE_TOKENS else GLASSES_TOKENS
        val text = when (c.provider) {
            Provider.CLAUDE -> claude(c, c.model.ifBlank { DEFAULT_CLAUDE_MODEL }, system, recent, question, image, tokens, c.webSearch, turn)
            Provider.OPENAI_COMPATIBLE -> openAi(c, c.model, system, recent, question, image, tokens, turn)
        }.replace(VISOR_NOTE, "").trim()
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

    private suspend fun write(c: AssistantConfig, model: String, system: String, text: String, tokens: Int): String = when (c.provider) {
        Provider.CLAUDE -> claude(c, model.ifBlank { DEFAULT_CLAUDE_MODEL }, system, emptyList(), text, null, tokens, false, null)
        Provider.OPENAI_COMPATIBLE -> openAi(c, model, system, emptyList(), text, null, tokens, null)
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
        phoneTools.run(name, input)?.let { (result, action) ->
            action?.let(turn.actions::add)
            return result
        }
        val glasses = turn.glasses
        if (glasses != null) glasses.tools.find { it.short == name }?.let { return glasses.call(it.full, input) }
        val tool = turn.box.find(name) ?: return ToolResult("unknown tool", true)
        val (result, action) = hub.call(tool, input, turn.photo)
        action?.let(turn.actions::add)
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
        turn.box.tools.forEach { add(Triple(it.name, it.help(), it.tool.schema)) }
        turn.glasses?.tools?.forEach { add(Triple(it.short, it.help, it.schema)) }
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
        tokens: Int, turn: Turn?,
    ): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system)).putPast(past)
        val user: Any = if (image == null) question else JSONArray()
            .put(JSONObject().put("type", "text").put("text", question))
            .put(openAiImage(image))
        messages.put(JSONObject().put("role", "user").put("content", user))
        val headers = if (c.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${c.apiKey}")
        val url = c.baseUrl.trimEnd('/') + "/chat/completions"
        var tools: JSONArray? = JSONArray().apply {
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
                if (tools == null || turn?.box?.tools.orEmpty().isNotEmpty() || !TOOLS_REFUSED.containsMatchIn(error.message.orEmpty())) throw error
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
