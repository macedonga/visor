package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.protocol.Thrift
import com.macedonga.visor.core.protocol.ThriftWriter
import java.util.zip.Inflater

/** svc15, Oxide: the assistant. From lensbreak/services/assistant.py and docs/protocol/assistant.md. */
const val SVC_ASSISTANT = 15

internal const val MT_PAIRED_DEVICE_ANDROID = 11
internal const val MT_INIT_MESSAGE_REQUEST = 1000
internal const val MT_INIT_MESSAGE_RESPONSE = 1001
// A message too big for one frame: a header {1 type, 2 total size, 3 id}, then buffers {1 bytes, 2 id, 3 last}.
internal const val MT_SEGMENTED_HEADER = 1002
internal const val MT_SEGMENTED_BUFFER = 1003
internal const val MT_NET_EVENT = 1010
internal const val MT_DEVICE_ACTIVE = 1016
internal const val MT_RUNNING_LOCALE = 1026
internal const val MT_NUM_OF_DEVICES_PAIRED = 1029
internal const val MT_AIR_RESOURCES_UNAVAILABLE = 1031
internal const val MT_AIR_RESOURCES_AVAILABLE = 1032
internal const val MT_LIVE_MODE_AIR_RESOURCES_UNAVAILABLE = 1038
internal const val MT_LIVE_MODE_AIR_RESOURCES_AVAILABLE = 1039
internal const val MT_INIT_STREAM_REQUEST = 5000
internal const val MT_INIT_STREAM_RESPONSE = 5001
internal const val MT_STREAM_HEADER = 5002
internal const val MT_STREAM_AUDIO = 5003
internal const val MT_STREAM_STATUS = 5005
internal const val MT_STREAM_END = 6000
internal const val MT_START_INTERACTION = 1023
internal const val MT_ENVELOPE = 9000
internal const val MT_ENVELOPE_ACK = 9001

// NetEvent ids, named from the glasses' own EventId table (libassistantruntime.so, the value-to-index
// table beside the names; all 206 are in events.tsv). Several are not what lensbreak called them,
// but every send below is byte for byte the one the glasses accepted from it.
internal const val EV_FINAL_TRANSCRIPTION = 52
internal const val EV_VOICE_ACTION_RESPONSE = 53
internal const val EV_FULFILLMENT_REQUEST = 57
internal const val EV_ACTION_FULFILLMENT_REQUEST = 55
internal const val EV_MWA_VOICE_INTERACTION_CLOSE_REQUEST = 59
internal const val EV_MWA_VOICE_INTERACTION_COMPLETE = 60
internal const val EV_KEEP_MWA_ALIVE = 63
internal const val EV_MMAI_INTERACTION_REQUEST = 65
internal const val EV_ACTION_FULFILLMENT_RESPONSE = 67
internal const val EV_START_OF_USER_SPEECH = 71
internal const val EV_REMOTE_IMAGE_CAPTURE_REQUEST = 73
internal const val EV_REMOTE_IMAGE_CAPTURE_RESPONSE = 74
internal const val EV_LIVE_AI_STREAM_INITIATION_ACK = 261
internal const val EV_LIVE_AI_CLIENT_RECEIVED_FIRST_FRAME = 262
internal const val EV_SERVER_CONNECTION_STATUS = 263
internal const val EV_MWA_INTERACTION_CREATION_SUCCESS = 265
internal const val EV_LIVE_AI_STATE_UPDATE = 264
internal const val EV_SESSION_MODE_UPDATE_RESPONSE = 289
internal const val EV_FRAME_KEEP_ALIVE = 302
internal const val EV_FRAME_KEEP_ALIVE_RESPONSE = 304
internal const val EV_REMOTE_INTERACTION_COMPLETE = 360
internal const val EV_DEVICE_VOICE_STATE = 307
internal const val EV_SESSION_STATE_BROADCAST = 333
internal const val EV_SESSION_STATE_UPDATE = 339
internal const val EV_ON_PHONE_UNDERSTANDING = 342
internal const val EV_TTS_START_INFO = 388
internal const val EV_TTS_END_INFO = 389
internal const val EV_KEEP_AIR_SESSION_ALIVE = 392
// The glasses' own tools and intents, run for a Display turn (see GlassesTools.kt).
internal const val EV_REMOTE_INTENT_REQUEST = 380
internal const val EV_REMOTE_INTENT_RESPONSE = 381
internal const val EV_MCP_REQUEST = 311
internal const val EV_MCP_RESPONSE = 312
internal const val EV_MCP_REQUEST_ACK = 387

/** The glasses' name for a NetEvent id. */
internal fun eventName(id: Int): String = EVENT_NAMES[id] ?: "event $id"

private val EVENT_NAMES: Map<Int, String> by lazy {
    val stream = Assistant::class.java.getResourceAsStream("events.tsv") ?: return@lazy emptyMap()
    stream.bufferedReader().useLines { lines ->
        lines.mapNotNull { line -> line.split('	').takeIf { it.size == 2 }?.let { it[0].toInt() to it[1] } }.toMap()
    }
}

internal const val REPLY_INTENT = "IN:GET_WEATHER"

/**
 * The svc15 carrier. Field 4 must be I64: written as I32 the glasses discard the whole event
 * without a word.
 */
internal fun netEvent(eventId: Int, body: ByteArray): ByteArray =
    ThriftWriter().i32(1, eventId).str(2, body).bool(3, false).i64(4, body.size.toLong()).bool(11, false).done()

internal class NetEvent(val id: Int, val body: ByteArray)

/** A NetEvent's id and body, inflating a compressed body. */
internal fun parseNetEvent(blob: ByteArray): NetEvent {
    val f = Thrift.loads(blob)
    var body = f[2].asBytes()
    if (f[3] == true && body.isNotEmpty()) {
        body = runCatching { inflate(body) }.getOrDefault(body)
    }
    return NetEvent((f[1] as? Long)?.toInt() ?: -1, body)
}

/** The Thrift reader turns valid UTF-8 into a String; a valid string re-encodes to the same bytes. */
internal fun Any?.asBytes(): ByteArray = when (this) {
    is ByteArray -> this
    is String -> toByteArray(Charsets.UTF_8)
    else -> ByteArray(0)
}

internal fun Any?.asText(): String = when (this) {
    is String -> this
    is ByteArray -> String(this, Charsets.UTF_8)
    else -> ""
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asStruct(): Map<Int, Any?> = this as? Map<Int, Any?> ?: emptyMap()

private fun inflate(data: ByteArray): ByteArray {
    val inflater = Inflater()
    inflater.setInput(data)
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (!inflater.finished()) {
        val n = inflater.inflate(buffer)
        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
        out.write(buffer, 0, n)
    }
    inflater.end()
    return out.toByteArray()
}

/**
 * The answer struct: a real VARM the glasses accepted, with our strings spliced in. The capture's place and its
 * request and interaction ids were replaced in the file with neutral ones of the same shape.
 * Splicing keeps every other byte; re-encoding it would change integer widths.
 */
internal fun varm(
    interactionId: String,
    transcript: String,
    text: String,
    onGlasses: Boolean = false,
    voiceId: String? = null,
    card: Boolean = false,
): ByteArray {
    val raw = checkNotNull(Assistant::class.java.getResourceAsStream("varm_template.bin")) { "varm_template.bin is missing" }
        .use { it.readBytes() }
    val old = Thrift.loads(raw)
    val server = old[1].asStruct()
    val mapping = HashMap<String, ByteArray>()
    mapping[old[8].asText()] = interactionId.toByteArray()
    server[2].asStruct()[1].asText().takeIf { it.isNotEmpty() }?.let { mapping[it] = (transcript.ifEmpty { text }).toByteArray() }
    val answer = (server[3] as? List<*>)?.firstOrNull().asStruct()[2].asStruct()[1].asText()
    if (answer.isNotEmpty()) mapping[answer] = text.toByteArray()
    // Field 13 is speakRequestVoiceId ("en_US.58" when captured).
    if (onGlasses && !voiceId.isNullOrEmpty()) old[13].asText().takeIf { it.isNotEmpty() }?.let { mapping[it] = voiceId.toByteArray() }
    val spliced = Thrift.replaceStrings(raw, mapping)
    if (!onGlasses) return spliced
    // No audio follows: streamingBackTts (6) and isUsingPartialResponseStreaming (14) go false; ev60 then carries the text.
    val quiet = listOf(6, 14).fold(spliced) { b, field -> Thrift.setBool(b, field, false) }
    // expectedUxTemplate (24) tells the glasses a card is coming in ev60.
    return if (card) Thrift.setBool(quiet, 24, true) else quiet
}

/**
 * A simple card for a Display, as ev60's onDeviceUxMessage carries it: base64 of a compact AssistantTemplate
 * (type 0, a SimpleTemplate inside). The Display throws on a template without hideAfterMs.
 */
internal fun answerCard(title: String, text: String): String {
    val simple = ThriftWriter().str(1, title).str(2, text).done()
    val hideAfterMs = (8_000L + 60L * text.length).coerceAtMost(30_000L)
    val template = ThriftWriter().i32(1, 0).str(2, simple).str(9, text).i64(10, hideAfterMs).done()
    return java.util.Base64.getEncoder().encodeToString(template)
}

/**
 * ev60 MwaVoiceInteractionComplete with a fulfillmentResult the glasses speak themselves: ttsMessage, fulfillment
 * type 1 (server), requiresAnswer when they should listen for a reply, and nothing streaming. Status 1000 is success.
 * A [card] goes in onDeviceUxMessage (field 27) for the Display to draw.
 */
internal fun interactionComplete(interactionId: String, text: String, expectsAnswer: Boolean = false, card: String? = null): ByteArray {
    var result = ThriftWriter().str(1, text).i32(2, 1).bool(3, expectsAnswer).bool(4, false).bool(7, false).bool(8, false)
    if (card != null) {
        val ux = ThriftWriter().listOfBinaries(1, listOf(card.toByteArray(Charsets.UTF_8))).str(2, interactionId).done()
        result = result.struct(27, ux)
    }
    return ThriftWriter().str(1, interactionId).i32(2, 1000).str(3, "").struct(4, result.done()).done()
}
