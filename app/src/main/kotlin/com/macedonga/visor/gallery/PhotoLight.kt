package com.macedonga.visor.gallery

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.util.WeakHashMap
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo's colours as a soft pool of light; [center] and [radius] are fractions of the size. */
@Composable
fun PhotoLight(
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
    level: Float = 0.6f,
    center: Offset = Offset(0.5f, 0.5f),
    radius: Float = 0.75f,
) {
    val glow by produceState<ImageBitmap?>(image?.let { glows[it] }, image) {
        value = image?.let { glows[it] ?: withContext(Dispatchers.Default) { glowOf(it) }.also { g -> glows[it] = g } }
    }
    val breath = rememberInfiniteTransition(label = "light")
    val swell by breath.animateFloat(1f, 1.07f, infiniteRepeatable(tween(BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "swell")
    Crossfade(glow, modifier, animationSpec = tween(CROSSFADE_MS), label = "glow") { field ->
        if (field == null) return@Crossfade
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = level
                    renderEffect = BlurEffect(size.minDimension * 0.16f, size.minDimension * 0.16f, TileMode.Clamp)
                },
        ) {
            val w = size.width * swell * 1.15f
            val h = size.height * swell * 1.15f
            drawImage(
                field,
                dstOffset = IntOffset((center.x * size.width - w / 2).toInt(), (center.y * size.height - h / 2).toInt()),
                dstSize = IntSize(w.toInt(), h.toInt()),
                filterQuality = FilterQuality.Low,
            )
            // The pool: full at the heart, nothing at the rim.
            val c = Offset(center.x * size.width, center.y * size.height)
            drawRect(
                Brush.radialGradient(
                    0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.8f), 0.75f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent,
                    center = c, radius = max(size.width, size.height) * radius,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/** Glows already worked out, so a picture seen before is lit from its first frame; main thread only. */
private val glows = WeakHashMap<ImageBitmap, ImageBitmap>()

/** The picture at 6×8 pixels: only its colours and where they sit are left. */
private fun glowOf(image: ImageBitmap): ImageBitmap {
    val source = image.asAndroidBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    val portrait = source.height >= source.width
    return Bitmap.createScaledBitmap(source, if (portrait) 6 else 8, if (portrait) 8 else 6, true).asImageBitmap()
}

private const val BREATH_MS = 4500
private const val CROSSFADE_MS = 700
