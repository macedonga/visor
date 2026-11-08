package com.macedonga.visor.dat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.design.VisorTheme
import com.macedonga.visor.settings.ProgressRing
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.WidePill
import com.macedonga.visor.stage.SETTLE_EASE
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import com.macedonga.visor.ui.rememberHaptics
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The toolkit setup's sheet: the once-only question when an app first needs the glasses' motion or display, then
 * every step with its check as it ends, the toolkit app's download in percent, and how it ended. The ring round the
 * glasses fills with the whole setup.
 */
@AndroidEntryPoint
class ToolkitActivity : ComponentActivity() {
    @Inject lateinit var toolkit: Toolkit

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VisorTheme {
                val progress by toolkit.progress.collectAsState()
                val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val scope = rememberCoroutineScope()
                val haptics = rememberHaptics()
                // The answers slide the sheet away first; a swipe down has done that already.
                val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { leave() } }
                LaunchedEffect(progress) {
                    when (progress) {
                        ToolkitProgress.Ready -> {
                            haptics.done()
                            delay(DONE_SHOWN_MS)
                            close()
                        }
                        is ToolkitProgress.Failed -> haptics.error()
                        ToolkitProgress.Idle -> leave()
                        else -> Unit
                    }
                }
                VisorSheet(onDismiss = ::leave, state = sheet) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = SIDE)) {
                        Emblem(progress)
                        AnimatedContent(
                            targetState = progress,
                            contentKey = ::phase,
                            transitionSpec = {
                                (fadeIn(tween(260, delayMillis = 90)) togetherWith fadeOut(tween(120)))
                                    .using(SizeTransform(clip = false) { _, _ -> tween(360, easing = SETTLE_EASE) })
                            },
                            label = "phase",
                        ) { shown ->
                            when (shown) {
                                is ToolkitProgress.Asking -> Ask(shown.app, onAllow = { toolkit.answer(true) }, onDeny = close)
                                is ToolkitProgress.Running -> Working(shown.step, shown.fraction)
                                ToolkitProgress.Ready -> Working(null, null)
                                is ToolkitProgress.Failed -> Stopped(shown, close)
                                ToolkitProgress.Idle -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    // Going away while asked is a no; going away while the setup runs leaves it running, in its notification.
    private fun leave() {
        if (toolkit.progress.value is ToolkitProgress.Asking) toolkit.answer(false)
        toolkit.dismiss()
        finish()
    }

    private companion object {
        const val DONE_SHOWN_MS = 1_600L

        // Running and Ready share their page, so the last step's check lands in place before the sheet goes.
        fun phase(progress: ToolkitProgress): Int = when (progress) {
            is ToolkitProgress.Asking -> 0
            is ToolkitProgress.Running, ToolkitProgress.Ready -> 1
            is ToolkitProgress.Failed -> 2
            ToolkitProgress.Idle -> 3
        }
    }
}

private enum class Mark { GLASSES, DONE, FAILED }

private enum class StepState { DONE, NOW, LATER, FAILED }

/** The glasses in a raised disc; a ring round it fills with the setup, then the disc turns white with a check. */
@Composable
private fun Emblem(progress: ToolkitProgress) {
    val colors = Visor.colors
    val running = progress as? ToolkitProgress.Running
    // Each step is a quarter; the download fills its own quarter as it goes.
    val whole = when {
        progress == ToolkitProgress.Ready -> 1f
        running != null -> (running.step.ordinal + (running.fraction ?: 0.35f)) / ToolkitStep.entries.size
        else -> 0f
    }
    val ring by animateFloatAsState(whole, tween(700, easing = SETTLE_EASE), label = "ring")
    val ringShown by animateFloatAsState(if (running != null) 1f else 0f, tween(400), label = "ringShown")
    val mark = when (progress) {
        ToolkitProgress.Ready -> Mark.DONE
        is ToolkitProgress.Failed -> Mark.FAILED
        else -> Mark.GLASSES
    }
    val fill by animateFloatAsState(if (mark == Mark.DONE) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 380f), label = "fill")
    Box(Modifier.padding(top = 6.dp, bottom = 22.dp).size(68.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(68.dp)) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2
            val box = Size(size.width - stroke, size.height - stroke)
            drawCircle(colors.control.copy(alpha = ringShown), radius = size.minDimension / 2 - inset, style = Stroke(stroke))
            if (ring > 0f) {
                drawArc(Color.White.copy(alpha = ringShown), -90f, 360f * ring, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Box(
            Modifier
                .size(54.dp)
                .graphicsLayer {
                    // A small swell as the check lands.
                    val swell = 1f + 0.06f * fill * (1f - fill) * 4f
                    scaleX = swell
                    scaleY = swell
                }
                .clip(CircleShape)
                .background(lerp(colors.raised, Color.White, fill)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = mark,
                transitionSpec = { (fadeIn(tween(220, delayMillis = 80)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.6f)) togetherWith fadeOut(tween(100)) },
                label = "mark",
            ) { shown ->
                val (icon, tint) = when (shown) {
                    Mark.GLASSES -> VisorIcons.Glasses to colors.textPrimary
                    Mark.DONE -> VisorIcons.Check to Color.Black
                    Mark.FAILED -> VisorIcons.Close to colors.signal
                }
                Icon(icon, null, Modifier.size(26.dp), tint = tint)
            }
        }
    }
}

@Composable
private fun Ask(app: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    val colors = Visor.colors
    Column {
        Text(stringResource(R.string.toolkit_title), style = Visor.type.title, color = colors.textPrimary, modifier = Modifier.asHeading())
        Text(stringResource(R.string.toolkit_body, app), style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp))
        Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Point(VisorIcons.Download, stringResource(R.string.toolkit_point_app))
            Point(VisorIcons.Shield, stringResource(R.string.toolkit_point_trust))
            Point(VisorIcons.Glasses, stringResource(R.string.toolkit_point_once))
        }
        Row(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WidePill(stringResource(R.string.toolkit_deny), onDeny, Modifier.weight(1f), quiet = true)
            WidePill(stringResource(R.string.toolkit_allow), onAllow, Modifier.weight(1f))
        }
    }
}

/** What the setup does, one line each beside its sign. */
@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Visor.colors.raised), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = Visor.colors.textPrimary)
        }
        Text(text, style = Visor.type.body, color = Visor.colors.textPrimary, modifier = Modifier.padding(start = 14.dp))
    }
}

/** The steps as they run, at [current]; all done when it is null. */
@Composable
private fun Working(current: ToolkitStep?, fraction: Float?) {
    val colors = Visor.colors
    Column {
        AnimatedContent(
            targetState = current == null,
            transitionSpec = { fadeIn(tween(260, delayMillis = 100)) togetherWith fadeOut(tween(120)) },
            label = "title",
        ) { done ->
            Column {
                Text(
                    stringResource(if (done) R.string.toolkit_ready else R.string.toolkit_notification),
                    style = Visor.type.title, color = colors.textPrimary, modifier = Modifier.asHeading(),
                )
                Text(
                    stringResource(if (done) R.string.toolkit_ready_sub else R.string.toolkit_background),
                    style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        Steps(current, fraction, failed = false)
    }
}

@Composable
private fun Stopped(failed: ToolkitProgress.Failed, onClose: () -> Unit) {
    val colors = Visor.colors
    Column {
        Text(stringResource(R.string.toolkit_failed), style = Visor.type.title, color = colors.textPrimary, modifier = Modifier.asHeading())
        if (failed.reason.isNotBlank()) {
            Text(failed.reason, style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp))
        }
        if (failed.step != null) Steps(failed.step, null, failed = true)
        WidePill(stringResource(R.string.toolkit_close), onClose, Modifier.fillMaxWidth().padding(top = 24.dp), quiet = true)
    }
}

@Composable
private fun Steps(current: ToolkitStep?, fraction: Float?, failed: Boolean) {
    Column(Modifier.padding(top = 20.dp)) {
        for (step in ToolkitStep.entries) {
            val state = when {
                current == null || step.ordinal < current.ordinal -> StepState.DONE
                step == current -> if (failed) StepState.FAILED else StepState.NOW
                else -> StepState.LATER
            }
            StepRow(step, state, fraction.takeIf { state == StepState.NOW })
        }
    }
}

/** A step: an empty circle to come, a turning ring (or the download's share) now, a white check once done. */
@Composable
private fun StepRow(step: ToolkitStep, state: StepState, fraction: Float?) {
    val colors = Visor.colors
    val words by animateColorAsState(if (state == StepState.LATER) colors.textTertiary else colors.textPrimary, tween(300), label = "words")
    val fill by animateFloatAsState(if (state == StepState.DONE) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 380f), label = "check")
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.NOW -> ProgressRing(fraction?.takeIf { it > 0f }, Modifier.size(22.dp))
                StepState.FAILED -> Box(Modifier.size(22.dp).border(1.5.dp, colors.signal, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(VisorIcons.Close, null, Modifier.size(12.dp), tint = colors.signal)
                }
                else -> Box(
                    Modifier.size(22.dp)
                        .border(1.5.dp, if (state == StepState.DONE) Color.White else colors.control, CircleShape)
                        .graphicsLayer {
                            scaleX = 0.6f + 0.4f * fill
                            scaleY = scaleX
                        }
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = fill)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state == StepState.DONE) Icon(VisorIcons.Check, null, Modifier.size(16.dp), tint = Color.Black)
                }
            }
        }
        Text(stringResource(Toolkit.stepText(step)), style = Visor.type.body, color = words, modifier = Modifier.padding(start = 14.dp).weight(1f))
        if (fraction != null) {
            Text("${(fraction * 100).toInt()}%", style = Visor.type.label.copy(fontFeatureSettings = "tnum"), color = colors.textSecondary)
        }
    }
}
