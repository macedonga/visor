package com.macedonga.visor.stage

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The glasses, their light and their pose for the life of the activity, above navigation: the model loads once and
 * is drawn by the one [SharedStage]; screens say where they want it.
 */
class StageHolder(private val context: Context, markAtLaunch: Boolean) {
    val light = LightFrame()
    /** The mark's opening, held from the first frame when the app opens on the home or the welcome. */
    val intro = MarkIntro(armed = markAtLaunch)
    val pose = GlassesPose()
    /** The home has played its entrance in this process; coming back to it plays nothing. */
    var entered = false
    /** The Live view left the pair where the open home has it: the home comes back scrolled to the top. */
    var homeToTop = false
    /** The mood the home last gave the pair, and whether the glasses were last folded: other screens start from these. */
    var mood = Mood.ABSENT
    var lastFolded = false
    /** The Live has just closed: the home counts the glasses as here while Bluetooth comes back. */
    var fromLive = false
    /** Turned by a finger; springs back to 0. */
    val yaw = Animatable(0f)
    /** Sideways out of the light, in stage widths: a pair leaving for the next one. */
    val slide = Animatable(0f)
    /** A pair Visor has no model for, or one not connected, shows as Meta pictures it; null shows the model. */
    var picture by mutableStateOf<ImageBitmap?>(null)
    /** Nothing under the light: the home's page for adding another pair. */
    var vacant by mutableStateOf(false)

    /** The pair slides out [direction] (1 to the left), [swap] puts the next one up, and it slides in from the far side. */
    suspend fun page(direction: Int, swap: () -> Unit) {
        coroutineScope {
            launch { yaw.animateTo(0f, tween(PAGE_OUT_MS, easing = FastOutLinearInEasing)) }
            slide.animateTo(-direction.toFloat(), tween(PAGE_OUT_MS, easing = FastOutLinearInEasing))
        }
        swap()
        slide.snapTo(direction.toFloat())
        slide.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 260f))
    }

    internal val spots = mutableStateListOf<Spot>()
    internal val looks = mutableStateListOf<LookRequest>()
    internal var restingLook = LightLook(Color.Black, Color.Black, 0f, 0.7f)
    internal var restingClosed = true

    // The pair's place: where it was when the newest spot took it, where that spot wants it, and where it is.
    private val from = StagePlace()
    private val target = StagePlace()
    private val now = StagePlace()
    private val handover = mutableFloatStateOf(1f)
    private var startedAt = 0L
    private var started = false

    private var made: GlassesRenderer? = null

    val renderer: GlassesRenderer get() = made ?: GlassesRenderer(context, light, pose, Looks.keptModel(context).asset).also { made = it }

    /**
     * How much [spot]'s screen should show its own things around the pair: the newest goes 0 to 1 as the pair arrives,
     * the one it came from 1 to 0.
     */
    fun shown(spot: Spot): Float = if (spots.lastOrNull() === spot) handover.floatValue else 1f - handover.floatValue

    internal fun arrive(spot: Spot) {
        val travel = spots.isNotEmpty()
        spots.add(spot)
        if (travel) startHandover() else handover.floatValue = 1f
    }

    internal fun leave(spot: Spot) {
        val wasNewest = spots.lastOrNull() === spot
        spots.remove(spot)
        // A back gesture given up: the screen underneath goes, and the pair goes back to the one still showing.
        if (wasNewest && spots.isNotEmpty()) startHandover()
    }

    private fun startHandover() {
        from.set(now)
        handover.floatValue = 0f
        started = false
    }

    /** The pair is on its way from one screen's place to another's. */
    internal val travelling: Boolean get() = handover.floatValue < 1f

    /** [still]: the phone's "Remove animations", and the pair lands at once. */
    internal fun step(frameNanos: Long, still: Boolean = false) {
        if (handover.floatValue >= 1f) return
        if (still) {
            handover.floatValue = 1f
            return
        }
        if (!started) {
            started = true
            startedAt = frameNanos
        }
        val t = ((frameNanos - startedAt).toFloat() / HANDOVER_NS).coerceIn(0f, 1f)
        handover.floatValue = if (t >= 1f) 1f else HANDOVER_EASE.transform(t)
    }

    /** Where the pair is this frame. Read from layout and drawing, so it follows whatever the newest spot reads. */
    internal fun placement(): StagePlace {
        val spot = spots.lastOrNull() ?: return now
        spot.place(target)
        val t = handover.floatValue
        if (t >= 1f) now.set(target) else now.mix(from, target, t)
        return now
    }

    /** A point of the pair's view, as fractions of the stage, on the window: where the stage is now, [widthPx] wide. */
    fun onWindow(fx: Float, fy: Float, widthPx: Float): androidx.compose.ui.geometry.Offset {
        val p = placement()
        val h = widthPx / STAGE_ASPECT
        val x = p.pivotX * widthPx + (fx - p.pivotX) * widthPx * p.scale
        val y = p.top + p.pivotY * h + (fy - p.pivotY) * h * p.scale + p.shift
        return androidx.compose.ui.geometry.Offset(x, y)
    }

    fun destroy() {
        made?.destroy()
        made = null
    }
}

val LocalStage = staticCompositionLocalOf<StageHolder> { error("no stage above this screen") }

@Composable
fun rememberStageHolder(markAtLaunch: Boolean): StageHolder {
    val context = LocalContext.current.applicationContext
    val stage = remember { StageHolder(context, markAtLaunch) }
    DisposableEffect(stage) { onDispose { stage.destroy() } }
    return stage
}

private const val PAGE_OUT_MS = 200
