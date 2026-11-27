package com.macedonga.visor.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.media.LiveOverlay
import com.macedonga.visor.core.media.LiveWifi
import com.macedonga.visor.core.media.StreamQuality
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.Footnote
import com.macedonga.visor.settings.Pills
import com.macedonga.visor.settings.PillsRow
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.SectionLabel
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.settings.SheetField
import com.macedonga.visor.settings.SheetTitle
import com.macedonga.visor.settings.ToggleRow
import com.macedonga.visor.settings.ValueRow
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.row

/** The Live view's settings. */
@Composable
fun LiveSettingsScreen(model: LiveViewModel = hiltViewModel()) {
    val settings by model.settings.settings.collectAsStateWithLifecycle()
    var networkSheet by remember { mutableStateOf(false) }

    SettingsPage(stringResource(R.string.live_settings_title), sheet = {
        if (networkSheet) {
            val phone = remember { model.phoneNetwork().orEmpty() }
            var saved by remember { mutableStateOf<List<String>>(emptyList()) }
            LaunchedEffect(Unit) { saved = model.savedNetworks() }
            var name by remember { mutableStateOf(settings.wifiSsid.ifEmpty { phone }) }
            var password by remember { mutableStateOf(settings.wifiPassword) }
            var open by remember { mutableStateOf(settings.wifiOpen) }
            val suggestions = remember(phone, saved) { (listOfNotNull(phone.takeIf { it.isNotEmpty() }) + saved).distinct() }
            VisorSheet({ networkSheet = false }) {
                SheetTitle(stringResource(R.string.live_wifi_network))
                if (suggestions.isNotEmpty()) {
                    Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                        Pills(suggestions.map { it to it }, name.trim().takeIf { it in suggestions }) { picked ->
                            name = picked
                            if (picked in saved && picked != settings.wifiSsid) {
                                password = ""
                                open = false
                            }
                        }
                    }
                }
                SheetField(name, { name = it }, hint = stringResource(R.string.wifi_network_name), focus = name.isEmpty())
                Box(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 10.dp)) {
                    Pills(listOf(false to stringResource(R.string.wifi_password), true to stringResource(R.string.wifi_open)), open) { open = it }
                }
                if (!open) {
                    SheetField(
                        password, { password = it },
                        hint = stringResource(R.string.live_wifi_password_hint),
                        secret = true,
                        focus = name.isNotEmpty() && password.isEmpty(),
                    )
                }
                val trimmed = name.trim()
                val valid = trimmed.isEmpty() || open || password.isEmpty() || password.length >= 8
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionPill(stringResource(R.string.settings_save), {
                        model.update {
                            if (trimmed.isEmpty()) it.copy(wifiSsid = "", wifiPassword = "", wifiOpen = false)
                            else it.copy(wifiSsid = trimmed, wifiPassword = if (open) "" else password, wifiOpen = open)
                        }
                        networkSheet = false
                    }, enabled = valid)
                }
            }
        }
    }) {
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
                )
            }
        }
        item(key = "wifi") {
            Column {
                SectionLabel(stringResource(R.string.live_settings_wifi))
                Rows(
                    {
                        PillsRow(
                            stringResource(R.string.live_wifi_connection),
                            listOf(
                                LiveWifi.DIRECT to stringResource(R.string.live_wifi_direct),
                                LiveWifi.GLASSES to stringResource(R.string.live_wifi_glasses),
                                LiveWifi.NETWORK to stringResource(R.string.live_wifi_network),
                            ),
                            settings.wifi,
                            { w -> model.update { it.copy(wifi = w) } },
                        )
                    },
                    if (settings.wifi == LiveWifi.NETWORK) row {
                        ValueRow(
                            stringResource(R.string.wifi_network_name),
                            settings.wifiSsid.ifEmpty { stringResource(R.string.live_wifi_network_auto) },
                            onClick = { networkSheet = true },
                        )
                    } else null,
                    if (settings.wifi == LiveWifi.NETWORK && settings.wifiSsid.isNotEmpty()) row {
                        val value = when {
                            settings.wifiOpen -> stringResource(R.string.wifi_open)
                            settings.wifiPassword.isNotEmpty() -> "•".repeat(minOf(settings.wifiPassword.length, 12))
                            else -> stringResource(R.string.live_wifi_password_saved)
                        }
                        ValueRow(
                            stringResource(R.string.wifi_password),
                            value,
                            onClick = { networkSheet = true },
                        )
                    } else null,
                )
                if (settings.wifi == LiveWifi.NETWORK) Footnote(stringResource(R.string.live_wifi_network_note))
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
