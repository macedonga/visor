package com.macedonga.visor.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.Ear
import com.macedonga.visor.core.assistant.Hearing
import com.macedonga.visor.core.assistant.HearingMode
import com.macedonga.visor.core.assistant.HearingRefusal
import com.macedonga.visor.core.assistant.HearingRun
import com.macedonga.visor.core.assistant.Thresholds
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.settings.ChoicePill
import com.macedonga.visor.settings.LocalBack
import com.macedonga.visor.settings.LocalHost
import com.macedonga.visor.settings.RoundIcon
import com.macedonga.visor.settings.SettingsHost
import com.macedonga.visor.settings.StoryWords
import com.macedonga.visor.settings.WidePill
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
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the hearing test is. */
sealed interface TestStep {
    data object Who : TestStep

    /** The answer means hearing correction is not for this person. */
    data class NotFor(val why: Int) : TestStep
    data object Ready : TestStep
    data object Starting : TestStep
    data class Tone(val index: Int, val level: Int) : TestStep
    data object Fitting : TestStep
    data object Done : TestStep

    /** The profile is made, but the glasses did not start hearing correction with it. */
    data class Saved(val why: Int) : TestStep
    data class Failed(val why: Int) : TestStep
}

/** The hearing test: four tones an ear from 40 dB HL in 5 dB steps; the last 1 kHz one counts. */
@HiltViewModel
class HearingTestModel @Inject constructor(private val hearing: Hearing, private val features: Features) : ViewModel() {
    private val _step = MutableStateFlow<TestStep>(TestStep.Who)
    val step: StateFlow<TestStep> = _step.asStateFlow()

    private val levels = IntArray(TONES.size) { START_DBHL }
    private var testing = false

    // One tone at a time, 600 ms after the last answer; stop the old one first or the glasses refuse the next.
    private val tones = Mutex()
    private var asked = 0
    private var visits = 0
    private var moving: Job? = null

    fun answer(rating: Int) {
        _step.value = when (rating) {
            RATING_MILD -> TestStep.Ready
            RATING_NONE -> TestStep.NotFor(R.string.hct_not_none)
            RATING_SEVERE -> TestStep.NotFor(R.string.hct_not_severe)
            else -> TestStep.NotFor(R.string.hct_not_implant)
        }
    }

    fun begin() {
        if (_step.value != TestStep.Ready) return
        _step.value = TestStep.Starting
        val visit = visits
        viewModelScope.launch {
            hearing.startCorrection(test = true)
            val running = hearing.awaitRunning(HearingMode.CORRECTION_SETUP, START_MS)
            // Gone while the glasses got ready: the setup they started is stopped again.
            if (visit != visits) {
                if (running) hearing.stop()
                return@launch
            }
            if (!running) {
                _step.value = TestStep.Failed(R.string.hct_failed_start)
                return@launch
            }
            testing = true
            levels.fill(START_DBHL)
            show(0)
        }
    }

    /** Louder or quieter by one step, and play it again. */
    fun nudge(up: Boolean) {
        val s = _step.value as? TestStep.Tone ?: return
        if (moving?.isActive == true) return
        val level = (s.level + if (up) STEP_DBHL else -STEP_DBHL).coerceIn(Hearing.MIN_DBHL, Hearing.MAX_DBHL)
        if (level != s.level) show(s.index, level)
    }

    /** Can't hear it: the loudest, played again. */
    fun loudest() {
        val s = _step.value as? TestStep.Tone ?: return
        if (moving?.isActive == true || s.level == Hearing.MAX_DBHL) return
        show(s.index, Hearing.MAX_DBHL)
    }

    fun next() {
        val s = _step.value as? TestStep.Tone ?: return
        if (moving?.isActive == true) return
        levels[s.index] = s.level
        moving = viewModelScope.launch {
            silence(s.index)
            if (s.index + 1 < TONES.size) show(s.index + 1) else fit()
        }
    }

    fun retry() {
        _step.value = TestStep.Ready
    }

    private fun show(index: Int, level: Int = START_DBHL) {
        if (!testing) return
        _step.value = TestStep.Tone(index, level)
        val ask = ++asked
        viewModelScope.launch {
            tones.withLock {
                if (ask != asked) return@withLock
                val (ear, hz) = TONES[index]
                if (hearing.playTone(ear, hz, level)) delay(BETWEEN_MS) else _step.value = TestStep.Failed(R.string.hct_failed_tone)
            }
        }
    }

    /** Stop [index]'s tone, waited for; a refusal to stop is no failure. */
    private suspend fun silence(index: Int) {
        asked++
        tones.withLock {
            val (ear, hz) = TONES[index]
            if (hearing.playTone(ear, hz, levels[index], on = false)) delay(BETWEEN_MS)
        }
    }

    private fun fit() {
        _step.value = TestStep.Fitting
        val visit = visits
        viewModelScope.launch {
            // Tone 1 is practice; tone 4, 1 kHz again, is the one sent.
            fun ear(from: Int) = Thresholds(at1k = levels[from + 3], at2k = levels[from + 1], at4k = levels[from + 2])
            val made = hearing.fit(ear(0), ear(TONES_PER_EAR))
            if (made) {
                testing = false
                features.hearingTested()
            }
            // Gone while the glasses fitted: leave hearing correction as it is.
            if (visit != visits) return@launch
            if (!made) {
                _step.value = TestStep.Failed(R.string.hct_failed_fit)
                return@launch
            }
            val now = hearing.state.value
            val on = (now.mode == HearingMode.CORRECTION && now.run == HearingRun.ON) || run {
                hearing.stop()
                hearing.awaitStopped()
                hearing.startCorrection()
                hearing.awaitRunning(HearingMode.CORRECTION, START_MS)
            }
            _step.value = if (on) TestStep.Done
            else TestStep.Saved(if (hearing.state.value.refusal == HearingRefusal.ENABLE_FAILED) R.string.hc_refused_chip else R.string.cf_refused)
        }
    }

    /** Each visit starts from the first question: the model outlives the screen. */
    fun enter() {
        visits++
        asked++
        levels.fill(START_DBHL)
        _step.value = TestStep.Who
    }

    /** Leaving mid-test stops the hearing app, as the Meta app does. */
    fun leave() {
        visits++
        asked++
        if (testing) {
            testing = false
            hearing.stop()
        }
    }

    override fun onCleared() = leave()

    companion object {
        const val RATING_NONE = 0
        const val RATING_MILD = 1
        const val RATING_SEVERE = 2
        const val RATING_IMPLANT = 3
        const val TONES_PER_EAR = 4
        private const val START_DBHL = 40
        const val STEP_DBHL = 5
        private const val BETWEEN_MS = 600L
        private const val START_MS = 10_000L
        private val TONES = listOf(Ear.LEFT, Ear.RIGHT).flatMap { ear -> listOf(1000, 2000, 4000, 1000).map { ear to it } }
    }
}

/** The hearing test on the stage: the pair turns to the ear under test. */
@Composable
fun HearingTestScreen(model: HearingTestModel = hiltViewModel()) = SettingsHost(seeThrough = true) {
    val step by model.step.collectAsStateWithLifecycle()
    val stage = LocalStage.current
    val host = LocalHost.current
    val colors = Visor.colors
    DisposableEffect(model) {
        model.enter()
        onDispose { model.leave() }
    }

    val cue = remember { StageCue() }
    val tone = step as? TestStep.Tone
    val ear = tone?.let { if (it.index < HearingTestModel.TONES_PER_EAR) Ear.LEFT else Ear.RIGHT }
    cue.yaw = when (ear) {
        Ear.LEFT -> LEFT_EAR_YAW
        Ear.RIGHT -> RIGHT_EAR_YAW
        null -> 0f
    }
    cue.orbit = step == TestStep.Starting || step == TestStep.Fitting
    cue.source = when (ear) {
        Ear.LEFT -> "temple_l"
        Ear.RIGHT -> "temple_r"
        null -> null
    }
    cue.inward = false
    cue.level = tone?.let { 0.3f + 0.7f * (it.level - Hearing.MIN_DBHL) / (Hearing.MAX_DBHL - Hearing.MIN_DBHL).toFloat() } ?: 0f
    val look = when (step) {
        is TestStep.Tone -> LightLook(colors.lightNeutral, colors.lightWarm, level = 0.9f, spread = 1f, breathSeconds = 1.6f, breathDepth = 0.3f)
        TestStep.Starting, TestStep.Fitting -> lookOf(Mood.SEARCHING, colors).copy(breathSeconds = 2.2f)
        is TestStep.Failed, is TestStep.Saved, is TestStep.NotFor -> lookOf(Mood.FAR, colors)
        else -> lookOf(Mood.CONNECTED, colors)
    }
    val moment = step::class
    LaunchedEffect(moment) {
        when (step) {
            TestStep.Done -> {
                host.haptics.connected()
                stage.light.flash()
                delay(400)
                stage.pose.nod()
            }
            is TestStep.Failed, is TestStep.Saved -> {
                host.haptics.error()
                stage.light.flicker()
                stage.pose.shake()
            }
            else -> Unit
        }
    }

    // During the tones the pair comes closer, so the temple and its sound fill the stage.
    StoryPage(
        stringResource(R.string.hct_title), cue, look, if (tone != null) 1f else 0.8f, step,
        key = { it::class to (it as? TestStep.Tone)?.index },
    ) { shown ->
        TestStepContent(shown, model)
    }
}

@Composable
private fun TestStepContent(step: TestStep, model: HearingTestModel) {
    val back = LocalBack.current
    when (step) {
        TestStep.Who -> {
            StoryWords(stringResource(R.string.hct_who_title), stringResource(R.string.hct_who_body))
            Text(stringResource(R.string.hct_question), style = Visor.type.label, color = Visor.colors.textSecondary, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((rating, words) in listOf(
                    HearingTestModel.RATING_MILD to R.string.hct_rating_mild,
                    HearingTestModel.RATING_NONE to R.string.hct_rating_none,
                    HearingTestModel.RATING_SEVERE to R.string.hct_rating_severe,
                    HearingTestModel.RATING_IMPLANT to R.string.hct_rating_implant,
                )) ChoicePill(stringResource(words)) { model.answer(rating) }
            }
        }
        is TestStep.NotFor -> StoryWords(stringResource(R.string.hct_not_title), stringResource(step.why)) {
            WidePill(stringResource(R.string.done), back, Modifier.fillMaxWidth())
        }
        TestStep.Ready -> StoryWords(stringResource(R.string.hct_ready_title), stringResource(R.string.hct_ready_body)) {
            WidePill(stringResource(R.string.hct_begin), model::begin, Modifier.fillMaxWidth())
        }
        TestStep.Starting -> StoryWords(stringResource(R.string.hct_starting), null)
        is TestStep.Tone -> ToneControls(step, model)
        TestStep.Fitting -> StoryWords(stringResource(R.string.hct_fitting), null)
        is TestStep.Saved -> StoryWords(stringResource(R.string.hct_saved_title), stringResource(step.why)) {
            WidePill(stringResource(R.string.done), back, Modifier.fillMaxWidth())
        }
        TestStep.Done -> StoryWords(stringResource(R.string.hct_done_title), stringResource(R.string.hct_done_body)) {
            WidePill(stringResource(R.string.done), back, Modifier.fillMaxWidth())
        }
        is TestStep.Failed -> StoryWords(stringResource(R.string.hct_failed_title), stringResource(step.why)) {
            WidePill(stringResource(R.string.try_again), model::retry, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ToneControls(s: TestStep.Tone, model: HearingTestModel) {
    val perEar = HearingTestModel.TONES_PER_EAR
    val last = s.index == perEar * 2 - 1
    Text(stringResource(if (s.index < perEar) R.string.hct_left else R.string.hct_right), style = Visor.type.label, color = Visor.colors.textSecondary)
    Text(stringResource(R.string.hct_tone, s.index % perEar + 1, perEar), style = Visor.type.title, color = Visor.colors.textPrimary, modifier = Modifier.padding(top = 4.dp))
    if (s.index == 0) Text(stringResource(R.string.hct_tone_body), style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(VisorIcons.Minus, stringResource(R.string.hct_quieter), { model.nudge(up = false) }, size = 68.dp, background = Visor.colors.control)
        LevelDots(s.level, Modifier.padding(horizontal = 24.dp))
        RoundIcon(VisorIcons.Plus, stringResource(R.string.hct_louder), { model.nudge(up = true) }, size = 68.dp, background = Visor.colors.control)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (s.level < Hearing.MAX_DBHL) {
            WidePill(stringResource(R.string.hct_cant_hear), model::loudest, quiet = true)
            Spacer(Modifier.width(8.dp))
        }
        WidePill(stringResource(if (last) R.string.hct_finish else R.string.hct_next), model::next, Modifier.weight(1f))
    }
}

/** The tone's level as a column of steps, the lit ones up to where it is. */
@Composable
private fun LevelDots(level: Int, modifier: Modifier) {
    val steps = (Hearing.MAX_DBHL - Hearing.MIN_DBHL) / HearingTestModel.STEP_DBHL + 1
    val lit = (level - Hearing.MIN_DBHL) / HearingTestModel.STEP_DBHL + 1
    val words = stringResource(R.string.hct_level, level)
    Column(modifier.semantics { contentDescription = words },verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (k in steps downTo 1) {
            val on = k <= lit
            Box(Modifier.size(width = (10 + k * 2).dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) Color.White else Visor.colors.control))
        }
    }
}

// The pair rests at -24 degrees; these show the left temple, then the right, to the viewer.
private const val LEFT_EAR_YAW = -46f
private const val RIGHT_EAR_YAW = 70f
