package com.macedonga.visor.stage

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.unit.dp

/** The stage's width to height, the same wherever the pair stands. */
const val STAGE_ASPECT = 1.02f

/** The pool's centre, in widths from the top: where the floor under the pair projects, a touch forward. */
const val POOL_Y = 0.575f

/** The strip the home's stage folds down to under the status bar, and how small the pair is in it. */
val HOME_STRIP = 200.dp
const val HOME_STRIP_SCALE = 0.75f

/** The rounded top corners of a sheet that slides up under a stage. */
val SHEET_RADIUS = 28.dp

/** A sheet whose rounded top sits at [top]. */
fun sheetShape(top: Float, radius: Float) = GenericShape { size, _ ->
    addRoundRect(
        RoundRect(0f, top, size.width, size.height + radius, topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius)),
    )
}
