package com.macedonga.visor.stage

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import com.macedonga.visor.ui.animationsOff
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Where a screen wants the pair this frame: the stage's box on the window, and the pair with its pool inside it,
 * scaled about [pivotX], [pivotY] (fractions of the stage) and moved down by [shift].
 */
class StagePlace {
    var top = 0f
    var height = 0f
    var scale = 1f
    var pivotX = 0.5f
    var pivotY = 0.5f
    var shift = 0f
    var alpha = 1f

    /** The open stage folded [fold] of the way into a strip [stripPx] high, the pair [foldedScale] there and centred on [foldedCentre]. */
    fun folding(fullPx: Float, stripPx: Float, fold: Float, foldedScale: Float, foldedCentre: Float) {
        top = 0f
        height = fullPx - fold * (fullPx - stripPx)
        scale = 1f - (1f - foldedScale) * fold
        pivotX = 0.5f
        pivotY = 0.5f
        shift = fold * (foldedCentre - fullPx / 2)
        alpha = 1f
    }

    internal fun set(o: StagePlace) = mix(o, o, 0f)

    internal fun mix(a: StagePlace, b: StagePlace, t: Float) {
        fun l(x: Float, y: Float) = x + (y - x) * t
        top = l(a.top, b.top)
        height = l(a.height, b.height)
        scale = l(a.scale, b.scale)
        pivotX = l(a.pivotX, b.pivotX)
        pivotY = l(a.pivotY, b.pivotY)
        shift = l(a.shift, b.shift)
        alpha = l(a.alpha, b.alpha)
    }
}

/** A screen's hold on the pair while it is composed: where, whether the temples are closed, how restless it is. */
class Spot internal constructor(internal val place: StagePlace.() -> Unit) {
    var closed by mutableStateOf(true)
        internal set
    var liveliness by mutableStateOf(1f)
        internal set
}

/** A screen's wish for the light while it is composed. */
class LookRequest internal constructor(look: LightLook) {
    var look by mutableStateOf(look)
        internal set
}

/**
 * Shows the pair on this screen, placed by [place] every frame. The newest screen to ask has it; the pair travels
 * there from wherever it was, and [StageHolder.shown] says how far, for the screen's own things to follow.
 */
@Composable
fun StageSpot(closed: Boolean, liveliness: Float, place: StagePlace.() -> Unit): Spot {
    val stage = LocalStage.current
    val current = rememberUpdatedState(place)
    val spot = remember { Spot { current.value(this) }.also { it.closed = closed; it.liveliness = liveliness } }
    SideEffect {
        spot.closed = closed
        spot.liveliness = liveliness
    }
    DisposableEffect(spot) {
        stage.arrive(spot)
        onDispose { stage.leave(spot) }
    }
    return spot
}

/** Asks for the light to look like [look] while this is composed; the newest ask is the one shown. */
@Composable
fun StageLight(look: LightLook) {
    val stage = LocalStage.current
    val request = remember { LookRequest(look) }
    SideEffect { request.look = look }
    DisposableEffect(request) {
        stage.looks.add(request)
        onDispose { stage.looks.remove(request) }
    }
}

/** The pairs a drag on the stage moves between: whether there is one [direction] (1 the next), and going there. */
class StagePages(val can: (direction: Int) -> Boolean, val go: (direction: Int) -> Unit)

/**
 * Dragging sideways turns the pair, and it springs back to rest. With [pages] it does not turn: it follows the finger
 * sideways, and a long drag or a fling takes it out of the light for the next one.
 */
@Composable
fun Modifier.turnsThePair(pages: StagePages? = null, onDetent: () -> Unit): Modifier {
    val stage = LocalStage.current
    val scope = rememberCoroutineScope()
    val detent = rememberUpdatedState(onDetent)
    val paging = rememberUpdatedState(pages)
    return pointerInput(stage) {
        var lastDetent = 0
        var travel = 0f
        var past = false
        val tracker = VelocityTracker()
        val back = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)
        fun settle() {
            scope.launch { stage.yaw.animateTo(0f, back) }
            scope.launch { stage.slide.animateTo(0f, back) }
        }
        detectHorizontalDragGestures(
            onDragStart = {
                travel = 0f
                past = false
                tracker.resetTracking()
            },
            onDragEnd = {
                val pages = paging.value
                val fling = tracker.calculateVelocity().x
                val direction = when {
                    pages == null -> 0
                    travel < -size.width * PAGE_AT || fling < -PAGE_FLING -> 1
                    travel > size.width * PAGE_AT || fling > PAGE_FLING -> -1
                    else -> 0
                }
                if (direction != 0 && pages!!.can(direction)) {
                    if (!past) detent.value()
                    scope.launch { stage.page(direction) { pages.go(direction) } }
                } else {
                    settle()
                }
            },
            onDragCancel = { settle() },
        ) { change, dx ->
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            travel += dx
            val pages = paging.value
            if (pages != null) {
                // Towards a page it follows the finger; at the end of the row it barely moves. A tick says it will go.
                val towards = if (travel < 0) 1 else -1
                val can = pages.can(towards)
                scope.launch { stage.slide.snapTo(travel / size.width * if (can) FOLLOW else FOLLOW_AT_END) }
                val nowPast = can && abs(travel) > size.width * PAGE_AT
                if (nowPast != past) {
                    past = nowPast
                    if (nowPast) detent.value()
                }
                return@detectHorizontalDragGestures
            }
            // Free for the first half turn either way, then it resists like a rubber band.
            val yaw = stage.yaw.value
            val next = (yaw + dx * DEGREES_PER_PX * (1f - (abs(yaw) / MAX_YAW).coerceIn(0f, 0.9f))).coerceIn(-MAX_YAW, MAX_YAW)
            scope.launch { stage.yaw.snapTo(next) }
            val d = (next / DETENT_DEGREES).toInt()
            if (d != lastDetent) {
                lastDetent = d
                detent.value()
            }
        }
    }
}

/**
 * The one stage: the pair, its pool and its light, made once and kept for the life of the activity under every
 * screen. Screens ask for it with [StageSpot] and leave holes where it shows; it goes where the newest one wants it,
 * so moving between screens never makes a second pair, a second pool or a second light. [shown] is false while the
 * screen on top has no pair: the stage fades out with the screen it was on.
 */
@Composable
fun SharedStage(stage: StageHolder, shown: Boolean) {
    val newest = stage.spots.lastOrNull()
    // The light runs while a screen asks for it; nothing ticks behind one that does not, or under the Live's picture.
    // While the mark holds the dark it stays down, and comes up as the mark lets go.
    val marked by remember { derivedStateOf { stage.intro.holding } }
    if (stage.looks.isNotEmpty()) {
        val look = stage.looks.lastOrNull()?.look ?: stage.restingLook
        AnimateLight(stage.light, if (marked) look.copy(level = 0f) else look)
    }
    SideEffect { stage.looks.lastOrNull()?.let { stage.restingLook = it.look } }
    Hinge(newest?.closed ?: stage.restingClosed, stage.pose, firstVisit = true, arrival = stage.intro::awaitLetGo)
    SideEffect { newest?.let { stage.restingClosed = it.closed } }
    LaunchedEffect(stage) {
        snapshotFlow { stage.travelling }.collect {
            while (stage.travelling) {
                val still = animationsOff()
                withFrameNanos { stage.step(it, still) }
            }
        }
    }
    LaunchedEffect(stage) { snapshotFlow { stage.yaw.value }.collect { stage.pose.yawDegrees = it; stage.renderer.invalidate() } }
    LaunchedEffect(stage) { snapshotFlow { stage.slide.value }.collect { stage.pose.slide = it; stage.renderer.invalidate() } }
    // At once, not faded: a page changes while the pair is out of sight, and a fade would show both as it slides in.
    val picture = stage.picture.takeIf { !stage.vacant }
    val pictured = if (picture != null) 1f else 0f
    val vacant = if (stage.vacant) 1f else 0f
    val curtain by animateFloatAsState(if (shown && newest != null) 1f else 0f, if (shown) snap() else tween(CURTAIN_MS), label = "stage")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stageHeight = maxWidth / STAGE_ASPECT
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, stage.placement().top.roundToInt()) }
                .layout { measurable, constraints ->
                    val h = stage.placement().height.roundToInt().coerceAtLeast(0)
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .clipToBounds()
                .graphicsLayer { alpha = curtain },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .height(stageHeight)
                    .graphicsLayer {
                        val p = stage.placement()
                        scaleX = p.scale
                        scaleY = p.scale
                        transformOrigin = TransformOrigin(p.pivotX, p.pivotY)
                        translationY = p.shift
                        alpha = p.alpha
                        // Nothing is drawn while nothing of it shows: behind another screen, or under the Live's picture.
                        stage.renderer.paused = curtain == 0f || p.alpha <= 0f
                    },
            ) {
                LightPool(stage.light, Offset(0.5f, POOL_Y), Size(0.21f, 0.058f), Size(0.17f, 0.02f), Modifier.fillMaxSize())
                GlassesStage(
                    liveliness = newest?.liveliness ?: 0.25f, active = curtain > 0f,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - maxOf(pictured, vacant) },
                )
                picture?.let { PairPicture(it, stage, Modifier.fillMaxSize()) }
            }
        }
    }
}

/** Meta's picture of the pair, front on where the model would stand, sliding with it. */
@Composable
private fun PairPicture(picture: ImageBitmap, stage: StageHolder, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        Image(
            picture, null,
            Modifier
                .align(BiasAlignment(0f, PICTURE_Y))
                .fillMaxWidth(PICTURE_WIDTH)
                // Its own shape, so it fills the width rather than drawing at its pixel size.
                .aspectRatio(picture.width.toFloat() / picture.height)
                .graphicsLayer { translationX = stage.slide.value * size.width / PICTURE_WIDTH },
            contentScale = ContentScale.Fit,
        )
    }
}

/** How the pair travels from one screen's place to the next. */
internal val HANDOVER_EASE = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal const val HANDOVER_NS = 640_000_000L
private const val CURTAIN_MS = 180
// As wide as the model looks on the home, and low enough that it ends in the light where the model's temples do.
private const val PICTURE_WIDTH = 0.56f
private const val PICTURE_Y = 0.1f
private const val DEGREES_PER_PX = 0.32f
private const val MAX_YAW = 160f
private const val DETENT_DEGREES = 30f
// A third of the width, or a flick, goes to the next pair.
private const val PAGE_AT = 0.33f
private const val PAGE_FLING = 1_400f
private const val FOLLOW = 0.9f
private const val FOLLOW_AT_END = 0.12f
