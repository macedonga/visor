package com.macedonga.visor.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.media.LiveOverlay
import com.macedonga.visor.core.media.StreamQuality
import com.macedonga.visor.settings.PillsRow
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SectionLabel
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.settings.ToggleRow
import com.macedonga.visor.settings.row

/** The Live view's settings. */
@Composable
fun LiveSettingsScreen(model: LiveViewModel = hiltViewModel()) {
    val settings by model.settings.settings.collectAsStateWithLifecycle()

    SettingsPage(stringResource(R.string.live_settings_title)) {
        item(key = "quality") {
            Column {
                SectionLabel(stringResource(R.string.live_settings_quality))
                Rows(
                    { ToggleRow(stringResource(R.string.live_auto), !settings.manual, { auto -> model.update { it.copy(manual = !auto) } }) },
                    if (settings.manual) row {
                        PillsRow(
                            stringResource(R.string.live_settings_resolution), StreamQuality.entries.filter { it != StreamQuality.LIVE_AI }.map { it to it.label },
                            settings.quality, { q -> model.update { it.copy(quality = q) } },
                        )
                    } else null,
                    if (settings.manual) row {
                        PillsRow(
                            stringResource(R.string.live_settings_bitrate),
                            listOf(600_000, 1_000_000, 1_500_000, 2_500_000, 4_000_000, 6_000_000, 8_000_000).map { it to "%.1f Mbit/s".format(it / 1_000_000f) },
                            settings.bitrate, { b -> model.update { it.copy(bitrate = b) } },
                        )
                    } else null,
                    if (settings.manual) row {
                        PillsRow(stringResource(R.string.live_settings_frame_rate), listOf(24, 30).map { it to "$it fps" }, settings.frameRate, { f -> model.update { it.copy(frameRate = f) } })
                    } else null,
                )
            }
        }
        item(key = "replay") {
            Column(Modifier.padding(top = 28.dp)) {
                Rows(
                    {
                        PillsRow(
                            stringResource(R.string.live_settings_replay), listOf(30, 60, 120, 180).map { it to spanWords(it.toLong()) }, settings.replaySeconds,
                            { r -> model.update { it.copy(replaySeconds = r) } },
                        )
                    },
                    { ToggleRow(stringResource(R.string.live_listen_long), settings.listen, { on -> model.update { it.copy(listen = on) } }) },
                    { ToggleRow(stringResource(R.string.live_use_glasses_host), settings.glassesHost, { on -> model.update { it.copy(glassesHost = on) } }) },
                )
            }
        }
        item(key = "picture") {
            Column {
                SectionLabel(stringResource(R.string.live_settings_on_picture))
                LiveOverlayRows(model, settings.overlays)
            }
        }
    }
}

/** What sits on the picture, each on or off: here and in the Live view's sheet. */
@Composable
internal fun LiveOverlayRows(model: LiveViewModel, overlays: Set<LiveOverlay>) {
    val rows = listOf(
        LiveOverlay.READOUTS to R.string.live_overlay_readouts,
        LiveOverlay.HEAT to R.string.live_overlay_heat,
        LiveOverlay.GRID to R.string.live_overlay_grid,
    )
    Rows(rows) { (overlay, name) -> ToggleRow(stringResource(name), overlay in overlays, { model.setOverlay(overlay, it) }) }
}
