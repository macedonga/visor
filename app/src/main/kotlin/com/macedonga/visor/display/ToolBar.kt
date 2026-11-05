package com.macedonga.visor.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.SnApp
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.settings.Spinner

/** Room at the end of a page for the bar over it. */
internal val BAR_ROOM = 120.dp

/** At the foot of the page, over a fade: a status line, then a spinner while [busy], else the [actions]. */
@Composable
internal fun ToolBar(line: String?, bad: Boolean, busy: Boolean, actions: @Composable () -> Unit) {
    val colors = Visor.colors
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to colors.canvas))
                .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = bottom + 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (line != null) {
                Text(line, style = Visor.type.label, color = if (bad) colors.signal else colors.textSecondary, modifier = Modifier.padding(bottom = 12.dp))
            }
            if (busy) Box(Modifier.height(52.dp), contentAlignment = Alignment.Center) { Spinner() } else actions()
        }
    }
}

@Composable
internal fun refusalWords(code: Long): String = stringResource(
    when (code) {
        SnApp.NOT_ENOUGH_BATTERY -> R.string.prompter_refused_battery
        SnApp.THERMAL_THROTTLING -> R.string.prompter_refused_heat
        SnApp.DENIED_BY_PEAK_POWER -> R.string.prompter_refused_power
        else -> R.string.prompter_refused
    },
)
