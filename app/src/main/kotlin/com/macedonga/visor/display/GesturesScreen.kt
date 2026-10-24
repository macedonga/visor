package com.macedonga.visor.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.band.BandLink
import com.macedonga.visor.band.Bands
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.protocol.Band
import com.macedonga.visor.settings.RowFrame
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SettingsPage
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
        /**
         * The band's gestures as lessons, as the Display's input filter takes them: an index tap selects, a middle tap goes
         * back, a thumb swipe scrolls, the thumb's double tap is Meta AI, the middle's wakes, holding the middle opens the
         * apps. Each tap comes as press, release, button release and single tap; the second tap of a double adds a double
         * tap right after its single tap. So a single tap waits a moment for a double, and only the final events count.
         */
        suspend fun judge(gestures: Flow<Band.Gesture>, onLesson: suspend (Lesson) -> Unit) = coroutineScope {
            var pending: Job? = null
            var quietUntil = 0L
            gestures.collect { g ->
                val now = System.currentTimeMillis()
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
        // The second tap of a double came about 200 ms after the first on the user's band.
        private const val DOUBLE_WINDOW_MS = 420L
        private const val QUIET_MS = 600L
    }
}

@HiltViewModel
class GesturesModel @Inject constructor(val band: BandLink, val bands: Bands, val connection: GlassesConnection) : ViewModel()

/**
 * The band's gestures in a list: what each does and how it is made, three dots that fill as the band hears it and a
 * check once it has three times. The band is the phone's while the page is open.
 */
@Composable
fun GesturesScreen(model: GesturesModel = hiltViewModel()) {
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
            if (counts[i] < GOAL) haptics.tick(0.7f) else haptics.done()
        }
    }
    SettingsPage(
        stringResource(R.string.gestures_title),
        // A line only while practice cannot happen.
        lede = when {
            !setUp -> stringResource(R.string.gestures_no_band)
            !band.gestures -> stringResource(R.string.gestures_waiting)
            else -> null
        },
    ) {
        item(key = "lessons") {
            Rows(
                *Lesson.entries.map { lesson ->
                    @Composable {
                        RowFrame(stringResource(lesson.title), onClick = null, sub = stringResource(lesson.how)) { Progress(counts[lesson.ordinal]) }
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
        Icon(VisorIcons.Check, null, Modifier.padding(start = 12.dp).size(20.dp), tint = Color.White)
        return
    }
    Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(GOAL) { i ->
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (i < count) Color.White else Visor.colors.control))
        }
    }
}

private const val GOAL = 3
