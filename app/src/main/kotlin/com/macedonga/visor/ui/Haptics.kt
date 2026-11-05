package com.macedonga.visor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The app's touch vocabulary, built from the vibrator's primitives where it has them. */
class Haptics(context: Context) {
    private val vibrator: Vibrator = (context.getSystemService(VibratorManager::class.java)).defaultVibrator

    /** The glasses' light comes on: a swell and a click. */
    fun connected() = play(
        listOf(Composition.PRIMITIVE_QUICK_RISE to 0.5f, Composition.PRIMITIVE_CLICK to 0.7f),
        VibrationEffect.EFFECT_HEAVY_CLICK,
    )

    /** A photo on the glasses. */
    fun shutter() = play(listOf(Composition.PRIMITIVE_CLICK to 1f), VibrationEffect.EFFECT_CLICK)

    /** Something finished: an import, a send. */
    fun done() = play(listOf(Composition.PRIMITIVE_THUD to 0.6f, Composition.PRIMITIVE_TICK to 0.4f), VibrationEffect.EFFECT_DOUBLE_CLICK)

    /** A detent while turning or dragging something; [touch] follows the phone's Touch feedback switch. */
    fun tick(strength: Float = 0.5f, touch: Boolean = true) =
        play(listOf(Composition.PRIMITIVE_LOW_TICK to strength), VibrationEffect.EFFECT_TICK, touch)

    /** A switch or a choice. */
    fun toggle() = play(listOf(Composition.PRIMITIVE_CLICK to 0.6f), VibrationEffect.EFFECT_CLICK, touch = true)

    /** Something went wrong. */
    fun error() = play(listOf(Composition.PRIMITIVE_TICK to 1f, Composition.PRIMITIVE_TICK to 1f), VibrationEffect.EFFECT_DOUBLE_CLICK)

    @SuppressLint("WrongConstant") // every caller passes Composition.PRIMITIVE_* and VibrationEffect.EFFECT_*
    private fun play(primitives: List<Pair<Int, Float>>, fallback: Int, touch: Boolean = false) {
        if (!vibrator.hasVibrator()) return
        val effect = if (vibrator.areAllPrimitivesSupported(*primitives.map { it.first }.toIntArray())) {
            VibrationEffect.startComposition().apply { primitives.forEach { (p, s) -> addPrimitive(p, s) } }.compose()
        } else {
            VibrationEffect.createPredefined(fallback)
        }
        when {
            !touch -> vibrator.vibrate(effect)
            Build.VERSION.SDK_INT >= 33 -> vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            else -> {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            }
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val context = LocalContext.current.applicationContext
    return remember { Haptics(context) }
}
