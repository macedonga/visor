package com.macedonga.visor.stage

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale

/** The glasses' light on an unseen floor, darker where the frame sits; [center], [radius] and [contact] in canvas widths. */
@Composable
fun LightPool(light: LightFrame, center: Offset, radius: Size, contact: Size, modifier: Modifier = Modifier) {
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) PoolShader() else null }
    Canvas(modifier) {
        light.tick.longValue
        if (Build.VERSION.SDK_INT >= 33 && shader != null) shader.draw(this, light, center, radius, contact) else fallback(light, center, radius)
    }
}

@RequiresApi(33)
private class PoolShader {
    private val shader = RuntimeShader(SOURCE)
    private val brush = ShaderBrush(shader)

    fun draw(scope: DrawScope, light: LightFrame, center: Offset, radius: Size, contact: Size) = with(scope) {
        val w = size.width
        val cx = center.x * w
        val cy = center.y * w
        shader.setFloatUniform("center", cx, cy)
        shader.setFloatUniform("radius", radius.width * w * light.spread, radius.height * w * light.spread)
        shader.setFloatUniform("contact", contact.width * w, contact.height * w)
        // The drift's offsets wrap where the noise repeats, so they stay small however long the light has run.
        shader.setFloatUniform("shift", ((light.time * 0.07) % PERIOD).toFloat(), ((light.time * 0.05) % PERIOD).toFloat())
        shader.setFloatUniform("level", light.poolLevel)
        shader.setFloatUniform("warp", light.warp)
        shader.setFloatUniform("core", light.flashedCore())
        shader.setFloatUniform("rim", light.rim)
        drawRect(brush)
    }
}

/** Before Android 13: the same pool as one radial gradient, without the drift. */
private fun DrawScope.fallback(light: LightFrame, center: Offset, radius: Size) {
    val w = size.width
    val c = Offset(center.x * w, center.y * w)
    val core = light.flashedCore()
    val color = Color(core[0], core[1], core[2])
    val r = radius.width * w * light.spread
    scale(1f, radius.height / radius.width, pivot = c) {
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = light.poolLevel.coerceIn(0f, 1f)), Color.Transparent), c, r),
            radius = r,
            center = c,
        )
    }
}

private const val PERIOD = 64.0

private val SOURCE = """
const float PERIOD = $PERIOD;
uniform float2 center;
uniform float2 radius;
uniform float2 contact;
uniform float2 shift;
uniform float level;
uniform float warp;
uniform float3 core;
uniform float3 rim;

// Sine-free hash: no seams between noise cells.
float hash(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Value noise that repeats every PERIOD cells, so the drift can wrap its offset there without a jump.
float corner(float2 i) { return hash(mod(i, PERIOD)); }

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = p - i;
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(corner(i), corner(i + float2(1.0, 0.0)), u.x),
               mix(corner(i + float2(0.0, 1.0)), corner(i + float2(1.0, 1.0)), u.x), u.y);
}

half4 main(float2 p) {
    float2 q = (p - center) / radius;
    float2 drift = float2(noise(q * 1.4 + float2(shift.x, 0.0)),
                          noise(q * 1.4 + float2(4.1, shift.y))) - 0.5;
    q += drift * warp;
    float d2 = dot(q, q);
    // Light on a floor has no plateau and no edge: it falls off continuously into a long faint tail.
    float pool = pow(1.0 + d2 / 0.5, -1.7);
    float a = level * 0.5 * pool;
    float2 c = (p - center - float2(0.0, -contact.y * 0.6)) / contact;
    a *= 1.0 - 0.35 * exp(-dot(c, c) * 1.1);
    float3 col = mix(rim, core, clamp(pool * 1.6, 0.0, 1.0));
    a = clamp(a, 0.0, 1.0);
    // About one step of triangular dither: without it the faint tail steps down to black in rings.
    float n = (hash(p) + hash(p + 17.31) - 1.0) / 255.0;
    return half4(half3(col * a + n), half(a + n));
}
"""
