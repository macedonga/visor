package com.macedonga.visor.settings

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.asBool
import com.macedonga.visor.features.ChoicePill
import com.macedonga.visor.features.StoryWords
import com.macedonga.visor.pairs.pairPrefs
import com.macedonga.visor.setup.BigPill
import com.macedonga.visor.stage.LightLook
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.stage.STAGE_ASPECT
import com.macedonga.visor.stage.StageCue
import com.macedonga.visor.stage.StoryStage
import com.macedonga.visor.stage.lookOf
import com.macedonga.visor.stage.nod
import com.macedonga.visor.stage.shake
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * A custom wake word, enrolled on the glasses as the Meta app does it on the device: the phrase, three recordings on
 * the glasses' microphone, and the glasses' answer. The model outlives the screen, so each visit starts over.
 */
@HiltViewModel
class WakeWordModel @Inject constructor(
    private val assistant: Assistant,
    val settings: GlassesSettings,
    connection: GlassesConnection,
    @ApplicationContext context: Context,
) : ViewModel() {
    // Enrolled on the glasses: each pair has its own phrase.
    private val prefs = pairPrefs(context, "wake_word", connection.pair.value)

    private val _enrolled = MutableStateFlow(prefs.getString(ENROLLED, null))

    /** The phrase last enrolled from here. */
    val enrolled: StateFlow<String?> = _enrolled.asStateFlow()

    private val _step = MutableStateFlow<WakeStep>(WakeStep.Phrase)
    val step: StateFlow<WakeStep> = _step.asStateFlow()

    private val _phrase = MutableStateFlow("")
    val phrase: StateFlow<String> = _phrase.asStateFlow()

    /** How loud the voice is in the take under way, 0 to 1. */
    val level: StateFlow<Float> = assistant.wakeWord.level

    private val clips = mutableListOf<ByteArray>()
    private var session: String? = null

    init {
        // A success that comes after the wait gave up still counts, here and on the screen.
        viewModelScope.launch {
            assistant.wakeWord.results.collect { r ->
                if (!r.success) return@collect
                val name = r.wakeword.ifEmpty { _phrase.value }
                remember(name)
                if (_step.value is WakeStep.Failed || _step.value is WakeStep.Enrolling) _step.value = WakeStep.Enrolled(name)
            }
        }
    }

    private fun remember(name: String) {
        prefs.edit().putString(ENROLLED, name).apply()
        _enrolled.value = name
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
        if (text.length < MIN_LETTERS) return
        clips.clear()
        leave()
        viewModelScope.launch {
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
            remember(name)
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

/**
 * The enrolment told as the setup tells its story: the pair waits under its light while the phrase is chosen; while a
 * take records, the voice is drawn going into the front of the frame and the stage light swells with it; a good take gets a nod, a missed one a shake; the pair sways while the glasses make the model, and
 * flashes and nods when it is theirs.
 */
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val stagePx = with(density) { (maxWidth / STAGE_ASPECT).toPx() }
        StoryStage(cue, look, with(density) { (top + BAR + STAGE_ZONE / 2).toPx() }, if (take != null) 1f else 0.85f, stagePx)
        AnimatedContent(
            targetState = step,
            contentKey = { it::class to (it as? WakeStep.Take)?.index },
            // A step dissolves into the next: nothing slides, and the size stays put.
            transitionSpec = { (fadeIn(tween(380, delayMillis = 90)) togetherWith fadeOut(tween(260))) using null },
            label = "step",
        ) { shown ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(top = top + BAR + STAGE_ZONE, bottom = bottom + 20.dp).padding(horizontal = 24.dp),
            ) {
                WakeStepContent(shown, phrase, model) { editing = true }
            }
        }
        TopBar(stringResource(R.string.ww_title))
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
            StoryWords(stringResource(R.string.ww_phrase_title), stringResource(R.string.ww_phrase_body))
            Box(Modifier.padding(top = 20.dp)) {
                ChoicePill(phrase.ifEmpty { stringResource(R.string.ww_phrase_short) }, edit)
            }
            // The glasses' check (libassistantruntime): one locale, en_US, else the usual engine.
            if (model.settings.value("assistant_voice_id")?.startsWith("en_US") != true) {
                Text(stringResource(R.string.ww_english), style = Visor.type.label, color = Visor.colors.textSecondary, modifier = Modifier.padding(top = 12.dp))
            }
            Box(Modifier.padding(top = 28.dp)) {
                BigPill(stringResource(R.string.ww_continue), model::begin, enabled = phrase.length >= WakeWordModel.MIN_LETTERS)
            }
        }
        is WakeStep.Take -> {
            val live = (current as? WakeStep.Take)?.takeIf { it.index == step.index } ?: step
            TakeDots(live.index, Modifier.padding(bottom = 14.dp))
            StoryWords(
                stringResource(R.string.ww_take_title, phrase),
                when {
                    live.recording -> stringResource(R.string.ww_listening)
                    live.missed -> stringResource(R.string.ww_missed)
                    else -> stringResource(R.string.ww_take, live.index + 1, WakeWordModel.TAKES)
                },
            ) {
                BigPill(stringResource(R.string.ww_record), model::record, enabled = !live.recording)
            }
        }
        WakeStep.Enrolling -> StoryWords(stringResource(R.string.ww_enrolling), null)
        is WakeStep.Enrolled -> StoryWords(stringResource(R.string.ww_done_title), stringResource(R.string.ww_done_body, step.phrase)) {
            BigPill(stringResource(R.string.features_restart_done), back)
        }
        is WakeStep.Failed -> StoryWords(stringResource(R.string.ww_failed_title), step.why ?: stringResource(step.fallback)) {
            BigPill(stringResource(R.string.hct_retry), model::retry)
        }
    }
}

/** Three takes: done ones white, the one to come outlined. */
@Composable
private fun TakeDots(done: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in 0 until WakeWordModel.TAKES) {
            val fill by animateColorAsState(if (k < done) Color.White else Visor.colors.control, tween(300), label = "dot")
            Box(Modifier.size(width = 22.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(fill))
        }
    }
}

/** How tall the stage zone is above a step's words. */
private val STAGE_ZONE = 290.dp

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
    ValueRow(stringResource(R.string.ww_title), value, onClick = onClick)
}

private const val CWW_KEY = "custom_wake_word_enabled"
