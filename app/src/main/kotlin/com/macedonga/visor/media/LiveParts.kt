package com.macedonga.visor.media

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.AssistantPhase
import com.macedonga.visor.core.assistant.TurnSource
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.BroadcastCodec
import com.macedonga.visor.core.media.BroadcastPhase
import com.macedonga.visor.core.media.LiveOverlay
import com.macedonga.visor.core.media.LivePhase
import com.macedonga.visor.core.media.LiveProblem
import com.macedonga.visor.core.media.LiveStatus
import com.macedonga.visor.core.media.STOP_BATTERY_LOW
import com.macedonga.visor.core.media.STOP_TEMPLES_CLOSED
import com.macedonga.visor.core.media.STOP_THERMAL
import com.macedonga.visor.gallery.duration
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.Card
import com.macedonga.visor.settings.ChoiceMark
import com.macedonga.visor.settings.NavRow
import com.macedonga.visor.settings.RoundIcon
import com.macedonga.visor.settings.RowFrame
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.SheetField
import com.macedonga.visor.settings.SheetTitle
import com.macedonga.visor.settings.Spinner
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.stage.StageLight
import com.macedonga.visor.stage.lookOf
import com.macedonga.visor.stage.turnsThePair
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.animationsOff
import com.macedonga.visor.ui.asHeading
import com.macedonga.visor.ui.rememberHaptics
import com.macedonga.visor.ui.switchRow
import java.net.URI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val TABULAR_LABEL @Composable get() = Visor.type.label.copy(fontFeatureSettings = "tnum")

// ---- The picture ----

/** The video laid out once at [cover], moved to [placed] by a layer transform and clipped to [visible]. */
@Composable
internal fun LiveFrame(video: LiveVideo, cover: Rect, placed: Rect, visible: Rect, radius: Float, bloomCenter: Offset, bloomRadius: Float) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                clip = true
                shape = FrameShape(visible, radius, bloomCenter, bloomRadius)
            },
    ) {
        LiveVideoView(
            video,
            Modifier
                .layout { measurable, constraints ->
                    val p = measurable.measure(Constraints.fixed(cover.width.roundToInt(), cover.height.roundToInt()))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(cover.left.roundToInt(), cover.top.roundToInt()) }
                }
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = placed.width / cover.width
                    scaleY = placed.height / cover.height
                    // The layer's origin is its own corner, already at the cover's corner.
                    translationX = placed.left - cover.left
                    translationY = placed.top - cover.top
                },
        )
    }
}

private class FrameShape(val rect: Rect, val radius: Float, val center: Offset, val circle: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val frame = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }
        if (circle < 0f) return Outline.Generic(frame)
        val disc = Path().apply { addOval(Rect(center, circle.coerceAtLeast(0.5f))) }
        return Outline.Generic(Path.combine(PathOperation.Intersect, frame, disc))
    }
}

/** The picture's colours, blurred large round the window, drifting towards each sample. */
@Composable
internal fun LiveGlow(sample: ImageBitmap?, window: Rect, step: Float) {
    val target = remember { FloatArray(GLOW_PIXELS * 3) }
    val current = remember { FloatArray(GLOW_PIXELS * 3) }
    val bitmap = remember { Bitmap.createBitmap(GLOW_W, GLOW_H, Bitmap.Config.ARGB_8888) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var primed by remember { mutableStateOf(false) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sample) {
        val source = sample?.asAndroidBitmap() ?: return@LaunchedEffect
        val pixels = IntArray(GLOW_PIXELS)
        source.getPixels(pixels, 0, GLOW_W, 0, 0, GLOW_W, GLOW_H)
        for (i in pixels.indices) {
            target[i * 3] = ((pixels[i] shr 16) and 0xFF) / 255f
            target[i * 3 + 1] = ((pixels[i] shr 8) and 0xFF) / 255f
            target[i * 3 + 2] = (pixels[i] and 0xFF) / 255f
        }
        if (!primed) {
            target.copyInto(current)
            primed = true
        }
    }
    // Only while it can be seen: drift, and write the drifted colours into the bitmap that is drawn.
    LaunchedEffect(step > 0f) {
        if (step <= 0f) return@LaunchedEffect
        var last = 0L
        val pixels = IntArray(GLOW_PIXELS)
        while (true) {
            val still = animationsOff()
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                val k = if (still) 1f else 1f - exp(-dt / GLOW_DRIFT_SECONDS)
                for (i in current.indices) current[i] += (target[i] - current[i]) * k
                for (i in pixels.indices) {
                    val r = (current[i * 3] * 255).toInt().coerceIn(0, 255)
                    val g = (current[i * 3 + 1] * 255).toInt().coerceIn(0, 255)
                    val b = (current[i * 3 + 2] * 255).toInt().coerceIn(0, 255)
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                bitmap.setPixels(pixels, 0, GLOW_W, 0, 0, GLOW_W, GLOW_H)
                frame++
            }
        }
    }
    val breath = rememberInfiniteTransition(label = "glow")
    val swell by breath.animateFloat(1f, 1.06f, infiniteRepeatable(tween(4_500), RepeatMode.Reverse), label = "swell")
    if (!primed || step <= 0f) return
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = step * 0.85f
                renderEffect = BlurEffect(size.minDimension * 0.14f, size.minDimension * 0.14f, TileMode.Clamp)
            },
    ) {
        frame
        val w = window.width * 1.9f * swell
        val h = window.height * 1.35f * swell
        drawImage(
            image,
            dstOffset = IntOffset((window.center.x - w / 2).toInt(), (window.center.y - h / 2).toInt()),
            dstSize = IntSize(w.toInt(), h.toInt()),
            filterQuality = FilterQuality.Low,
        )
        drawRect(
            Brush.radialGradient(
                0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.75f), 0.8f to Color.Black.copy(alpha = 0.2f), 1f to Color.Transparent,
                center = window.center, radius = maxOf(size.width, size.height) * 0.7f,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}

internal const val GLOW_W = 6
internal const val GLOW_H = 10
private const val GLOW_PIXELS = GLOW_W * GLOW_H
private const val GLOW_DRIFT_SECONDS = 0.7f

// ---- Coming up out of the glasses ----

/** Round the glasses while the stream comes up: where it has got to, or why it failed. */
@Composable
internal fun Arrival(
    status: LiveStatus,
    allowed: Boolean,
    stageHeight: Float,
    bloom: Float,
    leaving: Boolean,
    onAllow: () -> Unit,
    onWifi: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = Visor.colors
    val haptics = rememberHaptics()
    val failed = status.phase == LivePhase.FAILED || !allowed
    StageLight(lookOf(if (leaving) Mood.CONNECTED else if (failed) Mood.FAR else Mood.GOING_LIVE, colors))
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - bloom * 1.5f).coerceIn(0f, 1f) }) {
        // Where the pair stands: a drag turns it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(with(density) { stageHeight.toDp() })
                .turnsThePair { haptics.tick(0.35f) },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, stageHeight.roundToInt()) }
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!leaving) {
                if (failed) {
                    Text(stringResource(R.string.live_failed), style = Visor.type.heading, color = colors.textPrimary, modifier = Modifier.asHeading())
                    val problem = if (!allowed) LiveProblem.WIFI_DIRECT_DENIED else status.problem
                    Text(
                        stringResource(problemWords(problem)),
                        style = Visor.type.label, color = colors.textSecondary, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
                    )
                    when (problem) {
                        LiveProblem.WIFI_DIRECT_DENIED -> ActionPill(stringResource(R.string.allow), onAllow)
                        LiveProblem.WIFI_OFF -> ActionPill(stringResource(R.string.live_wifi_on), onWifi)
                        else -> ActionPill(stringResource(R.string.try_again), onRetry)
                    }
                } else {
                    Steps(status.phase)
                }
            }
        }
        if (!leaving) {
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                RoundIcon(VisorIcons.Close, stringResource(R.string.live_close), onClose, size = 48.dp)
            }
        }
    }
}

/** "Wi-Fi · Glasses · Camera": done ones quiet, the current one breathing, the rest dim. */
@Composable
private fun Steps(phase: LivePhase) {
    val colors = Visor.colors
    val at = when (phase) {
        LivePhase.IDLE, LivePhase.WIFI -> 0
        LivePhase.GLASSES -> 1
        LivePhase.CAMERA, LivePhase.RESTARTING -> 2
        else -> 3
    }
    val breath = rememberInfiniteTransition(label = "steps")
    val pulse by breath.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val names = listOf(R.string.live_step_wifi, R.string.live_step_glasses, R.string.live_step_camera)
        names.forEachIndexed { i, name ->
            if (i > 0) Box(Modifier.size(3.dp).clip(CircleShape).background(colors.textTertiary))
            val color by animateColorAsState(
                when {
                    i < at -> colors.textSecondary
                    i == at -> colors.textPrimary
                    else -> colors.textTertiary
                },
                tween(400), label = "step",
            )
            Text(
                stringResource(name), style = Visor.type.label, color = color,
                modifier = Modifier.graphicsLayer { alpha = if (i == at) pulse else 1f },
            )
        }
    }
}

private fun problemWords(problem: LiveProblem?): Int = when (problem) {
    LiveProblem.NOT_CONNECTED, null -> R.string.live_problem_not_connected
    LiveProblem.IN_CASE -> R.string.live_problem_in_case
    LiveProblem.WIFI_OFF -> R.string.live_problem_wifi_off
    LiveProblem.WIFI_DIRECT_DENIED -> R.string.live_problem_wifi_direct
    LiveProblem.NO_KEY -> R.string.live_problem_no_key
    LiveProblem.JOIN_FAILED -> R.string.live_problem_join
    LiveProblem.SESSION_FAILED -> R.string.live_problem_session
    LiveProblem.NO_VIDEO -> R.string.live_problem_no_video
    LiveProblem.GAVE_UP -> R.string.live_problem_gave_up
    LiveProblem.VPN -> R.string.live_problem_vpn
    LiveProblem.WIFI_BUSY -> R.string.live_problem_wifi_busy
}

// ---- Over the picture ----

/** On the full-screen picture: the status, and whichever of readouts, heat and grid are on. */
@Composable
internal fun LiveOverlays(model: LiveViewModel, visible: Rect, step: Float, bloom: Float) {
    val settings by model.settings.settings.collectAsStateWithLifecycle()
    val status by model.live.status.collectAsStateWithLifecycle()
    val shown = ((bloom - 0.75f) / 0.25f).coerceIn(0f, 1f)
    if (LiveOverlay.GRID in settings.overlays) Grid(visible, shown)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown * (1f - step * 2.2f).coerceIn(0f, 1f) },
    ) {
        // A dark breath behind the words at the top and, with the heat on, the bottom.
        Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.42f), Color.Transparent))))
        if (LiveOverlay.HEAT in settings.overlays) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f)))))
        }
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            StatusChip(model)
            if (status.phase == LivePhase.RESTARTING) Reconnecting(Modifier.padding(top = 8.dp))
        }
        Column(
            Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (LiveOverlay.READOUTS in settings.overlays) Readouts(model, stacked = true)
        }
        if (LiveOverlay.HEAT in settings.overlays) {
            Heat(model, Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp))
        }
    }
}

/** "Hey Meta" turns as subtitles at the foot of the picture. */
@Composable
internal fun Captions(model: LiveViewModel, visible: Rect, alpha: Float) {
    val colors = Visor.colors
    val turns by model.assistant.turns.collectAsStateWithLifecycle()
    val phase by model.assistant.phase.collectAsStateWithLifecycle()
    val since by model.live.since.collectAsStateWithLifecycle()
    val turn = turns.lastOrNull { it.source != TurnSource.PHONE }?.takeIf { since > 0 && it.at >= since }
    val busy = phase == AssistantPhase.THINKING || phase == AssistantPhase.SPEAKING
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(turn?.interactionId, turn?.answer, busy) {
        showing = turn != null
        if (turn != null && !busy && turn.answer.isNotEmpty()) {
            delay(CAPTION_HOLD_MS)
            showing = false
        }
    }
    val density = LocalDensity.current
    AnimatedVisibility(
        visible = showing && turn != null,
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, (visible.bottom - with(density) { 180.dp.toPx() }).roundToInt()) }
            .graphicsLayer { this.alpha = alpha },
        enter = fadeIn(tween(240)) + slideInVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it / 4 },
        exit = fadeOut(tween(400)),
    ) {
        val t = turn ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(t.question, style = Visor.type.label, color = colors.textPrimary.copy(alpha = 0.7f), textAlign = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            val pulse = rememberInfiniteTransition(label = "thinking").animateFloat(0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "thinking")
            if (t.answer.isEmpty()) {
                Text(stringResource(R.string.live_ai_thinking), style = Visor.type.body, color = colors.textPrimary, modifier = Modifier.graphicsLayer { this.alpha = pulse.value })
            } else {
                Text(t.answer, style = Visor.type.heading, color = colors.textPrimary, textAlign = TextAlign.Center, maxLines = 4)
            }
        }
    }
}

private const val CAPTION_HOLD_MS = 6_000L

/** Thirds over the visible picture, hairline. */
@Composable
private fun Grid(visible: Rect, alpha: Float) {
    Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        val c = Color.White.copy(alpha = 0.28f)
        val stroke = 1.dp.toPx()
        for (k in 1..2) {
            val x = visible.left + visible.width * k / 3
            val y = visible.top + visible.height * k / 3
            drawLine(c, Offset(x, visible.top), Offset(x, visible.bottom), stroke)
            drawLine(c, Offset(visible.left, y), Offset(visible.right, y), stroke)
        }
    }
}

/** "● LIVE 12:04", or REC with the recording's own time, or ON AIR. The dot blinks once a second, like the LED. */
@Composable
internal fun StatusChip(model: LiveViewModel, modifier: Modifier = Modifier) {
    val colors = Visor.colors
    val since by model.live.since.collectAsStateWithLifecycle()
    val recording by model.outputs.recording.collectAsStateWithLifecycle()
    val broadcast by model.outputs.broadcast.state.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    val rec = recording
    val (word, from) = when {
        rec != null -> stringResource(R.string.live_rec) to rec.since
        broadcast.phase == BroadcastPhase.LIVE -> stringResource(R.string.live_on_air) to broadcast.since
        else -> stringResource(R.string.live_badge) to since
    }
    val blink = rememberInfiniteTransition(label = "led")
    val led by blink.animateFloat(1f, 0.25f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "led")
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.38f)).padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).graphicsLayer { alpha = led }.clip(CircleShape).background(colors.signal))
        Spacer(Modifier.width(8.dp))
        Text(word, style = Visor.type.label, color = colors.textPrimary)
        if (from > 0) {
            Spacer(Modifier.width(8.dp))
            Text(duration(now - from), style = TABULAR_LABEL, color = colors.textPrimary)
        }
    }
}

@Composable
private fun Reconnecting(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.live_reconnecting),
        style = Visor.type.label, color = Visor.colors.textPrimary,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.38f)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Size, frame rate, bitrate and whether Visor picks them: what the link is doing right now. */
@Composable
internal fun Readouts(model: LiveViewModel, stacked: Boolean) {
    val colors = Visor.colors
    val stats by model.live.receiver.stats.collectAsStateWithLifecycle()
    val decoded by model.live.decodedSize.collectAsStateWithLifecycle()
    val auto by model.live.auto.collectAsStateWithLifecycle()
    val rung by model.live.rung.collectAsStateWithLifecycle()
    val size = decoded?.let { "${minOf(it.width, it.height)}p" } ?: rung?.quality?.let { "${minOf(it.width, it.height)}p" } ?: "–"
    val mbit = "%.1f Mbit/s".format(stats.bytesPerSecond * 8 / 1_000_000f)
    val parts = listOf(size, "${stats.fps} fps", mbit, stringResource(if (auto) R.string.live_auto else R.string.live_manual))
    if (stacked) {
        Column(horizontalAlignment = Alignment.End) {
            for (p in parts) Text(p, style = TABULAR_LABEL, color = colors.textPrimary.copy(alpha = 0.85f))
        }
    } else {
        Text(parts.joinToString("  ·  "), style = TABULAR_LABEL, color = colors.textSecondary)
    }
}

/** How warm the glasses are and how much battery they have left. */
@Composable
internal fun Heat(model: LiveViewModel, modifier: Modifier = Modifier) {
    val colors = Visor.colors
    val device by model.live.device.collectAsStateWithLifecycle()
    val stream by model.live.health.collectAsStateWithLifecycle()
    val health = device?.thermal?.health ?: stream.thermal
    val percent = device?.battery?.percent ?: stream.battery?.toLong()
    val hot = health == "SEVERE" || health == "CRITICAL" || health == "SHUTDOWN"
    val words = thermalWords(health)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (words != null) {
            Icon(VisorIcons.Heat, stringResource(R.string.fact_temperature), Modifier.size(16.dp), tint = if (hot) colors.signal else colors.textPrimary)
            Text(stringResource(words), style = Visor.type.label, color = if (hot) colors.signal else colors.textPrimary)
        }
        if (percent != null) {
            if (words != null) Spacer(Modifier.width(6.dp))
            Icon(VisorIcons.Battery, stringResource(R.string.live_battery), Modifier.size(16.dp), tint = if (percent <= 10) colors.signal else colors.textPrimary)
            Text("$percent%", style = TABULAR_LABEL, color = colors.textPrimary)
        }
    }
}

/** A THERMAL_HEALTH value in words; null when the glasses do not say. */
internal fun thermalWords(health: String?): Int? = when (health?.uppercase()) {
    "NORMAL" -> R.string.live_heat_normal
    "LIGHT" -> R.string.live_heat_light
    "MODERATE" -> R.string.live_heat_moderate
    "SEVERE" -> R.string.live_heat_severe
    "CRITICAL", "SHUTDOWN" -> R.string.live_heat_critical
    else -> null
}

/** "2 min", "47 s": how long a span of the Live is. */
@Composable
fun spanWords(seconds: Long): String = when {
    seconds >= 60 && seconds % 60 == 0L -> stringResource(R.string.span_minutes, seconds / 60)
    seconds >= 60 -> duration(seconds * 1000)
    else -> stringResource(R.string.span_seconds, seconds)
}

/** Stops, heat and broadcast changes, said once as they happen. */
@Composable
internal fun LiveWatchers(model: LiveViewModel) {
    val device by model.live.device.collectAsStateWithLifecycle()
    val stream by model.live.health.collectAsStateWithLifecycle()
    val broadcast by model.outputs.broadcast.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val health = device?.thermal?.health ?: stream.thermal
    LaunchedEffect(model) {
        model.live.stopped.collect { reason ->
            when (reason) {
                STOP_THERMAL -> model.say(R.string.live_stopped_heat, warning = true, icon = VisorIcons.Heat)
                STOP_BATTERY_LOW -> model.say(R.string.live_stopped_battery, warning = true, icon = VisorIcons.Battery)
                STOP_TEMPLES_CLOSED -> model.say(R.string.live_stopped_folded, warning = true, icon = VisorIcons.Glasses)
            }
        }
    }
    // Only heat that may stop the camera is worth a word, and only once the picture is up.
    val status by model.live.status.collectAsStateWithLifecycle()
    val severe = health == "SEVERE" || health == "CRITICAL"
    // Kept across a trip to the settings, so coming back does not say it again.
    var warned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(severe, status.phase == LivePhase.RUNNING) {
        if (!severe) {
            warned = false
        } else if (status.phase == LivePhase.RUNNING && !warned) {
            warned = true
            model.say(R.string.live_heat_severe_warning, warning = true, icon = VisorIcons.Heat)
            haptics.error()
        }
    }
    var heard by rememberSaveable { mutableStateOf(broadcast.phase) }
    LaunchedEffect(broadcast.phase) {
        if (broadcast.phase == heard) return@LaunchedEffect
        heard = broadcast.phase
        when (broadcast.phase) {
            BroadcastPhase.RECONNECTING -> model.say(R.string.live_broadcast_retrying, warning = true, icon = VisorIcons.Live)
            BroadcastPhase.FAILED -> {
                model.live.note("Broadcast stopped: ${broadcast.problem}")
                model.say(R.string.live_broadcast_failed, warning = true, icon = VisorIcons.Live)
                haptics.error()
            }
            BroadcastPhase.LIVE -> haptics.done()
            else -> Unit
        }
    }
}

/** In picture in picture: only that something is being kept. */
@Composable
internal fun BoxScope.PipBadge(recording: Boolean) {
    Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(10.dp).clip(CircleShape).background(if (recording) Visor.colors.signal else Color.White))
}

/** One line at the top for a few seconds: what was saved, with a way to the gallery, or a warning. */
@Composable
internal fun BoxScope.NoticeIsland(model: LiveViewModel, onOpen: () -> Unit) {
    val colors = Visor.colors
    val notice by model.notice.collectAsStateWithLifecycle()
    var last by remember { mutableStateOf(notice) }
    if (notice != null) last = notice
    AnimatedVisibility(
        visible = notice != null,
        modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 56.dp, start = 24.dp, end = 24.dp),
        enter = fadeIn(tween(220)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { -it / 2 },
        exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { -it / 3 },
    ) {
        val n = last ?: return@AnimatedVisibility
        Row(
            Modifier.clip(RoundedCornerShape(22.dp)).background(colors.raised.copy(alpha = 0.94f)).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            n.icon?.let {
                Icon(it, null, Modifier.size(18.dp), tint = colors.textPrimary)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (n.arg != null) stringResource(n.text, n.arg) else stringResource(n.text),
                style = Visor.type.label, color = colors.textPrimary,
                modifier = Modifier.padding(vertical = 6.dp).weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (n.uri != null) {
                Spacer(Modifier.width(10.dp))
                ActionPill(stringResource(R.string.gallery_title), onOpen)
            }
            if (n.glassesHost) {
                Spacer(Modifier.width(10.dp))
                ActionPill(stringResource(R.string.live_use_glasses_host), { model.useGlassesHost() })
            }
            if (n.uri == null && !n.glassesHost) Spacer(Modifier.width(8.dp))
        }
    }
}

// ---- Stepped back ----

/** Round the lit window: close and settings above, readouts below, toggles and save buttons at the bottom. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SteppedChrome(
    model: LiveViewModel,
    window: Rect,
    step: Float,
    onClose: () -> Unit,
    onMore: () -> Unit,
    onBroadcast: () -> Unit,
    onSnapshot: () -> Unit,
    onGlassesPhoto: () -> Unit,
) {
    val colors = Visor.colors
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val settings by model.settings.settings.collectAsStateWithLifecycle()
    val recording by model.outputs.recording.collectAsStateWithLifecycle()
    val broadcast by model.outputs.broadcast.state.collectAsStateWithLifecycle()
    val held by produceState(0L) {
        while (true) {
            value = model.outputs.replay.heldUs / 1_000_000
            delay(500)
        }
    }
    val rise = with(density) { 24.dp.toPx() } * (1f - step)
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = step; translationY = rise }) {
        // Placed by the bars' room whether they show or not, as the window is, so nothing moves when they come back.
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(VisorIcons.Close, stringResource(R.string.live_close), onClose, size = 48.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { StatusChip(model) }
            RoundIcon(VisorIcons.Settings, stringResource(R.string.live_more), onMore, size = 48.dp)
        }
        Column(
            Modifier.fillMaxWidth().offset { IntOffset(0, (window.bottom + with(density) { 14.dp.toPx() }).roundToInt()) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (LiveOverlay.READOUTS in settings.overlays) Readouts(model, stacked = false)
            if (LiveOverlay.HEAT in settings.overlays) Heat(model)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle(VisorIcons.Live, stringResource(R.string.live_broadcast), broadcast.airing, Modifier.semantics { selected = broadcast.airing }) { haptics.toggle(); onBroadcast() }
                Toggle(if (settings.listen) VisorIcons.Speaker else VisorIcons.SpeakerOff, stringResource(R.string.live_listen), settings.listen, Modifier.switchRow(settings.listen)) {
                    haptics.toggle()
                    model.update { it.copy(listen = !it.listen) }
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                Captioned(duration(held * 1000)) {
                    RoundIcon(VisorIcons.Replay, stringResource(R.string.live_replay), { haptics.done(); model.saveReplay() }, size = 56.dp, background = colors.raised.copy(alpha = 0.85f))
                }
                RecordButton(recording != null) {
                    haptics.shutter()
                    model.toggleRecording()
                }
                Captioned(stringResource(R.string.live_photo)) {
                    // A tap keeps the picture on screen; a long press asks the glasses for a full photo.
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(colors.raised.copy(alpha = 0.85f))
                            .combinedClickable(
                                onClickLabel = stringResource(R.string.live_photo),
                                onLongClickLabel = stringResource(R.string.live_glasses_photo),
                                onLongClick = {
                                    haptics.connected()
                                    onGlassesPhoto()
                                },
                                onClick = {
                                    haptics.shutter()
                                    onSnapshot()
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(VisorIcons.Camera, stringResource(R.string.live_photo), Modifier.size(22.dp), tint = colors.textPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Captioned(caption: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(caption, style = TABULAR_LABEL, color = Visor.colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
    }
}

/** A white ring; inside it a white disc, or while recording a small square. */
@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val inner by animateDpAsState(if (recording) 26.dp else 58.dp, spring(dampingRatio = 0.7f, stiffness = 500f), label = "rec")
    val corner by animateDpAsState(if (recording) 7.dp else 29.dp, spring(dampingRatio = 0.7f, stiffness = 500f), label = "corner")
    val label = stringResource(if (recording) R.string.live_stop_recording else R.string.live_record)
    Box(
        Modifier.size(76.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(Color.White))
    }
}

@Composable
private fun Toggle(icon: ImageVector, text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Visor.colors
    val back by animateColorAsState(if (on) Color.White else colors.raised.copy(alpha = 0.85f), tween(180), label = "back")
    val front by animateColorAsState(if (on) Color.Black else colors.textPrimary, tween(180), label = "front")
    Row(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(back)
            .clickable(interactionSource = null, indication = ripple(color = if (on) Color.Black else colors.textPrimary), onClick = onClick).then(modifier).padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = front)
        Spacer(Modifier.width(8.dp))
        Text(text, style = Visor.type.label, color = front)
    }
}

// ---- Sheets ----

/** What sits on the picture, each on or off, and the way to the rest of the settings. */
@Composable
internal fun LiveSheet(model: LiveViewModel, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val settings by model.settings.settings.collectAsStateWithLifecycle()
    val auto by model.live.auto.collectAsStateWithLifecycle()
    val rung by model.live.rung.collectAsStateWithLifecycle()
    VisorSheet(onDismiss, full = true) {
        SheetTitle(stringResource(R.string.live_settings_on_picture))
        LiveOverlayRows(model, settings.overlays)
        Spacer(Modifier.height(12.dp))
        Card {
            val detail = (if (auto) stringResource(R.string.live_auto) else stringResource(R.string.live_manual)) +
                (rung?.let { " · ${it.quality.label}" } ?: "")
            NavRow(null, stringResource(R.string.settings_title), detail, onClick = onSettings, onCard = true)
        }
    }
}

/** Where the Live goes out to; while on air, its rate and End. */
@Composable
internal fun BroadcastSheet(model: LiveViewModel, onDismiss: () -> Unit) {
    val colors = Visor.colors
    val settings by model.settings.settings.collectAsStateWithLifecycle()
    val state by model.outputs.broadcast.state.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(settings.rtmpUrl) }
    var key by rememberSaveable { mutableStateOf(settings.rtmpKey) }
    var codec by rememberSaveable { mutableStateOf(settings.rtmpCodec) }
    VisorSheet(onDismiss, full = true) {
        if (state.airing) {
            val host = runCatching { URI(settings.rtmpUrl).host }.getOrNull() ?: settings.rtmpUrl
            SheetTitle(stringResource(R.string.live_broadcast), stringResource(R.string.live_broadcast_to, host))
            if (state.phase == BroadcastPhase.LIVE) {
                Text(
                    "%.1f Mbit/s".format(state.kbps / 1000f),
                    style = Visor.type.readoutSmall, color = colors.textPrimary, modifier = Modifier.padding(horizontal = SIDE).padding(bottom = 22.dp),
                )
            } else {
                Spinner(Modifier.padding(horizontal = SIDE).padding(top = 18.dp, bottom = 22.dp))
            }
            ActionPill(stringResource(R.string.live_broadcast_end), {
                model.stopBroadcast()
                onDismiss()
            }, modifier = Modifier.padding(horizontal = SIDE))
        } else {
            SheetTitle(stringResource(R.string.live_broadcast))
            SheetField(url, { url = it }, hint = stringResource(R.string.live_broadcast_server), keyboard = KeyboardType.Uri)
            Spacer(Modifier.height(10.dp))
            SheetField(key, { key = it }, hint = stringResource(R.string.live_broadcast_key), secret = true)
            Spacer(Modifier.height(12.dp))
            Rows(listOf(BroadcastCodec.H264 to R.string.live_broadcast_h264, BroadcastCodec.HEVC to R.string.live_broadcast_hevc)) { (choice, name) ->
                RowFrame(stringResource(name), onClick = { codec = choice }, modifier = Modifier.semantics { selected = codec == choice }) {
                    ChoiceMark(codec == choice, Modifier.padding(start = 12.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            // A server with a host and an application, like rtmp://host/live2.
            val address = runCatching { URI(url.trim()) }.getOrNull()
            val ready = address != null && address.scheme?.lowercase().let { it == "rtmp" || it == "rtmps" } &&
                address.host != null && address.path?.trim('/')?.isNotEmpty() == true && key.isNotBlank()
            ActionPill(stringResource(R.string.live_broadcast_go), {
                model.update { it.copy(rtmpUrl = url.trim(), rtmpKey = key.trim(), rtmpCodec = codec) }
                model.startBroadcast()
                onDismiss()
            }, modifier = Modifier.padding(horizontal = SIDE), enabled = ready)
        }
    }
}
