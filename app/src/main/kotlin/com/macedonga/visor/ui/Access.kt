package com.macedonga.visor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import kotlinx.coroutines.currentCoroutineContext

/** A screen's or a section's title: TalkBack can jump from one to the next. */
fun Modifier.asHeading(): Modifier = semantics { heading() }

/** A row with a switch in it: the row takes the tap, so it carries the switch's state for TalkBack. */
fun Modifier.switchRow(on: Boolean): Modifier = semantics {
    toggleableState = ToggleableState(on)
    role = Role.Switch
}

/** The phone's "Remove animations" as Compose sees it, for hand-written frame loops to hold still. */
suspend fun animationsOff(): Boolean = (currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f) == 0f

/** [lines] at the usual text size, and no limit once the phone's font is large, so nothing is cut short. */
@Composable
fun lineLimit(lines: Int): Int = if (largeFont()) Int.MAX_VALUE else lines

@Composable
fun largeFont(): Boolean = LocalDensity.current.fontScale >= LARGE_FONT

private const val LARGE_FONT = 1.3f
