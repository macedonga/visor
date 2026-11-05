package com.macedonga.visor.display

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.macedonga.visor.core.design.Visor

/** The Display's 600 px square at phone scale; [content] gets phone pixels per glasses pixel. */
@Composable
fun DisplaySquare(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable BoxScope.(px: Float) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    BoxWithConstraints(
        modifier.aspectRatio(1f).clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(Color.Black).border(1.dp, Visor.colors.hairline, shape),
    ) {
        val px = with(LocalDensity.current) { maxWidth.toPx() } / DISPLAY_PX
        content(px)
    }
}

/** A size in the glasses' sp (one of their pixels: they draw at density 160), at the square's scale. */
@Composable
fun glassesSp(size: Float, px: Float): TextUnit = with(LocalDensity.current) { (size * px).toSp() }

const val DISPLAY_PX = 600f

/** [size] of the glasses' pixels at the square's scale, as a phone length. */
@Composable
fun glassesDp(size: Float, px: Float): Dp = with(LocalDensity.current) { (size * px).toDp() }
