package com.macedonga.visor.core.assistant

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Why a cloud speech service gave no audio, in its own words where it gave any. */
class SpeechError(message: String) : Exception(message)

// The path is not on this service; the other request shape may be.
private class NotHere : Exception()

// "OpenAI compatible" speech has two shapes: POST audio/speech answers with the audio; some services (MiMo)
// only have chat completions with an audio object, answered base64 in the message.
private enum class SpeechShape { AUDIO_SPEECH, CHAT_AUDIO }

private val shapes = ConcurrentHashMap<String, SpeechShape>()

/** [text] spoken by the cloud speech service. The first call to an address finds out which shape it answers. */
suspend fun cloudSpeech(config: AssistantConfig, text: String): SpeechPcm = withContext(Dispatchers.IO) {
    val base = config.ttsBaseUrl.trim().trimEnd('/')
    if (base.isEmpty()) throw SpeechError("no speech service is set")
    if (config.ttsModel.isBlank()) throw SpeechError("no speech model is chosen")
    val known = shapes[base]
    for (shape in known?.let(::listOf) ?: SpeechShape.entries) {
        try {
            val wav = request(config, base, text, shape)
            shapes[base] = shape
            return@withContext parseWav(wav)
        } catch (_: NotHere) {
        }
    }
    throw SpeechError(if (known != null) "the service answered 404" else "the service has neither audio/speech nor audio in chat completions")
}

/** Says [line] in the cloud voice on this phone's speaker: no model and no glasses. */
suspend fun previewSpeech(config: AssistantConfig, line: String) = playAloud(cloudSpeech(config, line))

// WAV, not raw PCM: its header carries the rate, whatever the service samples at.
private fun body(config: AssistantConfig, text: String, shape: SpeechShape): JSONObject = when (shape) {
    SpeechShape.AUDIO_SPEECH -> JSONObject().put("model", config.ttsModel).put("input", text)
        .put("voice", config.ttsVoice.ifBlank { "alloy" }).put("response_format", "wav")
    // The text to say goes in the assistant's message; the user's would be a style instruction.
    SpeechShape.CHAT_AUDIO -> JSONObject().put("model", config.ttsModel)
        .put("messages", JSONArray().put(JSONObject().put("role", "assistant").put("content", text)))
        .put("audio", JSONObject().put("format", "wav").apply { if (config.ttsVoice.isNotBlank()) put("voice", config.ttsVoice) })
}

/** Cancelled with its turn: a blocking read ignores cancellation, so the connection is closed under it. */
private suspend fun request(config: AssistantConfig, base: String, text: String, shape: SpeechShape): ByteArray =
    suspendCancellableCoroutine { call ->
        val chat = shape == SpeechShape.CHAT_AUDIO
        val http = URL(if (chat) "$base/chat/completions" else "$base/audio/speech").openConnection() as HttpURLConnection
        call.invokeOnCancellation { thread { http.disconnect() } }
        try {
            http.requestMethod = "POST"
            http.doOutput = true
            http.connectTimeout = 8_000
            http.readTimeout = 20_000
            http.setRequestProperty("Content-Type", "application/json")
            if (config.ttsApiKey.isNotBlank()) http.setRequestProperty("Authorization", "Bearer ${config.ttsApiKey.trim()}")
            http.outputStream.use { it.write(body(config, text, shape).toString().toByteArray()) }
            val code = http.responseCode
            if (code !in 200..299) {
                val words = http.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
                val message = runCatching { JSONObject(words).getJSONObject("error").getString("message") }.getOrNull()
                // A 404 with the API's own error is an answer (an unknown model); a bare one is a missing path.
                if (code == 404 && message == null) throw NotHere()
                throw SpeechError(message ?: words.take(200).ifBlank { "HTTP $code" })
            }
            val answer = http.inputStream.use { it.readBytes() }
            call.resume(
                if (!chat) answer
                else {
                    val data = runCatching {
                        JSONObject(answer.decodeToString()).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getJSONObject("audio").getString("data")
                    }.getOrElse { throw SpeechError("the service sent no audio in its answer") }
                    Base64.decode(data, Base64.DEFAULT)
                },
            )
        } catch (error: Exception) {
            call.resumeWithException(error)
        } finally {
            http.disconnect()
        }
    }
