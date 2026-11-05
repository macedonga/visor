package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.SVC_STATUS
import com.macedonga.visor.core.connection.SnApp
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** The glasses' hearing service: conversation focus and hearing correction report their state here and take their sound. */
const val SVC_HEARING = 31337

/** Where the hearing app is on the glasses. */
enum class HearingRun(val number: Long) {
    UNKNOWN(0), ON(1), PAUSED(2), PAUSED_BY_USER(4), OFF(3);

    val paused: Boolean get() = this == PAUSED || this == PAUSED_BY_USER

    companion object {
        fun of(n: Long) = entries.firstOrNull { it.number == n } ?: UNKNOWN
    }
}

/** What the hearing app runs: conversation focus, hearing correction, or the hearing test that sets it up. */
enum class HearingMode(val number: Long) {
    UNSET(0), FOCUS(1), CORRECTION(2), CORRECTION_SETUP(3), CORRECTION_FITTING(4), CORRECTION_NARROW(10);

    companion object {
        fun of(n: Long) = entries.firstOrNull { it.number == n } ?: UNSET
    }
}

enum class Ear(val side: Long) { LEFT(1), RIGHT(2) }

/** One ear's hearing test: the quietest level heard at 1, 2 and 4 kHz, in dB HL. */
data class Thresholds(val at1k: Int, val at2k: Int, val at4k: Int)

/** Hearing correction's sound: amplification, sharpness and balance (-3 to 3, balance negative to the left), and the louder-places style. */
data class CorrectionTone(val volume: Int = 0, val sharpness: Int = 0, val balance: Int = 0, val noisy: Boolean = false)

/** Why the glasses paused or ended it, as they report it. */
enum class HearingCause(val number: Long) {
    NONE(0), PHONE_CALL(1), MUSIC(2), LIVE_STREAMING(3), STREAMING_AUDIO(4), CAPTURING_VIDEO(5), THERMAL(7), SHUTDOWN(8), TIMEOUT(9), CRASH(10);

    companion object {
        fun of(n: Long) = entries.firstOrNull { it.number == n } ?: NONE
    }
}

/** Why the glasses refused to start it. */
enum class HearingRefusal(val number: Long) {
    UNKNOWN(0), NUX_REQUIRED(1), FREE_TRIAL_ENDED(2), SUBSCRIPTION_EXPIRED(3), GEOGATED(4), BT_REQUIRED(5),
    OUTSIDE_GRACE_PERIOD(6), INVALID_DEVICE(7), USER_SETTINGS_FAILED(8), NO_ACCESS(9), ENABLE_FAILED(10);

    companion object {
        fun of(n: Long) = entries.firstOrNull { it.number == n } ?: UNKNOWN
    }
}

/** The two styles the glasses take: Standard brings voices closer, Boosted further. */
enum class FocusStyle(val preset: Int) { STANDARD(1), BOOSTED(2) }

data class HearingState(
    val run: HearingRun = HearingRun.UNKNOWN,
    val cause: HearingCause = HearingCause.NONE,
    val style: FocusStyle? = null,
    /** How much louder the voice is, -2 to 2. */
    val volume: Int? = null,
    val refusal: HearingRefusal? = null,
    val mode: HearingMode = HearingMode.UNSET,
    /** Whether hearing correction has a profile from a hearing test; null until the glasses say. */
    val fitted: Boolean? = null,
    val correction: CorrectionTone? = null,
    /** When the glasses last said anything here. */
    val at: Long = 0,
)

/**
 * Conversation focus and hearing correction run from the phone, as the Meta app runs them. Starting and stopping go to
 * the app manager on svc28 (StartApp 2000 and StopApp 2002 for app 22, HEARING, with the mode and a session id as
 * intent data); focus's style and volume are a UserSetting (type 15) on the hearing service, correction's sound a
 * Personalization (type 4). The service reports its AppState (type 1) and why a start was refused (type 7). The hearing
 * test plays the tones the phone asks for (PlayTone 2, answered 17) and fits from the thresholds (HearingInputs 5,
 * FitComplete 6).
 */
class Hearing(private val connection: GlassesConnection) : Responder {
    private val _state = MutableStateFlow(HearingState())
    val state: StateFlow<HearingState> = _state.asStateFlow()

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    // The hearing channel the glasses opened and its link, answered on its mirror; until then Visor opens its own.
    @Volatile
    private var channel: Pair<DataXConnection, Int>? = null

    // The one tone or fit the phone is waiting on: the Meta app asks for one at a time.
    @Volatile
    private var toneAnswer: CompletableDeferred<Boolean>? = null

    @Volatile
    private var fitAnswer: CompletableDeferred<Boolean>? = null

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        channel = connection to message.info.channelId
        val f = runCatching { Wire.parse(message.payload) }.getOrNull() ?: return
        when (message.info.messageType) {
            MT_APP_STATE -> {
                val setting = if (f.has(5)) Wire.parse(f.bytes(5)) else null
                val tone = if (f.has(4)) Wire.parse(f.bytes(4)) else null
                val profile = if (f.has(8)) Wire.parse(f.bytes(8)) else null
                _state.update {
                    it.copy(
                        run = HearingRun.of(f.varint(1)),
                        cause = HearingCause.of(f.varint(2)),
                        style = setting?.takeIf { s -> s.has(13) }?.let { s -> FocusStyle.entries.firstOrNull { st -> st.preset.toLong() == s.varint(13) } } ?: it.style,
                        volume = setting?.takeIf { s -> s.has(2) }?.varint(2)?.toInt() ?: it.volume,
                        refusal = if (f.varint(1) == 1L) null else it.refusal,
                        mode = if (f.has(3)) HearingMode.of(f.varint(3)) else it.mode,
                        fitted = profile?.let { p -> p.varint(1) == 1L } ?: it.fitted,
                        correction = tone?.let { t ->
                            CorrectionTone(t.varint(1).toInt(), t.varint(2).toInt(), t.varint(3).toInt(), t.varint(4) == ENVIRONMENT_NOISY)
                        } ?: it.correction,
                        at = System.currentTimeMillis(),
                    )
                }
                val s = _state.value
                note(
                    "${s.mode.name.lowercase()}: ${s.run.name.lowercase()}" + (if (f.has(2)) " (${s.cause.name.lowercase()})" else "") +
                        (s.fitted?.let { if (it) ", fitted" else ", not fitted" } ?: ""),
                )
            }
            MT_ACTIVATION_FAILURE -> {
                val why = HearingRefusal.of(f.varint(1))
                _state.update { it.copy(refusal = why, run = HearingRun.OFF, at = System.currentTimeMillis()) }
                note("Start refused: ${why.name.lowercase()}")
            }
            MT_USER_SETTING_FAILURE -> note("Conversation focus did not take the style or volume")
            MT_PLAY_TONE_RESPONSE -> {
                val ok = f.varint(3) == 1L
                toneAnswer?.complete(ok)
                if (!ok) note("Tone not played (code ${f.varint(2)})")
            }
            MT_FIT_COMPLETE -> {
                val ok = f.varint(1) == 1L
                fitAnswer?.complete(ok)
                note(if (ok) "Hearing correction profile made" else "Hearing correction profile failed")
            }
            MT_PERSONALIZATION_FAILURE -> note("Hearing correction did not take the sound")
            // EstimatedNoiseLevel {1 float, 2 timestamp}, four a second while the hearing app runs.
            MT_NOISE_LEVEL -> Unit
            else -> note("message type ${message.info.messageType} not handled")
        }
    }

    override suspend fun closed(channelId: Int, connection: DataXConnection) {
        if (channel?.let { it.first === connection && it.second == channelId } == true) channel = null
    }

    /** Start conversation focus now, in [style] when given. */
    fun start(style: FocusStyle?) = startApp(HearingMode.FOCUS, style?.preset)

    /** Start hearing correction, or with [test] the hearing test that makes its profile. */
    fun startCorrection(test: Boolean = false) = startApp(if (test) HearingMode.CORRECTION_SETUP else HearingMode.CORRECTION, null)

    private fun startApp(mode: HearingMode, preset: Int?) {
        val data = listOfNotNull(
            SnApp.data("requestedMode", mode.number.toInt()), SnApp.data("hearingTrigger", TRIGGER_PHONE),
            SnApp.data("hearingSessionId", UUID.randomUUID().toString()),
            preset?.let { SnApp.data("enhancementPreset", it) },
        )
        _state.update { it.copy(refusal = null) }
        connection.submit { it.sendOnService(SVC_STATUS, SnApp.MT_START_APP, SnApp.app(APP_HEARING, data)) }
        note("Starting ${mode.name.lowercase()}")
    }

    fun stop() {
        connection.submit { it.sendOnService(SVC_STATUS, SnApp.MT_STOP_APP, SnApp.app(APP_HEARING)) }
        note("Stopping the hearing app")
    }

    /** Wait for the glasses to run [mode]: true once they do, false on a refusal or after [ms]. */
    suspend fun awaitRunning(mode: HearingMode, ms: Long = ANSWER_MS): Boolean {
        val s = withTimeoutOrNull(ms) { state.first { (it.mode == mode && it.run == HearingRun.ON) || it.refusal != null } }
        return s != null && s.refusal == null
    }

    /** Wait for the hearing app to stop, or [ms]. */
    suspend fun awaitStopped(ms: Long = ANSWER_MS) {
        withTimeoutOrNull(ms) { state.first { it.run == HearingRun.OFF || it.run == HearingRun.UNKNOWN } }
    }

    /** Play one test tone in [ear] at [dbHl] (10 to 70), or stop it; true once the glasses say they played it. */
    suspend fun playTone(ear: Ear, hz: Int, dbHl: Int, on: Boolean = true): Boolean {
        val answer = CompletableDeferred<Boolean>()
        toneAnswer = answer
        sendHearing(
            MT_PLAY_TONE,
            Wire.fieldVarint(1, ear.side) + Wire.fieldVarint(2, hz.toLong()) + Wire.fieldVarint(3, dbHl.coerceIn(MIN_DBHL, MAX_DBHL).toLong()) +
                Wire.fieldVarint(4, if (on) 1 else 0) + Wire.fieldVarint(5, Random.nextInt(1, Int.MAX_VALUE).toLong()),
        )
        return withTimeoutOrNull(ANSWER_MS) { answer.await() } ?: false
    }

    /** Give the glasses both ears' thresholds; true once they made the profile from them. */
    suspend fun fit(left: Thresholds, right: Thresholds): Boolean {
        val answer = CompletableDeferred<Boolean>()
        fitAnswer = answer
        fun side(t: Thresholds) = listOf(t.at1k, t.at2k, t.at4k).withIndex()
            .fold(ByteArray(0)) { out, (i, v) -> out + Wire.fieldVarint(i + 1, v.coerceIn(MIN_DBHL, MAX_DBHL).toLong()) }
        sendHearing(MT_HEARING_INPUTS, Wire.fieldBytes(1, side(left)) + Wire.fieldBytes(2, side(right)) + Wire.fieldString(3, UUID.randomUUID().toString()))
        note("Hearing test sent")
        return withTimeoutOrNull(FIT_MS) { answer.await() } ?: false
    }

    /** Hearing correction's sound, applied at once while it runs. */
    fun tuneCorrection(tone: CorrectionTone) {
        val t = tone.copy(
            volume = tone.volume.coerceIn(MIN_TONE, MAX_TONE),
            sharpness = tone.sharpness.coerceIn(MIN_TONE, MAX_TONE),
            balance = tone.balance.coerceIn(MIN_TONE, MAX_TONE),
        )
        sendHearing(
            MT_PERSONALIZATION,
            Wire.fieldVarintUnsigned(1, t.volume.toLong()) + Wire.fieldVarintUnsigned(2, t.sharpness.toLong()) +
                Wire.fieldVarintUnsigned(3, t.balance.toLong()) + Wire.fieldVarint(4, if (t.noisy) ENVIRONMENT_NOISY else ENVIRONMENT_REGULAR),
        )
        _state.update { it.copy(correction = t) }
    }

    /** Carry on after a pause. */
    fun resume() = sendHearing(MT_RESUME, Wire.fieldString(1, UUID.randomUUID().toString()))

    /** The style and how much louder the voice is (-2 to 2): applied at once while it runs, kept for next time otherwise. */
    fun tune(style: FocusStyle, volume: Int) {
        val v = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        sendHearing(
            MT_USER_SETTING,
            Wire.fieldVarintUnsigned(2, v.toLong()) + Wire.fieldString(10, "1") +
                Wire.fieldString(11, UUID.randomUUID().toString()) + Wire.fieldVarint(13, style.preset.toLong()),
        )
        _state.update { it.copy(style = style, volume = v) }
    }

    private fun sendHearing(type: Int, payload: ByteArray) {
        connection.submit { mux ->
            val open = channel?.takeIf { it.first === mux }?.second
            if (open != null) mux.send(DataXConnection.mirror(open), payload, messageType = type)
            else mux.sendOnService(SVC_HEARING, type, payload)
        }
    }

    private fun note(text: String) {
        _notes.tryEmit(text)
    }

    companion object {
        const val MIN_VOLUME = -2
        const val MAX_VOLUME = 2
        const val DEFAULT_VOLUME = 1
        const val MIN_TONE = -3
        const val MAX_TONE = 3
        const val MIN_DBHL = 10
        const val MAX_DBHL = 70
        private const val ANSWER_MS = 5_000L
        private const val FIT_MS = 15_000L
        private const val ENVIRONMENT_REGULAR = 1L
        private const val ENVIRONMENT_NOISY = 2L
        private const val APP_HEARING = 22L
        private const val TRIGGER_PHONE = 9
        private const val MT_APP_STATE = 1
        private const val MT_PLAY_TONE = 2
        private const val MT_PERSONALIZATION = 4
        private const val MT_HEARING_INPUTS = 5
        private const val MT_FIT_COMPLETE = 6
        private const val MT_ACTIVATION_FAILURE = 7
        private const val MT_NOISE_LEVEL = 8
        private const val MT_USER_SETTING = 15
        private const val MT_PLAY_TONE_RESPONSE = 17
        private const val MT_USER_SETTING_FAILURE = 18
        private const val MT_RESUME = 20
        private const val MT_PERSONALIZATION_FAILURE = 22
    }
}
