package com.macedonga.visor.core.assistant

import android.content.Context
import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.assistant.mcp.ToolResult
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.media.AirshipReceiver
import com.macedonga.visor.core.media.ExtraFiles
import com.macedonga.visor.core.media.NAMESPACE_SHARING
import com.macedonga.visor.core.media.Offer
import com.macedonga.visor.core.media.Received
import com.macedonga.visor.core.protocol.Thrift
import com.macedonga.visor.core.protocol.ThriftWriter
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.Responder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

enum class AssistantPhase { OFF, WAITING, REGISTERING, LISTENING, THINKING, SPEAKING }

/** One "Hey Meta" turn, for the screen. */
data class TurnRecord(
    val interactionId: String,
    val at: Long,
    val question: String,
    val answer: String = "",
    val problem: String = "",
    val modelMs: Long = 0,
    val sentMs: Long = 0,
    // What the glasses report having played, from their 5005; -1 until it arrives.
    val playedMs: Long = -1,
    // The photo the answer was given, as sent to the model.
    val image: String = "",
    // The conversation it belongs to: questions within the memory window of each other, or a Live AI session.
    val conversation: String = "",
    val source: TurnSource = TurnSource.GLASSES,
    // The Live AI session it was asked in.
    val session: String = "",
    // What the model chose to remember while answering.
    val remembered: List<String> = emptyList(),
    // What it did with the person's tools.
    val actions: List<ToolAction> = emptyList(),
)

/**
 * "Hey Meta", answered by this phone: svc15 bring-up on every Bluetooth link, the envelope acks and
 * state mirrors, and each fulfilment request answered with a model's reply spoken by the phone's
 * TTS. Port of lensbreak's services/assistant.py; sends are serialised by the link's own lock, so
 * the mid-burst ev339 mirror goes out from the reader while the audio streams from here.
 */
class Assistant(
    private val context: Context,
    private val connection: GlassesConnection,
    private val voice: Voice,
    val brain: Brain,
    private val store: AssistantConfigStore,
    airship: AirshipReceiver,
    /** Every turn, kept by day, with the photos the answers were given. */
    val diary: Diary,
    /** The Live view's Wi-Fi session while there is one: "Hey Meta" keeps working on it. */
    liveSession: Flow<DataXConnection?> = emptyFlow(),
    /** The Live view's picture now, when one runs: the image for every question asked meanwhile. */
    private val liveFrame: suspend () -> ByteArray? = { null },
    /** The camera stream for Live AI: on, off, and whether frames are coming. */
    private val camera: LiveAiCamera? = null,
    /** The photos the glasses take for questions, on svc53. */
    private val glassesPhotos: GlassesPhotos? = null,
    /** The voice the glasses speak in (assistant_voice_id), for answers in their own voice. */
    private val glassesVoice: () -> String? = { null },
    /** The wearer asked the glasses for detailed Live AI answers (detailed_responses_live_ai_enabled). */
    private val liveAiDetailed: () -> Boolean = { false },
    /** Conversation focus, which the phone starts when the wearer asks for it by voice. */
    private val hearing: Hearing? = null,
    /** How long the glasses wait for an answer (assistant_on_sn.default_processing_timeout_s), when known. */
    private val processingTimeoutS: () -> Long? = { null },
    /** The phone's player, for pausing, resuming and skipping by voice. */
    private val phone: Phone? = null,
    /** The connected glasses have a display: each answer then comes with a card for it. */
    private val display: () -> Boolean = { false },
) : Responder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _phase = MutableStateFlow(AssistantPhase.WAITING)
    val phase: StateFlow<AssistantPhase> = _phase.asStateFlow()

    /** Every turn, oldest first. */
    val turns: StateFlow<List<TurnRecord>> = diary.turns
    private val photos: File get() = diary.photos

    // Days whose lines are being written, and the last turn each failed for, so a failure is not retried until there is more.
    private val writing: MutableSet<LocalDate> = Collections.newSetFromMap(ConcurrentHashMap())
    private val linesFailed = ConcurrentHashMap<LocalDate, Long>()

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    /** The glasses' custom wake word enrolment, over this session. */
    val wakeWord = CustomWakeWord(
        send = { id, body ->
            val l = link
            val m0 = l?.m0
            if (l == null || m0 == null) false
            else runCatching { sendNetEvent(l, m0, netEvent(id, body)) }
                .onFailure { say("Custom wake word: event $id did not go: ${it.message}") }
                .isSuccess
        },
        headset = { ms, note, peak ->
            headsetTake(context, (connection.state.value.target?.link as? GlassesLink.Bluetooth)?.address, ms, note, peak)
        },
    )

    /**
     * A NetEvent onto the session; one bigger than a frame carries goes as a segmented message, a header (1002)
     * {1 type, 2 size, 3 id} then pieces (1003) {1 bytes, 2 id, 3 last}, the way the glasses send theirs.
     */
    private suspend fun sendNetEvent(l: Link, m0: Int, event: ByteArray) {
        if (event.size <= SEGMENT_BYTES) return l.mux.send(m0, event, messageType = MT_NET_EVENT)
        val id = UUID.randomUUID().toString().encodeToByteArray()
        l.mux.send(m0, Wire.fieldVarint(1, MT_NET_EVENT.toLong()) + Wire.fieldVarint(2, event.size.toLong()) + Wire.fieldBytes(3, id), messageType = MT_SEGMENTED_HEADER)
        var at = 0
        while (at < event.size) {
            val end = minOf(event.size, at + SEGMENT_BYTES)
            val piece = Wire.fieldBytes(1, event.copyOfRange(at, end)) + Wire.fieldBytes(2, id) + Wire.fieldVarint(3, if (end == event.size) 1 else 0)
            l.mux.send(m0, piece, messageType = MT_SEGMENTED_BUFFER)
            at = end
        }
    }

    private val liveAi = LiveAi(context, scope, object : LiveAiHost {
        override suspend fun event(id: Int, body: ByteArray) {
            val l = link ?: return
            val m0 = l.m0 ?: return
            l.mux.send(m0, netEvent(id, body), messageType = MT_NET_EVENT)
        }

        override suspend fun answer(subInteraction: String, question: String) {
            link?.let { liveAnswer(it, subInteraction, question) }
        }

        override fun cameraOn() {
            camera?.on()
        }

        override fun cameraOff() {
            camera?.off()
        }

        override fun note(text: String) = say(text)
    }, display)

    // The glasses' last language, kept so the speech model can be fetched at launch, before they connect.
    private val speechPrefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE)
    private var lastSpeech = ""

    // When the current microphone stream ended.
    @Volatile
    private var micEnded: CompletableDeferred<Unit>? = null

    // The ears for one turn's microphone, and the interaction they listen for.
    @Volatile
    private var ears: GlassesEars? = null

    /**
     * The glasses opened their microphone for [interaction]. They recognise a "Hey Meta" question themselves and
     * send it as ev57; a follow-up after an answer they leave to the server. Both are heard here, and an utterance
     * is answered only if no ev57 for it has come a moment after it ended.
     */
    private fun listen(l: Link, interaction: String) {
        ears?.stop()
        val ended = CompletableDeferred<Unit>().also { micEnded = it }
        l.listening = interaction
        if (interaction.isEmpty() || !store.config.value.enabled) return
        say("Listening to the microphone for $interaction")
        ears = GlassesEars(
            context, l.locale, continuous = true,
            onUtterance = { text, startMs, _ ->
                scope.launch {
                    delay(OWN_RECOGNITION_GRACE_MS)
                    if (interaction in l.closed) return@launch
                    when {
                        // The question itself, which the glasses recognised and sent as ev57.
                        interaction in l.taken && !l.answered.contains(interaction) -> Unit
                        // After an answer: a follow-up, a sub-interaction of its own as the server opens one.
                        interaction in l.answered -> {
                            val sub = java.util.UUID.randomUUID().toString()
                            say("Heard \"$text\" myself: a follow-up, answered as $sub")
                            l.mux.send(checkNotNull(l.m0), netEvent(EV_START_OF_USER_SPEECH, ThriftWriter().str(1, sub).i64(2, startMs).done()), messageType = MT_NET_EVENT)
                            l.taken.add(sub)
                            answer(l, sub, text, Visual(null, "", "", false, ""))
                        }
                        // Nothing came from the glasses: the whole question was left to the server. Their own
                        // transcript comes only after their stream ends, so wait for that, and never answer the wake word.
                        else -> {
                            if (WAKE_WORD_ONLY.matches(text.trim())) return@launch
                            withTimeoutOrNull(MIC_END_WAIT_MS) { ended.await() }
                            delay(QUESTION_GRACE_MS)
                            if (interaction in l.taken || interaction in l.closed) return@launch
                            l.taken.add(interaction)
                            l.fresh.add(interaction)
                            say("Heard \"$text\" myself: the glasses left this question to the server")
                            answer(l, interaction, text, Visual(null, "", "", false, ""))
                        }
                    }
                }
            },
            onProblem = { say("Assistant: $it") },
        ).also { it.start() }
    }

    /** Live AI, while the glasses run a session with this phone as its server. */
    val liveAiPhase: StateFlow<LiveAiPhase> = liveAi.phase

    /** The glasses' assistant language, from their 1026. */
    val locale: Locale get() = link?.locale ?: Locale.US

    @Volatile
    private var link: Link? = null

    /** svc15 state for one Bluetooth session. */
    private class Link(val mux: DataXConnection) {
        // Whether the current turn is a session (SCI) one, which only plays streamed answers.
        @Volatile var sessionTurn = false
        // The interaction the microphone is open for, and each request's session when the two differ.
        @Volatile var listening: String? = null
        val sessionOf = java.util.concurrent.ConcurrentHashMap<String, String>()
        @Volatile var m0: Int? = null
        @Volatile var s0: Int? = null
        val m0Answered = CompletableDeferred<Unit>()
        val s0Answered = CompletableDeferred<Unit>()
        // The stream id the glasses allocate per interaction, from their own 5002.
        val streamIds = ConcurrentHashMap<String, String>()
        @Volatile var latestStream = ""
        val confirmed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
        // Interactions whose answer has been spoken: what is said after is a follow-up.
        val answered: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
        // Interactions the glasses have closed: an answer that comes later would talk over the next one.
        val closed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
        val taken: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
        // Questions asked with the wake word rather than as a follow-up: they continue a conversation only right after it.
        val fresh: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
        @Volatile var locale: Locale = Locale.US
        // ev57's field 30, MobileConfig assistant_on_sn.simplify_mwa_message_forwarding. Off (the factory value),
        // the glasses act on a VARM the moment it lands and end the turn there.
        @Volatile var simplified = true
        // Segmented messages being put back together, by id: their type, size and bytes so far.
        val segments = ConcurrentHashMap<String, Triple<Int, Int, java.io.ByteArrayOutputStream>>()
        // The text the glasses read in each question's photo, from their ev65.
        private val ocr = ConcurrentHashMap<String, CompletableDeferred<String>>()
        fun ocrOf(interaction: String): CompletableDeferred<String> = ocr.getOrPut(interaction) { CompletableDeferred() }
        // The glasses' tool calls waiting for their ev312, by JSON-RPC id; and device intents waiting for their ev381.
        val calls = ConcurrentHashMap<String, CompletableDeferred<String>>()
        val handovers = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, String>>>()
        val callIds = AtomicInteger(1)
    }

    /** A photo this side is waiting for, for one interaction. */
    private class PendingImage(val interaction: String) {
        val image = CompletableDeferred<ByteArray>()
        @Volatile var answered = false
    }

    @Volatile
    private var pending: PendingImage? = null

    /** A photo that came over airship with no question waiting: some pairs send it just before the question. */
    private class EarlyImage(val bytes: ByteArray, val from: String, val at: Long)

    @Volatile
    private var early: EarlyImage? = null

    /** The phone's messaging, for replies by voice; set by the notification listener. */
    @Volatile
    var messaging: Messaging? = null

    // A reply asked for and not yet dictated: the next request's words are its text.
    private class AwaitingReply(val conversation: Conversation, val until: Long)

    @Volatile
    private var awaitingReply: AwaitingReply? = null

    init {
        // The assistant's photo comes over airship, in a namespace the media import refuses.
        airship.extras += object : ExtraFiles {
            override fun wants(offer: Offer): Boolean {
                val namespace = offer.namespace
                val size = offer.size
                val waiting = pending != null && size < 20_000_000 ||
                    namespace == NAMESPACE_SHARING && store.config.value.enabled && size < EARLY_MAX_BYTES
                if (waiting) say("Taking ${size / 1000} KB in namespace \"$namespace\" for the photo")
                return waiting
            }

            // Read here, because the receiver deletes the file; decoded off the reader.
            override fun take(received: Received) {
                val got = EarlyImage(received.file.readBytes(), "airship \"${received.offer.namespace}\"", System.currentTimeMillis())
                early = got
                scope.launch { offerEarly(got) }
            }

            override fun peek(received: Received) {
                if (pending == null || received.offer.size >= 20_000_000) return
                val bytes = received.file.readBytes()
                scope.launch { offerImage(bytes, "the media namespace") }
            }
        }
        scope.launch {
            combine(
                connection.state.map { it.link == LinkState.READY }.distinctUntilChanged(),
                store.config.map { it.enabled }.distinctUntilChanged(),
            ) { ready, enabled -> ready to enabled }.collectLatest { (ready, enabled) ->
                link = null
                _phase.value = when {
                    !enabled -> AssistantPhase.OFF
                    !ready -> AssistantPhase.WAITING
                    else -> AssistantPhase.REGISTERING
                }
                if (ready && enabled) connection.submit { mux -> scope.launch { bringUp(mux) } }
            }
        }
        scope.launch { camera?.frames?.collect { if (it) liveAi.onFirstFrame() } }
        // A language switch restarts the glasses' assistant, which closes our channels: register again once it settles.
        scope.launch {
            var again: Job? = null
            connection.warnings.collect { warning ->
                val channel = CHANNEL_CLOSED.find(warning)?.groupValues?.get(1)?.toIntOrNull(16) ?: return@collect
                val l = link ?: return@collect
                if (channel != (l.m0 ?: -1) and 0x7FFF && channel != (l.s0 ?: -1) and 0x7FFF) return@collect
                again?.cancel()
                again = scope.launch {
                    delay(REGISTER_AGAIN_MS)
                    if (link !== l || connection.state.value.link != LinkState.READY) return@launch
                    say("The glasses' assistant closed our channels; registering again")
                    connection.submit { mux -> scope.launch { bringUp(mux) } }
                }
            }
        }
        // At launch: the speech model for the glasses' language, as last heard.
        Locale.forLanguageTag(speechPrefs.getString("language", null) ?: "en-US").let {
            lastSpeech = speechTag(it)
            SpeechModels.prepare(context, it, ::say)
        }
        scope.launch { glassesPhotos?.arrived?.collect { (id, bytes) -> if (pending != null) offerImage(bytes, "svc53 photo $id") } }
        // A day's lines are written again once its latest conversation has closed.
        scope.launch {
            diary.turns.map { list -> list.lastOrNull()?.let { it.date to it.at } }.distinctUntilChanged().collectLatest { last ->
                val (date, at) = last ?: return@collectLatest
                val closes = at + windowMs() - System.currentTimeMillis()
                if (closes > 0) delay(closes + LINES_GRACE_MS)
                writeLines(date)
            }
        }
        // While the Live runs over Wi-Fi the Bluetooth session is paused; the assistant registers on the Wi-Fi one.
        scope.launch {
            liveSession.collectLatest { mux ->
                if (mux != null && store.config.value.enabled) {
                    say("Moving the assistant onto the Live view's Wi-Fi session")
                    bringUp(mux)
                }
            }
        }
    }

    private fun say(text: String) {
        _notes.tryEmit(text)
    }

    // --- bring-up ------------------------------------------------------------

    /** Register m0 and s0, declare the companion's resources, announce a session. */
    private suspend fun bringUp(mux: DataXConnection) {
        val l = Link(mux)
        link = l
        try {
            val client = UUID.randomUUID().toString()
            val session = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
            fun registration(endpoint: String) = Wire.fieldBytes(1, session.toByteArray()) +
                Wire.fieldBytes(2, client.toByteArray()) + Wire.fieldBytes(3, endpoint.toByteArray()) + Wire.fieldVarint(4, 2)

            val m0 = mux.openChannel(SVC_ASSISTANT).also { l.m0 = it }
            mux.send(m0, registration("$client-m0"), messageType = MT_INIT_MESSAGE_REQUEST, serviceId = SVC_ASSISTANT)
            withTimeoutOrNull(4_000) { l.m0Answered.await() } ?: say("The glasses did not answer the assistant's message registration")
            val s0 = mux.openChannel(SVC_ASSISTANT).also { l.s0 = it }
            mux.send(s0, registration("$client-s0"), messageType = MT_INIT_STREAM_REQUEST, serviceId = SVC_ASSISTANT)
            withTimeoutOrNull(4_000) { l.s0Answered.await() } ?: say("The glasses did not answer the assistant's stream registration")

            // In this order, as the app declares them. Without them the glasses take the audio and play nothing.
            fun id() = Wire.fieldBytes(1, UUID.randomUUID().toString().toByteArray())
            val resources = listOf(
                MT_PAIRED_DEVICE_ANDROID to Wire.fieldBytes(1, "${System.currentTimeMillis()}-${UUID.randomUUID()}".toByteArray()),
                MT_AIR_RESOURCES_UNAVAILABLE to id(),
                MT_LIVE_MODE_AIR_RESOURCES_UNAVAILABLE to id(),
                MT_DEVICE_ACTIVE to ByteArray(0),
                MT_NUM_OF_DEVICES_PAIRED to "1".toByteArray(),
                MT_LIVE_MODE_AIR_RESOURCES_AVAILABLE to id(),
                MT_AIR_RESOURCES_AVAILABLE to id(),
            )
            for ((type, payload) in resources) {
                mux.send(m0, payload, messageType = type)
                delay(30)
            }
            announceSession(mux, m0)
            if (link === l) _phase.value = AssistantPhase.LISTENING
            say("Registered as the glasses' assistant: \"Hey Meta\" now comes to Visor")
        } catch (error: Exception) {
            if (link === l) say("Could not register as the assistant: ${error.message}")
        }
    }

    /** Four events that tell the glasses a working assistant backend is behind this client. */
    private suspend fun announceSession(mux: DataXConnection, m0: Int) {
        val sid = UUID.randomUUID().toString()
        val sessionString = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val open = ThriftWriter().str(1, sid).bool(2, true).bool(3, false)
            .str(4, UUID.randomUUID().toString().uppercase()).str(5, "prod.facebookvirtualassistant.com")
            .str(6, "ble").str(10, sessionString).bool(11, false).bool(13, true).bool(14, false).done()
        val stepA = ThriftWriter().str(1, sid).bool(2, false).bool(3, false).i32(4, 1).bool(7, false).done()
        val stepB = ThriftWriter().str(1, sid).bool(2, true).bool(3, false).i32(4, 2).done()
        val close = ThriftWriter().str(1, sid).i32(2, 1).i32(3, 0).done()
        for ((event, body) in listOf(EV_MWA_INTERACTION_CREATION_SUCCESS to open, EV_SERVER_CONNECTION_STATUS to stepA, EV_SERVER_CONNECTION_STATUS to stepB, EV_LIVE_AI_CLIENT_RECEIVED_FIRST_FRAME to close)) {
            mux.send(m0, netEvent(event, body), messageType = MT_NET_EVENT)
            delay(20)
        }
    }

    // --- the responder ---------------------------------------------------------

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val l = link ?: return
        // The Wi-Fi session gets this responder too; only the link we registered on is ours.
        if (connection !== l.mux) return
        val payload = message.payload
        when (val type = message.info.messageType) {
            MT_INIT_MESSAGE_RESPONSE -> l.m0Answered.complete(Unit)
            MT_INIT_STREAM_RESPONSE -> l.s0Answered.complete(Unit)
            MT_RUNNING_LOCALE -> {
                l.locale = parseLocale(payload)
                say("The glasses' assistant speaks ${l.locale.getDisplayName(Locale.ENGLISH)}")
                if (speechTag(l.locale) != lastSpeech) {
                    lastSpeech = speechTag(l.locale)
                    speechPrefs.edit().putString("language", l.locale.toLanguageTag()).apply()
                    SpeechModels.prepare(context, l.locale, ::say)
                }
            }
            MT_STREAM_HEADER -> {
                val header = Wire.parse(payload)
                val stream = header.bytes(1).decodeToString()
                val interaction = header.bytes(2).decodeToString()
                if (stream.length > 8) {
                    l.streamIds[interaction] = stream
                    l.latestStream = stream
                }
                if (liveAi.active) liveAi.onMicHeader(hex(payload)) else listen(l, interaction)
            }
            // The microphone: Live AI's, or one turn's, which the server may have to recognise itself.
            MT_STREAM_AUDIO, 0 -> if (liveAi.active) liveAi.onMic(payload) else ears?.hear(payload)
            MT_STREAM_END -> {
                micEnded?.complete(Unit)
                ears?.finish()
            }
            MT_STREAM_STATUS -> streamStatus(payload.decodeToString())
            // A custom wake word sample, recorded on the glasses for an enrolment.
            CustomWakeWord.MT_ENROLLMENT_HEADER, CustomWakeWord.MT_STREAMER_DATA, CustomWakeWord.MT_STREAMER_END -> wakeWord.onStream(type, payload)
            MT_ENVELOPE -> envelope(l, payload)
            // Some glasses send the same messages bare, without the envelope.
            MT_NET_EVENT, MT_START_INTERACTION, MT_SEGMENTED_HEADER, MT_SEGMENTED_BUFFER -> inner(l, type, payload)
            // Init answers, time stretch, TTS buffer config, MMAI enabled, assistant ready, stream consumed.
            1028, 1033, 1036, 1037, 6001 -> Unit
            else -> say("Assistant: message type $type, ${payload.size} bytes ${hex(payload)}")
        }
    }

    private suspend fun envelope(l: Link, payload: ByteArray) {
        val m0 = l.m0 ?: return
        val env = Wire.parse(payload)
        // Unacked, the glasses resend the same envelope for minutes.
        if (env.has(1)) l.mux.send(m0, Wire.fieldVarint(1, env.varint(1)), messageType = MT_ENVELOPE_ACK)
        inner(l, env.varint(2).toInt(), env.bytes(3))
    }

    /** A start of interaction or a NetEvent, from an envelope or sent bare. */
    private suspend fun inner(l: Link, type: Int, blob: ByteArray) {
        val m0 = l.m0 ?: return
        if (type == MT_START_INTERACTION) {
            val fields = runCatching { Thrift.loads(blob) }.getOrDefault(emptyMap())
            // Use case 3 is LIVE_AI_INTERACTION; after a switch to LIVE_AI the next interaction is the session's.
            if (fields[5] == 3L || liveAi.phase.value == LiveAiPhase.STARTING) liveAi.onStartInteraction(fields, l.locale)
            else {
                // Use case 7 opens a session ("SCI", English): there the glasses never speak text from the phone.
                l.sessionTurn = fields[5] == SESSION_USE_CASE
                say("Assistant: start interaction ${deep(fields)}")
            }
            return
        }
        if (type == MT_SEGMENTED_HEADER || type == MT_SEGMENTED_BUFFER) return segment(l, type, blob)
        if (type != MT_NET_EVENT) {
            say("Assistant: an envelope carrying type $type, ${blob.size} bytes ${hex(blob)}")
            return
        }
        val event = try {
            parseNetEvent(blob)
        } catch (error: Exception) {
            say("Assistant: an unreadable event (${error.message})")
            return
        }
        when (event.id) {
            EV_SESSION_MODE_UPDATE_RESPONSE -> {
                val fields = runCatching { Thrift.loads(event.body) }.getOrDefault(emptyMap())
                val id = fields[1].asText()
                // Mode 2 is LIVE_AI: no speech confirmation, the session is the server's to serve.
                if (fields[2] == MODE_LIVE_AI) {
                    liveAi.onModeLiveAi(id, fields)
                    return
                }
                if (liveAi.active) liveAi.end("the session went back to mode ${fields[2]}")
                // The glasses wait for this confirmation before they hand over the transcript.
                if (id.isNotEmpty() && l.confirmed.add(id)) {
                    l.mux.send(m0, netEvent(EV_START_OF_USER_SPEECH, ThriftWriter().str(1, id).done()), messageType = MT_NET_EVENT)
                }
            }
            EV_SESSION_STATE_UPDATE -> {
                mirrorState(l, m0, event.body)
                // Mode 0: the session is over, Live AI with it.
                if (liveAi.active && runCatching { Thrift.loads(event.body)[2] }.getOrNull() == 0L) liveAi.end("the session closed")
            }
            EV_REMOTE_INTERACTION_COMPLETE -> {
                runCatching { Thrift.loads(event.body)[1].asText() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { l.closed.add(it) }
                say("Assistant: remote interaction complete ${describe(event.body)}")
                liveAi.end("the glasses completed the interaction")
            }
            EV_FRAME_KEEP_ALIVE_RESPONSE, EV_KEEP_MWA_ALIVE -> Unit
            // The answer's display action, passed on to the phone; the glasses wait for the phone to take it.
            EV_ACTION_FULFILLMENT_REQUEST -> {
                val id = runCatching { Thrift.loads(event.body)[1].asStruct()[1].asText() }.getOrDefault("")
                l.mux.send(m0, netEvent(EV_ACTION_FULFILLMENT_RESPONSE, ThriftWriter().str(1, id).bool(2, true).done()), messageType = MT_NET_EVENT)
            }
            EV_MWA_VOICE_INTERACTION_CLOSE_REQUEST -> closed(l, event.body)
            EV_FULFILLMENT_REQUEST -> fulfillmentRequest(l, event.body)
            EV_MMAI_INTERACTION_REQUEST -> mmaiRequest(l, event.body)
            EV_DEVICE_VOICE_STATE -> voiceState(event.body)
            EV_REMOTE_IMAGE_CAPTURE_RESPONSE -> {
                pending?.answered = true
                say("The glasses took the photo: ${describe(event.body)}")
            }
            CustomWakeWord.EV_RESULT -> wakeWord.onResult(runCatching { Thrift.loads(event.body) }.getOrDefault(emptyMap()))
            EV_MCP_REQUEST_ACK -> Unit
            EV_MCP_RESPONSE -> glassesReply(l, event.body)
            EV_REMOTE_INTENT_RESPONSE -> handoverReply(l, event.body)
            else -> say("Assistant: ${eventName(event.id)} (${event.id}), ${event.body.size} bytes ${describe(event.body)}")
        }
    }

    /** A tool call's answer, ev312 {1 jsonContent}: handed to the call waiting for its JSON-RPC id. */
    private fun glassesReply(l: Link, body: ByteArray) {
        val json = runCatching { Thrift.loads(body)[1].asText() }.getOrDefault("")
        val id = runCatching { JSONObject(json).opt("id")?.toString() }.getOrNull() ?: return say("Assistant: an unreadable tool answer")
        l.calls[id]?.complete(json)
    }

    /** ev381 {1 requestId, 2 success, 3 message}: the glasses' answer to a device intent they were asked to run. */
    private fun handoverReply(l: Link, body: ByteArray) {
        val f = runCatching { Thrift.loads(body) }.getOrNull() ?: return
        l.handovers[f[1].asText()]?.complete((f[2] == true) to f[3].asText())
    }

    /**
     * ev59 MwaVoiceInteractionCloseRequest {1 interactionId, 2 audioLogData {3 transcription}}: the glasses end a turn. One
     * they never asked about they did themselves, as "play something": it goes in the diary with its words.
     */
    private fun closed(l: Link, body: ByteArray) {
        val f = runCatching { Thrift.loads(body) }.getOrNull() ?: return
        val interaction = f[1].asText().takeIf { it.isNotEmpty() } ?: return
        l.closed.add(interaction)
        val words = f[2].asStruct()[3].asText().trim()
        if (interaction in l.taken || words.isEmpty() || WAKE_WORD_ONLY.matches(words)) return
        say("The glasses did \"$words\" themselves")
        record(TurnRecord(interaction, System.currentTimeMillis(), words, problem = ON_GLASSES))
    }

    /** 1002 opens a segmented message, 1003 carries its pieces; the whole one goes on as its own type. */
    private suspend fun segment(l: Link, type: Int, blob: ByteArray) {
        val f = Wire.parse(blob)
        val id = f.bytes(if (type == MT_SEGMENTED_HEADER) 3 else 2).decodeToString()
        if (type == MT_SEGMENTED_HEADER) {
            l.segments[id] = Triple(f.varint(1).toInt(), f.varint(2).toInt(), java.io.ByteArrayOutputStream())
            return
        }
        val (kind, size, bytes) = l.segments[id] ?: return say("Assistant: a segment of unknown message $id")
        bytes.write(f.bytes(1))
        if (f.varint(3) == 0L) return
        l.segments.remove(id)
        if (bytes.size() != size) return say("Assistant: segmented message $id came to ${bytes.size()} bytes of $size")
        inner(l, kind, bytes.toByteArray())
    }

    /**
     * ev65 MmaiInteractionRequest {1 interactionId, 2 ocrResult, 3 ocrPatchUuids}: the text the glasses' own OCR read in
     * the photo for a visual question. ocrResult is base64 of a gzipped struct: {1 lines, each {1 box, 2 word indices},
     * 2 words, each {2 text}}, lines top to bottom.
     */
    private fun mmaiRequest(l: Link, body: ByteArray) {
        val f = runCatching { Thrift.loads(body) }.getOrNull() ?: return say("Assistant: an unreadable MmaiInteractionRequest")
        val interaction = f[1].asText()
        val text = runCatching {
            val ocr = Thrift.loads(java.util.zip.GZIPInputStream(java.util.Base64.getDecoder().decode(f[2].asText()).inputStream()).readBytes())
            val words = (ocr[2] as? List<*>).orEmpty().map { it.asStruct()[2].asText() }
            (ocr[1] as? List<*>).orEmpty().map { line ->
                (line.asStruct()[2] as? List<*>).orEmpty().mapNotNull { words.getOrNull((it as? Long)?.toInt() ?: -1) }.joinToString(" ")
            }.filter { it.isNotBlank() }.joinToString("\n")
        }.getOrElse { return say("Assistant: unreadable OCR from the glasses (${it.message})") }
        say("The glasses read ${text.lines().size} lines of text in the photo")
        l.ocrOf(interaction).complete(text)
    }

    /** ev339 echoed straight back as ev333, field for field. */
    private suspend fun mirrorState(l: Link, m0: Int, body: ByteArray) {
        val state = runCatching { Thrift.loads(body) }.getOrNull() ?: return
        val w = ThriftWriter()
        for (f in state.keys.sorted()) {
            val v = state[f]
            if (v is Long) w.i32(f, v.toInt())
        }
        l.mux.send(m0, netEvent(EV_SESSION_STATE_BROADCAST, w.done()), messageType = MT_NET_EVENT)
    }

    /** ev307: the live transcript, or what the glasses are about to say themselves. */
    private fun voiceState(body: ByteArray) {
        val f = runCatching { Thrift.loads(body) }.getOrNull() ?: return
        val words = f.values.filterIsInstance<String>().firstOrNull { it.isNotBlank() && !isUuid(it) } ?: return
        say("Glasses: \"$words\"")
    }

    private fun fulfillmentRequest(l: Link, body: ByteArray) {
        if (l.m0 == null || l.s0 == null) return say("A question came before the assistant channels were up; dropped")
        val m = runCatching { Thrift.loads(body) }.getOrNull() ?: return say("An unreadable question, ${body.size} bytes ${hex(body)}")
        val interaction = m[1].asText()
        val transcript = m[2].asStruct()[1].asStruct()[1].asText()
        if (interaction.isEmpty() || !l.taken.add(interaction)) return
        if (m[11] != FOLLOW_UP_INVOCATION) l.fresh.add(interaction)
        // In a session the request has its own id; the microphone's is the session's, and it is the same turn.
        l.listening?.takeIf { it != interaction }?.let {
            l.sessionOf[interaction] = it
            l.taken.add(it)
        }
        m[8].asText().takeIf { it.length > 8 && !l.streamIds.containsKey(interaction) }?.let { l.streamIds[interaction] = it }
        l.simplified = m[30] != false
        val visual = Visual.from(m)
        say("Heard \"$transcript\" (request ${describe(body)}, understanding ${describeRead(m[2])})")
        // The glasses' own understanding of the request, e.g. IN:REPLY_MESSAGE, and its slots: fields 23 and 26, or the
        // OnDeviceIntent {1 name, 4 slots} that Meta's schema puts in 55 and in understanding.nluResult, with 23 and 26 flags.
        val nlu = m[55].asStruct().ifEmpty { m[2].asStruct()[2].asStruct()[2].asStruct() }
        val intent = m[23].asText().ifEmpty { nlu[1].asText() }
        val slots = m[26] as? Map<*, *> ?: nlu[4]
        say("Understood $intent, slots ${deep(slots)}")
        val waiting = awaitingReply?.takeIf { System.currentTimeMillis() < it.until }
        // The glasses fulfil this one themselves, by switching the session to Live AI; an answer would talk over it.
        if (intent == "IN:START_LIVE_AI") {
            say("Live AI asked for; the glasses start it themselves")
            return
        }
        // "Hey Meta" and nothing after it: the glasses close the turn at once.
        if (intent == "IN:WAKE_WORD") return
        // Meta's server answers these with an action Visor cannot write; the phone starts and stops it itself.
        if (hearing != null && intent in FOCUS_INTENTS) {
            when (intent) {
                "IN:START_CONVERSATION_FOCUS" -> hearing.start(null)
                "IN:STOP_CONVERSATION_FOCUS", "IN:PAUSE_CONVERSATION_FOCUS" -> hearing.stop()
                "IN:RESUME_CONVERSATION_FOCUS" -> hearing.resume()
            }
            say("Conversation focus: $intent")
            return
        }
        // The same for a group conversation: the glasses' group app (svc28 app 47) finds or opens the group itself.
        if (intent in GROUP_INTENTS) {
            val type = if (intent == "IN:LEAVE_GROUP_CONVERSATION") MT_STOP_APP else MT_START_APP
            connection.submit { it.sendOnService(SVC_COMPANION, type, Wire.fieldVarint(1, APP_GROUP_CONVERSATIONS)) }
            say("Group conversation: $intent")
            return
        }
        // The Display's own tools for this turn, as Meta's server names them (ev57 field 54).
        val advertised = (m[54] as? List<*>).orEmpty().flatMap { server -> (server.asStruct()[2] as? List<*>).orEmpty().map { it.asText() } }
        val glasses = if (display()) glassesToolbox(advertised) { full, input -> glassesCall(l, full, input) } else null
        when {
            // The glasses' own words for giving up.
            waiting != null && intent in CANCEL_INTENTS -> {
                awaitingReply = null
                say("Reply to ${waiting.conversation.sender} cancelled ($intent)")
            }
            // The message itself, as the glasses heard it: their content slot, else the whole utterance.
            waiting != null -> scope.launch {
                sendReply(l, interaction, transcript, waiting.conversation, slot(slots, "SL:CONTENT_EXACT").ifEmpty { transcript })
            }
            intent == "IN:REPLY_MESSAGE" -> scope.launch { replyRequested(l, interaction, transcript, slot(slots, "SL:CONTENT_EXACT")) }
            // A device action the glasses understood: they run it, and the model answers only if they do not.
            display() && intent in DEVICE_INTENTS -> scope.launch {
                if (!handOver(l, interaction, transcript, body, intent)) answer(l, interaction, transcript, visual, glasses)
            }
            // Left to the server by the glasses: the phone's player does them at once.
            phone != null && intent in MEDIA_CONTROLS -> scope.launch { mediaControl(l, interaction, transcript, phone, MEDIA_CONTROLS.getValue(intent)) }
            else -> scope.launch { answer(l, interaction, transcript, visual, glasses) }
        }
    }

    // --- replying ------------------------------------------------------------

    private suspend fun replyRequested(l: Link, interaction: String, transcript: String, content: String) {
        record(TurnRecord(interaction, System.currentTimeMillis(), transcript))
        val conversation = messaging?.lastAnnounced()
        if (conversation == null) {
            return speakTurn(l, interaction, transcript, "Tell the wearer there is no message to reply to.")
        }
        val text = content
        if (text.isEmpty()) {
            awaitingReply = AwaitingReply(conversation, System.currentTimeMillis() + REPLY_WAIT_MS)
            say("Asking what to reply to ${conversation.sender} on ${conversation.app}")
            return speakTurn(l, interaction, transcript, "Ask the wearer what they want to reply to ${conversation.sender}.")
        }
        sendReply(l, interaction, transcript, conversation, text)
    }

    private suspend fun sendReply(l: Link, interaction: String, transcript: String, conversation: Conversation, text: String) {
        awaitingReply = null
        record(TurnRecord(interaction, System.currentTimeMillis(), transcript))
        val phone = messaging
        val why = if (phone == null) "the notification listener is not running" else phone.reply(conversation, text)
        say(if (why == null) "Replied to ${conversation.sender} on ${conversation.app}: \"$text\"" else "Could not reply to ${conversation.sender}: $why")
        speakTurn(
            l, interaction, transcript,
            if (why == null) "Tell the wearer the reply to ${conversation.sender} was sent." else "Tell the wearer the reply to ${conversation.sender} could not be sent.",
        )
    }

    private suspend fun mediaControl(l: Link, interaction: String, transcript: String, phone: Phone, control: MediaControl) {
        val done = phone.control(control)
        record(TurnRecord(interaction, System.currentTimeMillis(), transcript, actions = listOf(control.action(done))))
        say(if (done.failure == null) "${control.what}: done in ${done.app.ifEmpty { "the phone's player" }}" else "${control.what} did not work: ${done.failure}")
        speakTurn(
            l, interaction, transcript,
            if (done.failure == null) "Tell the wearer in two or three words that ${control.done}."
            else "Tell the wearer briefly that it did not work: ${done.failure}.",
        )
    }

    /**
     * A tool call on the glasses: ev311 {1 jsonContent} with a JSON-RPC id, answered by ev312 with the same id. Only
     * while the turn is open, which is while the model is asked.
     */
    private suspend fun glassesCall(l: Link, full: String, input: JSONObject): ToolResult {
        val m0 = l.m0 ?: return ToolResult("the glasses are not connected", true)
        val id = l.callIds.getAndIncrement().toString()
        val reply = CompletableDeferred<String>()
        l.calls[id] = reply
        try {
            val params = JSONObject().put("name", full).put("arguments", input)
            val request = JSONObject().put("jsonrpc", "2.0").put("id", id.toInt()).put("method", "tools/call").put("params", params)
            l.mux.send(m0, netEvent(EV_MCP_REQUEST, ThriftWriter().str(1, request.toString()).done()), messageType = MT_NET_EVENT)
            val json = withTimeoutOrNull(MCP_WAIT_MS) { reply.await() }
            say(if (json == null) "The glasses did not answer $full" else "The glasses answered $full")
            return json?.let(::glassesResult) ?: ToolResult("no answer from the glasses", true)
        } finally {
            l.calls.remove(id)
        }
    }

    /**
     * A device intent the glasses understood, handed back as ev380 {1 requestId, 2 interactionId, 3 intent}, the intent
     * as they sent it. True when they ran it and the wearer hears it done; false leaves the turn to the model.
     */
    private suspend fun handOver(l: Link, interaction: String, transcript: String, body: ByteArray, intent: String): Boolean {
        val m0 = l.m0 ?: return false
        val raw = Thrift.rawStruct(body, 55) ?: return false
        val requestId = UUID.randomUUID().toString()
        val answer = CompletableDeferred<Pair<Boolean, String>>()
        l.handovers[requestId] = answer
        val (done, message) = try {
            l.mux.send(m0, netEvent(EV_REMOTE_INTENT_REQUEST, ThriftWriter().str(1, requestId).str(2, interaction).struct(3, raw).done()), messageType = MT_NET_EVENT)
            withTimeoutOrNull(INTENT_WAIT_MS) { answer.await() } ?: (false to "no answer")
        } finally {
            l.handovers.remove(requestId)
        }
        say("Glasses intent $intent handed back: ${if (done) "done" else "not done"} ($message)")
        if (!done) return false
        if (diary.turn(interaction) == null) record(TurnRecord(interaction, System.currentTimeMillis(), transcript))
        speakTurn(l, interaction, transcript, "Tell the wearer in two or three words that it is done.")
        return true
    }

    /** Speak, as this interaction's answer, a sentence the model writes from [what] in the glasses' language. */
    private suspend fun speakTurn(l: Link, interaction: String, transcript: String, what: String) {
        _phase.value = AssistantPhase.THINKING
        try {
            val text = brain.phrase(what, l.locale)
            update(interaction) { it.copy(answer = text) }
            deliver(l, interaction, transcript, text)
        } catch (error: Exception) {
            say("The reply prompt did not reach the glasses: ${error.message}")
        } finally {
            if (link === l) _phase.value = AssistantPhase.LISTENING
        }
    }

    // --- answering -----------------------------------------------------------

    private suspend fun answer(l: Link, interaction: String, transcript: String, visual: Visual, glasses: GlassesToolbox? = null) {
        val started = System.currentTimeMillis()
        record(TurnRecord(interaction, started, transcript))
        _phase.value = AssistantPhase.THINKING
        var problem = ""
        // During the Live every question comes with the picture: the wearer is showing it.
        val live = if (store.config.value.vision) runCatching { liveFrame() }.getOrNull()?.let { toModelJpeg(it) } else null
        if (live != null) say("Answering with the Live view's picture")
        val visualQuestion = live != null || (store.config.value.vision && (visual.glassesCaptured || visual.glassesWantImage || soundsVisual(transcript)))
        val image = live ?: if (visualQuestion) photo(l, interaction, visual) else null
        if (image != null) {
            val file = File(photos, "$interaction.jpg")
            runCatching {
                photos.mkdirs()
                file.writeBytes(image)
            }
            update(interaction) { it.copy(image = file.path) }
        }
        // The glasses' OCR of the photo they took comes just after the question; a model without vision has only this.
        val read = if (visual.glassesCaptured) withTimeoutOrNull(OCR_WAIT_MS) { l.ocrOf(interaction).await() } else null
        val note = listOfNotNull(
            "The wearer seems to ask about what they see, but the photo from the glasses did not arrive; say so briefly."
                .takeIf { visualQuestion && image == null && read.isNullOrBlank() },
            read?.takeIf { it.isNotBlank() }?.let { "The glasses' OCR read this text in what the wearer sees, line by line, with errors: ${it.take(MAX_OCR)}" },
        ).joinToString(" ")
        val waiting = scope.launch { holdOn(l) }
        val reply = try {
            // The glasses give up after their processing timeout; speech and streaming need the last seconds of it.
            val budget = (processingTimeoutS() ?: FACTORY_PROCESSING_S) * 1000 - DELIVERY_MS
            val left = (budget - (System.currentTimeMillis() - started)).coerceAtLeast(MIN_MODEL_MS)
            withTimeout(left) { brain.answer(transcript, l.locale, pastOf(interaction), image, note, glasses = glasses) }
        } catch (error: Exception) {
            problem = error.message ?: error::class.simpleName.orEmpty()
            Reply(fallback(l.locale, store.config.value.apiKey.isBlank() && store.config.value.provider == Provider.CLAUDE))
        }
        val text = reply.text
        // Without simplified forwarding the VARM follows the audio, and the glasses keep waiting for it until then.
        if (l.simplified) waiting.cancel()
        val modelMs = System.currentTimeMillis() - started
        if (problem.isNotEmpty()) say("The model did not answer: $problem")
        if (interaction in l.closed) {
            waiting.cancel()
            say("The glasses closed this turn before the answer was ready (${modelMs / 1000} s); not sending it")
            update(interaction) { it.copy(answer = text, problem = ANSWER_TOO_LATE, modelMs = modelMs, remembered = reply.remembered, actions = reply.actions) }
            if (link === l) _phase.value = AssistantPhase.LISTENING
            return
        }
        update(interaction) { it.copy(answer = text, problem = problem, modelMs = modelMs, remembered = reply.remembered, actions = reply.actions) }
        try {
            val sentMs = deliver(l, interaction, transcript, text)
            l.answered.add(interaction)
            l.sessionOf[interaction]?.let { l.answered.add(it) }
            update(interaction) { it.copy(sentMs = sentMs) }
        } catch (error: Exception) {
            say("The answer did not reach the glasses: ${error.message}")
            update(interaction) { it.copy(problem = error.message.orEmpty()) }
        } finally {
            waiting.cancel()
            if (link === l) _phase.value = AssistantPhase.LISTENING
        }
    }

    /**
     * While the model thinks: KeepWearableAirSessionAlive every few seconds, which the server sends to keep the
     * glasses waiting for an answer that is on its way.
     */
    private suspend fun holdOn(l: Link) {
        var seq = 1
        while (true) {
            delay(HOLD_ON_MS)
            val m0 = l.m0 ?: return
            l.mux.send(m0, netEvent(EV_KEEP_AIR_SESSION_ALIVE, ThriftWriter().str(1, "AwaitingResponse").i32(2, 20_000).i32(3, seq++).done()), messageType = MT_NET_EVENT)
        }
    }

    /** One Live AI turn: the camera's picture now, the model, and the answer spoken as [sub]. */
    private suspend fun liveAnswer(l: Link, sub: String, question: String) {
        val started = System.currentTimeMillis()
        val session = liveAi.sessionId.ifEmpty { liveAi.interaction }
        record(TurnRecord(sub, started, question, source = TurnSource.LIVE_AI, session = session, conversation = "live-$session"))
        val image = runCatching { liveFrame() }.getOrNull()?.let { toModelJpeg(it) }
        if (image != null) {
            val file = File(photos, "$sub.jpg")
            runCatching {
                photos.mkdirs()
                file.writeBytes(image)
            }
            update(sub) { it.copy(image = file.path) }
        }
        var problem = ""
        val reply = try {
            brain.answer(question, l.locale, pastOf(sub), image, liveAiNote())
        } catch (error: Exception) {
            problem = error.message ?: error::class.simpleName.orEmpty()
            Reply(fallback(l.locale, store.config.value.apiKey.isBlank() && store.config.value.provider == Provider.CLAUDE))
        }
        val text = reply.text
        update(sub) { it.copy(answer = text, problem = problem, modelMs = System.currentTimeMillis() - started, remembered = reply.remembered, actions = reply.actions) }
        try {
            deliver(l, sub, question, text)
        } catch (error: Exception) {
            say("Live AI: the answer did not reach the glasses: ${error.message}")
        }
    }

    /**
     * The photo for a visual question: inline in the request, or the file the glasses send over
     * airship. If they did not take one, ask with RemoteImageCaptureRequest (73); they answer 74
     * with the image's id and then send the file.
     */
    private suspend fun photo(l: Link, interaction: String, visual: Visual): ByteArray? {
        glassesPhotos?.take(visual.imageUuid)?.let { bytes ->
            toModelJpeg(bytes)?.let {
                say("The photo had come already on svc53, ${bytes.size / 1000} KB")
                return it
            }
        }
        visual.inline?.let { bytes ->
            toModelJpeg(bytes)?.let {
                say("The photo came inside the request, ${bytes.size / 1000} KB")
                return it
            }
        }
        val waiting = PendingImage(interaction)
        pending = waiting
        try {
            early?.takeIf { System.currentTimeMillis() - it.at < EARLY_IMAGE_MS }?.let { offerEarly(it) }
            if (waiting.image.isCompleted) return waiting.image.await()
            if (!visual.glassesCaptured) {
                val m0 = l.m0 ?: return null
                val request = ThriftWriter().bool(1, false).bool(2, true).bool(3, false).str(4, interaction).done()
                l.mux.send(m0, netEvent(EV_REMOTE_IMAGE_CAPTURE_REQUEST, request), messageType = MT_NET_EVENT)
                say("Asked the glasses for a photo")
            } else {
                say("The glasses took a photo (${visual.imageUuid.ifEmpty { visual.captureHandle }}); waiting for it")
            }
            val image = withTimeoutOrNull(PHOTO_WAIT_MS) { waiting.image.await() }
            if (image == null) say("No photo arrived in ${PHOTO_WAIT_MS / 1000} s" + if (waiting.answered) " (the glasses said they took it)" else "")
            return image
        } finally {
            if (pending === waiting) pending = null
        }
    }

    /** Hand [bytes] to the question waiting for a photo; true once it has one. */
    private fun offerImage(bytes: ByteArray, from: String): Boolean {
        val waiting = pending ?: return false
        if (waiting.image.isCompleted) return true
        val jpeg = toModelJpeg(bytes)
        if (jpeg == null) {
            say("A file of ${bytes.size / 1000} KB from $from is not an image I can read: ${hex(bytes)}")
            return false
        }
        if (waiting.image.complete(jpeg)) say("Got the photo from $from, ${bytes.size / 1000} KB")
        return true
    }

    // Used once: a later question must not get it.
    private fun offerEarly(got: EarlyImage) {
        if (offerImage(got.bytes, got.from) && early === got) early = null
    }

    /** Speak [text] as [interaction]'s answer in the voice chosen. The milliseconds of audio sent, 0 in the glasses' voice. */
    private suspend fun deliver(l: Link, interaction: String, transcript: String, text: String): Long {
        if (store.config.value.glassesVoice && !l.sessionTurn) {
            speakOnGlasses(l, interaction, transcript, text)
            return 0
        }
        val packets = voice.speak(text, l.locale)
        stream(l, interaction, transcript, text, packets)
        return packets.sumOf { it.durationUs } / 1000
    }

    /** ev71, ev52 and ev342, which open every answer. ev52 and ev342 go at a tagged type 0, as the app sends them. */
    private suspend fun preamble(l: Link, interaction: String, transcript: String, text: String) {
        val m0 = checkNotNull(l.m0)
        val e71 = ThriftWriter().str(1, interaction).done()
        val e52 = ThriftWriter().str(1, transcript.ifEmpty { text }).i32(2, 0).str(4, "").bool(7, false).str(8, interaction).done()
        val e342 = ThriftWriter().str(1, interaction).bool(2, false).str(3, REPLY_INTENT).bool(4, false).done()
        for ((type, event, body) in listOf(
            Triple(MT_NET_EVENT, EV_START_OF_USER_SPEECH, e71),
            Triple(0, EV_FINAL_TRANSCRIPTION, e52),
            Triple(0, EV_ON_PHONE_UNDERSTANDING, e342),
        )) {
            l.mux.send(m0, netEvent(event, body), messageType = type)
            delay(20)
        }
    }

    /**
     * The answer in the glasses' own voice: a VARM that streams nothing, then ev60 with the text, which is what makes
     * them speak it with their own TTS.
     */
    private suspend fun speakOnGlasses(l: Link, interaction: String, transcript: String, text: String) {
        val m0 = checkNotNull(l.m0)
        preamble(l, interaction, transcript, text)
        _phase.value = AssistantPhase.SPEAKING
        val voiceId = glassesVoice()
        val card = if (display()) answerCard(transcript.trim().take(80).ifEmpty { "Meta AI" }, text) else null
        val answer = netEvent(EV_VOICE_ACTION_RESPONSE, varm(interaction, transcript, text, onGlasses = true, voiceId = voiceId, card = card != null))
        // An answer that asks something has the glasses listen for the reply, without the wake word.
        val complete = netEvent(
            EV_MWA_VOICE_INTERACTION_COMPLETE,
            interactionComplete(interaction, text, expectsAnswer = ASKS.containsMatchIn(text), card = card),
        )
        // Without simplified forwarding the VARM is acted on as it lands, so the text must be there before it.
        for (event in if (l.simplified) listOf(answer, complete) else listOf(complete, answer)) {
            l.mux.send(m0, event, messageType = MT_NET_EVENT)
            delay(20)
        }
        val mono = System.nanoTime() / 1_000_000
        val e392 = ThriftWriter().str(1, "VoiceActionResponseMessage").i32(2, (mono and 0xFFFF).toInt()).i32(3, 1).done()
        l.mux.send(m0, netEvent(EV_KEEP_AIR_SESSION_ALIVE, e392), messageType = MT_NET_EVENT)
        say("Answered in the glasses' voice${voiceId?.let { " ($it)" }.orEmpty()}: \"$text\"")
    }

    /**
     * The answer, in the app's order: ev71, ev52, ev342, ev388; the stream header and the first
     * audio message; the VARM; the rest of the audio as bare frames in real time; 6000; ev389, ev392.
     */
    private suspend fun stream(l: Link, interaction: String, transcript: String, text: String, packets: List<OpusPacket>) {
        val mux = l.mux
        val m0 = checkNotNull(l.m0)
        val s0 = checkNotNull(l.s0)
        preamble(l, interaction, transcript, text)
        val mono = System.nanoTime() / 1_000_000
        val e388 = ThriftWriter().i64(1, System.currentTimeMillis()).i64(2, mono).str(3, interaction).done()
        mux.send(m0, netEvent(EV_TTS_START_INFO, e388), messageType = MT_NET_EVENT)
        delay(20)

        // Field 1 is the stream the glasses allocated. Without it they drop the stream silently.
        val streamId = l.streamIds[interaction] ?: l.latestStream
        val header = (if (streamId.isNotEmpty()) Wire.fieldBytes(1, streamId.toByteArray()) else ByteArray(0)) +
            Wire.fieldBytes(2, interaction.toByteArray()) + Wire.fieldVarint(4, 1) + Wire.fieldVarint(7, 1) + Wire.fieldVarint(19, 1)
        if (streamId.isEmpty()) say("No stream id from the glasses for this answer; it may not play")
        mux.send(s0, header, messageType = MT_STREAM_HEADER)

        _phase.value = AssistantPhase.SPEAKING
        val answer = netEvent(EV_VOICE_ACTION_RESPONSE, varm(interaction, transcript, text))
        val start = System.nanoTime()
        var dueUs = 0L
        for ((i, chunk) in packets.chunked(2).withIndex()) {
            val frame = chunk.fold(ByteArray(0)) { acc, p ->
                acc + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(p.data.size).array() + p.data
            }
            if (i == 0) {
                mux.send(s0, frame, messageType = MT_STREAM_AUDIO)
                // The VARM goes after the first audio message, never before; without simplified forwarding it would
                // end the turn on arrival, so it waits for the end of the audio.
                if (l.simplified) mux.send(m0, answer, messageType = MT_NET_EVENT)
            } else {
                // A bare header: tagged with type 0, only the first 40 ms would ever play.
                mux.send(s0, frame)
            }
            dueUs += chunk.sumOf { it.durationUs }
            val wait = dueUs / 1000 - (System.nanoTime() - start) / 1_000_000
            if (wait > 0) delay(wait)
        }
        mux.send(s0, ByteArray(0), messageType = MT_STREAM_END)
        if (!l.simplified) mux.send(m0, answer, messageType = MT_NET_EVENT)

        val e389 = ThriftWriter().i64(1, System.currentTimeMillis()).i64(2, mono).i64(3, 7000).str(4, interaction).done()
        val e392 = ThriftWriter().str(1, "VoiceActionResponseMessage").i32(2, (mono and 0xFFFF).toInt()).i32(3, 1).done()
        for ((event, body) in listOf(EV_TTS_END_INFO to e389, EV_KEEP_AIR_SESSION_ALIVE to e392)) {
            mux.send(m0, netEvent(event, body), messageType = MT_NET_EVENT)
            delay(20)
        }
        say("Answered: \"$text\"")
    }

    /** 5005: `<streamId>_<interactionId>_<pcmBytes>_<ms>`, 96 bytes of PCM per millisecond played. */
    private fun streamStatus(text: String) {
        val parts = text.split("_")
        if (parts.size < 4) return say("Assistant: stream status $text")
        val ms = parts.last().toLongOrNull() ?: return
        val pcm = parts[parts.size - 2].toLongOrNull() ?: return
        val interaction = parts.subList(1, parts.size - 2).joinToString("_")
        update(interaction) { it.copy(playedMs = pcm / 96) }
        if (pcm < ms * 96 - 960) say("The glasses played only ${pcm / 96} ms of $ms ms")
    }

    private fun fallback(locale: Locale, noKey: Boolean): String {
        val (none, sorry) = FALLBACKS[locale.language] ?: FALLBACKS.getValue("en")
        return if (noKey) none else sorry
    }

    /** Ask the model from the phone, without the glasses or the diary. */
    suspend fun ask(question: String): String = brain.answer(question, locale).text

    /**
     * A question typed or dictated in the app, answered there as text. It continues [conversation] if given, else
     * the open one. The id of its turn.
     */
    fun askFromPhone(question: String, conversation: String? = null): String {
        val id = UUID.randomUUID().toString()
        record(TurnRecord(id, System.currentTimeMillis(), question.trim(), source = TurnSource.PHONE, conversation = conversation.orEmpty()))
        scope.launch { answerHere(id) }
        return id
    }

    private fun liveAiNote(): String = if (liveAiDetailed()) LIVE_AI_NOTE_DETAILED else LIVE_AI_NOTE

    /** [id]'s question again, with the model and settings as they are now; the new answer replaces the old one. */
    fun askAgain(id: String) {
        if (diary.turn(id) == null) return
        diary.update(id) { it.copy(answer = "", problem = "", remembered = emptyList(), actions = emptyList(), modelMs = 0) }
        scope.launch { answerHere(id) }
    }

    /** The conversation a question asked now from the app would continue, or null when it would start a new one. */
    fun openConversation(): String? {
        val last = diary.turns.value.lastOrNull { it.source != TurnSource.LIVE_AI } ?: return null
        return last.conversation.takeIf { System.currentTimeMillis() - last.at <= windowMs() }
    }

    private suspend fun answerHere(id: String) {
        val turn = diary.turn(id) ?: return
        val started = System.currentTimeMillis()
        val image = turn.image.takeIf { it.isNotEmpty() }?.let { runCatching { File(it).readBytes() }.getOrNull() }
        val phone = turn.source == TurnSource.PHONE
        try {
            val reply = withTimeout(PHONE_BUDGET_MS) {
                brain.answer(
                    turn.question, if (phone) phoneLocale() else locale, pastOf(id), image,
                    if (turn.source == TurnSource.LIVE_AI) liveAiNote() else "", if (phone) Audience.PHONE else Audience.GLASSES,
                )
            }
            diary.update(id) { it.copy(answer = reply.text, problem = "", remembered = reply.remembered, actions = reply.actions, modelMs = System.currentTimeMillis() - started) }
        } catch (error: Exception) {
            val why = if (error is kotlinx.coroutines.TimeoutCancellationException) "no answer in ${PHONE_BUDGET_MS / 1000} s" else error.message ?: error::class.simpleName.orEmpty()
            say("The model did not answer in the app: $why")
            diary.update(id) { it.copy(problem = why, modelMs = System.currentTimeMillis() - started) }
        }
    }

    /** [date]'s lines, written again if the day has exchanges they do not cover yet and its last conversation has closed. */
    fun writeLines(date: LocalDate) {
        val turns = diary.turns.value.filter { it.date == date && it.answer.isNotBlank() && (it.problem.isEmpty() || it.problem == ANSWER_TOO_LATE) }
        if (turns.isEmpty()) return
        val lastAt = turns.maxOf { it.at }
        val written = diary.lines.value[date]
        if (written != null && written.writtenAt > lastAt) return
        if (System.currentTimeMillis() - lastAt < windowMs() || linesFailed[date] == lastAt) return
        val c = store.config.value
        if (c.apiKey.isBlank() && c.provider == Provider.CLAUDE) return
        if (!writing.add(date)) return
        scope.launch {
            try {
                val text = turns.joinToString("\n\n") { t ->
                    val how = when (t.source) {
                        TurnSource.PHONE -> " (written in the app)"
                        TurnSource.LIVE_AI -> " (during Live AI, showing the camera)"
                        TurnSource.GLASSES -> if (t.image.isNotEmpty()) " (with a photo of what they were looking at)" else ""
                    }
                    "Q$how: ${t.question}\nA: ${t.answer}"
                }
                diary.setLines(date, brain.dayLines(text, phoneLocale()))
                linesFailed.remove(date)
            } catch (error: Exception) {
                linesFailed[date] = lastAt
                say("The day's lines for $date were not written: ${error.message}")
            } finally {
                writing.remove(date)
            }
        }
    }

    private fun windowMs(): Long = store.config.value.memoryMinutes * 60_000L

    /** The app's language: what the phone's own words are in. */
    private fun phoneLocale(): Locale = context.resources.configuration.locales[0]

    /** What the model has heard of [id]'s conversation before it. */
    private fun pastOf(id: String): List<Exchange> {
        val conversation = diary.turn(id)?.conversation ?: return emptyList()
        return diary.past(conversation, before = id).map { Exchange(it.question, it.answer, it.actions) }
    }

    /** Into the diary, in the open conversation unless it names its own. */
    private fun record(turn: TurnRecord) {
        if (turn.conversation.isNotEmpty()) return diary.record(turn)
        // A follow-up continues the open conversation; a new "Hey Meta" only if it comes right after the last turn.
        val window = if (link?.fresh?.contains(turn.interactionId) == true) minOf(windowMs(), FRESH_WINDOW_MS) else windowMs()
        diary.record(turn.copy(conversation = diary.conversationAt(turn.at, window)))
    }

    private fun update(interaction: String, change: (TurnRecord) -> TurnRecord) = diary.update(interaction, change)

    private companion object {
        // What the glasses say when there is no model, and when the model fails, in each of their languages.
        val FALLBACKS = mapOf(
            "en" to ("Visor is connected, but no AI model is set up yet. Add an API key in the app." to "Sorry, I couldn't get an answer."),
            "it" to ("Visor è collegato, ma non c'è ancora un modello. Aggiungi una chiave API nell'app." to "Scusa, non sono riuscito a ottenere una risposta."),
            "es" to ("Visor está conectado, pero aún no hay ningún modelo configurado. Añade una clave de API en la app." to "Lo siento, no he podido obtener una respuesta."),
            "fr" to ("Visor est connecté, mais aucun modèle n'est encore configuré. Ajoutez une clé API dans l'application." to "Désolé, je n'ai pas pu obtenir de réponse."),
            "de" to ("Visor ist verbunden, aber es ist noch kein Modell eingerichtet. Füge in der App einen API-Schlüssel hinzu." to "Entschuldigung, ich konnte keine Antwort bekommen."),
            "pt" to ("O Visor está conectado, mas ainda não há um modelo configurado. Adicione uma chave de API no app." to "Desculpe, não consegui obter uma resposta."),
            "nl" to ("Visor is verbonden, maar er is nog geen model ingesteld. Voeg een API-sleutel toe in de app." to "Sorry, ik kon geen antwoord krijgen."),
            "tr" to ("Visor bağlı, ancak henüz bir model ayarlanmadı. Uygulamada bir API anahtarı ekleyin." to "Üzgünüm, bir yanıt alamadım."),
            "hi" to ("Visor कनेक्ट है, लेकिन अभी कोई मॉडल सेट नहीं है। ऐप में एक API कुंजी जोड़ें।" to "माफ़ कीजिए, मुझे जवाब नहीं मिल सका।"),
            "ja" to ("Visorは接続されていますが、まだモデルが設定されていません。アプリでAPIキーを追加してください。" to "すみません、回答を取得できませんでした。"),
            "ko" to ("Visor가 연결되었지만 아직 모델이 설정되지 않았습니다. 앱에서 API 키를 추가하세요." to "죄송합니다. 답변을 받지 못했습니다."),
            "zh" to ("Visor 已连接，但还没有设置模型。请在应用中添加 API 密钥。" to "抱歉，我没能获得回答。"),
        )
        const val FACTORY_PROCESSING_S = 20L
        const val DELIVERY_MS = 4_000L
        const val HOLD_ON_MS = 3_000L
        /** How long the glasses' own recognition gets to send its ev57 before the server's answer goes instead. */
        const val OWN_RECOGNITION_GRACE_MS = 1_200L
        const val MIC_END_WAIT_MS = 10_000L
        const val SESSION_USE_CASE = 7L
        const val REGISTER_AGAIN_MS = 2_500L
        val CHANNEL_CLOSED = Regex("service 15 answered status 0xc0(?:02|10) on channel 0x([0-9a-f]{4})")
        const val QUESTION_GRACE_MS = 3_000L
        // ev57 field 11, invocationMethod: 1 for the wake word, 3 for a follow-up the glasses listened for.
        const val FOLLOW_UP_INVOCATION = 3L
        const val FRESH_WINDOW_MS = 2 * 60_000L
        val WAKE_WORD_ONLY = Regex("^(hey|hi|ehi|ei|ok|okay)?[ ,]*(meta|mata|metta|matta|meda)[.!?]*$", RegexOption.IGNORE_CASE)
        const val MIN_MODEL_MS = 6_000L
        const val PHOTO_WAIT_MS = 8_000L
        const val MCP_WAIT_MS = 8_000L
        const val INTENT_WAIT_MS = 5_000L
        // The "Sharing" photo came 0.2 s before its question (issue #18); a low-res one is 13 KB.
        const val EARLY_IMAGE_MS = 10_000L
        const val EARLY_MAX_BYTES = 2_000_000L
        // ev65 came 0.14, 0.45 and 1.2 s after the question.
        const val OCR_WAIT_MS = 2_500L
        const val MAX_OCR = 4_000

        // How long a reply asked for waits for its words.
        const val REPLY_WAIT_MS = 30_000L
        const val PHONE_BUDGET_MS = 60_000L
        // After a conversation closes, a moment before its day's lines are written.
        const val LINES_GRACE_MS = 2_000L
    }
}

/** 1026 is either the bare locale string or `{1: locale}`. */
private val CANCEL_INTENTS = setOf("IN:CANCEL", "IN:NEVER_MIND", "IN:STOP", "IN:NEGATE_INTENT", "IN:CANCEL_MESSAGE")

/** The glasses' own device intents Visor hands back to them (ev380) on a Display: volume and brightness only. */
private val DEVICE_INTENTS = setOf(
    "IN:SET_VOLUME", "IN:INCREASE_VOLUME", "IN:DECREASE_VOLUME", "IN:MUTE_VOLUME", "IN:UNMUTE_VOLUME",
    "IN:SET_BRIGHTNESS", "IN:INCREASE_BRIGHTNESS", "IN:DECREASE_BRIGHTNESS", "IN:MAXIMIZE_BRIGHTNESS",
)

/** The text of [name] in the glasses' slot map (field 26): each slot is a list of {1 name, 4 text, 5 start, 6 end}. */
internal fun slot(slots: Any?, name: String): String =
    ((slots as? Map<*, *>)?.get(name) as? List<*>)?.firstOrNull().asStruct()[4].asText().trim()

private fun parseLocale(payload: ByteArray): Locale {
    val text = if (payload.size > 2 && payload[0] == 0x0a.toByte() && payload[1].toInt() == payload.size - 2) {
        payload.copyOfRange(2, payload.size).decodeToString()
    } else {
        payload.decodeToString()
    }
    return Locale.forLanguageTag(text.trim().replace('_', '-')).takeIf { it.language.isNotEmpty() } ?: Locale.US
}

private fun isUuid(s: String) = s.length == 36 && s.count { it == '-' } == 4

private fun hex(b: ByteArray): String = b.take(24).joinToString("") { "%02x".format(it) }

/** A Thrift struct in one line: scalars and short strings shown, structs by their field ids. */
internal fun describe(body: ByteArray): String {
    val f = runCatching { Thrift.loads(body) }.getOrNull() ?: return hex(body)
    return describeRead(f)
}

/** Every value, nested, for reading what the glasses' understanding carries. */
internal fun deep(value: Any?, depth: Int = 0): String = when {
    depth > 6 -> "…"
    value is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (k, v) -> "$k=${deep(v, depth + 1)}" }
    value is List<*> -> value.joinToString(", ", "[", "]") { deep(it, depth + 1) }
    value is String -> "\"" + value.take(120) + "\""
    value is ByteArray -> runCatching { deep(Thrift.loads(value), depth + 1) }.getOrElse { "${value.size}B" }
    else -> value.toString()
}

private fun describeRead(value: Any?): String {
    val f = value.asStruct()
    return f.entries.joinToString(" ", "{", "}") { (k, v) ->
        k.toString() + "=" + when (v) {
            is String -> if (isUuid(v)) "uuid" else "\"" + v.take(40) + "\""
            is Map<*, *> -> "{" + v.keys.joinToString(",") + "}"
            is List<*> -> "[${v.size}]"
            is ByteArray -> "${v.size}B"
            else -> v.toString()
        }
    }
}

/** The problem of an answer that came after the glasses had closed its turn: kept, never spoken. */
const val ANSWER_TOO_LATE = "too late for the glasses"

/** The problem of a turn the glasses did themselves, with no answer from Visor. */
const val ON_GLASSES = "done on the glasses"

/** An answer that ends on a question. */
private val ASKS = Regex("""[?？؟][\s"'”’»)]*$""")

/** What the glasses understand as a request for conversation focus. */
private val FOCUS_INTENTS = setOf(
    "IN:START_CONVERSATION_FOCUS", "IN:STOP_CONVERSATION_FOCUS", "IN:PAUSE_CONVERSATION_FOCUS", "IN:RESUME_CONVERSATION_FOCUS",
)

/** What the glasses understand as pausing, resuming or skipping what plays. */
private val MEDIA_CONTROLS = mapOf(
    "IN:PAUSE_MUSIC" to MediaControl.PAUSE, "IN:PAUSE_MEDIA" to MediaControl.PAUSE,
    "IN:PAUSE_PODCAST" to MediaControl.PAUSE, "IN:PAUSE_AUDIOBOOK" to MediaControl.PAUSE,
    "IN:RESUME_MUSIC" to MediaControl.RESUME, "IN:RESUME_MEDIA" to MediaControl.RESUME, "IN:RESUME_PODCAST" to MediaControl.RESUME,
    "IN:STOP_MUSIC" to MediaControl.STOP, "IN:STOP_MEDIA" to MediaControl.STOP,
    "IN:STOP_PODCAST" to MediaControl.STOP, "IN:STOP_AUDIOBOOK" to MediaControl.STOP,
    "IN:SKIP_TRACK_MUSIC" to MediaControl.NEXT, "IN:PLAY_NEXT_SONG" to MediaControl.NEXT,
    "IN:PREVIOUS_TRACK_MUSIC" to MediaControl.PREVIOUS, "IN:PLAY_PREVIOUS_SONG" to MediaControl.PREVIOUS,
    "IN:REPLAY_MUSIC" to MediaControl.RESTART, "IN:START_OVER_MEDIA" to MediaControl.RESTART,
)

/** What the glasses understand as starting, joining or leaving a group conversation. */
private val GROUP_INTENTS = setOf("IN:START_GROUP_CONVERSATION", "IN:JOIN_GROUP_CONVERSATION", "IN:LEAVE_GROUP_CONVERSATION")

private const val SVC_COMPANION = 28
private const val MT_START_APP = 2000
private const val MT_STOP_APP = 2002
private const val APP_GROUP_CONVERSATIONS = 47L

/** ev289's mode for a Live AI session. */
private const val MODE_LIVE_AI = 2L

/** The most a NetEvent goes as in one piece: an AirShield frame holds 255 blocks, and the glasses send 1 KB pieces. */
private const val SEGMENT_BYTES = 1_024

private const val LIVE_AI_NOTE =
    "This is Live AI: a continuous conversation while the wearer shows you what they see. The image is the camera's " +
        "view right now. Answer briefly and naturally; speak only about what matters to the question."

private const val LIVE_AI_NOTE_DETAILED =
    "This is Live AI: a continuous conversation while the wearer shows you what they see. The image is the camera's " +
        "view right now. The wearer asked for detailed answers: explain fully, with the details you can see, still in speech."

/** The camera stream Live AI sees through. */
interface LiveAiCamera {
    fun on()
    fun off()

    /** True once frames come. */
    val frames: kotlinx.coroutines.flow.Flow<Boolean>
}
