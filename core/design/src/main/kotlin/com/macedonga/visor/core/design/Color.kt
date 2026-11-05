package com.macedonga.visor.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Black, white and greys; [signal] marks state only (recording, live, critical battery, errors), never a button. */
@Immutable
data class VisorColors(
    val canvas: Color = Color(0xFF000000),
    val surface: Color = Color(0xFF161617),
    val raised: Color = Color(0xFF1D1D1F),
    val control: Color = Color(0xFF333336),
    val hairline: Color = Color(0x14FFFFFF),
    val textPrimary: Color = Color(0xFFFFFFFF),
    val textSecondary: Color = Color(0xFF98989D),
    val textTertiary: Color = Color(0xFF86868B),
    val signal: Color = Color(0xFFFF5F34),
    /** The glasses' light, from the colour of a searching lamp to the cold rim of glasses far away. */
    val lightWarm: Color = Color(0xFFFFD2A1),
    val lightNeutral: Color = Color(0xFFFFF1E2),
    val lightCold: Color = Color(0xFFAFC2FF),
)

val LocalVisorColors = staticCompositionLocalOf { VisorColors() }
