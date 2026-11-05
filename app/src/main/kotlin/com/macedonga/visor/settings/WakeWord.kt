package com.macedonga.visor.settings

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.assistant.AssistantPhase
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.asBool
import com.macedonga.visor.language.VOICE_KEY
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.pairs.pairPrefs
import com.macedonga.visor.stage.LightLook
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.stage.StageCue
import com.macedonga.visor.stage.StoryPage
import com.macedonga.visor.stage.lookOf
import com.macedonga.visor.stage.nod
import com.macedonga.visor.stage.shake
import com.macedonga.visor.ui.VisorIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Where a custom wake word enrolment is. */
sealed interface WakeStep {
    data object Phrase : WakeStep
    data class Take(val index: Int, val recording: Boolean = false, val missed: Boolean = false) : WakeStep
    data object Enrolling : WakeStep
    data class Enrolled(val phrase: String) : WakeStep

    /** [why] is the glasses' own words when they gave any, else [fallback]. */
    data class Failed(val why: String?, val fallback: Int) : WakeStep
}

/** A custom wake word enrolled on the glasses: the phrase, three takes on their microphone, their answer. Each visit starts over. */
@HiltViewModel
class WakeWordModel @Inject constructor(
    private val assistant: Assistant,
    val settings: GlassesSettings,
    connection: GlassesConnection,
    @ApplicationContext context: Context,
) : ViewModel() {
    // Enrolled on the glasses: each pair has its own phrase.
    private val prefs = pairPrefs(context, "wake_word", connection.pair.value)

    private val _enrolled = MutableStateFlow(prefs.getString(ENROLLED, null)?.ifBlank { null })

    /** The phrase last enrolled from here. */
    val enrolled: StateFlow<String?> = _enrolled.asStateFlow()

    private val _step = MutableStateFlow<WakeStep>(WakeStep.Phrase)
    val step: StateFlow<WakeStep> = _step.asStateFlow()

    private val _phrase = MutableStateFlow("")
    val phrase: StateFlow<String> = _phrase.asStateFlow()

    /** How loud the voice is in the take under way, 0 to 1. */
    val level: StateFlow<Float> = assistant.wakeWord.level

    val phase: StateFlow<AssistantPhase> = assistant.phase

    private val clips = mutableListOf<ByteArray>()
    private var session: String? = null
    private var starting: Job? = null

    init {
        // A success that comes after the wait gave up still counts, here and on the screen.
        viewModelScope.launch {
            assistant.wakeWord.results.collect { r ->
                if (!r.success) return@collect
                val name = r.wakeword.ifEmpty { _phrase.value }
                // The Meta AI row's own model has no phrase of its own.
                if (name.isBlank()) return@collect
                keep(name)
                if (_step.value is WakeStep.Failed || _step.value is WakeStep.Enrolling) _step.value = WakeStep.Enrolled(name)
            }
        }
    }

    private fun keep(name: String) {
        prefs.edit().putString(ENROLLED, name).apply()
        _enrolled.value = name
    }

    fun turnOff() {
        settings.write(CWW_KEY, "false")
    }

    fun enter() {
        clips.clear()
        _step.value = WakeStep.Phrase
        // A session left open (the app killed, a reinstall) keeps the glasses from waking on "Hey Meta".
        (prefs.getStringSet(OPEN, null).orEmpty() - setOfNotNull(session)).forEach(::close)
    }

    /** Leaving mid-enrolment closes the session, so the glasses wake on the phrase again. */
    fun leave() {
        val open = session ?: return
        session = null
        close(open)
    }

    // Not on viewModelScope: leaving clears the model, and the close must still go out. One that did not stays listed.
    private fun close(id: String) {
        lasting.launch {
            if (assistant.wakeWord.end(id)) mark(id, open = false)
        }
    }

    private fun opened(id: String) = mark(id, open = true)

    private fun mark(id: String, open: Boolean) = synchronized(prefs) {
        val ids = prefs.getStringSet(OPEN, null).orEmpty()
        prefs.edit().putStringSet(OPEN, if (open) ids + id else ids - id).apply()
    }

    override fun onCleared() = leave()

    fun setPhrase(text: String) {
        _phrase.value = text.trim()
    }

    fun begin() {
        val text = _phrase.value
        if (text.length < MIN_LETTERS || starting?.isActive == true) return
        clips.clear()
        leave()
        starting = viewModelScope.launch {
            session = assistant.wakeWord.begin(text)?.also(::opened) ?: run {
                _step.value = WakeStep.Failed(null, R.string.ww_no_link)
                return@launch
            }
            _step.value = WakeStep.Take(0)
        }
    }

    fun record() {
        val take = _step.value as? WakeStep.Take ?: return
        if (take.recording) return
        _step.value = take.copy(recording = true, missed = false)
        viewModelScope.launch {
            val clip = assistant.wakeWord.record()
            if (clip == null || clip.size < MIN_BYTES) {
                _step.value = take.copy(recording = false, missed = true)
                return@launch
            }
            clips += clip
            if (clips.size < TAKES) _step.value = WakeStep.Take(clips.size) else enroll()
        }
    }

    private suspend fun enroll() {
        _step.value = WakeStep.Enrolling
        val text = _phrase.value
        val result = assistant.wakeWord.enroll(text, clips.toList())
        leave()
        if (result?.success == true) {
            val name = result.wakeword.ifEmpty { text }
            keep(name)
            _step.value = WakeStep.Enrolled(name)
        } else if (_step.value is WakeStep.Enrolling) {
            _step.value = WakeStep.Failed(result?.error?.ifEmpty { null }, R.string.ww_failed_body)
        }
    }

    fun retry() {
        leave()
        clips.clear()
        _step.value = WakeStep.Phrase
    }

    companion object {
        const val TAKES = 3
        const val MIN_LETTERS = 4
        // A quarter second of 16 kHz 16-bit audio: anything shorter is no phrase.
        private const val MIN_BYTES = 8_000
        private const val ENROLLED = "enrolled"
        private const val OPEN = "open_sessions"
        private val lasting = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** The enrolment on the stage: the phrase, three takes, the glasses' answer. */
@Composable
fun WakeWordScreen(model: WakeWordModel = hiltViewModel()) = SettingsHost(seeThrough = true) {
    val step by model.step.collectAsStateWithLifecycle()
    val phrase by model.phrase.collectAsStateWithLifecycle()
    val level by model.level.collectAsStateWithLifecycle()
    val stage = LocalStage.current
    val host = LocalHost.current
    val colors = Visor.colors
    var editing by remember { mutableStateOf(false) }
    DisposableEffect(model) {
        model.enter()
        onDispose { model.leave() }
    }

    val cue = remember { StageCue() }
    val take = step as? WakeStep.Take
    val recording = take?.recording == true
    cue.yaw = if (take != null) FRONT_YAW else 0f
    cue.orbit = step == WakeStep.Enrolling
    cue.source = if (recording) "front" else null
    cue.inward = true
    cue.level = if (recording) 0.25f + 0.75f * level else 0f
    val look = when {
        recording -> LightLook(colors.lightNeutral, colors.lightWarm, level = 0.85f, spread = 1f, breathSeconds = 2.4f, breathDepth = 0.15f)
        step == WakeStep.Enrolling -> lookOf(Mood.SEARCHING, colors).copy(breathSeconds = 2.2f)
        step is WakeStep.Failed -> lookOf(Mood.FAR, colors)
        else -> lookOf(Mood.CONNECTED, colors)
    }
    // A take that came back with nothing, a take that counted, the end either way.
    val beat = when (val s = step) {
        is WakeStep.Take -> if (s.missed) "missed-${s.index}" else "take-${s.index}"
        else -> s::class.simpleName.orEmpty()
    }
    LaunchedEffect(beat) {
        when (val s = step) {
            is WakeStep.Take -> when {
                s.missed -> {
                    host.haptics.error()
                    stage.light.flicker()
                    stage.pose.shake()
                }
                s.index > 0 && !s.recording -> {
                    host.haptics.tick(0.7f)
                    stage.pose.nod()
                }
            }
            is WakeStep.Enrolled -> {
                host.haptics.connected()
                stage.light.flash()
                delay(400)
                stage.pose.nod()
            }
            is WakeStep.Failed -> {
                host.haptics.error()
                stage.light.flicker()
                stage.pose.shake()
            }
            else -> Unit
        }
    }

    StoryPage(
        stringResource(R.string.ww_title), cue, look, if (take != null) 1f else 0.85f, step,
        key = { it::class to (it as? WakeStep.Take)?.index },
    ) { shown ->
        WakeStepContent(shown, phrase, model) { editing = true }
    }
    if (editing) {
        TextSheet(
            stringResource(R.string.ww_phrase), phrase, onSave = model::setPhrase, onDismiss = { editing = false },
            hint = "Hey Jarvis", valid = { it.trim().length >= WakeWordModel.MIN_LETTERS },
        )
    }
}

@Composable
private fun WakeStepContent(step: WakeStep, phrase: String, model: WakeWordModel, edit: () -> Unit) {
    val back = LocalBack.current
    // The step that is shown, not the newest: a take keeps its own words while the next one comes in.
    val current by model.step.collectAsStateWithLifecycle()
    when (step) {
        WakeStep.Phrase -> {
            val phase by model.phase.collectAsStateWithLifecycle()
            val state by model.settings.state.collectAsStateWithLifecycle()
            val linked = phase == AssistantPhase.LISTENING || phase == AssistantPhase.THINKING || phase == AssistantPhase.SPEAKING
            val on = (state.stored[CWW_KEY] ?: model.settings.value(CWW_KEY))?.let { asBool(it) } == true
            StoryWords(stringResource(R.string.ww_phrase_title), null)
            Box(Modifier.padding(top = 20.dp)) {
                ChoicePill(phrase.ifEmpty { stringResource(R.string.ww_phrase) }, edit)
            }
            // The glasses use a custom phrase only in en_US.
            if (model.settings.value(VOICE_KEY)?.startsWith("en_US") != true) {
                Text(stringResource(R.string.ww_english), style = Visor.type.label, color = Visor.colors.textSecondary, modifier = Modifier.padding(top = 12.dp))
            }
            val host = LocalHost.current
            Row(Modifier.padding(top = 28.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (on) {
                    WidePill(stringResource(R.string.ww_turn_off), model::turnOff, quiet = true, enabled = host.ready && CWW_KEY !in state.pending)
                    Spacer(Modifier.width(12.dp))
                }
                WidePill(stringResource(R.string.ww_continue), model::begin, Modifier.weight(1f), enabled = linked && phrase.length >= WakeWordModel.MIN_LETTERS)
            }
            when (phase) {
                AssistantPhase.OFF -> R.string.ww_no_link
                AssistantPhase.WAITING -> R.string.settings_locked
                else -> null
            }?.let { Text(stringResource(it), style = Visor.type.label, color = Visor.colors.textSecondary, modifier = Modifier.padding(top = 12.dp)) }
        }
        is WakeStep.Take -> {
            val live = (current as? WakeStep.Take)?.takeIf { it.index == step.index } ?: step
            val count = stringResource(R.string.ww_take_count, live.index + 1, WakeWordModel.TAKES)
            TakeDots(live.index, Modifier.padding(bottom = 14.dp).clearAndSetSemantics { contentDescription = count })
            StoryWords(
                stringResource(R.string.ww_take_title, phrase),
                when {
                    live.recording -> stringResource(R.string.ww_listening)
                    live.missed -> stringResource(R.string.ww_missed)
                    else -> null
                },
            ) {
                WidePill(stringResource(R.string.ww_record), model::record, Modifier.fillMaxWidth(), enabled = !live.recording)
            }
        }
        WakeStep.Enrolling -> StoryWords(stringResource(R.string.ww_enrolling), null)
        is WakeStep.Enrolled -> StoryWords(stringResource(R.string.ww_done_title), stringResource(R.string.ww_done_body, step.phrase)) {
            WidePill(stringResource(R.string.done), back, Modifier.fillMaxWidth())
        }
        is WakeStep.Failed -> StoryWords(stringResource(R.string.ww_failed_title), step.why ?: stringResource(step.fallback)) {
            WidePill(stringResource(R.string.try_again), model::retry, Modifier.fillMaxWidth())
        }
    }
}

/** Three takes: done ones white, the rest grey. */
@Composable
private fun TakeDots(done: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in 0 until WakeWordModel.TAKES) {
            val fill by animateColorAsState(if (k < done) Color.White else Visor.colors.control, tween(300), label = "dot")
            Box(Modifier.size(width = 22.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(fill))
        }
    }
}

/** The pair faces the viewer square on while it listens: rest is -24 degrees. */
private const val FRONT_YAW = 24f

/**
 * The custom wake word's row on the Meta AI page. The glasses' own switch says whether there is one (they set it when
 * an enrolment takes); the phrase is what Visor kept from that enrolment, if it saw the answer.
 */
@Composable
fun WakeWordRow(onClick: () -> Unit, model: WakeWordModel = hiltViewModel()) {
    val enrolled by model.enrolled.collectAsStateWithLifecycle()
    val state by model.settings.state.collectAsStateWithLifecycle()
    val on = (state.stored[CWW_KEY] ?: model.settings.value(CWW_KEY))?.let { asBool(it) }
    val value = when (on) {
        true -> enrolled ?: stringResource(R.string.ww_on)
        false -> stringResource(R.string.settings_not_set)
        null -> enrolled ?: stringResource(R.string.settings_not_set)
    }
    NavRow(VisorIcons.Mic, stringResource(R.string.ww_title), value, onClick = onClick, onCard = true)
}

/** With a custom phrase on the glasses, turns hatch_enabled on after each read: newer firmware wakes on the phrase only with it. */
fun keepMuseWithWakeWord(scope: CoroutineScope, settings: GlassesSettings, log: EventLog) {
    scope.launch {
        settings.state.map { it.readAt }.distinctUntilChanged().collect { readAt ->
            if (readAt == null || settings.value(CWW_KEY)?.let { asBool(it) } != true) return@collect
            val state = settings.state.value
            if (MUSE_KEY in state.pending || MUSE_KEY in state.kept || settings.value(MUSE_KEY)?.let { asBool(it) } == true) return@collect
            if (settings.writeQuietly(MUSE_KEY, "true")) log.add("Custom wake word: turning on the glasses' Muse switch, which newer firmware wants for it")
        }
    }
}

private const val CWW_KEY = "custom_wake_word_enabled"
private const val MUSE_KEY = "hatch_enabled"
