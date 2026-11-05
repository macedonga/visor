package com.macedonga.visor.stage

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** The 3D pair from [LocalStage] over nothing, its pool drawn by [LightPool]; only [SharedStage] shows it. */
@Composable
internal fun GlassesStage(liveliness: Float, active: Boolean, modifier: Modifier = Modifier) {
    val stage = LocalStage.current
    val light = stage.light
    val pose = stage.pose
    val renderer = stage.renderer
    // The tilt sensor listens only while the pair is on screen.
    if (active) Tilt(light, pose, renderer)
    Drift(light, pose, liveliness, renderer)
    AndroidView(factory = { TextureView(it).also(renderer::attach) }, modifier = modifier, onRelease = { renderer.detach(it) })
}

/** The pair's own slow turn, lean, roll and float, from waves with no common beat; [liveliness] scales it, eased. */
@Composable
private fun Drift(light: LightFrame, pose: GlassesPose, liveliness: Float, renderer: GlassesRenderer) {
    val target = rememberUpdatedState(liveliness)
    LaunchedEffect(renderer) {
        var amount = target.value
        snapshotFlow { light.tick.longValue }.collect {
            amount += (target.value - amount) * 0.02f
            val t = light.time
            pose.idleYaw = amount * (2.2f * wave(t, 13.7f) + 0.9f * wave(t, 7.3f, 1.3f))
            pose.idlePitch = amount * (0.8f * wave(t, 10.9f, 0.4f) + 0.3f * wave(t, 4.7f, 2.2f))
            pose.idleRoll = amount * 0.5f * wave(t, 12.1f, 2.1f)
            pose.idleLift = amount * 0.0012f * wave(t, 5.9f, 0.7f)
            renderer.invalidate()
        }
    }
}

private fun wave(t: Float, periodSeconds: Float, phase: Float = 0f) = sin(2 * PI.toFloat() * t / periodSeconds + phase)

/** The phone's tilt as parallax, against a rest that follows slowly so any grip levels out; only while started. */
@Composable
private fun Tilt(light: LightFrame, pose: GlassesPose, renderer: GlassesRenderer) {
    val context = LocalContext.current
    LifecycleStartEffect(pose) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val listener = object : SensorEventListener {
            private var restSide = Float.NaN
            private var restForward = 0f
            private var side = 0f
            private var forward = 0f
            private var last = 0L

            override fun onSensorChanged(event: SensorEvent) {
                val (gx, gy, gz) = event.values
                val sideNow = Math.toDegrees(atan2(gx.toDouble(), sqrt((gy * gy + gz * gz).toDouble()))).toFloat()
                val forwardNow = Math.toDegrees(atan2(gz.toDouble(), gy.toDouble())).toFloat()
                val dt = if (last == 0L) 0f else ((event.timestamp - last) / 1e9f).coerceIn(0f, 0.1f)
                last = event.timestamp
                if (restSide.isNaN()) {
                    restSide = sideNow
                    restForward = forwardNow
                }
                val follow = dt / REST_SECONDS
                restSide += (sideNow - restSide) * follow
                restForward += (forwardNow - restForward) * follow
                val ease = 1f - exp(-dt / EASE_SECONDS)
                side += ((sideNow - restSide).coerceIn(-MAX_TILT, MAX_TILT) - side) * ease
                forward += ((forwardNow - restForward).coerceIn(-MAX_TILT, MAX_TILT) - forward) * ease
                // With animations off the pair stays put, whatever the phone does.
                val moved = if (light.still) 0f else 1f
                pose.tiltYawDegrees = -side * 0.55f * moved
                pose.tiltPitchDegrees = forward * 0.3f * moved
                pose.shiftX = -side * 0.00028f * moved
                pose.shiftY = forward * 0.00012f * moved
                renderer.invalidate()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensor?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onStopOrDispose { sensors?.unregisterListener(listener) }
    }
}

private const val MAX_TILT = 18f
private const val REST_SECONDS = 4f
private const val EASE_SECONDS = 0.12f
