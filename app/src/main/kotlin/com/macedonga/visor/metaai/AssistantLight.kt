package com.macedonga.visor.metaai

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.macedonga.visor.core.assistant.AssistantPhase
import com.macedonga.visor.core.assistant.LiveAiPhase
import com.macedonga.visor.core.assistant.TurnRecord
import com.macedonga.visor.core.assistant.TurnSource
import com.macedonga.visor.core.design.VisorColors
import com.macedonga.visor.stage.LightFrame
import com.macedonga.visor.stage.LightLook
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** What the assistant is doing, as its light tells it. */
enum class AssistantMood { UNSET, REST, LISTENING, THINKING, SPEAKING }

fun assistantMood(
    attention: String?,
    phase: AssistantPhase,
    live: LiveAiPhase,
    phoneListening: Boolean,
    waiting: Boolean,
    speaking: Boolean,
    configured: Boolean,
): AssistantMood = when {
    phoneListening -> AssistantMood.LISTENING
    speaking || attention == "RESPONDING" -> AssistantMood.SPEAKING
    waiting || attention == "PROCESSING" || phase == AssistantPhase.THINKING || live == LiveAiPhase.THINKING -> AssistantMood.THINKING
    attention == "WAKE" || attention == "LISTENING" || live == LiveAiPhase.LISTENING -> AssistantMood.LISTENING
    !configured -> AssistantMood.UNSET
    else -> AssistantMood.REST
}

/** The assistant light for [mood], leaning towards [tint] when the question had a photo. */
fun lookOf(mood: AssistantMood, c: VisorColors, tint: Color?): LightLook {
    val core = tint?.let { lerp(c.lightNeutral, it, 0.5f) } ?: c.lightNeutral
    val rim = tint?.let { lerp(c.lightWarm, it, 0.75f) } ?: c.lightWarm
    return when (mood) {
        AssistantMood.UNSET -> LightLook(c.lightCold, c.lightCold, level = 0.1f, spread = 0.8f, warp = 0.1f)
        AssistantMood.REST -> LightLook(core, rim, level = 0.34f, spread = 0.9f, breathSeconds = 9f, breathDepth = 0.08f, warp = 0.16f)
        AssistantMood.LISTENING -> LightLook(
            lerp(core, c.lightCold, 0.16f), lerp(rim, c.lightCold, 0.3f),
            level = 0.62f, spread = 1f, breathSeconds = 1.8f, breathDepth = 0.35f, warp = 0.22f,
        )
        AssistantMood.THINKING -> LightLook(core, rim, level = 0.52f, spread = 0.95f, breathSeconds = 1.1f, breathDepth = 0.2f, warp = 0.5f, pace = 7f)
        AssistantMood.SPEAKING -> LightLook(core, rim, level = 0.68f, spread = 1.02f, warp = 0.24f, pace = 2f)
    }
}

/** How much of the newest answer shows: all of it, except while it is being spoken or written out. */
@Stable
class Speech {
    var id by mutableStateOf<String?>(null)
        internal set
    /** Characters of [id]'s answer shown so far. */
    var shown by mutableIntStateOf(Int.MAX_VALUE)
        internal set

    val active: Boolean get() = id != null

    fun shownOf(turn: TurnRecord): Int = if (turn.interactionId == id) shown else Int.MAX_VALUE
}

/** Reveals the newest answer word by word while the glasses speak it (svc28 attention); phone answers type out fast. */
@Composable
fun rememberSpeech(turns: List<TurnRecord>, attention: () -> String?, light: LightFrame): Speech {
    val speech = remember { Speech() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("meta_ai", Context.MODE_PRIVATE) }
    val newest = turns.lastOrNull()
    val current by rememberUpdatedState(newest)
    // Already answered when the page opened: shown whole unless the glasses are still saying it.
    val answeredAtEntry = remember { newest?.takeIf { it.answer.isNotEmpty() }?.interactionId }
    LaunchedEffect(newest?.interactionId, newest?.answer?.isNotEmpty()) {
        val turn = current ?: return@LaunchedEffect
        val answer = turn.answer
        if (answer.isEmpty() || turn.problem.isNotEmpty() || System.currentTimeMillis() - turn.at > FRESH_MS ||
            (turn.interactionId == answeredAtEntry && attention() != "RESPONDING")
        ) {
            speech.id = null
            return@LaunchedEffect
        }
        speech.id = turn.interactionId
        speech.shown = 0
        try {
            val phone = turn.source == TurnSource.PHONE
            var cps = prefs.getFloat("speech_cps", DEFAULT_CPS)
            val began = if (phone) null else withTimeoutOrNull(SPEAK_WAIT_MS) { snapshotFlow { attention() }.first { it == "RESPONDING" } }
            val glasses = began != null
            val durationMs = if (phone) (answer.length * 1000f / TYPE_CPS).coerceIn(500f, 3500f) else answer.length * 1000f / cps
            val start = withFrameMillis { it }
            var words = 0
            while (true) {
                val now = withFrameMillis { it }
                val elapsed = now - start
                val done = glasses && elapsed > STOP_GRACE_MS && attention() != "RESPONDING"
                val fraction = if (done) 1f else (elapsed / durationMs).coerceAtMost(if (glasses) HOLD_BACK else 1f)
                val cut = wordEnd(answer, (fraction * answer.length).toInt())
                if (cut > speech.shown) {
                    val spoken = answer.substring(0, cut).count { it == ' ' }
                    if (spoken > words) light.pulse(if (phone) 0.06f else 0.1f + Random.nextFloat() * 0.08f)
                    words = spoken
                    speech.shown = cut
                }
                if (done || (!glasses && cut >= answer.length)) {
                    // The glasses' own pace, for the next answer.
                    if (glasses && elapsed > MIN_MEASURE_MS) {
                        cps = cps * 0.6f + answer.length * 1000f / elapsed * 0.4f
                        prefs.edit().putFloat("speech_cps", cps.coerceIn(8f, 30f)).apply()
                    }
                    break
                }
            }
        } finally {
            speech.id = null
        }
    }
    return speech
}

/** [at] moved on to the end of the word it falls in. */
private fun wordEnd(text: String, at: Int): Int {
    if (at <= 0) return 0
    if (at >= text.length) return text.length
    val space = text.indexOf(' ', at)
    return if (space < 0) text.length else space
}

// An answer older than this is history, shown whole.
private const val FRESH_MS = 90_000L
private const val SPEAK_WAIT_MS = 4_000L
private const val STOP_GRACE_MS = 500L
private const val HOLD_BACK = 0.96f
private const val MIN_MEASURE_MS = 1_200L
private const val DEFAULT_CPS = 15f
private const val TYPE_CPS = 90f
