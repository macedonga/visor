package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.connection.objects
import java.net.URLEncoder
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

/** Playout and diary sink for a [LiveModelSession]. */
internal interface LivePlayout {
    fun recordTurn(
        turnId: String,
        startedAt: Long,
        question: String,
        answer: String,
        photo: ByteArray?,
        remembered: List<String>,
        actions: List<ToolAction>,
    )

    fun pushAudio(turnId: String, samples: ShortArray, rate: Int, question: String, answer: String)

    fun finishAudio(turnId: String)

    suspend fun awaitAudioIdle()

    val isSpeaking: Boolean

    fun interrupt()

    fun note(text: String)
}

internal enum class LiveProtocol { GEMINI, OPENAI_GPT_LIVE, OPENAI_REALTIME }

internal fun liveProtocolFor(provider: Provider, model: String): LiveProtocol = when {
    provider == Provider.GEMINI -> LiveProtocol.GEMINI
    model.trim().lowercase().startsWith("gpt-live") -> LiveProtocol.OPENAI_GPT_LIVE
    else -> LiveProtocol.OPENAI_REALTIME
}

internal fun toWsBase(httpBase: String): String {
    val s = httpBase.trim().trimEnd('/')
    return when {
        s.startsWith("wss://", ignoreCase = true) || s.startsWith("ws://", ignoreCase = true) -> s
        s.startsWith("https://", ignoreCase = true) -> "wss://" + s.substring(8)
        s.startsWith("http://", ignoreCase = true) -> "ws://" + s.substring(7)
        else -> "wss://$s"
    }
}

/**
 * A bidirectional WebSocket audio-to-audio session for Live AI across Gemini Live (`BidiGenerateContent`),
 * OpenAI GPT-Live (`/v1/live/sessions`), and OpenAI / Grok / compatible Realtime (`/v1/realtime`).
 */
internal class LiveModelSession(
    parentScope: CoroutineScope,
    private val config: AssistantConfig,
    private val locale: Locale,
    private val toolset: LiveToolset,
    private val playout: LivePlayout,
    private val frameProvider: suspend () -> ByteArray?,
    private val onSessionFailed: (String) -> Unit = {},
    private val wsUrlOverride: String? = null,
    httpClient: OkHttpClient = defaultWsClient,
) {
    private val provider = checkNotNull(config.liveProvider) { "no live provider set" }
    private val model = config.effectiveLiveModel
    private val voice = config.effectiveLiveVoice
    private val key = config.effectiveLiveKey
    private val api = config.effectiveLiveApi
    private val protocol = liveProtocolFor(provider, model)
    private val vision = config.vision && provider != Provider.GROK
    private val webSearch = config.webSearch && (provider == Provider.GEMINI || provider == Provider.OPENAI || provider == Provider.GROK)

    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + parentScope.coroutineContext)
    private val client = httpClient
    private val inputRate = if (protocol == LiveProtocol.GEMINI) 16_000 else 24_000
    private val micResampler = PcmResampler(16_000, inputRate)

    // Coalesce microphone audio to ~40 ms frames and hold early speech while the handshake finishes.
    private val micLock = Any()
    private val minSendBytes = inputRate / 25 * 2
    private var micStaging = ByteArray(0)
    private val earlyAudio = ArrayDeque<ByteArray>()
    private var earlyBytes = 0

    @Volatile
    private var ws: WebSocket? = null
    private var readyDeferred = CompletableDeferred<Unit>()
    @Volatile
    private var ready = false
    private val closed = AtomicBoolean(false)
    private var videoJob: Job? = null
    private var idleTurnJob: Job? = null
    @Volatile
    private var lastAudioDeltaMs = 0L
    private val activeToolCalls = AtomicInteger(0)

    @Volatile
    private var resumptionHandle: String? = null
    @Volatile
    private var latestFrame: ByteArray? = null
    private var legacyRealtimeRetried = false
    private var carryByte = -1
    @Volatile
    private var lastVisionSentMs = 0L

    private class TurnState(
        val id: String = UUID.randomUUID().toString(),
        val startedAt: Long = System.currentTimeMillis(),
        var photo: ByteArray? = null,
    ) {
        val question = StringBuilder()
        val answer = StringBuilder()
        val remembered = ArrayList<String>()
        val actions = ArrayList<ToolAction>()
        var lastItemId = ""
        var itemStart = 0
        var audioSent = false
        var done = false
    }

    private data class CallSpec(val id: String, val name: String, val arguments: String)

    private val stateLock = Any()
    private var currentTurn: TurnState? = null
    private val pendingCalls = LinkedHashMap<String, CallSpec>()
    private val handledCallIds: MutableSet<String> = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** Opens the WebSocket and waits for the session handshake (`setupComplete`, `session.started`, or `session.updated`). */
    suspend fun start() {
        connect(resuming = false)
        withTimeout(HANDSHAKE_TIMEOUT_MS) { readyDeferred.await() }
        if (vision) {
            videoJob = scope.launch { refreshFrames() }
        }
    }

    /** 16 kHz mono PCM from the glasses' s0 Opus microphone stream. */
    fun hearPcm16k(samples: ShortArray) {
        if (closed.get() || samples.isEmpty()) return
        val bytes = synchronized(micLock) {
            val resampled = micResampler.push(samples)
            if (resampled.isEmpty()) return
            micStaging += pcmToBytes(resampled)
            if (micStaging.size < minSendBytes) return
            val chunk = micStaging
            micStaging = ByteArray(0)
            if (!ready) {
                while (earlyBytes + chunk.size > MAX_EARLY_BYTES && earlyAudio.isNotEmpty()) {
                    earlyBytes -= earlyAudio.removeFirst().size
                }
                earlyAudio.addLast(chunk)
                earlyBytes += chunk.size
                return
            }
            chunk
        }
        sendAudioChunk(bytes)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        videoJob?.cancel()
        idleTurnJob?.cancel()
        val socket = ws
        ws = null
        if (socket != null) {
            if (protocol == LiveProtocol.OPENAI_GPT_LIVE) {
                runCatching { socket.send(JSONObject().put("type", "session.close").toString()) }
            }
            runCatching { socket.close(1000, null) }
        }
        playout.interrupt()
        scope.cancel()
    }

    private fun connect(resuming: Boolean) {
        if (closed.get()) return
        if (resuming) {
            ready = false
            readyDeferred = CompletableDeferred()
        }
        val url = wsUrlOverride ?: when (protocol) {
            LiveProtocol.GEMINI -> GEMINI_WS_URL
            LiveProtocol.OPENAI_GPT_LIVE -> "${toWsBase(api)}/live/sessions"
            LiveProtocol.OPENAI_REALTIME -> "${toWsBase(api)}/realtime?model=" + URLEncoder.encode(model, "UTF-8")
        }
        val req = Request.Builder().url(url).apply {
            when (protocol) {
                LiveProtocol.GEMINI -> if (key.isNotBlank()) header("x-goog-api-key", key)
                LiveProtocol.OPENAI_GPT_LIVE, LiveProtocol.OPENAI_REALTIME -> if (key.isNotBlank()) header("Authorization", "Bearer $key")
            }
        }.build()
        playout.note("Live AI: connecting to ${provider.title.ifEmpty { "server" }} ($model)")
        ws = client.newWebSocket(req, Listener())
    }

    private inner class Listener : WebSocketListener() {
        private var updateSent = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed.get()) return
            when (protocol) {
                LiveProtocol.GEMINI -> sendGeminiSetup(webSocket)
                LiveProtocol.OPENAI_GPT_LIVE -> sendGptLiveStart(webSocket)
                LiveProtocol.OPENAI_REALTIME -> {
                    // OpenAI and Grok send session.created first; if a compatible server stays quiet, send session.update anyway.
                    scope.launch {
                        delay(700)
                        if (!closed.get() && ws === webSocket && !updateSent) {
                            updateSent = true
                            sendRealtimeUpdate(webSocket, legacy = false)
                        }
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(webSocket, text) { updateSent = true }

        // Gemini BidiGenerateContent delivers UTF-8 JSON inside binary WebSocket frames.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleMessage(webSocket, bytes.utf8()) { updateSent = true }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(code, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closed.get() || ws !== webSocket) return
            val detail = reason.ifBlank { "code $code" }
            if (!readyDeferred.isCompleted) {
                readyDeferred.completeExceptionally(IllegalStateException("connection closed ($detail)"))
                return
            }
            if (protocol == LiveProtocol.GEMINI && resumptionHandle != null) {
                playout.note("Live AI: reconnecting Gemini session ($detail)")
                connect(resuming = true)
            } else {
                playout.note("Live AI: live connection closed ($detail)")
                onSessionFailed(detail)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed.get() || ws !== webSocket) return
            val detail = response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName)
            if (!readyDeferred.isCompleted) {
                readyDeferred.completeExceptionally(IllegalStateException(detail, t))
                return
            }
            playout.note("Live AI: live connection failed ($detail)")
            onSessionFailed(detail)
        }
    }

    private fun markReady() {
        if (closed.get()) return
        val pending = synchronized(micLock) {
            ready = true
            val copy = earlyAudio.toList()
            earlyAudio.clear()
            earlyBytes = 0
            copy
        }
        readyDeferred.complete(Unit)
        playout.note("Live AI: $model is listening")
        for (chunk in pending) sendAudioChunk(chunk)
    }

    private fun sendAudioChunk(pcmBytes: ByteArray) {
        val socket = ws ?: return
        if (!ready || closed.get() || pcmBytes.isEmpty()) return
        val b64 = Base64.getEncoder().encodeToString(pcmBytes)
        val msg = when (protocol) {
            LiveProtocol.GEMINI -> JSONObject().put(
                "realtimeInput",
                JSONObject().put("audio", JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64)),
            )
            LiveProtocol.OPENAI_GPT_LIVE -> JSONObject().put("type", "session.input_audio.append").put("audio", b64)
            LiveProtocol.OPENAI_REALTIME -> JSONObject().put("type", "input_audio_buffer.append").put("audio", b64)
        }
        socket.send(msg.toString())
    }

    private suspend fun refreshFrames() {
        while (scope.isActive && !closed.get()) {
            val jpeg = runCatching { frameProvider() }.getOrNull()
            if (jpeg != null) {
                latestFrame = jpeg
                val socket = ws
                if (protocol == LiveProtocol.GEMINI && ready && socket != null) {
                    val b64 = Base64.getEncoder().encodeToString(jpeg)
                    val msg = JSONObject().put(
                        "realtimeInput",
                        JSONObject().put("video", JSONObject().put("mimeType", "image/jpeg").put("data", b64)),
                    )
                    socket.send(msg.toString())
                }
            }
            delay(GEMINI_VIDEO_INTERVAL_MS)
        }
    }

    // --- Handshakes ----------------------------------------------------------

    private fun sendGeminiSetup(socket: WebSocket) {
        val modelId = "models/" + model.removePrefix("models/")
        val genConfig = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
        if (voice.isNotBlank()) {
            genConfig.put(
                "speechConfig",
                JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))),
            )
        }
        // Only thinking variants accept thinkingConfig; plain gemini-3.8-live rejects it.
        if ("thinking" in modelId.lowercase()) {
            genConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        }
        val tools = JSONArray()
        if (webSearch) tools.put(JSONObject().put("googleSearch", JSONObject()))
        if (toolset.geminiDeclarations.length() > 0) {
            tools.put(JSONObject().put("functionDeclarations", toolset.geminiDeclarations))
        }
        val setup = JSONObject()
            .put("model", modelId)
            .put("generationConfig", genConfig)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", toolset.instructions))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", JSONObject().apply { resumptionHandle?.let { put("handle", it) } })
        if (tools.length() > 0) setup.put("tools", tools)
        socket.send(JSONObject().put("setup", setup).toString())
    }

    private fun sendGptLiveStart(socket: WebSocket) {
        val voicePrompt = "You are the voice of Visor in a pair of Meta smart glasses. Speak naturally and concisely in " +
            "${locale.getDisplayLanguage(Locale.ENGLISH)}, matching the language the wearer speaks. No lists, markdown, emoji or URLs. " +
            "Rely on your responses backend whenever tools, memories, web search or the camera view are needed."
        val audio = JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", 24_000))
        if (voice.isNotBlank()) audio.put("output", JSONObject().put("voice", voice))
        val delegationModel = config.model.takeIf { config.provider == Provider.OPENAI && it.isNotBlank() && !isLiveModelId(it) }
            ?: DEFAULT_GPT_LIVE_BACKEND
        val responsesTools = JSONArray()
        if (webSearch) responsesTools.put(JSONObject().put("type", "web_search"))
        for (i in 0 until toolset.openAiTools.length()) responsesTools.put(toolset.openAiTools.get(i))
        val responses = JSONObject()
            .put("model", delegationModel)
            .put("instructions", toolset.instructions)
        if (responsesTools.length() > 0) responses.put("tools", responsesTools).put("tool_choice", "auto")
        val session = JSONObject()
            .put("model", model)
            .put("instructions", voicePrompt)
            .put("audio", audio)
            .put("delegation", JSONObject().put("type", "responses").put("responses", responses))
        socket.send(JSONObject().put("type", "session.start").put("session", session).toString())
    }

    private fun sendRealtimeUpdate(socket: WebSocket, legacy: Boolean) {
        val session = when {
            provider == Provider.GROK -> {
                val tools = JSONArray()
                if (webSearch) tools.put(JSONObject().put("type", "web_search"))
                for (i in 0 until toolset.openAiTools.length()) tools.put(toolset.openAiTools.get(i))
                JSONObject()
                    .put("instructions", toolset.instructions)
                    .put("turn_detection", JSONObject().put("type", "server_vad"))
                    .put(
                        "audio",
                        JSONObject()
                            .put("input", JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", 24_000)))
                            .put("output", JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", 24_000))),
                    )
                    .apply {
                        if (voice.isNotBlank()) put("voice", voice)
                        if (tools.length() > 0) put("tools", tools)
                    }
            }
            legacy -> {
                JSONObject()
                    .put("modalities", JSONArray().put("text").put("audio"))
                    .put("instructions", toolset.instructions)
                    .put("input_audio_format", "pcm16")
                    .put("output_audio_format", "pcm16")
                    .put("turn_detection", JSONObject().put("type", "server_vad"))
                    .apply {
                        if (voice.isNotBlank()) put("voice", voice)
                        if (toolset.openAiTools.length() > 0) put("tools", toolset.openAiTools).put("tool_choice", "auto")
                    }
            }
            else -> {
                val inputAudio = JSONObject()
                    .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24_000))
                    .put("turn_detection", JSONObject().put("type", "semantic_vad"))
                if (provider == Provider.OPENAI) {
                    inputAudio.put("transcription", JSONObject().put("model", "gpt-4o-mini-transcribe"))
                }
                val outputAudio = JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", 24_000))
                if (voice.isNotBlank()) outputAudio.put("voice", voice)
                JSONObject()
                    .put("type", "realtime")
                    .put("output_modalities", JSONArray().put("audio"))
                    .put("instructions", toolset.instructions)
                    .put("audio", JSONObject().put("input", inputAudio).put("output", outputAudio))
                    .apply {
                        if (toolset.openAiTools.length() > 0) put("tools", toolset.openAiTools).put("tool_choice", "auto")
                    }
            }
        }
        socket.send(JSONObject().put("type", "session.update").put("session", session).toString())
    }

    // --- Incoming events -----------------------------------------------------

    private fun handleMessage(socket: WebSocket, raw: String, markUpdateSent: () -> Unit) {
        if (closed.get() || raw.isBlank()) return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (protocol) {
            LiveProtocol.GEMINI -> handleGemini(socket, json)
            LiveProtocol.OPENAI_GPT_LIVE -> handleGptLive(socket, json)
            LiveProtocol.OPENAI_REALTIME -> handleRealtime(socket, json, markUpdateSent)
        }
    }

    private fun handleGemini(socket: WebSocket, json: JSONObject) {
        if (json.has("setupComplete")) {
            markReady()
            return
        }
        json.optJSONObject("error")?.let { err ->
            val msg = err.optString("message").ifEmpty { err.toString() }
            if (!readyDeferred.isCompleted) readyDeferred.completeExceptionally(IllegalStateException(msg))
            else playout.note("Live AI: Gemini error: $msg")
            return
        }
        json.optJSONObject("sessionResumptionUpdate")?.let { upd ->
            upd.optString("newHandle").takeIf { it.isNotEmpty() }?.let { resumptionHandle = it }
        }
        json.optJSONObject("goAway")?.let {
            if (resumptionHandle != null) {
                playout.note("Live AI: server requested reconnect; resuming session")
                runCatching { socket.close(1000, null) }
                connect(resuming = true)
                return
            }
        }
        json.optJSONObject("serverContent")?.let { sc ->
            if (sc.optBoolean("interrupted")) {
                interruptTurn()
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { delta ->
                appendUserTranscript(delta, replace = false)
            }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { delta ->
                appendAssistantTranscript(delta, replace = false)
            }
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.objects()?.forEach { part ->
                val inline = part.optJSONObject("inlineData") ?: return@forEach
                if (inline.optString("mimeType").startsWith("audio/pcm")) {
                    val b64 = inline.optString("data")
                    if (b64.isNotEmpty()) emitPcmBytes(Base64.getDecoder().decode(b64), 24_000)
                }
            }
            if (sc.optBoolean("turnComplete")) {
                finishModelTurn()
            }
        }
        json.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.objects()?.takeIf { it.isNotEmpty() }?.let { calls ->
            activeToolCalls.incrementAndGet()
            scope.launch {
                try {
                    val responses = JSONArray()
                    for (call in calls) {
                        val name = call.optString("name")
                        val id = call.optString("id")
                        val args = call.optJSONObject("args") ?: JSONObject()
                        val outcome = runToolAndRecord(name, args)
                        val res = JSONObject()
                            .put("name", name)
                            .put("response", JSONObject().put(if (outcome.result.isError) "error" else "result", outcome.result.text))
                        if (id.isNotEmpty()) res.put("id", id)
                        responses.put(res)
                    }
                    ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    playout.note("Live AI: tool execution failed: ${e.message}")
                } finally {
                    activeToolCalls.decrementAndGet()
                }
            }
        }
    }

    private fun handleGptLive(socket: WebSocket, json: JSONObject) {
        when (json.optString("type")) {
            "session.started" -> markReady()
            "session.input_transcript.delta" -> {
                val delta = json.optString("delta")
                if (delta.isNotEmpty()) {
                    val wasNew = isFreshUserTurn()
                    if (wasNew && playout.isSpeaking) interruptTurn()
                    appendUserTranscript(delta, replace = false)
                    if (wasNew && vision) sendVisionFrame(socket, "response.item.create")
                }
            }
            "session.output_transcript.delta" -> {
                val delta = json.optString("delta")
                if (delta.isNotEmpty()) appendAssistantTranscript(delta, replace = false)
            }
            "session.output_audio.delta" -> {
                val b64 = json.optString("audio").ifEmpty { json.optString("delta") }
                if (b64.isNotEmpty()) {
                    emitPcmBytes(Base64.getDecoder().decode(b64), 24_000)
                    scheduleGptLiveTurnEnd()
                }
            }
            "response.event" -> {
                val ev = json.optJSONObject("event") ?: return
                when (ev.optString("type")) {
                    "response.output_item.done" -> collectFunctionCall(ev.optJSONObject("item"))
                    "response.done" -> {
                        ev.optJSONObject("response")?.optJSONArray("output")?.objects()?.forEach(::collectFunctionCall)
                        val calls = drainPendingCalls()
                        if (calls.isNotEmpty()) {
                            runOpenAiToolCalls(socket, calls, itemCreateType = "response.item.create")
                        }
                    }
                }
            }
            "error" -> {
                val msg = json.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { json.toString() }
                if (!readyDeferred.isCompleted) readyDeferred.completeExceptionally(IllegalStateException(msg))
                else playout.note("Live AI: $msg")
            }
        }
    }

    private fun handleRealtime(socket: WebSocket, json: JSONObject, markUpdateSent: () -> Unit) {
        when (json.optString("type")) {
            "session.created" -> {
                markUpdateSent()
                sendRealtimeUpdate(socket, legacy = false)
                // Minimal compatible servers may not emit session.updated; mark ready shortly if no error came.
                scope.launch {
                    delay(1_200)
                    if (!closed.get() && ws === socket && !readyDeferred.isCompleted) markReady()
                }
            }
            "session.updated" -> markReady()
            "input_audio_buffer.speech_started" -> {
                val wasSpeaking = playout.isSpeaking
                interruptTurn()
                if (wasSpeaking) socket.send(JSONObject().put("type", "response.cancel").toString())
                startNewUserTurn()
                if (vision) sendVisionFrame(socket, "conversation.item.create")
            }
            "conversation.item.input_audio_transcription.delta" -> {
                val delta = json.optString("delta")
                if (delta.isNotEmpty()) appendUserTranscript(delta, replace = false, itemId = json.optString("item_id"))
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val text = json.optString("transcript").trim()
                if (text.isNotEmpty()) appendUserTranscript(text, replace = true, itemId = json.optString("item_id"))
            }
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" -> {
                val delta = json.optString("delta")
                if (delta.isNotEmpty()) appendAssistantTranscript(delta, replace = false)
            }
            "response.output_audio_transcript.done", "response.audio_transcript.done" -> {
                val text = json.optString("transcript").trim()
                if (text.isNotEmpty()) appendAssistantTranscript(text, replace = true)
            }
            "response.output_audio.delta", "response.audio.delta" -> {
                val b64 = json.optString("delta")
                if (b64.isNotEmpty()) emitPcmBytes(Base64.getDecoder().decode(b64), 24_000)
            }
            "response.output_audio.done", "response.audio.done" -> {
                synchronized(stateLock) { currentTurn?.id }?.let(playout::finishAudio)
            }
            "response.output_item.done" -> collectFunctionCall(json.optJSONObject("item"))
            "response.function_call_arguments.done" -> {
                val callId = json.optString("call_id")
                val name = json.optString("name")
                if (callId.isNotEmpty() && name.isNotEmpty()) {
                    synchronized(stateLock) {
                        if (callId !in handledCallIds) pendingCalls[callId] = CallSpec(callId, name, json.optString("arguments"))
                    }
                }
            }
            "response.done" -> {
                json.optJSONObject("response")?.optJSONArray("output")?.objects()?.forEach(::collectFunctionCall)
                val calls = drainPendingCalls()
                if (calls.isNotEmpty()) {
                    runOpenAiToolCalls(socket, calls, itemCreateType = "conversation.item.create")
                } else {
                    finishModelTurn()
                }
            }
            "error" -> {
                val msg = json.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { json.toString() }
                if (!ready && !legacyRealtimeRetried && provider != Provider.GROK &&
                    ("session.type" in msg || "output_modalities" in msg || "unknown_parameter" in msg.lowercase())
                ) {
                    legacyRealtimeRetried = true
                    sendRealtimeUpdate(socket, legacy = true)
                    return
                }
                if (!readyDeferred.isCompleted) readyDeferred.completeExceptionally(IllegalStateException(msg))
                else if ("cancellation failed" !in msg.lowercase() && "no active response" !in msg.lowercase()) {
                    playout.note("Live AI: $msg")
                }
            }
        }
    }

    // --- Turn, vision & tool helpers -----------------------------------------

    private fun collectFunctionCall(item: JSONObject?) {
        if (item == null || item.optString("type") != "function_call") return
        val callId = item.optString("call_id").ifEmpty { item.optString("id") }
        val name = item.optString("name")
        if (callId.isEmpty() || name.isEmpty()) return
        synchronized(stateLock) {
            if (callId !in handledCallIds) {
                pendingCalls[callId] = CallSpec(callId, name, item.optString("arguments"))
            }
        }
    }

    private fun drainPendingCalls(): List<CallSpec> = synchronized(stateLock) {
        if (pendingCalls.isEmpty()) return emptyList()
        val list = pendingCalls.values.toList()
        pendingCalls.clear()
        list.forEach { handledCallIds += it.id }
        list
    }

    private fun runOpenAiToolCalls(socket: WebSocket, calls: List<CallSpec>, itemCreateType: String) {
        activeToolCalls.incrementAndGet()
        scope.launch {
            try {
                var attachedPhoto: ByteArray? = null
                for (call in calls) {
                    val args = runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject())
                    val outcome = runToolAndRecord(call.name, args)
                    val outItem = JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", call.id)
                        .put("output", outcome.result.text)
                    socket.send(JSONObject().put("type", itemCreateType).put("item", outItem).toString())
                    if (outcome.photo != null) attachedPhoto = outcome.photo
                }
                if (attachedPhoto != null && vision) {
                    sendImageItem(socket, itemCreateType, attachedPhoto)
                }
                playout.awaitAudioIdle()
                socket.send(JSONObject().put("type", "response.create").toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                playout.note("Live AI: tool execution failed: ${e.message}")
            } finally {
                activeToolCalls.decrementAndGet()
            }
        }
    }

    private suspend fun runToolAndRecord(name: String, args: JSONObject): LiveToolOutcome {
        val latest = if (vision) runCatching { frameProvider() }.getOrNull()?.also { latestFrame = it } ?: latestFrame else null
        val outcome = toolset.call(name, args, latest)
        synchronized(stateLock) {
            val turn = ensureTurnLocked()
            if (outcome.photo != null) turn.photo = outcome.photo
            turn.remembered += outcome.remembered
            turn.actions += outcome.actions
            syncTurnLocked(turn)
        }
        return outcome
    }

    private fun sendVisionFrame(socket: WebSocket, itemCreateType: String) {
        val now = System.currentTimeMillis()
        if (now - lastVisionSentMs < MIN_VISION_GAP_MS) return
        lastVisionSentMs = now
        scope.launch {
            val jpeg = runCatching { frameProvider() }.getOrNull()?.also { latestFrame = it } ?: latestFrame ?: return@launch
            synchronized(stateLock) {
                val turn = ensureTurnLocked()
                if (turn.photo == null) turn.photo = jpeg
                syncTurnLocked(turn)
            }
            sendImageItem(socket, itemCreateType, jpeg)
        }
    }

    private fun sendImageItem(socket: WebSocket, itemCreateType: String, jpeg: ByteArray) {
        val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
        val content = JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", "Current view from the wearer's glasses."))
            .put(JSONObject().put("type", "input_image").put("image_url", dataUrl))
        val item = JSONObject().put("type", "message").put("role", "user").put("content", content)
        socket.send(JSONObject().put("type", itemCreateType).put("item", item).toString())
    }

    private fun scheduleGptLiveTurnEnd() {
        lastAudioDeltaMs = System.nanoTime() / 1_000_000
        if (idleTurnJob?.isActive == true) return
        idleTurnJob = scope.launch {
            while (isActive && !closed.get()) {
                delay(150)
                val idleMs = System.nanoTime() / 1_000_000 - lastAudioDeltaMs
                if (idleMs >= GPT_LIVE_AUDIO_IDLE_MS && activeToolCalls.get() == 0) {
                    finishModelTurn()
                    return@launch
                }
            }
        }
    }

    private fun isFreshUserTurn(): Boolean = synchronized(stateLock) {
        val t = currentTurn
        t == null || t.done || t.audioSent
    }

    private fun startNewUserTurn() = synchronized(stateLock) {
        carryByte = -1
        val cur = currentTurn
        if (cur == null || cur.done || cur.audioSent || cur.answer.isNotEmpty() || cur.actions.isNotEmpty()) {
            currentTurn = TurnState(photo = if (vision) latestFrame else null)
        }
    }

    private fun appendUserTranscript(text: String, replace: Boolean, itemId: String = "") = synchronized(stateLock) {
        val cur = currentTurn
        val turn = when {
            cur == null -> TurnState(photo = if (vision) latestFrame else null).also { currentTurn = it }
            // An async completed transcript arriving right after a fast answer fills that turn's question.
            replace && cur.question.isEmpty() -> cur
            cur.done -> TurnState(photo = if (vision) latestFrame else null).also { currentTurn = it }
            else -> cur
        }
        if (itemId.isNotEmpty() && itemId != turn.lastItemId) {
            if (turn.question.isNotEmpty() && !turn.question.endsWith(' ')) turn.question.append(' ')
            turn.lastItemId = itemId
            turn.itemStart = turn.question.length
        }
        if (replace) {
            turn.question.setLength(turn.itemStart)
            turn.question.append(text)
        } else {
            turn.question.append(text)
        }
        syncTurnLocked(turn)
    }

    private fun appendAssistantTranscript(text: String, replace: Boolean) = synchronized(stateLock) {
        val cur = currentTurn
        val turn = if (cur == null || cur.done) {
            TurnState(photo = if (vision) latestFrame else null).also { currentTurn = it }
        } else cur
        if (replace) {
            if (turn.answer.isEmpty()) turn.answer.append(text)
        } else {
            turn.answer.append(text)
        }
        syncTurnLocked(turn)
    }

    private fun emitPcmBytes(raw: ByteArray, rate: Int) {
        if (raw.isEmpty() || closed.get()) return
        val samples: ShortArray
        val turnId: String
        val q: String
        val a: String
        synchronized(stateLock) {
            val combined = if (carryByte >= 0) {
                ByteArray(raw.size + 1).also {
                    it[0] = carryByte.toByte()
                    System.arraycopy(raw, 0, it, 1, raw.size)
                }
            } else raw
            val even = combined.size and 1.inv()
            carryByte = if (combined.size > even) combined[even].toInt() and 0xFF else -1
            if (even == 0) return
            samples = bytesToPcm(combined, even)
            val turn = if (currentTurn == null || currentTurn!!.done) {
                TurnState(photo = if (vision) latestFrame else null).also { currentTurn = it }
            } else currentTurn!!
            turn.audioSent = true
            syncTurnLocked(turn)
            turnId = turn.id
            q = turn.question.toString().trim()
            a = turn.answer.toString().trim()
        }
        playout.pushAudio(turnId, samples, rate, q, a)
    }

    private fun finishModelTurn() {
        val turnId = synchronized(stateLock) {
            carryByte = -1
            val turn = currentTurn ?: return
            turn.done = true
            syncTurnLocked(turn)
            turn.id
        }
        playout.finishAudio(turnId)
    }

    private fun interruptTurn() {
        idleTurnJob?.cancel()
        synchronized(stateLock) {
            carryByte = -1
            val cur = currentTurn
            if (cur != null && (cur.audioSent || cur.answer.isNotEmpty() || cur.actions.isNotEmpty())) {
                cur.done = true
            }
        }
        playout.interrupt()
    }

    private fun ensureTurnLocked(): TurnState {
        val cur = currentTurn
        if (cur != null && !cur.done) return cur
        return TurnState(photo = if (vision) latestFrame else null).also { currentTurn = it }
    }

    private fun syncTurnLocked(turn: TurnState) {
        val q = turn.question.toString().trim()
        val a = turn.answer.toString().trim()
        if (q.isEmpty() && a.isEmpty() && turn.actions.isEmpty() && !turn.audioSent) return
        playout.recordTurn(
            turnId = turn.id,
            startedAt = turn.startedAt,
            question = q.ifEmpty { "…" },
            answer = a,
            photo = turn.photo,
            remembered = turn.remembered.toList(),
            actions = turn.actions.toList(),
        )
    }

    private companion object {
        const val GEMINI_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val DEFAULT_GPT_LIVE_BACKEND = "gpt-6-luna"
        const val HANDSHAKE_TIMEOUT_MS = 12_000L
        const val GEMINI_VIDEO_INTERVAL_MS = 1_000L
        const val GPT_LIVE_AUDIO_IDLE_MS = 650L
        const val MIN_VISION_GAP_MS = 2_500L
        // ~8 seconds of 24 kHz 16-bit mono PCM held while the WebSocket shakes hands.
        const val MAX_EARLY_BYTES = 24_000 * 2 * 8

        val defaultWsClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
