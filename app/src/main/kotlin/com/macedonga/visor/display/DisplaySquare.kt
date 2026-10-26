package com.macedonga.visor.display

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.macedonga.visor.core.design.Visor

/**
 * The Display's screen as the phone shows it: a black square at its true proportions, the 600 px an app draws on
 * across the square. [content] gets how many phone pixels one of theirs is.
 */
@Composable
fun DisplaySquare(modifier: Modifier = Modifier, radius: androidx.compose.ui.unit.Dp = 14.dp, content: @Composable BoxScope.(px: Float) -> Unit) {
    val shape = RoundedCornerShape(radius)
    BoxWithConstraints(modifier.aspectRatio(1f).clip(shape).background(Color.Black).border(1.dp, Visor.colors.hairline, shape)) {
        val px = with(LocalDensity.current) { maxWidth.toPx() } / DISPLAY_PX
        Box(Modifier.fillMaxSize()) { content(px) }
    }
}

/** A size in the glasses' sp (one of their pixels: they draw at density 160), at the square's scale. */
@Composable
fun glassesSp(size: Double, px: Float): TextUnit = with(LocalDensity.current) { (size.toFloat() * px).toSp() }

const val DISPLAY_PX = 600f

/** [size] of the glasses' pixels at the square's scale, as a phone length. */
@Composable
fun glassesDp(size: Float, px: Float): androidx.compose.ui.unit.Dp = with(LocalDensity.current) { (size * px).toDp() }
