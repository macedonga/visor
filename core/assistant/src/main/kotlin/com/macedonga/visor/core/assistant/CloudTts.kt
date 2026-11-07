package com.macedonga.visor.core.assistant

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Audio a synthesis service returned: 16-bit PCM and the rate it was sampled at. */
class SpeechPcm(val samples: ShortArray, val rate: Int)

/** Why a cloud synthesis could not be had, with the service's own words where it gave any. */
class SpeechError(message: String) : Exception(message)

/** The service answered 404: the path is not there, which is not the same as a refusal. */
private class NotHere : Exception()

/**
 * Two shapes hide behind the words "OpenAI compatible", and a speech service is only useful if it
 * speaks one of them:
 *
 *  - [SpeechShape.OPENAI_AUDIO] is the standard one — `POST {base}/audio/speech` with
 *    `{model, input, voice, response_format}`, answered with the audio itself.
 *  - [SpeechShape.CHAT_AUDIO] is what Xiaomi MiMo does and several others: the plain
 *    `POST {base}/chat/completions`, with the text to be said in a `role: assistant` message and an
 *    `audio` object naming the format and the voice, answered with `choices[0].message.audio.data`
 *    as base64. There is no `/audio/speech` on such a service at all — asking it for one earns a
 *    bare 404 from whatever fronts the API, which is exactly how a misconfigured voice and a
 *    mis-shapen request used to look alike.
 *
 * So the shape is found out rather than configured: try the standard, and if the path is not there,
 * try the other and remember which one worked for this address. That keeps one form to fill in
 * whichever service is used, and costs one extra request only on the first call after a launch.
 *
 * The format asked for is `wav`, never `pcm`: a WAV carries its own sample rate in the header, so
 * the same decoder works whatever the service synthesises at. Raw PCM would mean guessing the rate
 * out of each provider's documentation.
 */
private enum class SpeechShape { OPENAI_AUDIO, CHAT_AUDIO }

private val learnedShapes = ConcurrentHashMap<String, SpeechShape>()

suspend fun cloudSpeech(config: AssistantConfig, text: String): SpeechPcm = withContext(Dispatchers.IO) {
    val base = config.ttsBaseUrl.trimEnd('/')
    check(base.isNotBlank()) { "no speech service is set" }
    check(config.ttsModel.isNotBlank()) { "no speech model is chosen" }
    val known = learnedShapes[base]
    if (known != null) return@withContext request(config, base, text, known)
    for (shape in SpeechShape.entries) {
        try {
            val pcm = request(config, base, text, shape)
            learnedShapes[base] = shape
            return@withContext pcm
        } catch (e: NotHere) {
            // The path is not on this service; try the other shape before calling it a failure.
        }
    }
    throw SpeechError("this service offers neither audio/speech nor audio in chat completions")
}

private fun request(config: AssistantConfig, base: String, text: String, shape: SpeechShape): SpeechPcm {
    val body = when (shape) {
        SpeechShape.OPENAI_AUDIO -> org.json.JSONObject()
            .put("model", config.ttsModel)
            .put("input", text)
            .put("voice", config.ttsVoice.ifBlank { "alloy" })
            .put("response_format", "wav")
        SpeechShape.CHAT_AUDIO -> org.json.JSONObject()
            .put("model", config.ttsModel)
            // The text to be said goes in the assistant's message, never the user's: this is what the
            // service reads aloud. The user message is where a style instruction would go, and there
            // is none here.
            .put("messages", org.json.JSONArray().put(org.json.JSONObject().put("role", "assistant").put("content", text)))
            .put(
                "audio",
                org.json.JSONObject()
                    .put("format", "wav")
                    .apply { if (config.ttsVoice.isNotBlank()) put("voice", config.ttsVoice) },
            )
    }.toString().toByteArray()

    val http = URL(if (shape == SpeechShape.OPENAI_AUDIO) "$base/audio/speech" else "$base/chat/completions").openConnection() as HttpURLConnection
    try {
        http.requestMethod = "POST"
        http.doOutput = true
        http.connectTimeout = 8_000
        http.readTimeout = 30_000
        http.setRequestProperty("Content-Type", "application/json")
        if (config.ttsApiKey.isNotBlank()) http.setRequestProperty("Authorization", "Bearer ${config.ttsApiKey}")
        http.outputStream.use { it.write(body) }
        val code = http.responseCode
        if (code == 404) throw NotHere()
        if (code !in 200..299) {
            val words = http.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
            val message = runCatching { org.json.JSONObject(words).getJSONObject("error").getString("message") }
                .getOrDefault(words.take(200))
            throw SpeechError(message.ifEmpty { "HTTP $code" })
        }
        val answer = http.inputStream.use { it.readBytes() }
        return when (shape) {
            SpeechShape.OPENAI_AUDIO -> {
                check(answer.size > 44) { "the speech service returned no audio" }
                wavToPcm(answer)
            }
            SpeechShape.CHAT_AUDIO -> {
                // The audio arrives base64 in the message rather than as bytes, so this is the only
                // place the two shapes really differ.
                val data = runCatching {
                    org.json.JSONObject(answer.decodeToString())
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getJSONObject("audio").getString("data")
                }.getOrElse { throw SpeechError("the speech service sent no audio in its answer") }
                val wav = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                check(wav.size > 44) { "the speech service returned no audio" }
                wavToPcm(wav)
            }
        }
    } finally {
        http.disconnect()
    }
}

/**
 * 16-bit PCM WAV, mixed down to mono. Some services leave the data size unset, so it is clamped to
 * what is actually there — the same tolerance the phone's own text to speech needs.
 */
internal fun wavToPcm(bytes: ByteArray): SpeechPcm {
    val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    var rate = 48_000
    var channels = 1
    var bits = 16
    var offset = 12
    var dataAt = -1
    var dataSize = -1
    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4, Charsets.US_ASCII)
        val size = b.getInt(offset + 4)
        val body = offset + 8
        when (id) {
            "fmt " -> {
                channels = b.getShort(body + 2).toInt().coerceAtLeast(1)
                rate = b.getInt(body + 4).coerceAtLeast(1)
                bits = b.getShort(body + 14).toInt().coerceAtLeast(8)
            }
            "data" -> {
                dataAt = body
                dataSize = size
            }
        }
        offset = body + size + (size and 1)
    }
    if (dataAt < 0) throw SpeechError("the speech service's audio has no data chunk")
    val have = (bytes.size - dataAt).coerceAtMost(if (dataSize > 0) dataSize else bytes.size - dataAt)
    val step = (bits / 8).coerceAtLeast(1)
    val frames = have / (step * channels)
    val out = ShortArray(frames)
    for (i in 0 until frames) {
        var sum = 0
        for (c in 0 until channels) {
            val at = dataAt + (i * channels + c) * step
            sum += if (bits == 8) ((bytes[at].toInt() and 0xFF) - 128) shl 8 else b.getShort(at).toInt()
        }
        out[i] = (sum / channels).toShort()
    }
    return SpeechPcm(out, rate)
}
