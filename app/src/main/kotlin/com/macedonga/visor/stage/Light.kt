package com.macedonga.visor.stage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.macedonga.visor.core.connection.CAMERA_VIDEO_STARTED
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.design.VisorColors
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.ui.animationsOff
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** What the glasses are doing, as the light tells it. */
enum class Mood { ABSENT, FAR, SEARCHING, CONNECTED, CHARGING, RECORDING, LOW_BATTERY, IMPORTING, ASLEEP, IN_CASE, GOING_LIVE }

/** One mood's light: colour at the heart and at the rim of the pool, how bright, how wide, how it breathes. */
data class LightLook(
    val core: Color,
    val rim: Color,
    val level: Float,
    val spread: Float,
    val breathSeconds: Float = 0f,
    val breathDepth: Float = 0f,
    val warp: Float = 0.18f,
    /** How fast the edge drifts: 1 is the slow drift of a diffuser. */
    val pace: Float = 1f,
)

/** Each mood's light: near white, tinted towards a hue of its own. */
fun lookOf(mood: Mood, c: VisorColors): LightLook = when (mood) {
    Mood.ABSENT -> LightLook(c.lightCold, c.lightCold, level = 0.05f, spread = 0.7f, warp = 0.1f)
    Mood.FAR -> LightLook(c.lightCold, c.lightCold, level = 0.16f, spread = 0.75f, warp = 0.12f)
    Mood.SEARCHING -> LightLook(
        lerp(c.lightWarm, HUE_SEARCHING, 0.14f), lerp(c.lightWarm, HUE_SEARCHING, 0.22f),
        level = 0.5f, spread = 0.86f, breathSeconds = 4f, breathDepth = 0.55f, warp = 0.24f,
    )
    Mood.CONNECTED -> LightLook(c.lightNeutral, c.lightWarm, level = 1f, spread = 1f, breathSeconds = 9f, breathDepth = 0.06f)
    // Charging, above all in the case, the light leans clearly green, bright enough that the pair reads.
    Mood.CHARGING, Mood.IN_CASE -> LightLook(
        lerp(c.lightWarm, HUE_CHARGING, 0.35f), lerp(c.lightWarm, HUE_CHARGING, 0.55f),
        level = 0.86f, spread = 0.86f, breathSeconds = 6f, breathDepth = 0.2f, warp = 0.14f,
    )
    Mood.RECORDING -> LightLook(
        lerp(c.lightNeutral, c.signal, 0.1f), lerp(c.lightNeutral, c.signal, 0.32f),
        level = 0.95f, spread = 1.04f, breathSeconds = 2f, breathDepth = 0.08f, warp = 0.2f,
    )
    Mood.LOW_BATTERY -> LightLook(lerp(c.lightNeutral, HUE_LOW, 0.18f), lerp(c.lightNeutral, HUE_LOW, 0.3f), level = 0.8f, spread = 0.95f)
    // Folded and asleep: as dim as away, a little warmer, nearly still.
    Mood.ASLEEP -> LightLook(lerp(c.lightCold, c.lightWarm, 0.3f), c.lightCold, level = 0.14f, spread = 0.75f, warp = 0.1f)
    // The camera coming on over Wi-Fi: the light turns towards the signal colour and breathes until the first frame.
    Mood.GOING_LIVE -> LightLook(
        lerp(c.lightNeutral, c.signal, 0.3f), lerp(c.lightNeutral, c.signal, 0.62f),
        level = 0.85f, spread = 0.95f, breathSeconds = 1.8f, breathDepth = 0.5f, warp = 0.26f,
    )
    // Media coming over: the light turns blue and pulses quickly, like a transfer.
    Mood.IMPORTING -> LightLook(
        lerp(c.lightNeutral, HUE_IMPORT, 0.4f), lerp(c.lightNeutral, HUE_IMPORT, 0.6f),
        level = 0.9f, spread = 0.96f, breathSeconds = 1.4f, breathDepth = 0.6f, warp = 0.2f,
    )
}

/** The add page: a cool light, breathing slowly under the ghost of a pair. */
fun ghostLook(c: VisorColors): LightLook =
    LightLook(c.lightCold, c.lightCold, level = 0.32f, spread = 0.8f, breathSeconds = 5f, breathDepth = 0.35f, warp = 0.16f)

private val HUE_SEARCHING = Color(0xFF8FB0FF)
private val HUE_CHARGING = Color(0xFF9DF0B4)
private val HUE_LOW = Color(0xFFFFA43A)
private val HUE_IMPORT = Color(0xFF5B9BFF)

/** At or below this the battery counts as low. */
const val CRITICAL_PERCENT = 10

fun moodOf(state: GlassesState, live: Boolean): Mood {
    if (state.target == null) return Mood.ABSENT
    val device = state.device
    return when {
        state.link == LinkState.DISCONNECTED || state.link == LinkState.LOST -> Mood.FAR
        state.link != LinkState.READY -> Mood.SEARCHING
        live || device?.streamingActive == true || device?.camera == CAMERA_VIDEO_STARTED -> Mood.RECORDING
        device?.battery?.charging == true -> Mood.CHARGING
        (device?.battery?.percent ?: 100L) <= CRITICAL_PERCENT -> Mood.LOW_BATTERY
        else -> Mood.CONNECTED
    }
}

/** The light this frame, for the pool and the 3D lights; main thread only, [tick] is the state the pool draws from. */
class LightFrame {
    var level = 0f
    var spread = 0.7f
    var warp = 0.1f
    var core = FloatArray(3)
    var rim = FloatArray(3)
    var time = 0f
    var flash = 0f
    /** A word spoken: a short swell of the level, without the flash's white. */
    var swell = 0f
    /** The phone's "Remove animations": each mood comes at once and holds, without breath, drift or flashes. */
    var still = false
    val tick = mutableLongStateOf(0L)

    private var target = LightLook(Color.Black, Color.Black, 0f, 0.7f)
    private var phase = 0f
    private var pace = 1f
    private var flickerLeft = 0f

    fun aim(look: LightLook) {
        target = look
    }

    /** Out, so the light comes up again from nothing: how the stage greets you on the way in. */
    fun dark() {
        level = 0f
        flash = 0f
    }

    /** A photo: a short white flash on top of whatever the light is doing. */
    fun flash() {
        flash = 1f
    }

    /** A beat of speech. */
    fun pulse(amount: Float) {
        swell = maxOf(swell, amount)
    }

    /** Something went wrong: the light falters twice. */
    fun flicker() {
        flickerLeft = FLICKER_SECONDS
    }

    fun step(dt: Float) {
        val k = if (still) 1f else 1f - exp(-dt / EASE_SECONDS)
        pace += (target.pace - pace) * k
        if (!still) time += dt * pace
        val breath = if (target.breathSeconds > 0f && !still) {
            phase = (phase + dt / target.breathSeconds) % 1f
            // Rises quickly, sinks slowly, like a held breath.
            val s = (sin(2 * PI * phase - PI / 2).toFloat() + 1f) / 2f
            1f - target.breathDepth * (1f - s * s)
        } else 1f
        var aimLevel = target.level * breath
        if (still) flickerLeft = 0f
        if (flickerLeft > 0f) {
            flickerLeft = (flickerLeft - dt).coerceAtLeast(0f)
            val t = FLICKER_SECONDS - flickerLeft
            if (t in 0.05f..0.13f || t in 0.32f..0.38f) aimLevel *= 0.15f
        }
        level += (aimLevel - level) * (if (flickerLeft > 0f) 0.85f else k)
        spread += (target.spread * (0.97f + 0.03f * breath) - spread) * k
        warp += (target.warp - warp) * k
        approach(core, target.core, k)
        approach(rim, target.rim, k)
        flash = if (still) 0f else (flash - dt / FLASH_SECONDS).coerceAtLeast(0f)
        swell = if (still) 0f else (swell - dt / SWELL_SECONDS).coerceAtLeast(0f)
        tick.longValue++
    }

    private fun approach(into: FloatArray, to: Color, k: Float) {
        into[0] += (to.red - into[0]) * k
        into[1] += (to.green - into[1]) * k
        into[2] += (to.blue - into[2]) * k
    }

    private companion object {
        const val EASE_SECONDS = 0.45f
        const val FLASH_SECONDS = 0.35f
        const val SWELL_SECONDS = 1.2f
        const val FLICKER_SECONDS = 0.6f
    }
}

/** The light's heart, turned towards white by a photo's flash. */
internal fun LightFrame.flashedCore() = FloatArray(3) { core[it] + (1 - core[it]) * flash }

/** How bright the pool is, with a photo's flash and the swell of a word. */
internal val LightFrame.poolLevel: Float get() = level + flash * 0.6f + swell

/** Steps [frame] towards [look] every frame while this is composed: it eases between moods, breathes, and drifts. */
@Composable
fun AnimateLight(frame: LightFrame, look: LightLook) {
    val current = rememberUpdatedState(look)
    LaunchedEffect(frame) {
        var last = 0L
        while (true) {
            frame.still = animationsOff()
            withFrameNanos { now ->
                frame.aim(current.value)
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                frame.step(dt)
            }
        }
    }
}
