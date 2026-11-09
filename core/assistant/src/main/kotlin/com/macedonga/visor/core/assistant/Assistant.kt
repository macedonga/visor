package com.macedonga.visor.core.assistant

import android.content.Context
import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.assistant.mcp.ToolResult
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.SVC_STATUS
import com.macedonga.visor.core.connection.SnApp
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

enum class AssistantPhase { OFF, WAITING, REGISTERING, LISTENING, THINKING, SPEAKING }

/** "Hey Meta" answered by this phone: svc15 registration on each link, then every turn answered with the user's model. */
class Assistant(
    private val context: Context,
    private val connection: GlassesConnection,
    private val voice: Voice,
    private val brain: Brain,
    private val store: AssistantConfigStore,
    airship: AirshipReceiver,
    /** Every turn, kept by day, with the photos the answers were given. */
    val diary: Diary,
    /** The Live view's Wi-Fi session while there is one: "Hey Meta" keeps working on it. */
    liveSession: Flow<DataXConnection?>,
    /** The Live view's picture now, when one runs: the image for every question asked meanwhile. */
    private val liveFrame: suspend () -> ByteArray?,
    /** The camera stream for Live AI: on, off, and whether frames are coming. */
    private val camera: LiveAiCamera,
    /** The photos the glasses take for questions, on svc53. */
    private val glassesPhotos: GlassesPhotos,
    /** The voice the glasses speak in (assistant_voice_id), for answers in their own voice. */
    private val glassesVoice: () -> String?,
    /** The wearer asked the glasses for detailed Live AI answers (detailed_responses_live_ai_enabled). */
    private val liveAiDetailed: () -> Boolean,
    /** Conversation focus, which the phone starts when the wearer asks for it by voice. */
    private val hearing: Hearing,
    /** How long the glasses wait for an answer (assistant_on_sn.default_processing_timeout_s), when known. */
    private val processingTimeoutS: () -> Long?,
    /** The phone's player, for pausing, resuming and skipping by voice. */
    private val phone: Phone,
    /** The connected glasses have a display: each answer then comes with a card for it. */
    private val display: () -> Boolean,
    private val cameraAction: suspend (CameraAction) -> Boolean,
    /** Apps that answer the questions naming them, through the VDK. */
    private val apps: AppVoices,
) : Responder {

    // A send on a channel the glasses closed, or after the link went, throws: it is logged, not a crash.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> say("Failed: $e") })

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
     * A NetEvent onto the session, at [type]. As the Meta app sends them: over 1 KB, to glasses of assistant protocol 1
     * or later, as a segmented message, a header (1002) {1 type, 2 size, 3 id} then pieces (1003) {1 bytes, 2 id, 3 last}.
     */
    private suspend fun sendNetEvent(l: Link, m0: Int, event: ByteArray, type: Int = MT_NET_EVENT) {
        if (event.size <= SEGMENT_BYTES || l.protocol == 0) return l.mux.send(m0, event, messageType = type)
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
            sendNetEvent(l, m0, netEvent(id, body))
        }

        override suspend fun answer(subInteraction: String, question: String) {
            link?.let { liveAnswer(it, subInteraction, question) }
        }

        override fun cameraOn() {
            camera.on()
        }

        override fun cameraOff() {
            camera.off()
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
     * The glasses opened their microphone for [micInteraction]. They recognise a "Hey Meta" question themselves and
     * send it as ev57; a follow-up after an answer they leave to the server. Both are heard here, and an utterance
     * is answered only if no ev57 for it has come a moment after it ended.
     */
    private fun listen(l: Link, micInteraction: String) {
        if (l.partnerSessions[micInteraction]?.let(l::ended) == true) return
        l.listening?.let { l.partnerSessions[it] }?.takeIf { it.id != micInteraction }?.let(l::end)
        ears?.stop()
        val ended = CompletableDeferred<Unit>().also { micEnded = it }
        val heard = CompletableDeferred<Unit>().also { l.recognitionDone = it }
        l.listening = micInteraction
        if (micInteraction.isEmpty() || !store.config.value.enabled) return
        say("Listening to the microphone for $micInteraction")
        // The question as the phone hears it, every utterance before the answer: the recogniser can split it, and its
        // first piece can be the wake word heard as words of the wearer's language.
        val words = CopyOnWriteArrayList<String>().also { l.words[micInteraction] = it }
        val asked = { words.joinToString(" ").trim() }
        ears = GlassesEars(
            context, spoken(l),
            onUtterance = utterance@ { text, startMs, endMs ->
                val partner = l.partnerSessions[micInteraction]
                if (partner != null) {
                    partnerUtterance(l, partner, text, startMs, endMs)
                    return@utterance
                }
                if ((l.foreign || micInteraction !in l.answered) && !WAKE_WORD_ONLY.matches(text.trim())) words += text
                scope.launch {
                    val interaction = l.partnerInteraction(micInteraction)
                    if (interaction !in l.partnerTurns) delay(OWN_RECOGNITION_GRACE_MS)
                    if (interaction in l.closed) return@launch
                    when {
                        // The question itself, which the glasses recognised and sent as ev57.
                        interaction in l.claimed && interaction !in l.answered -> Unit
                        // After an answer: a follow-up, a sub-interaction of its own as the server opens one.
                        interaction in l.answered -> {
                            val sub = UUID.randomUUID().toString()
                            say("Heard \"$text\" myself: a follow-up, answered as $sub")
                            val start = netEvent(EV_START_OF_USER_SPEECH, ThriftWriter().str(1, sub).i64(2, startMs).done())
                            runCatching { l.mux.send(checkNotNull(l.m0), start, messageType = MT_NET_EVENT) }
                                .onFailure { return@launch say("The follow-up did not reach the glasses: ${it.message}") }
                            l.claimed.add(sub)
                            answer(l, sub, text, Visual.NONE)
                        }
                        // Nothing came from the glasses: the whole question was left to the server. Their own
                        // transcript comes only after their stream ends, so wait for that, and never answer the wake word.
                        else -> {
                            if (WAKE_WORD_ONLY.matches(text.trim())) return@launch
                            if (interaction !in l.partnerTurns) {
                                withTimeoutOrNull(MIC_END_WAIT_MS) { ended.await() }
                                delay(QUESTION_GRACE_MS)
                            }
                            if (link !== l || interaction in l.closed || !l.claimed.add(interaction)) return@launch
                            l.fresh.add(interaction)
                            val question = asked().ifEmpty { text }
                            l.words.remove(micInteraction)
                            say("Heard \"$question\" myself: the glasses left this question to the server")
                            answer(l, interaction, question, Visual.NONE)
                        }
                    }
                }
            },
            onProblem = ::say,
            onDone = {
                heard.complete(Unit)
                // With a speech language set, the question waits for these words instead of the glasses'.
                if (store.config.value.speechLanguage.isNotEmpty() && chosenLocale() == null) {
                    l.heard.getOrPut(micInteraction) { CompletableDeferred() }.complete(asked())
                }
            },
        ).also { it.start() }
    }

    /** Live AI, while the glasses run a session with this phone as its server. */
    val liveAiPhase: StateFlow<LiveAiPhase> = liveAi.phase

    private fun spoken(l: Link): Locale =
        chosenLocale() ?: store.config.value.speechLanguage.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag) ?: l.locale

    /** The language of the confirmed glasses voice, else the glasses' from their 1026. */
    private val locale: Locale get() = link?.locale ?: chosenLocale() ?: Locale.US

    private fun chosenLocale(): Locale? = glassesVoice()?.takeIf { it.startsWith("3p_") }?.removePrefix("3p_")?.substringBefore('.')
        ?.replace('_', '-')?.let(Locale::forLanguageTag)?.takeIf { it.language.isNotEmpty() }

    @Volatile
    private var link: Link? = null

    /** A 3p session: its wake check and its turn now; answered and closed are the link's sets. */
    private class PartnerSession(val id: String, val initialInteraction: String) {
        val wake = CompletableDeferred<Boolean>()
        @Volatile var microphoneEnded = false
        @Volatile var interaction = initialInteraction
        var lastSpeechEnd = -1L
        // The segments of a turn that is waiting for its last ones before it is answered.
        var joining: MutableList<String>? = null
    }

    private suspend fun awaitPartnerWake(l: Link, interaction: String): Boolean {
        val session = l.partnerSession(interaction) ?: return interaction !in l.partnerTurns
        if (link !== l || l.ended(session) || interaction in l.closed) return false
        val verified = withTimeoutOrNull(WAKE_VERIFICATION_WAIT_MS) { session.wake.await() }
        if (verified != true) {
            l.end(session)
            say("3p: wake was not verified for session ${session.id}; dropped")
            return false
        }
        return link === l && l.partnerCanAnswer(interaction)
    }

    private fun partnerUtterance(l: Link, session: PartnerSession, text: String, startMs: Long, endMs: Long) {
        if (link !== l || WAKE_WORD_ONLY.matches(text.trim())) return
        val words = withoutWakeWord(text)
        if (words.isEmpty()) return
        // A turn still waiting for its last segments takes this one too.
        synchronized(session) {
            session.joining?.let { held ->
                held += words
                session.lastSpeechEnd = endMs
                return
            }
        }
        val interaction = l.claim(session, endMs) ?: return say("Dropped while answering: $text")
        l.sessionOf[interaction] = session.id
        l.partnerTurns.add(interaction)
        if (interaction == session.initialInteraction) l.fresh.add(interaction)
        val held = mutableListOf(words)
        synchronized(session) { session.joining = held }
        scope.launch {
            try {
                // The question's other segments come within this grace; they are joined into it.
                delay(PARTNER_JOIN_MS)
                val question = synchronized(session) { session.joining = null; held.joinToString(" ") }
                if (!awaitPartnerWake(l, interaction)) return@launch
                if (interaction != session.initialInteraction) {
                    if (session.microphoneEnded) return@launch
                    val m0 = l.m0 ?: return@launch
                    l.mux.send(m0, netEvent(EV_START_REMOTE_INTERACTION, ThriftWriter().str(1, interaction).done()), messageType = MT_NET_EVENT)
                    l.mux.send(m0, netEvent(EV_START_OF_USER_SPEECH, ThriftWriter().str(1, interaction).i64(2, startMs).done()), messageType = MT_NET_EVENT)
                    l.speechStarted.add(interaction)
                }
                say("Heard \"$question\" myself in verified 3p session ${session.id}, turn $interaction")
                answer(l, interaction, question, Visual.NONE)
            } catch (cancel: CancellationException) {
                synchronized(session) { if (session.joining === held) session.joining = null }
                l.end(session)
                throw cancel
            } catch (error: Exception) {
                l.end(session)
                say("3p: could not handle turn $interaction: ${error.message}")
            }
        }
    }

    /** [text] without a wake phrase at its start, such as "Hey Meta," or "Хей, мета". */
    private fun withoutWakeWord(text: String): String = text.trim().replace(WAKE_WORD_START, "").trim()

    /** svc15 state for one Bluetooth session. */
    private inner class Link(val mux: DataXConnection) {
        // Whether the current turn is a session (SCI) one, which only plays streamed answers.
        @Volatile var sessionTurn = false
        // The interaction the microphone is open for, and each request's session when the two differ.
        @Volatile var listening: String? = null
        val sessionOf = ConcurrentHashMap<String, String>()
        @Volatile var m0: Int? = null
        @Volatile var s0: Int? = null
        // The assistant protocol version from their init answer; 0 takes no segmented messages.
        @Volatile var protocol = 0
        val m0Answered = CompletableDeferred<Unit>()
        val s0Answered = CompletableDeferred<Unit>()
        // The stream id the glasses allocate per interaction, from their own 5002.
        val streamIds = ConcurrentHashMap<String, String>()
        @Volatile var latestStream = ""
        // Interactions whose ev71 has gone out.
        val speechStarted: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
        val partnerTurns: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
        val partnerSessions = ConcurrentHashMap<String, PartnerSession>()
        @Volatile var currentPartner: PartnerSession? = null
        fun partnerInteraction(id: String): String = partnerSessions[id]?.initialInteraction ?: id
        fun partnerSession(id: String): PartnerSession? = partnerSessions[sessionOf[id] ?: id]
        fun partnerCanAnswer(id: String): Boolean = partnerSession(id)?.let {
            verified(it) && it.interaction == id && id !in closed
        } ?: (id !in partnerTurns)
        fun ended(s: PartnerSession) = s.id in closed
        fun verified(s: PartnerSession) = s.wake.isCompleted && !ended(s)
        fun end(s: PartnerSession) {
            closed.add(s.id)
            s.microphoneEnded = true
            s.wake.complete(false)
        }
        // A start that cannot be served: closed and marked claimed, so no path answers it.
        fun drop(id: String) {
            closed += id
            claimed += id
            speechStarted += id
        }
        /** The turn for an utterance ending at [endMs]: the first, then one more once the last is answered and closed. */
        fun claim(s: PartnerSession, endMs: Long): String? = synchronized(s) {
            val first = s.initialInteraction !in claimed
            val busy = !first && (s.interaction !in answered || s.interaction !in closed || s.microphoneEnded)
            if (ended(s) || busy || endMs <= s.lastSpeechEnd) return null
            s.interaction = if (first) s.initialInteraction else UUID.randomUUID().toString()
            s.lastSpeechEnd = endMs
            claimed.add(s.interaction)
            s.interaction
        }
        // Interactions whose answer has been spoken: what is said after is a follow-up.
        val answered: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
        // Interactions the glasses have closed: an answer that comes later would talk over the next one.
        val closed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
        // Interactions an answer path has claimed: no other path answers them.
        val claimed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
        // Questions asked with the wake word rather than as a follow-up: they continue a conversation only right after it.
        val fresh: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
        // What the phone heard on each microphone stream, for a speech language the glasses do not have.
        val heard = ConcurrentHashMap<String, CompletableDeferred<String>>()
        val words = ConcurrentHashMap<String, MutableList<String>>()
        // The glasses' assistant language, from their 1026.
        @Volatile var glassesLocale: Locale = Locale.US
        val locale: Locale get() = chosenLocale() ?: glassesLocale
        // A partner (3p_) voice: the phone hears and speaks for the glasses.
        val foreign: Boolean get() = glassesVoice()?.startsWith("3p_") == true
        // The end of the microphone's current stream as the phone hears it.
        @Volatile var recognitionDone = CompletableDeferred<Unit>()
        // ev57's field 30, MobileConfig assistant_on_sn.simplify_mwa_message_forwarding. Off (the factory value),
        // the glasses act on a VARM the moment it lands and end the turn there.
        @Volatile var simplified = true
        // Segmented messages being put back together, by id: their type, size and bytes so far.
        val segments = ConcurrentHashMap<String, Triple<Int, Int, ByteArrayOutputStream>>()
        // The text the glasses read in each question's photo, from their ev65.
        private val ocr = ConcurrentHashMap<String, CompletableDeferred<String>>()
        fun ocrOf(interaction: String): CompletableDeferred<String> = ocr.getOrPut(interaction) { CompletableDeferred() }
        // The glasses' tool calls waiting for their ev312, by JSON-RPC id; and device intents waiting for their ev381.
        val calls = ConcurrentHashMap<String, CompletableDeferred<String>>()
        val handovers = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, String>>>()
        val callIds = AtomicInteger(1)

        // The glasses' tool descriptions from tools/list, by name, and when to ask again after no answer.
        val described = ConcurrentHashMap<String, ToolDescription>()
        @Volatile var describeAgainAt = 0L
        // The Display's interactions for questions typed in the app, to the app's turn for each.
        val typedOf = ConcurrentHashMap<String, String>()
        // Each start interaction's MMAI_ONFRAME_KILLSWITCH (field 41), for the photo log.
        val onFrameKillswitch = ConcurrentHashMap<String, Any>()
        // Questions typed or handwritten on the Display (ev341): no microphone, so no stream id for the phone's voice.
        val written: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    }

    /** A photo this side is waiting for, for one interaction. */
    private class PendingImage(val interaction: String) {
        val image = CompletableDeferred<ByteArray>()
        // What came while the photo was awaited, by source, for the log when none did.
        private val seen = ConcurrentHashMap<String, Int>()
        fun saw(source: String) { seen.merge(source, 1) { a, b -> a + b } }
        fun count(source: String): Int = seen[source] ?: 0
    }

    @Volatile
    private var pending: PendingImage? = null

    /** A photo that came over airship with no question waiting: some pairs send it just before the question. */
    private class EarlyImage(val bytes: ByteArray, val from: String)

    @Volatile
    private var early: EarlyImage? = null

    /** The phone's messaging, for replies by voice; set by the notification listener. */
    @Volatile
    var messaging: Messaging? = null

    // A reply asked for and not yet dictated: the next request's words are its text.
    private class AwaitingReply(val conversation: Conversation, val until: Long)

    @Volatile
    private var awaitingReply: AwaitingReply? = null

    /** A question typed in the app and sent to the Display: started on its 1023, taken by whoever completes [asked] first. */
    private class Typed(val id: String, val text: String) {
        val started = CompletableDeferred<Unit>()
        val asked = CompletableDeferred<Unit>()
    }

    @Volatile
    private var typed: Typed? = null

    init {
        // The assistant's photo comes over airship, in a namespace the media import refuses.
        airship.extras += object : ExtraFiles {
            override fun wants(offer: Offer): Boolean {
                val namespace = offer.namespace
                val size = offer.size
                pending?.saw("airship")
                val waiting = (pending != null && size < PHOTO_MAX_BYTES) ||
                    (namespace == NAMESPACE_SHARING && store.config.value.enabled && size < EARLY_MAX_BYTES)
                if (waiting) say("Taking ${size / 1000} KB in namespace \"$namespace\" for the photo")
                return waiting
            }

            // Read here, because the receiver deletes the file; decoded off the reader.
            override fun take(received: Received) {
                val got = EarlyImage(received.file.readBytes(), "airship \"${received.offer.namespace}\"")
                early = got
                scope.launch { offerEarly(got) }
                scope.launch {
                    delay(EARLY_IMAGE_MS)
                    if (early === got) early = null
                }
            }

            override fun peek(received: Received) {
                if (pending == null || received.offer.size >= PHOTO_MAX_BYTES) return
                val bytes = received.file.readBytes()
                scope.launch { offerImage(bytes, "the media namespace") }
            }
        }
        scope.launch {
            combine(
                connection.state.map { it.link == LinkState.READY }.distinctUntilChanged(),
                store.config.map { it.enabled }.distinctUntilChanged(),
            ) { ready, enabled -> ready to enabled }.collectLatest { (ready, enabled) ->
                dropLink()
                // A pause is the Live view's move to Wi-Fi, which Live AI's own camera causes.
                if (!ready && !connection.paused) liveAi.end("the glasses disconnected")
                _phase.value = when {
                    !enabled -> AssistantPhase.OFF
                    !ready -> AssistantPhase.WAITING
                    else -> AssistantPhase.REGISTERING
                }
                if (ready && enabled) connection.submit { mux -> scope.launch { bringUp(mux) } }
            }
        }
        scope.launch { camera.frames.collect { if (it) liveAi.onFirstFrame() } }
        // A language switch restarts the glasses' assistant, which closes our channels: register again once it settles.
        scope.launch {
            connection.errors.collect { e ->
                if (e.serviceId == SVC_ASSISTANT && e.errorId in CHANNEL_GONE) link?.let { registerAgain(it, e.channelId) }
            }
        }
        // Before the glasses connect, prepare the language last heard from them.
        scope.launch {
            prepareSpeech(link?.let(::spoken) ?: chosenLocale()
                ?: store.config.value.speechLanguage.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag)
                ?: Locale.forLanguageTag(speechPrefs.getString("language", null) ?: "en-US"))
        }
        // A speech language of the wearer's own needs the phone's model for it.
        scope.launch {
            store.config.map { it.speechLanguage }.distinctUntilChanged().collect { tag ->
                if (tag.isNotEmpty() && chosenLocale() == null) prepareSpeech(Locale.forLanguageTag(tag))
            }
        }
        scope.launch {
            glassesPhotos.arrived.collect { (id, bytes) ->
                pending?.saw("svc53")
                if (pending != null && offerImage(bytes, "svc53 photo $id")) glassesPhotos.take(id)
            }
        }
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

    // A turn on a link that has gone leaves the phase alone.
    private fun setPhase(l: Link, p: AssistantPhase) {
        if (link === l) _phase.value = p
    }

    @Volatile
    private var again: Job? = null

    /** [channel] of [l] was closed or answered closed: if it is m0 or s0, register again once the glasses settle. */
    private fun registerAgain(l: Link, channel: Int) {
        val ours = listOfNotNull(l.m0, l.s0).map { it and 0x7FFF }
        if ((channel and 0x7FFF) !in ours) return
        synchronized(this) {
            again?.cancel()
            again = scope.launch {
                delay(REGISTER_AGAIN_MS)
                if (link !== l || connection.state.value.link != LinkState.READY) return@launch
                say("The glasses' assistant closed our channels; registering again")
                connection.submit { mux -> scope.launch { bringUp(mux) } }
            }
        }
    }

    /** The phone's speech model for [locale], asked for once per language. */
    private fun prepareSpeech(locale: Locale) {
        if (speechTag(locale) == lastSpeech) return
        lastSpeech = speechTag(locale)
        SpeechModels.prepare(context, locale, ::say)
    }

    // --- bring-up ------------------------------------------------------------

    /** The link went or is replaced: its partner sessions end and the ears for its microphone stop. */
    private fun dropLink() {
        link?.let { k -> k.partnerSessions.values.forEach(k::end) }
        ears?.stop()
        ears = null
        link = null
    }

    /** Register m0 and s0, declare the companion's resources, announce a session. */
    private suspend fun bringUp(mux: DataXConnection) {
        val l = Link(mux)
        dropLink()
        link = l
        try {
            val client = UUID.randomUUID().toString()
            val session = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
            fun registration(endpoint: String) = Wire.fieldString(1, session) +
                Wire.fieldString(2, client) + Wire.fieldString(3, endpoint) + Wire.fieldVarint(4, 2)

            val m0 = mux.openChannel(SVC_ASSISTANT).also { l.m0 = it }
            mux.send(m0, registration("$client-m0"), messageType = MT_INIT_MESSAGE_REQUEST, serviceId = SVC_ASSISTANT)
            withTimeoutOrNull(4_000) { l.m0Answered.await() } ?: say("The glasses did not answer the assistant's message registration")
            val s0 = mux.openChannel(SVC_ASSISTANT).also { l.s0 = it }
            mux.send(s0, registration("$client-s0"), messageType = MT_INIT_STREAM_REQUEST, serviceId = SVC_ASSISTANT)
            withTimeoutOrNull(4_000) { l.s0Answered.await() } ?: say("The glasses did not answer the assistant's stream registration")

            // In this order, as the app declares them. Without them the glasses take the audio and play nothing.
            fun id() = Wire.fieldString(1, UUID.randomUUID().toString())
            val resources = listOf(
                MT_PAIRED_DEVICE_ANDROID to Wire.fieldString(1, "${System.currentTimeMillis()}-${UUID.randomUUID()}"),
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
            setPhase(l, AssistantPhase.LISTENING)
            say("Registered as the glasses' assistant (protocol ${l.protocol}): \"Hey Meta\" now comes to Visor")
        } catch (cancelled: CancellationException) {
            // The session went away while registering, such as a Wi-Fi one ending: not a failure.
            throw cancelled
        } catch (error: Exception) {
            if (link === l) say("Could not register as the assistant: ${error.message}")
        }
    }

    /** Four events that tell the glasses a working assistant backend is behind this client. */
    private suspend fun announceSession(mux: DataXConnection, m0: Int) {
        val sid = UUID.randomUUID().toString()
        val firstFrame = ThriftWriter().str(1, sid).i32(2, 1).i32(3, 0).done()
        for ((event, body) in serverHello(sid) + (EV_LIVE_AI_CLIENT_RECEIVED_FIRST_FRAME to firstFrame)) {
            mux.send(m0, netEvent(event, body), messageType = MT_NET_EVENT)
            delay(20)
        }
    }

    // --- the responder ---------------------------------------------------------

    override suspend fun closed(channelId: Int, connection: DataXConnection) {
        val l = link ?: return
        if (connection === l.mux) registerAgain(l, channelId)
    }

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val l = link ?: return
        // The Wi-Fi session gets this responder too; only the link we registered on is ours.
        if (connection !== l.mux) return
        val payload = message.payload
        when (val type = message.info.messageType) {
            // InitChannelResponse {1 protocolVersion, 2 lifecycleId, 3 connectionId, 4 channelId}, or the bare version in
            // 4 bytes of their byte order; nothing is version 0.
            MT_INIT_MESSAGE_RESPONSE -> {
                l.protocol = when {
                    payload.size > 4 -> runCatching { Wire.parse(payload).varint(1).toInt() }.getOrDefault(0)
                    payload.size == 4 -> ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int
                    else -> 0
                }
                l.m0Answered.complete(Unit)
            }
            MT_INIT_STREAM_RESPONSE -> l.s0Answered.complete(Unit)
            MT_RUNNING_LOCALE -> {
                l.glassesLocale = parseLocale(payload)
                say("The glasses' assistant speaks ${l.glassesLocale.getDisplayName(Locale.ENGLISH)}" +
                    if (l.foreign) "; Visor hears and answers in ${l.locale.getDisplayName(Locale.ENGLISH)}" else "")
                speechPrefs.edit().putString("language", l.glassesLocale.toLanguageTag()).apply()
                prepareSpeech(spoken(l))
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
                l.listening?.let { l.partnerSessions[it] }?.microphoneEnded = true
                micEnded?.complete(Unit)
                ears?.finish()
            }
            MT_STREAM_STATUS -> streamStatus(payload.decodeToString())
            // A custom wake word sample, recorded on the glasses for an enrolment.
            CustomWakeWord.MT_ENROLLMENT_HEADER, CustomWakeWord.MT_STREAMER_DATA, CustomWakeWord.MT_STREAMER_END -> wakeWord.onStream(type, payload)
            MT_ENVELOPE -> envelope(l, payload)
            // Some glasses send the same messages bare, without the envelope.
            MT_NET_EVENT, MT_START_INTERACTION, MT_SEGMENTED_HEADER, MT_SEGMENTED_BUFFER -> inner(l, type, payload)
            MT_TIME_STRETCH_ENABLED, MT_TTS_BUFFER_CONFIG, MT_MMAI_ENABLED, MT_ASSISTANT_READY, MT_STREAM_CONSUMED,
            MT_INCOMING_NOTIFICATION_RESPONSE -> Unit
            else -> say("Message type $type, ${payload.size} bytes ${hex(payload)}")
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
        if (type == MT_START_INTERACTION) return startInteraction(l, m0, blob)
        if (type == MT_SEGMENTED_HEADER || type == MT_SEGMENTED_BUFFER) return segment(l, type, blob)
        if (type != MT_NET_EVENT) {
            say("An envelope carrying type $type, ${blob.size} bytes ${hex(blob)}")
            return
        }
        val event = try {
            parseNetEvent(blob)
        } catch (error: Exception) {
            say("An unreadable event (${error.message})")
            return
        }
        when (event.id) {
            EV_WW_VERIFICATION_COMPLETE -> {
                val fields = runCatching { Thrift.loads(event.body) }.getOrDefault(emptyMap())
                val session = l.partnerSessions[fields[1].asText()] ?: return
                if (!l.ended(session)) session.wake.complete(true)
                if (l.verified(session)) say("3p: wake verified for session ${session.id}")
            }
            EV_SESSION_MODE_UPDATE_RESPONSE -> {
                val fields = runCatching { Thrift.loads(event.body) }.getOrDefault(emptyMap())
                val id = fields[1].asText()
                // Mode 2 is LIVE_AI: no speech confirmation, the session is the server's to serve.
                if (fields[2] == MODE_LIVE_AI) {
                    l.partnerSessions.values.forEach(l::end)
                    l.currentPartner = null
                    liveAi.onModeLiveAi(id, fields)
                    return
                }
                if (liveAi.active) liveAi.end("the session went back to mode ${fields[2]}")
                // The glasses wait for this confirmation before they hand over the transcript.
                val interaction = l.partnerInteraction(id)
                if (interaction.isNotEmpty() && l.speechStarted.add(interaction)) {
                    l.mux.send(m0, netEvent(EV_START_OF_USER_SPEECH, ThriftWriter().str(1, interaction).done()), messageType = MT_NET_EVENT)
                }
            }
            EV_SESSION_STATE_UPDATE -> {
                mirrorState(l, m0, event.body)
                val state = runCatching { Thrift.loads(event.body) }.getOrDefault(emptyMap())
                if (state[1] in PARTNER_END_STATES) {
                    l.partnerSessions.values.forEach(l::end)
                }
                if (liveAi.active && state[2] == 0L) liveAi.end("the session closed")
            }
            EV_REMOTE_INTERACTION_COMPLETE -> {
                val fields = runCatching { Thrift.loads(event.body) }.getOrDefault(emptyMap())
                fields[1].asText().takeIf { it.isNotEmpty() }?.let {
                    val interaction = l.partnerInteraction(it)
                    l.closed.add(interaction)
                    // A completed turn is closed, which with its answer lets the session take the next; a failed one ends it.
                    l.partnerSession(it)?.let { session ->
                        if (it == session.id || (interaction == session.interaction && fields[2] != REMOTE_SUCCESS)) l.end(session)
                    }
                }
                say("Remote interaction complete ${deep(fields)}")
                liveAi.end("the glasses completed the interaction")
            }
            EV_FRAME_KEEP_ALIVE_RESPONSE, EV_KEEP_MWA_ALIVE -> Unit
            // The answer's display action, passed on to the phone; the glasses wait for the phone to take it.
            EV_ACTION_FULFILLMENT_REQUEST -> {
                val id = runCatching { Thrift.loads(event.body)[1].asStruct()[1].asText() }.getOrDefault("")
                l.mux.send(m0, netEvent(EV_ACTION_FULFILLMENT_RESPONSE, ThriftWriter().str(1, id).bool(2, true).done()), messageType = MT_NET_EVENT)
            }
            EV_MWA_VOICE_INTERACTION_CLOSE_REQUEST -> voiceInteractionClosed(l, event.body)
            EV_FULFILLMENT_REQUEST -> fulfillmentRequest(l, event.body)
            EV_MMAI_INTERACTION_REQUEST -> mmaiRequest(l, event.body)
            EV_DEVICE_VOICE_STATE -> voiceState(event.body)
            EV_REMOTE_IMAGE_CAPTURE_RESPONSE -> {
                pending?.saw("ev74")
                say("The glasses took the photo: ${describe(event.body)}")
            }
            CustomWakeWord.EV_RESULT -> wakeWord.onResult(runCatching { Thrift.loads(event.body) }.getOrDefault(emptyMap()))
            // ev387 acknowledges a tool call; ev396 is the tool registry sync sent at connect: nothing to answer.
            EV_MCP_REQUEST_ACK, EV_CARBON_CROSS_SURFACE -> Unit
            EV_EXTERNAL_TRANSCRIPTION_REQUEST -> externalTranscription(l, event.body)
            EV_MCP_RESPONSE -> glassesReply(l, event.body)
            EV_REMOTE_INTENT_RESPONSE -> handoverReply(l, event.body)
            else -> say("${eventName(event.id)} (${event.id}), ${event.body.size} bytes ${describe(event.body)}")
        }
    }

    /** 1023 StartInteraction: Live AI's, a typed question's, a 3p session's, or a plain turn. */
    private suspend fun startInteraction(l: Link, m0: Int, blob: ByteArray) {
        val fields = runCatching { Thrift.loads(blob) }.getOrDefault(emptyMap())
        // After a switch to LIVE_AI the next interaction is the session's.
        if (fields[5] == LIVE_AI_USE_CASE || liveAi.phase.value == LiveAiPhase.STARTING) {
            l.currentPartner?.let(l::end)
            l.currentPartner = null
            liveAi.onStartInteraction(fields, spoken(l))
            return
        }
        // Use case 7 opens a session ("SCI", English): there the glasses never speak text from the phone.
        l.sessionTurn = fields[5] == SESSION_USE_CASE
        val id = fields[1].asText()
        fields[41]?.let { l.onFrameKillswitch[id] = it }
        // On a voice's session turns the glasses never speak the phone's text, so the phone speaks; a plain turn undoes it.
        fields[4].asText().takeIf { it.isNotEmpty() && !it.startsWith("3p_") }?.let { voice ->
            val marked = voice in store.sessionVoices.value
            if (l.sessionTurn && !marked) {
                store.markSessionVoice(voice)
                say("$voice runs session turns: the phone speaks")
            } else if (!l.sessionTurn && marked) {
                store.forgetSessionVoice(voice)
                say("$voice runs plain turns again")
            }
        }
        // 55 isTranscriptionRequest: a turn that starts from text, as the question typed in the app does.
        if (fields[55] == true && id.isNotEmpty()) typed?.let { t ->
            l.typedOf[id] = t.id
            t.started.complete(Unit)
        }
        say("Start interaction ${deep(fields)}")
        val serverAsrContext = fields[18].asStruct()
        if (serverAsrContext[30].asText().startsWith("3p_")) {
            startPartner(l, m0, id, serverAsrContext[40].asText())
        } else {
            l.currentPartner?.let(l::end)
            l.currentPartner = null
        }
    }

    /** A 3p session's first turn [interaction]: confirmed to the glasses with ev337 and ev71. */
    private suspend fun startPartner(l: Link, m0: Int, session: String, interaction: String) {
        if (session.isBlank() || interaction.isBlank() || interaction == session) {
            l.drop(session)
            return say("3p: missing or colliding initInteractionId for session $session; dropped")
        }
        val known = l.partnerSessions[session]
        if (known != null) {
            if (known.initialInteraction != interaction) say("3p: conflicting start for session $session; dropped")
            return
        }
        if (l.sessionOf.containsKey(interaction) || l.partnerSessions.containsKey(interaction) || session in l.partnerTurns) {
            l.drop(session)
            return say("3p: reused session or interaction ID; dropped")
        }
        l.partnerSessions.values.forEach(l::end)
        val partner = PartnerSession(session, interaction)
        l.partnerSessions[session] = partner
        l.currentPartner = partner
        l.sessionOf[interaction] = session
        l.partnerTurns.add(interaction)
        val body = ThriftWriter().str(1, interaction).done()
        l.mux.send(m0, netEvent(EV_START_REMOTE_INTERACTION, body), messageType = MT_NET_EVENT)
        l.mux.send(m0, netEvent(EV_START_OF_USER_SPEECH, body), messageType = MT_NET_EVENT)
        l.speechStarted.add(interaction)
        say("3p: sent ev337 and ev71 for $interaction in session $session")
    }

    /** A tool call's answer, ev312 {1 jsonContent}: handed to the call waiting for its JSON-RPC id. */
    private fun glassesReply(l: Link, body: ByteArray) {
        val json = runCatching { Thrift.loads(body)[1].asText() }.getOrDefault("")
        val id = runCatching { JSONObject(json).opt("id")?.toString() }.getOrNull() ?: return say("An unreadable tool answer")
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
    private fun voiceInteractionClosed(l: Link, body: ByteArray) {
        val f = runCatching { Thrift.loads(body) }.getOrNull() ?: return
        val id = f[1].asText().takeIf { it.isNotEmpty() } ?: return
        val interaction = l.partnerInteraction(id)
        l.closed.add(interaction)
        val partner = l.partnerSession(id) ?: l.currentPartner
        if (partner != null) {
            if (id == partner.id) l.end(partner)
            if (!l.verified(partner) || interaction !in l.partnerTurns) return
        }
        val words = f[2].asStruct()[3].asText().trim()
        if (interaction in l.claimed || words.isEmpty() || WAKE_WORD_ONLY.matches(words)) return
        say("The glasses did \"$words\" themselves")
        val turn = claimTyped(l, interaction, words)
        if (turn != null) return update(turn) { it.copy(problem = ON_GLASSES) }
        record(TurnRecord(interaction, System.currentTimeMillis(), words, problem = ON_GLASSES))
    }

    /** The app's turn when [interaction] is the question typed there, now claimed; null once the app answered it. */
    private fun claimTyped(l: Link, interaction: String, words: String): String? {
        val t = typed
        val id = l.typedOf[interaction] ?: t?.takeIf { plain(it.text) == plain(words) }?.id ?: return null
        if (t != null && t.id == id) {
            typed = null
            t.started.complete(Unit)
            if (!t.asked.complete(Unit)) return null
        }
        l.typedOf[interaction] = id
        return id
    }

    /** ev341 ExternalTranscriptionRequest: a question typed or handwritten, on the Display or in the app; no audio comes. */
    private fun externalTranscription(l: Link, body: ByteArray) {
        if (!display()) return say("${eventName(EV_EXTERNAL_TRANSCRIPTION_REQUEST)} ($EV_EXTERNAL_TRANSCRIPTION_REQUEST), ${body.size} bytes ${describe(body)}")
        if (l.m0 == null || l.s0 == null) return say("A question came before the assistant channels were up; dropped")
        val f = runCatching { Thrift.loads(body) }.getOrNull() ?: return say("An unreadable transcription request, ${body.size} bytes ${hex(body)}")
        val interaction = f[1].asText()
        val words = f[2].asText().trim()
        if (interaction.isEmpty() || words.isEmpty() || !l.claimed.add(interaction)) return
        val turn = claimTyped(l, interaction, words)
        if (turn == null) l.fresh.add(interaction)
        l.written.add(interaction)
        say("Read \"$words\" (${if (f[4] == true) "handwritten" else "typed"}${if (turn != null) " in the app" else ""})")
        val question = turn?.let { diary.turn(it)?.question } ?: words
        scope.launch { answer(l, interaction, question, Visual.NONE, turn = turn ?: interaction) }
    }

    /** 1002 opens a segmented message, 1003 carries its pieces; the whole one goes on as its own type. */
    private suspend fun segment(l: Link, type: Int, blob: ByteArray) {
        val f = Wire.parse(blob)
        val id = f.bytes(if (type == MT_SEGMENTED_HEADER) 3 else 2).decodeToString()
        if (type == MT_SEGMENTED_HEADER) {
            l.segments[id] = Triple(f.varint(1).toInt(), f.varint(2).toInt(), ByteArrayOutputStream())
            return
        }
        val (kind, size, bytes) = l.segments[id] ?: return say("A segment of unknown message $id")
        bytes.write(f.bytes(1))
        if (f.varint(3) == 0L) return
        l.segments.remove(id)
        if (bytes.size() != size) return say("Segmented message $id came to ${bytes.size()} bytes of $size")
        inner(l, kind, bytes.toByteArray())
    }

    /**
     * ev65 MmaiInteractionRequest {1 interactionId, 2 ocrResult, 3 ocrPatchUuids}: the text the glasses' own OCR read in
     * the photo for a visual question. ocrResult is base64 of a gzipped struct: {1 lines, each {1 box, 2 word indices},
     * 2 words, each {2 text}}, lines top to bottom.
     */
    private fun mmaiRequest(l: Link, body: ByteArray) {
        val f = runCatching { Thrift.loads(body) }.getOrNull() ?: return say("An unreadable MmaiInteractionRequest")
        val interaction = f[1].asText()
        pending?.saw("ev65")
        val text = runCatching {
            val ocr = Thrift.loads(GZIPInputStream(Base64.getDecoder().decode(f[2].asText()).inputStream()).use { it.readBytes() })
            val words = (ocr[2] as? List<*>).orEmpty().map { it.asStruct()[2].asText() }
            (ocr[1] as? List<*>).orEmpty().map { line ->
                (line.asStruct()[2] as? List<*>).orEmpty().mapNotNull { words.getOrNull((it as? Long)?.toInt() ?: -1) }.joinToString(" ")
            }.filter { it.isNotBlank() }.joinToString("\n")
        }.getOrElse { return say("Unreadable OCR from the glasses (${it.message})") }
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
        val interaction = l.partnerInteraction(m[1].asText())
        val partner = l.partnerSession(interaction) ?: l.currentPartner
        if (partner != null) {
            if (l.foreign || l.ended(partner) || interaction in l.closed || interaction !in l.partnerTurns) return
            if (!l.verified(partner)) {
                scope.launch { if (awaitPartnerWake(l, interaction)) fulfillmentRequest(l, body) }
                return
            }
            if (link !== l || !l.partnerCanAnswer(interaction)) return
        }
        val transcript = m[2].asStruct()[1].asStruct()[1].asText()
        if (interaction.isEmpty() || !l.claimed.add(interaction)) return
        if (m[11] !in FOLLOW_UP_INVOCATIONS) l.fresh.add(interaction)
        // In a session the request has its own id; the microphone's is the session's, and it is the same turn.
        if (interaction !in l.partnerTurns) {
            l.listening?.takeIf { it != interaction }?.let {
                l.sessionOf[interaction] = it
                l.claimed.add(it)
            }
        }
        m[8].asText().takeIf { it.length > 8 && !l.streamIds.containsKey(interaction) }?.let { l.streamIds[interaction] = it }
        l.simplified = m[30] != false
        val visual = Visual.from(m)
        say("Heard \"$transcript\" (request ${describe(body)}, understanding ${describeRead(m[2])})")
        // The question typed in the app: no audio, and the model answers it whatever the glasses made of it.
        claimTyped(l, interaction, transcript)?.let { turn ->
            say("The Display asked the question typed in the app")
            val question = diary.turn(turn)?.question ?: transcript
            scope.launch { answer(l, interaction, question, visual, toolbox(l, m), turn) }
            return
        }
        // The wearer speaks a language the glasses have no pack for: their words and their understanding of them are
        // not the wearer's. The phone's, in that language, answer instead; the glasses' only if the phone heard nothing.
        if (store.config.value.speechLanguage.isNotEmpty() && chosenLocale() == null) {
            val mic = l.listening ?: interaction
            scope.launch {
                // The whole stream as heard; if the recogniser is still at it, what it has heard so far.
                val own = (withTimeoutOrNull(OWN_WORDS_MS) { l.heard.getOrPut(mic) { CompletableDeferred() }.await() }
                    ?: l.words[mic]?.joinToString(" "))?.trim()
                l.heard.remove(mic)
                l.words.remove(mic)
                if (own.isNullOrEmpty()) say("The phone heard nothing; answering the glasses' words") else say("Heard \"$own\" myself")
                answer(l, interaction, own?.takeIf { it.isNotEmpty() } ?: transcript, visual)
            }
            return
        }
        // The glasses' own understanding of the request, e.g. IN:REPLY_MESSAGE, and its slots: fields 23 and 26, or the
        // OnDeviceIntent {1 name, 4 slots} that Meta's schema puts in 55 and in understanding.nluResult, with 23 and 26 flags.
        val nlu = m[55].asStruct().ifEmpty { m[2].asStruct()[2].asStruct()[2].asStruct() }
        val intent = m[23].asText().ifEmpty { nlu[1].asText() }
        val slots = m[26] as? Map<*, *> ?: nlu[4]
        say("Understood $intent, slots ${deep(slots)}")
        // A language the glasses do not hear: their words and their understanding of them are noise, the phone's stand.
        if (l.foreign) {
            val heard = l.recognitionDone
            val mic = l.listening ?: interaction
            val glasses = toolbox(l, m)
            // The question has ended. In a follow-up the glasses keep the microphone open past it, and the phone's
            // recogniser gives its words only at the end of the audio, so it is told the end here.
            ears?.finish()
            scope.launch {
                withTimeoutOrNull(HEARD_WAIT_MS) { heard.await() }
                val text = l.words[mic]?.joinToString(" ")?.trim().orEmpty()
                val language = l.locale.getDisplayLanguage(Locale.ENGLISH)
                if (text.isEmpty() || WAKE_WORD_ONLY.matches(text)) {
                    say("Heard nothing in $language myself for $interaction")
                    return@launch speakTurn(l, interaction, "", "Tell the wearer you did not catch the question.")
                }
                say("Heard \"$text\" myself, in $language")
                dispatch(l, interaction, text, "", null, visual, body, glasses)
            }
            return
        }
        if (doneWithoutModel(intent)) return
        dispatch(l, interaction, transcript, intent, slots, visual, body, toolbox(l, m))
    }

    /** An intent the glasses or the phone carry out themselves, with nothing for the model to answer: true when [intent] is one. */
    private fun doneWithoutModel(intent: String): Boolean {
        when (intent) {
            // The glasses fulfil this one themselves, by switching the session to Live AI; an answer would talk over it.
            "IN:START_LIVE_AI" -> say("Live AI asked for; the glasses start it themselves")
            // "Hey Meta" and nothing after it: the glasses close the turn at once.
            "IN:WAKE_WORD" -> Unit
            // Meta's server answers these with an action Visor cannot write; the phone starts and stops it itself.
            "IN:START_CONVERSATION_FOCUS", "IN:STOP_CONVERSATION_FOCUS", "IN:PAUSE_CONVERSATION_FOCUS", "IN:RESUME_CONVERSATION_FOCUS" -> {
                say("Conversation focus: $intent")
                when (intent) {
                    "IN:START_CONVERSATION_FOCUS" -> hearing.start(null)
                    "IN:RESUME_CONVERSATION_FOCUS" -> hearing.resume()
                    else -> hearing.stop()
                }
            }
            // The same for a group conversation: the glasses' group app (svc28 app 47) finds or opens the group itself.
            "IN:START_GROUP_CONVERSATION", "IN:JOIN_GROUP_CONVERSATION", "IN:LEAVE_GROUP_CONVERSATION" -> {
                val type = if (intent == "IN:LEAVE_GROUP_CONVERSATION") SnApp.MT_STOP_APP else SnApp.MT_START_APP
                connection.submit { it.sendOnService(SVC_STATUS, type, SnApp.app(APP_GROUP_CONVERSATIONS)) }
                say("Group conversation: $intent")
            }
            else -> return false
        }
        return true
    }

    /** A question in words: a dictated reply, a reply asked for, a media control done on the phone, or an answer. */
    private fun dispatch(
        l: Link, interaction: String, transcript: String, intent: String, slots: Any?, visual: Visual, body: ByteArray, glasses: GlassesToolbox?,
    ) {
        val waiting = awaitingReply?.takeIf { System.currentTimeMillis() < it.until }
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
            intent in MEDIA_CONTROLS -> scope.launch { mediaControl(l, interaction, transcript, MEDIA_CONTROLS.getValue(intent)) }
            else -> {
                // A question that names an app goes to the app, not the model. A partner turn keeps Meta's own flow.
                val claim = if (interaction in l.partnerTurns) null else apps.claim(transcript)
                if (claim != null) scope.launch { appAnswer(l, interaction, transcript, intent, claim) }
                else scope.launch { answer(l, interaction, transcript, visual, glasses) }
            }
        }
    }

    /** [claim]'s app answers the question, in the glasses' voice; when it does not in time, the wearer hears so. */
    private suspend fun appAnswer(l: Link, interaction: String, transcript: String, intent: String, claim: AppClaim) {
        if (!awaitPartnerWake(l, interaction)) return
        val started = System.currentTimeMillis()
        record(TurnRecord(interaction, started, transcript))
        say("${claim.app} answers $interaction")
        setPhase(l, AssistantPhase.THINKING)
        val waiting = scope.launch { holdOn(l, interaction) }
        val budget = (processingTimeoutS() ?: FACTORY_PROCESSING_S) * 1000 - DELIVERY_MS
        val reply = try {
            apps.answer(claim, interaction, transcript, intent, budget)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say("${claim.app} failed to answer: ${e.message}")
            null
        }
        if (reply == null) {
            waiting.cancel()
            update(interaction) { it.copy(problem = "${claim.app} did not answer") }
            return speakTurn(l, interaction, transcript, "Tell the wearer that the app ${claim.app} did not answer.")
        }
        val answered = listOf(ToolAction(claim.app, "answered", true))
        update(interaction) { it.copy(answer = reply.text, modelMs = System.currentTimeMillis() - started, actions = answered) }
        if (l.simplified) waiting.cancel()
        try {
            val sentMs = deliver(l, interaction, transcript, reply.text, listen = reply.listen)
            l.answered.add(interaction)
            update(interaction) { it.copy(sentMs = sentMs) }
        } catch (error: Exception) {
            say("${claim.app}'s answer did not reach the glasses: ${error.message}")
            update(interaction) { it.copy(problem = error.why()) }
        } finally {
            waiting.cancel()
            setPhase(l, AssistantPhase.LISTENING)
        }
    }

    /** The Display's own tools for this turn, as Meta's server names them (ev57 field 54). */
    private fun toolbox(l: Link, m: Map<Int, Any?>): GlassesToolbox? {
        if (!display()) return null
        val advertised = (m[54] as? List<*>).orEmpty().flatMap { server -> (server.asStruct()[2] as? List<*>).orEmpty().map { it.asText() } }
        return glassesToolbox(advertised, { full, input -> glassesCall(l, full, input) }, { names -> describeTools(l, names) })
    }

    /**
     * The glasses' own descriptions of [names] (MCP tools/list over ev311), for tools Visor does not know, such as a
     * web app's: asked once a link and kept, and not again for a minute after an ask they did not answer.
     */
    private suspend fun describeTools(l: Link, names: List<String>): Map<String, ToolDescription> {
        fun known() = names.mapNotNull { n -> (l.described[n] ?: l.described[n.substringAfterLast('.')])?.let { n to it } }.toMap()
        if (names.all { n -> l.described.containsKey(n) || l.described.containsKey(n.substringAfterLast('.')) }) return known()
        if (System.currentTimeMillis() < l.describeAgainAt) return known()
        val json = mcp(l, "tools/list", JSONObject(), DESCRIBE_WAIT_MS)
        val found = json?.let(::toolDescriptions).orEmpty()
        if (found.isEmpty()) {
            l.describeAgainAt = System.currentTimeMillis() + DESCRIBE_RETRY_MS
            say("The glasses did not describe their tools ${names.joinToString()}")
        } else {
            l.described.putAll(found)
            say("The glasses described ${found.size} tools")
        }
        return known()
    }

    // --- replying ------------------------------------------------------------

    private suspend fun replyRequested(l: Link, interaction: String, transcript: String, content: String) {
        val conversation = messaging?.lastAnnounced()
        if (conversation != null && content.isNotEmpty()) return sendReply(l, interaction, transcript, conversation, content)
        record(TurnRecord(interaction, System.currentTimeMillis(), transcript))
        if (conversation == null) return speakTurn(l, interaction, transcript, "Tell the wearer there is no message to reply to.")
        awaitingReply = AwaitingReply(conversation, System.currentTimeMillis() + REPLY_WAIT_MS)
        say("Asking what to reply to ${conversation.sender} on ${conversation.app}")
        speakTurn(l, interaction, transcript, "Ask the wearer what they want to reply to ${conversation.sender}.")
    }

    private suspend fun sendReply(l: Link, interaction: String, transcript: String, conversation: Conversation, text: String) {
        awaitingReply = null
        record(TurnRecord(interaction, System.currentTimeMillis(), transcript))
        val messenger = messaging
        val why = if (messenger == null) "the notification listener is not running" else messenger.reply(conversation, text)
        say(if (why == null) "Replied to ${conversation.sender} on ${conversation.app}: \"$text\"" else "Could not reply to ${conversation.sender}: $why")
        speakTurn(
            l, interaction, transcript,
            if (why == null) "Tell the wearer the reply to ${conversation.sender} was sent." else "Tell the wearer the reply to ${conversation.sender} could not be sent.",
        )
    }

    private suspend fun mediaControl(l: Link, interaction: String, transcript: String, control: MediaControl) {
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
        if (l.m0 == null) return ToolResult("the glasses are not connected", true)
        val json = mcp(l, "tools/call", JSONObject().put("name", full).put("arguments", input), MCP_WAIT_MS)
        say(if (json == null) "The glasses did not answer $full" else "The glasses answered $full")
        return json?.let(::glassesResult) ?: ToolResult("no answer from the glasses", true)
    }

    // One JSON-RPC request to the glasses' MCP runtime (ev311), and its answer (ev312) with the same id, or null.
    private suspend fun mcp(l: Link, method: String, params: JSONObject, waitMs: Long): String? {
        val m0 = l.m0 ?: return null
        val id = l.callIds.getAndIncrement().toString()
        val reply = CompletableDeferred<String>()
        l.calls[id] = reply
        try {
            val request = JSONObject().put("jsonrpc", "2.0").put("id", id.toInt()).put("method", method).put("params", params)
            sendNetEvent(l, m0, netEvent(EV_MCP_REQUEST, ThriftWriter().str(1, request.toString()).done()))
            return withTimeoutOrNull(waitMs) { reply.await() }
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
            sendNetEvent(l, m0, netEvent(EV_REMOTE_INTENT_REQUEST, ThriftWriter().str(1, requestId).str(2, interaction).struct(3, raw).done()))
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
        if (!awaitPartnerWake(l, interaction)) return update(interaction) { it.copy(problem = "the glasses closed this question") }
        setPhase(l, AssistantPhase.THINKING)
        try {
            val text = brain.phrase(what, spoken(l))
            update(interaction) { it.copy(answer = text) }
            deliver(l, interaction, transcript, text)
        } catch (error: Exception) {
            say("Could not answer: ${error.message}")
            update(interaction) { it.copy(problem = error.why()) }
        } finally {
            setPhase(l, AssistantPhase.LISTENING)
        }
    }

    // --- answering -----------------------------------------------------------

    /** [turn] is the diary's turn for it: the interaction's own, or the app's for a question typed there. */
    private suspend fun answer(
        l: Link, interaction: String, transcript: String, visual: Visual, glasses: GlassesToolbox? = null, turn: String = interaction,
    ) {
        if (!awaitPartnerWake(l, interaction)) return
        val started = System.currentTimeMillis()
        if (turn == interaction) record(TurnRecord(interaction, started, transcript))
        if (interaction in l.partnerTurns) {
            try {
                preamble(l, interaction, transcript, transcript)
                say("3p: sent final transcription for $interaction")
            } catch (error: Exception) {
                l.partnerSession(interaction)?.let(l::end)
                say("Could not finish the 3p question: ${error.message}")
                update(turn) { it.copy(problem = error.why()) }
                return
            }
        }
        setPhase(l, AssistantPhase.THINKING)
        var problem = ""
        // During the Live every question comes with the picture: the wearer is showing it.
        val live = if (store.config.value.vision) runCatching { liveFrame() }.getOrNull()?.let { toModelJpeg(it) } else null
        if (live != null) say("Answering with the Live view's picture")
        val partner = interaction in l.partnerTurns
        val visualQuestion = live != null || (store.config.value.vision &&
            (visual.glassesCaptured || visual.glassesWantImage || (!partner && soundsVisual(transcript))))
        val image = live ?: if (visualQuestion) photo(l, interaction, visual) else null
        if (image != null) keepPhoto(turn, image)
        fun checkCameraTurn(needsCamera: Boolean = true) {
            check(!needsCamera || store.config.value.vision) { "camera use is disabled" }
            check(link === l && l.partnerCanAnswer(interaction)) { "the glasses closed this question" }
        }
        val capturePhoto: (suspend () -> ByteArray?)? = if (partner && store.config.value.vision && !visualQuestion) {
            {
                checkCameraTurn()
                photo(l, interaction, visual).also { captured ->
                    checkCameraTurn()
                    if (captured != null) keepPhoto(turn, captured)
                }
            }
        } else null
        val cameraForTurn: (suspend (CameraAction) -> Boolean)? = if (partner && !visualQuestion) {
            { action ->
                checkCameraTurn(action != CameraAction.STOP_RECORDING)
                // Once sent, what the glasses did is the result, even if they closed the turn meanwhile.
                cameraAction(action)
            }
        } else null
        // The glasses' OCR of the photo they took comes just after the question; a model without vision has only this.
        val read = if (visual.glassesCaptured) withTimeoutOrNull(OCR_WAIT_MS) { l.ocrOf(interaction).await() } else null
        val note = listOfNotNull(
            "The wearer seems to ask about what they see, but the photo from the glasses did not arrive; say so briefly."
                .takeIf { visualQuestion && image == null && read.isNullOrBlank() },
            read?.takeIf { it.isNotBlank() }?.let { "The glasses' OCR read this text in what the wearer sees, line by line, with errors: ${it.take(MAX_OCR)}" },
            "The phone heard this question, wake word and all: its first words may be \"Hey Meta\" misheard, even as a number. Leave them out."
                .takeIf { l.foreign },
        ).joinToString(" ")
        val waiting = scope.launch { holdOn(l, interaction) }
        val reply = try {
            // The glasses give up after their processing timeout; speech and streaming need the last seconds of it.
            val budget = (processingTimeoutS() ?: FACTORY_PROCESSING_S) * 1000 - DELIVERY_MS
            val left = (budget - (System.currentTimeMillis() - started)).coerceAtLeast(MIN_MODEL_MS)
            withTimeout(left) {
                brain.answer(transcript, spoken(l), pastOf(turn), image, note, glasses = glasses,
                    canEnd = interaction !in l.partnerTurns, capturePhoto = capturePhoto, cameraAction = cameraForTurn)
            }
        } catch (error: Exception) {
            problem = error.why()
            Reply(fallback(spoken(l)))
        }
        val text = reply.text
        val diaryText = text.ifEmpty { reply.actions.asAnswer() }
        // Without simplified forwarding the VARM follows the audio, and the glasses keep waiting for it until then.
        if (l.simplified && interaction !in l.partnerTurns) waiting.cancel()
        val modelMs = System.currentTimeMillis() - started
        if (problem.isNotEmpty()) say("The model did not answer: $problem")
        if (interaction in l.closed || (interaction in l.partnerTurns && (link !== l || !l.partnerCanAnswer(interaction)))) {
            waiting.cancel()
            say("The glasses closed this turn before the answer was ready (${modelMs / 1000} s); not sending it")
            // A turn whose every action worked and that has nothing to say lost nothing to the close.
            val done = text.isEmpty() && problem.isEmpty() && reply.actions.isNotEmpty() && reply.actions.all { it.ok }
            update(turn) { it.copy(answer = diaryText, problem = if (done) "" else ANSWER_TOO_LATE, modelMs = modelMs, remembered = reply.remembered, actions = reply.actions) }
            setPhase(l, AssistantPhase.LISTENING)
            return
        }
        update(turn) { it.copy(answer = diaryText, problem = problem, modelMs = modelMs, remembered = reply.remembered, actions = reply.actions) }
        if (reply.ends) say("The model ended the conversation: the glasses stop listening after this answer")
        // Every tool did what was asked and that is the answer, such as music that now plays: the turn ends without a word.
        val quiet = store.config.value.quietAfterActions && problem.isEmpty() && (!l.sessionTurn || interaction in l.partnerTurns) &&
            reply.actions.isNotEmpty() && reply.actions.all { it.ok } && !ASKS.containsMatchIn(text)
        try {
            val sentMs = if (quiet) {
                speakOnGlasses(l, interaction, transcript, "")
                0L
            } else {
                deliver(l, interaction, transcript, text, listen = !reply.ends)
            }
            l.answered.add(interaction)
            l.sessionOf[interaction]?.let { l.answered.add(it) }
            update(turn) { it.copy(sentMs = sentMs) }
        } catch (error: Exception) {
            l.partnerSession(interaction)?.let(l::end)
            say("The answer did not reach the glasses: ${error.message}")
            update(turn) { it.copy(problem = error.why()) }
        } finally {
            waiting.cancel()
            setPhase(l, AssistantPhase.LISTENING)
        }
    }

    /**
     * Keep a partner turn open through response generation and TTS; other turns retain their ev392 keepalive.
     */
    private suspend fun holdOn(l: Link, interaction: String) {
        val partner = interaction in l.partnerTurns
        var seq = 1
        if (partner) say("3p: keeping $interaction open with ev61") else delay(HOLD_ON_MS)
        while (link === l && interaction !in l.closed) {
            val m0 = l.m0 ?: return
            val keep = if (partner) {
                netEvent(EV_PARTIAL_VOICE_INTERACTION_FULFILLMENT_RESPONSE, ThriftWriter().str(2, interaction).bool(3, false).done())
            } else {
                netEvent(EV_KEEP_AIR_SESSION_ALIVE, ThriftWriter().str(1, "AwaitingResponse").i32(2, 20_000).i32(3, seq++).done())
            }
            // m0 closed or the link gone: nothing is waiting any more.
            if (runCatching { l.mux.send(m0, keep, messageType = MT_NET_EVENT) }.isFailure) return
            delay(HOLD_ON_MS)
        }
    }

    /** One Live AI turn: the camera's picture now, the model, and the answer spoken as [sub]. */
    private suspend fun liveAnswer(l: Link, sub: String, question: String) {
        val started = System.currentTimeMillis()
        val session = liveAi.sessionId.ifEmpty { liveAi.interaction }
        record(TurnRecord(sub, started, question, source = TurnSource.LIVE_AI, session = session, conversation = "live-$session"))
        val image = runCatching { liveFrame() }.getOrNull()?.let { toModelJpeg(it) }
        if (image != null) keepPhoto(sub, image)
        var problem = ""
        val reply = try {
            brain.answer(question, spoken(l), pastOf(sub), image, liveAiNote())
        } catch (error: Exception) {
            problem = error.why()
            Reply(fallback(spoken(l)))
        }
        val text = reply.text
        update(sub) { it.copy(answer = text, problem = problem, modelMs = System.currentTimeMillis() - started, remembered = reply.remembered, actions = reply.actions) }
        try {
            deliver(l, sub, question, text, live = true)
        } catch (error: Exception) {
            say("Live AI: the answer did not reach the glasses: ${error.message}")
            update(sub) { it.copy(problem = error.why()) }
        }
    }

    /** [jpeg] as [turn]'s photo, in the diary once it is on disk. */
    private fun keepPhoto(turn: String, jpeg: ByteArray) {
        val file = File(photos, "$turn.jpg")
        if (runCatching { photos.mkdirs(); file.writeBytes(jpeg) }.isSuccess) update(turn) { it.copy(image = file.path) }
    }

    /**
     * The photo for a visual question: inline in the request, or the file the glasses send over
     * airship. If they did not take one, ask with RemoteImageCaptureRequest (73); they answer 74
     * with the image's id and then send the file.
     */
    private suspend fun photo(l: Link, interaction: String, visual: Visual): ByteArray? {
        glassesPhotos.take(visual.imageUuid)?.let { bytes ->
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
            early?.let { offerEarly(it) }
            if (waiting.image.isCompleted) return waiting.image.await()
            val killswitch = l.onFrameKillswitch[interaction]?.let { deep(it) } ?: "absent"
            say("Photo for the question: imageUuid ${present(visual.imageUuid)}, captureHandle ${present(visual.captureHandle)}, " +
                "inline ${visual.inline?.let { "${it.size} bytes" } ?: "none"}, MMAI_ONFRAME_KILLSWITCH $killswitch")
            if (!visual.glassesCaptured) {
                val m0 = l.m0 ?: return null
                val request = ThriftWriter().bool(1, false).bool(2, true).bool(3, false).str(4, interaction).done()
                val failed = runCatching { l.mux.send(m0, netEvent(EV_REMOTE_IMAGE_CAPTURE_REQUEST, request), messageType = MT_NET_EVENT) }.exceptionOrNull()
                if (failed != null) {
                    say("Could not ask the glasses for a photo: ${failed.message}")
                    return null
                }
                say("Asked the glasses for a photo with ev73 ${request.toHexString()}")
            } else {
                say("The glasses took a photo (${visual.imageUuid.ifEmpty { visual.captureHandle }}); waiting for it")
            }
            val image = withTimeoutOrNull(PHOTO_WAIT_MS) { waiting.image.await() }
            if (image == null) {
                say("No photo arrived in ${PHOTO_WAIT_MS / 1000} s; during the wait svc53 ${waiting.count("svc53")}, " +
                    "airship ${waiting.count("airship")}, ev74 ${waiting.count("ev74")}, ev65 ${waiting.count("ev65")}")
            }
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

    /**
     * Speak [text] as [interaction]'s answer in the voice chosen; with [listen] false the glasses stop listening after
     * it. The milliseconds of audio sent, 0 in the glasses' voice.
     */
    private suspend fun deliver(l: Link, interaction: String, transcript: String, text: String, listen: Boolean = true, live: Boolean = false): Long {
        // A typed or handwritten question has no stream id for the phone's voice. The glasses' own voice speaks only their language.
        val written = l.typedOf.containsKey(interaction) || interaction in l.written
        val c = store.config.value
        val partner = interaction in l.partnerTurns
        val ownVoice = c.tts == TtsPath.GLASSES && !l.foreign && c.speechLanguage.isEmpty()
        if ((text.isEmpty() && partner) || (!l.sessionTurn && (written || ownVoice))) {
            speakOnGlasses(l, interaction, transcript, text, listen)
            return 0
        }
        val packets = synthesise(text, spoken(l))
        // A session turn ends on the VARM; there, and in Live AI, ev60 would end the whole session. A typed one ends on its card.
        val card = if (written && display() && text.isNotEmpty()) answerCard(text) else null
        stream(
            l, interaction, transcript, text, packets,
            complete = written || (!l.sessionTurn && !live && !partner), listen = listen, card = card,
        )
        return packets.sumOf { it.durationUs } / 1000
    }

    /** The answer as audio: the cloud voice when chosen, and the phone's engine when it fails, never silence. */
    private suspend fun synthesise(text: String, locale: Locale): List<OpusPacket> {
        val c = store.config.value
        if (c.tts == TtsPath.CLOUD) {
            try {
                val audio = withTimeoutOrNull(CLOUD_SPEECH_WAIT_MS) { cloudSpeech(c, text) }
                    ?: throw SpeechError("no answer in ${CLOUD_SPEECH_WAIT_MS / 1000} s")
                say("Speech: ${text.length} characters from ${c.ttsModel}")
                return voice.encode(audio)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                say("Speech: the service failed (${error.message}); the phone speaks instead")
            }
        }
        return voice.speak(text, locale)
    }

    /** ev71, ev52 and ev342, with explicit NetEvent types for partner turns and tagged type 0 otherwise. */
    private suspend fun preamble(l: Link, interaction: String, transcript: String, text: String) {
        val m0 = checkNotNull(l.m0)
        val responseType = if (interaction in l.partnerTurns) MT_NET_EVENT else 0
        val e71 = ThriftWriter().str(1, interaction).done()
        val e52 = ThriftWriter().str(1, transcript.ifEmpty { text }).i32(2, 0).str(4, "").bool(7, false).str(8, interaction).done()
        val e342 = ThriftWriter().str(1, interaction).bool(2, false).str(3, REPLY_INTENT).bool(4, false).done()
        for ((type, event, body) in listOf(
            Triple(MT_NET_EVENT, EV_START_OF_USER_SPEECH, e71),
            Triple(responseType, EV_FINAL_TRANSCRIPTION, e52),
            Triple(responseType, EV_ON_PHONE_UNDERSTANDING, e342),
        )) {
            if (event == EV_START_OF_USER_SPEECH && interaction in l.partnerTurns) continue
            sendNetEvent(l, m0, netEvent(event, body), type)
            delay(20)
        }
    }

    /**
     * The answer in the glasses' own voice: a VARM that streams nothing, then ev60 with the text, which is what makes
     * them speak it with their own TTS.
     */
    private suspend fun speakOnGlasses(l: Link, interaction: String, transcript: String, text: String, listen: Boolean = true) {
        val m0 = checkNotNull(l.m0)
        val partner = interaction in l.partnerTurns
        check(!partner || (link === l && l.partnerCanAnswer(interaction))) { "The glasses closed this 3p turn before the answer" }
        if (!partner) preamble(l, interaction, transcript, text)
        setPhase(l, AssistantPhase.SPEAKING)
        val voiceId = glassesVoice()
        val card = if (display() && text.isNotEmpty()) answerCard(text) else null
        val answer = netEvent(EV_VOICE_ACTION_RESPONSE, varm(interaction, transcript, text, onGlasses = true, voiceId = voiceId, card = card != null))
        val complete = voiceComplete(interaction, text, listen, card)
        // Without simplified forwarding the VARM is acted on as it lands, so the text must be there before it.
        val events = when {
            partner -> listOf(answer)
            l.simplified -> listOf(answer, complete)
            else -> listOf(complete, answer)
        }
        for (event in events) {
            sendNetEvent(l, m0, event)
            delay(20)
        }
        l.mux.send(m0, answerAlive(System.nanoTime() / 1_000_000), messageType = MT_NET_EVENT)
        say(if (text.isEmpty()) "Ended the turn without a word: every tool did what was asked" else "Answered in the glasses' voice${voiceId?.let { " ($it)" }.orEmpty()}: \"$text\"")
    }

    /** Stream phone speech; partner turns with simplified forwarding send the VARM before audio. */
    private suspend fun stream(
        l: Link, interaction: String, transcript: String, text: String, packets: List<OpusPacket>, complete: Boolean, listen: Boolean,
        card: String? = null,
    ) {
        val mux = l.mux
        val m0 = checkNotNull(l.m0)
        val s0 = checkNotNull(l.s0)
        check(interaction !in l.partnerTurns || (link === l && l.partnerCanAnswer(interaction))) { "The glasses closed this 3p turn before playback" }
        if (interaction !in l.partnerTurns) preamble(l, interaction, transcript, text)
        val answer = netEvent(EV_VOICE_ACTION_RESPONSE, varm(interaction, transcript, text, card = card != null))
        val answerBeforeAudio = l.simplified && interaction in l.partnerTurns
        if (answerBeforeAudio) sendNetEvent(l, m0, answer)
        val mono = System.nanoTime() / 1_000_000
        val e388 = ThriftWriter().i64(1, System.currentTimeMillis()).i64(2, mono).str(3, interaction).done()
        mux.send(m0, netEvent(EV_TTS_START_INFO, e388), messageType = MT_NET_EVENT)
        delay(20)

        // Field 1 is the stream the glasses allocated. Without it they drop the stream silently.
        val streamInteraction = if (interaction in l.partnerTurns) l.sessionOf.getValue(interaction) else interaction
        val streamId = l.streamIds[interaction] ?: l.streamIds[streamInteraction] ?: l.latestStream
        val header = (if (streamId.isNotEmpty()) Wire.fieldString(1, streamId) else ByteArray(0)) +
            Wire.fieldString(2, interaction) + Wire.fieldVarint(4, 1) + Wire.fieldVarint(7, 1) + Wire.fieldVarint(19, 1)
        if (streamId.isEmpty()) say("No stream id from the glasses for this answer; it may not play")
        mux.send(s0, header, messageType = MT_STREAM_HEADER)

        setPhase(l, AssistantPhase.SPEAKING)
        val start = System.nanoTime()
        var dueUs = 0L
        for ((i, chunk) in packets.chunked(2).withIndex()) {
            check(interaction !in l.partnerTurns || (link === l && l.partnerCanAnswer(interaction))) { "The glasses closed this 3p turn during playback" }
            val frame = chunk.fold(ByteArray(0)) { acc, p ->
                acc + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(p.data.size).array() + p.data
            }
            if (i == 0) {
                mux.send(s0, frame, messageType = MT_STREAM_AUDIO)
                if (l.simplified && !answerBeforeAudio) sendNetEvent(l, m0, answer)
            } else {
                // A bare header: tagged with type 0, only the first 40 ms would ever play.
                mux.send(s0, frame)
            }
            dueUs += chunk.sumOf { it.durationUs }
            val wait = dueUs / 1000 - (System.nanoTime() - start) / 1_000_000
            if (wait > 0) delay(wait)
        }
        mux.send(s0, ByteArray(0), messageType = MT_STREAM_END)
        if (!l.simplified) sendNetEvent(l, m0, answer)
        // Without it the glasses stay in their answering state with the mic on, and never offer a follow-up.
        if (complete) sendNetEvent(l, m0, voiceComplete(interaction, text, listen, card, streamed = true))

        val e389 = ThriftWriter().i64(1, System.currentTimeMillis()).i64(2, mono).i64(3, 7000).str(4, interaction).done()
        for (event in listOf(netEvent(EV_TTS_END_INFO, e389), answerAlive(mono))) {
            mux.send(m0, event, messageType = MT_NET_EVENT)
            delay(20)
        }
        say("Answered: \"$text\"")
    }

    /**
     * ev60. An answer that asks something has the glasses listen for the reply, without the wake word; any other answer
     * offers a follow-up, as the VARM's own shouldSmartFollowUp does.
     */
    private fun voiceComplete(interaction: String, text: String, listen: Boolean, card: String?, streamed: Boolean = false) = netEvent(
        EV_MWA_VOICE_INTERACTION_COMPLETE,
        interactionComplete(
            interaction, text, expectsAnswer = listen && ASKS.containsMatchIn(text), followUp = listen && text.isNotEmpty(), card = card,
            streamed = streamed,
        ),
    )

    /** ev392 VoiceActionResponseMessage: the answer has gone. */
    private fun answerAlive(mono: Long) =
        netEvent(EV_KEEP_AIR_SESSION_ALIVE, ThriftWriter().str(1, "VoiceActionResponseMessage").i32(2, (mono and 0xFFFF).toInt()).i32(3, 1).done())

    /** 5005: `<streamId>_<interactionId>_<pcmBytes>_<ms>`, the PCM bytes the glasses played. */
    private fun streamStatus(text: String) {
        val parts = text.split("_")
        if (parts.size < 4) return say("Stream status $text")
        val ms = parts.last().toLongOrNull() ?: return
        val pcm = parts[parts.size - 2].toLongOrNull() ?: return
        val interaction = parts.subList(1, parts.size - 2).joinToString("_")
        update(link?.typedOf?.get(interaction) ?: interaction) { it.copy(playedMs = pcm / PCM_BYTES_PER_MS) }
        if (pcm < (ms - PLAYED_SLACK_MS) * PCM_BYTES_PER_MS) say("The glasses played only ${pcm / PCM_BYTES_PER_MS} ms of $ms ms")
    }

    private fun fallback(locale: Locale): String {
        val (none, sorry) = FALLBACKS[locale.toLanguageTag().substringBefore('-')] ?: FALLBACKS.getValue("en")
        return if (store.config.value.hasModel) sorry else none
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

    /** A question typed in the app, asked on a Display through its svc28 text query; null when it cannot be. Its turn's id. */
    fun askOnDisplay(question: String, conversation: String? = null): String? {
        val text = question.trim()
        val l = link
        if (plain(text).isEmpty() || !display() || l == null || l.m0 == null || l.s0 == null || typed != null) return null
        val id = UUID.randomUUID().toString()
        record(TurnRecord(id, System.currentTimeMillis(), text, source = TurnSource.PHONE, conversation = conversation.orEmpty()))
        val t = Typed(id, text)
        typed = t
        connection.submit { if (!t.asked.isCompleted) it.sendOnService(SVC_STATUS, SnApp.MT_SNINTENT, textQueryIntent(text)) }
        say("Asked the Display \"$text\"")
        scope.launch {
            val started = withTimeoutOrNull(TYPED_WAIT_MS) { t.started.await() } != null
            if (started) withTimeoutOrNull(TYPED_WAIT_MS) { t.asked.await() }
            // Taken by the glasses' words meanwhile, or now by the app.
            if (!t.asked.complete(Unit)) return@launch
            link?.typedOf?.values?.removeAll { it == id }
            if (typed === t) typed = null
            say(if (started) "The Display started the typed question but did not ask it; answering in the app" else "The Display did not take the typed question; answering in the app")
            answerHere(id)
        }
        return id
    }

    private fun liveAiNote(): String = LIVE_AI_NOTE + if (liveAiDetailed()) LIVE_AI_DETAILED else LIVE_AI_BRIEF

    /** [id]'s question again, with the model and settings as they are now; the new answer replaces the old one. */
    fun askAgain(id: String) {
        if (diary.turn(id) == null) return
        diary.update(id) { it.copy(answer = "", problem = "", remembered = emptyList(), actions = emptyList(), modelMs = 0) }
        scope.launch { answerHere(id) }
    }

    /** The conversation a question asked now from the app would continue, or null when it would start a new one. */
    fun openConversation(): String? = diary.openConversation(System.currentTimeMillis(), windowMs())

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
            val why = if (error is TimeoutCancellationException) "no answer in ${PHONE_BUDGET_MS / 1000} s" else error.why()
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
        if (System.currentTimeMillis() - lastAt < windowMs() || linesFailed[date] == lastAt || !store.config.value.hasModel) return
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
        return diary.past(conversation, before = id).map {
            // A silent camera turn keeps the actions' names as its diary answer; the model gets only Visor's note of them.
            Exchange(it.question, if (it.onlyActed) "" else it.answer, it.actions)
        }
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
        // What the glasses say when there is no model, and when the model fails, in each language the wearer can speak.
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
            "vi" to ("Visor đã kết nối, nhưng chưa có model AI. Hãy thêm API key trong ứng dụng." to "Xin lỗi, tôi không lấy được câu trả lời."),
            "ru" to ("Visor подключён, но модель ИИ ещё не настроена. Добавьте ключ API в приложении." to "Извините, не удалось получить ответ."),
            "uk" to ("Visor підключено, але модель ШІ ще не налаштовано. Додайте ключ API в застосунку." to "Вибачте, не вдалося отримати відповідь."),
            "pl" to ("Visor jest połączony, ale nie ma jeszcze skonfigurowanego modelu AI. Dodaj klucz API w aplikacji." to "Przepraszam, nie udało mi się uzyskać odpowiedzi."),
            "cs" to ("Visor je připojený, ale zatím není nastavený žádný model AI. Přidejte klíč API v aplikaci." to "Promiňte, nepodařilo se mi získat odpověď."),
            "ro" to ("Visor este conectat, dar încă nu este configurat niciun model AI. Adaugă o cheie API în aplicație." to "Îmi pare rău, nu am putut obține un răspuns."),
            "hu" to ("A Visor csatlakoztatva van, de még nincs beállítva AI-modell. Adj hozzá egy API-kulcsot az alkalmazásban." to "Sajnálom, nem sikerült választ kapnom."),
            "el" to ("Το Visor είναι συνδεδεμένο, αλλά δεν έχει ρυθμιστεί ακόμα μοντέλο AI. Προσθέστε ένα κλειδί API στην εφαρμογή." to "Συγγνώμη, δεν μπόρεσα να λάβω απάντηση."),
            "sv" to ("Visor är anslutet, men ingen AI-modell är inställd ännu. Lägg till en API-nyckel i appen." to "Tyvärr, jag kunde inte få något svar."),
            "da" to ("Visor er forbundet, men der er endnu ikke sat nogen AI-model op. Tilføj en API-nøgle i appen." to "Beklager, jeg kunne ikke få et svar."),
            "nb" to ("Visor er tilkoblet, men ingen AI-modell er satt opp ennå. Legg til en API-nøkkel i appen." to "Beklager, jeg fikk ikke noe svar."),
            "fi" to ("Visor on yhdistetty, mutta tekoälymallia ei ole vielä määritetty. Lisää API-avain sovelluksessa." to "Valitettavasti en saanut vastausta."),
            "he" to ("Visor מחובר, אבל עדיין לא הוגדר מודל AI. יש להוסיף מפתח API באפליקציה." to "סליחה, לא הצלחתי לקבל תשובה."),
            "ar" to ("Visor متصل، لكن لم يتم إعداد نموذج ذكاء اصطناعي بعد. أضف مفتاح API في التطبيق." to "عذرًا، لم أتمكن من الحصول على إجابة."),
            "id" to ("Visor sudah terhubung, tetapi belum ada model AI yang disiapkan. Tambahkan kunci API di aplikasi." to "Maaf, saya tidak bisa mendapatkan jawaban."),
            "th" to ("Visor เชื่อมต่อแล้ว แต่ยังไม่ได้ตั้งค่าโมเดล AI โปรดเพิ่มคีย์ API ในแอป" to "ขออภัย ไม่สามารถรับคำตอบได้"),
        )
        const val FACTORY_PROCESSING_S = 20L
        const val DELIVERY_MS = 4_000L
        const val HOLD_ON_MS = 3_000L
        // 5005 counts the PCM played at 96 bytes per millisecond.
        const val PCM_BYTES_PER_MS = 96L
        // Played this much short of the whole still counts as played.
        const val PLAYED_SLACK_MS = 10L
        // How long a question waits for the phone's own words in a speech language the glasses lack.
        const val OWN_WORDS_MS = 4_000L
        /** How long the glasses' own recognition gets to send its ev57 before the server's answer goes instead. */
        const val OWN_RECOGNITION_GRACE_MS = 1_200L
        // From the glasses' question to the phone's own words for it, in a language the glasses do not hear.
        const val HEARD_WAIT_MS = 8_000L
        const val MIC_END_WAIT_MS = 10_000L
        const val SESSION_USE_CASE = 7L
        const val LIVE_AI_USE_CASE = 3L
        const val REMOTE_SUCCESS = 12L
        const val WAKE_VERIFICATION_WAIT_MS = 5_000L
        const val SESSION_COMPLETED = 5L
        const val SESSION_STOPPED = 6L
        const val SESSION_STOPPING = 10L
        val PARTNER_END_STATES = setOf(SESSION_COMPLETED, SESSION_STOPPED, SESSION_STOPPING)
        const val REGISTER_AGAIN_MS = 2_500L
        // svc15's statuses for a channel its restarted assistant closed.
        val CHANNEL_GONE = setOf(0xc002, 0xc010)
        const val QUESTION_GRACE_MS = 3_000L
        // How long a 3p turn waits for the rest of its question before it is answered.
        const val PARTNER_JOIN_MS = 1_500L
        // ev57 field 11, invocationMethod: 1 for the wake word, 3 for the reply to an answer that asked something, 5 for
        // a question after any other answer.
        val FOLLOW_UP_INVOCATIONS = setOf(3L, 5L)
        const val FRESH_WINDOW_MS = 2 * 60_000L
        val WAKE_WORD_ONLY = Regex(
            "^((hey|hi|ehi|ei|ok|okay)?[ ,]*(meta|mata|metta|matta|meda)|(хей|хэй|хай|эй)([ ,]+(мета|мэта|метта|мэтта|это|вета))?|мета|мэта|метта|мэтта|вета|всем[ ,]+это)[.!?]*$",
            RegexOption.IGNORE_CASE,
        )
        // A wake phrase at the start of a question, with the punctuation after it.
        val WAKE_WORD_START = Regex(
            "^((hey|hi|ehi|ei|ok|okay)?[ ,]*(meta|mata|metta|matta|meda)|(хей|хэй|хай|эй)([ ,]+(мета|мэта|метта|мэтта|это|вета))?|мета|мэта|метта|мэтта|вета|всем[ ,]+это)[ ,.!?]+",
            RegexOption.IGNORE_CASE,
        )
        const val MIN_MODEL_MS = 6_000L
        const val PHOTO_WAIT_MS = 8_000L
        // Then the phone's engine speaks instead.
        const val CLOUD_SPEECH_WAIT_MS = 20_000L
        const val PHOTO_MAX_BYTES = 20_000_000L
        const val MCP_WAIT_MS = 8_000L
        // A turn waits this long at most for the glasses to describe tools it has not seen.
        const val DESCRIBE_WAIT_MS = 1_500L
        const val DESCRIBE_RETRY_MS = 60_000L
        const val INTENT_WAIT_MS = 5_000L
        // How long a question typed in the app waits for the Display to start it, then to ask it.
        const val TYPED_WAIT_MS = 5_000L
        // Some pairs send the question's photo on "Sharing" just before it; a low-res one is about 13 KB.
        const val EARLY_IMAGE_MS = 10_000L
        const val EARLY_MAX_BYTES = 2_000_000L
        // The glasses' OCR (ev65) follows the question within about 1.2 s.
        const val OCR_WAIT_MS = 2_500L
        const val MAX_OCR = 4_000

        // How long a reply asked for waits for its words.
        const val REPLY_WAIT_MS = 30_000L
        const val PHONE_BUDGET_MS = 60_000L
        // After a conversation closes, a moment before its day's lines are written.
        const val LINES_GRACE_MS = 2_000L
    }
}

/** What the glasses understand as giving up a reply. */
private val CANCEL_INTENTS = setOf("IN:CANCEL", "IN:NEVER_MIND", "IN:STOP", "IN:NEGATE_INTENT", "IN:CANCEL_MESSAGE")

/** The glasses' own device intents Visor hands back to them (ev380) on a Display: volume and brightness only. */
private val DEVICE_INTENTS = setOf(
    "IN:SET_VOLUME", "IN:INCREASE_VOLUME", "IN:DECREASE_VOLUME", "IN:MUTE_VOLUME", "IN:UNMUTE_VOLUME",
    "IN:SET_BRIGHTNESS", "IN:INCREASE_BRIGHTNESS", "IN:DECREASE_BRIGHTNESS", "IN:MAXIMIZE_BRIGHTNESS",
)

/** The text of [name] in the glasses' slot map (field 26): each slot is a list of {1 name, 4 text, 5 start, 6 end}. */
internal fun slot(slots: Any?, name: String): String =
    ((slots as? Map<*, *>)?.get(name) as? List<*>)?.firstOrNull().asStruct()[4].asText().trim()

/** 1026 is either the bare locale string or `{1: locale}`. */
private fun parseLocale(payload: ByteArray): Locale {
    val text = if (payload.size > 2 && payload[0] == 0x0a.toByte() && payload[1].toInt() == payload.size - 2) {
        payload.copyOfRange(2, payload.size).decodeToString()
    } else {
        payload.decodeToString()
    }
    return Locale.forLanguageTag(text.trim().replace('_', '-')).takeIf { it.language.isNotEmpty() } ?: Locale.US
}

private fun isUuid(s: String) = s.length == 36 && s.count { it == '-' } == 4

/** Letters and digits only, lower case: typed text and the glasses' words for it compare equal. */
private fun plain(s: String) = s.filter(Char::isLetterOrDigit).lowercase()

private fun hex(b: ByteArray): String = b.toHexString(0, minOf(24, b.size))

private fun present(s: String) = if (s.isEmpty()) "empty" else "present"

private fun Throwable.why() = message ?: this::class.simpleName.orEmpty()

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

/** The diary's answer for a turn that only acted: its actions' names. */
private fun List<ToolAction>.asAnswer() = joinToString { it.what }

/** A turn whose diary answer is only its actions' names. */
val TurnRecord.onlyActed: Boolean get() = actions.isNotEmpty() && answer == actions.asAnswer()

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

private const val APP_GROUP_CONVERSATIONS = 47L

/** ev289's mode for a Live AI session. */
private const val MODE_LIVE_AI = 2L

/** The most a NetEvent goes as in one piece, and the size of each piece of a bigger one, as the Meta app sends them. */
private const val SEGMENT_BYTES = 1_024

private const val LIVE_AI_NOTE =
    "This is Live AI: a continuous conversation while the wearer shows you what they see. The image is the camera's " +
        "view right now."

private const val LIVE_AI_BRIEF = " Answer briefly and naturally; speak only about what matters to the question."

private const val LIVE_AI_DETAILED = " The wearer asked for detailed answers: explain fully, with the details you can see, still in speech."
