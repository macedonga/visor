package com.macedonga.visor.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.flow.first

/** How folded a stage is, 0 to 1, trailing [scrolled] through [range] px: curved, read [FOLD_DELAY_NS] late, eased. */
@Composable
fun rememberTrailingFold(range: Float, scrolled: () -> Float): State<Float> {
    // From where the page already is: a page coming back scrolled must not open for a frame and fold again.
    val fold = remember { mutableFloatStateOf(progress(scrolled(), range)) }
    val read = rememberUpdatedState(scrolled)
    LaunchedEffect(range) {
        val history = ArrayDeque<Pair<Long, Float>>()
        var last = 0L
        while (true) {
            val still = animationsOff()
            withFrameNanos { now ->
                history.addLast(now to progress(read.value(), range))
                while (history.size > 1 && history[1].first <= now - FOLD_DELAY_NS) history.removeFirst()
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                // With animations off the stage follows the page as it is, with no trail.
                fold.floatValue = if (still) history.last().second
                else fold.floatValue + (history.first().second - fold.floatValue) * (1f - exp(-dt / FOLD_EASE_S))
            }
            // At rest it asks for no frames until the page moves.
            val at = history.last().second
            if (history.all { it.second == at } && abs(fold.floatValue - at) < 0.001f) {
                fold.floatValue = at
                snapshotFlow { progress(read.value(), range) }.first { it != at }
                last = 0L
            }
        }
    }
    return fold
}

private fun progress(px: Float, range: Float) = if (range > 0f) FOLD_CURVE.transform((px / range).coerceIn(0f, 1f)) else 0f

private val FOLD_CURVE = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)
private const val FOLD_DELAY_NS = 70_000_000L
private const val FOLD_EASE_S = 0.12f
