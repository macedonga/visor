package com.macedonga.visor.display

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
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
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.R
import com.macedonga.visor.TopicKey
import com.macedonga.visor.band.BandLink
import com.macedonga.visor.band.Bands
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.protocol.Band
import com.macedonga.visor.settings.Footnote
import com.macedonga.visor.settings.RowFrame
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.settings.Topic
import com.macedonga.visor.settings.ValueRow
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** One gesture to learn: what it does on the Display and how it is made. */
enum class Lesson(val title: Int, val how: Int) {
    SELECT(R.string.lesson_select, R.string.lesson_select_how),
    BACK(R.string.lesson_back, R.string.lesson_back_how),
    SCROLL(R.string.lesson_scroll, R.string.lesson_scroll_how),
    ASSISTANT(R.string.lesson_assistant, R.string.lesson_assistant_how),
    WAKE(R.string.lesson_wake, R.string.lesson_wake_how),
    APPS(R.string.lesson_apps, R.string.lesson_apps_how);

    companion object {
        /** Band events to lessons; a single tap waits DOUBLE_WINDOW_MS for a double. */
        suspend fun judge(gestures: Flow<Band.Gesture>, onLesson: suspend (Lesson) -> Unit) = coroutineScope {
            var pending: Job? = null
            var quietUntil = 0L
            gestures.collect { g ->
                val now = SystemClock.uptimeMillis()
                when {
                    g.derived == DERIVED_SINGLE_TAP -> {
                        pending?.cancel()
                        if (now < quietUntil) return@collect
                        val lesson = when (g.finger) {
                            FINGER_INDEX -> SELECT
                            FINGER_MIDDLE -> BACK
                            else -> null
                        } ?: return@collect
                        pending = launch {
                            delay(DOUBLE_WINDOW_MS)
                            onLesson(lesson)
                        }
                    }
                    g.derived == DERIVED_DOUBLE_TAP -> {
                        pending?.cancel()
                        when (g.finger) {
                            FINGER_MIDDLE -> onLesson(WAKE)
                            FINGER_THUMB, FINGER_INDEX -> onLesson(ASSISTANT)
                        }
                    }
                    // A hold, and a swipe, can come as more than one event: the first counts.
                    now < quietUntil -> Unit
                    g.derived == DERIVED_HOLD && g.finger == FINGER_MIDDLE -> {
                        pending?.cancel()
                        quietUntil = now + QUIET_MS
                        onLesson(APPS)
                    }
                    g.action in ACTION_UP..ACTION_RIGHT || g.derived in DERIVED_UP..DERIVED_RIGHT -> {
                        quietUntil = now + QUIET_MS
                        onLesson(SCROLL)
                    }
                }
            }
        }

        private const val FINGER_THUMB = 1
        private const val FINGER_INDEX = 2
        private const val FINGER_MIDDLE = 3
        private const val ACTION_UP = 6
        private const val ACTION_RIGHT = 9
        private const val DERIVED_SINGLE_TAP = 1
        private const val DERIVED_DOUBLE_TAP = 2
        private const val DERIVED_HOLD = 3
        private const val DERIVED_UP = 5
        private const val DERIVED_RIGHT = 8
        // Longer than the gap between a double tap's two taps.
        private const val DOUBLE_WINDOW_MS = 420L
        private const val QUIET_MS = 600L
    }
}

@HiltViewModel
class GesturesModel @Inject constructor(val band: BandLink, val bands: Bands, val connection: GlassesConnection) : ViewModel()

/** Each band gesture with three dots that fill as the band hears it; the band is the phone's while open. */
@Composable
fun GesturesScreen(open: (NavKey) -> Unit, model: GesturesModel = hiltViewModel()) {
    val band by model.band.state.collectAsStateWithLifecycle()
    val all by model.bands.all.collectAsStateWithLifecycle()
    val pair by model.connection.pair.collectAsStateWithLifecycle()
    val setUp = all.any { it.glasses == pair }
    DisposableEffect(Unit) {
        model.band.startLesson()
        onDispose { model.band.endLesson() }
    }
    val haptics = rememberHaptics()
    val counts = remember { mutableStateListOf(*Array(Lesson.entries.size) { 0 }) }
    LaunchedEffect(Unit) {
        Lesson.judge(model.band.gestures) { lesson ->
            val i = lesson.ordinal
            if (counts[i] >= GOAL) return@judge
            counts[i]++
            if (counts[i] < GOAL) haptics.tick(0.7f, touch = false) else haptics.done()
        }
    }
    SettingsPage(stringResource(R.string.gestures_title)) {
        if (setUp && !band.gestures) {
            item(key = "state") {
                Footnote(stringResource(R.string.gestures_waiting))
                Spacer(Modifier.height(16.dp))
            }
        }
        if (!setUp) {
            item(key = "band") {
                Rows({ ValueRow(stringResource(R.string.onboard_band_pair), null, onClick = { open(TopicKey(Topic.BAND.name)) }) })
                Spacer(Modifier.height(16.dp))
            }
        }
        item(key = "lessons") {
            val idle = !setUp || !band.gestures
            Rows(
                *Lesson.entries.map { lesson ->
                    @Composable {
                        RowFrame(stringResource(lesson.title), onClick = null, sub = stringResource(lesson.how), dim = idle) { Progress(counts[lesson.ordinal]) }
                    }
                }.toTypedArray(),
            )
        }
    }
}

/** Three dots, one lit for each time the band heard it; a check once all are. */
@Composable
private fun Progress(count: Int) {
    if (count >= GOAL) {
        Icon(VisorIcons.Check, stringResource(R.string.done), Modifier.padding(start = 12.dp).size(20.dp), tint = Color.White)
        return
    }
    val heard = stringResource(R.string.lesson_progress, count, GOAL)
    Row(Modifier.padding(start = 12.dp).clearAndSetSemantics { contentDescription = heard }, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(GOAL) { i ->
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (i < count) Color.White else Visor.colors.control))
        }
    }
}

private const val GOAL = 3
