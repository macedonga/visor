package com.macedonga.visor.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.session.LinkState

/** The Neural Band as the Display holds it: whether it is paired and connected, its battery and its fit. */
@Composable
internal fun BandPart(model: SettingsViewModel) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val paired by model.bandPeers.bands.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    LaunchedEffect(ready) { if (ready) model.bandPeers.refresh() }
    val device = glasses.device
    val band = paired?.firstOrNull()
    Rows(
        {
            ValueRow(
                stringResource(R.string.band_glasses),
                when {
                    !ready || paired == null -> stringResource(R.string.settings_unknown)
                    band == null -> stringResource(R.string.band_not_paired)
                    band.connected -> stringResource(R.string.band_paired_connected)
                    else -> stringResource(R.string.band_paired_away)
                },
                onClick = null,
            )
        },
        device?.bandBattery?.let { b -> row { ValueRow(stringResource(R.string.band_battery), batteryWords(b, device.bandCharging), onClick = null) } },
        fitWords(device?.bandFit)?.let { fit ->
            row {
                ValueRow(
                    stringResource(R.string.band_fit), fit, onClick = null,
                    problem = if (device?.bandOrientation == "INCORRECT") stringResource(R.string.band_wrong_way) else null,
                )
            }
        },
    )
}

@Composable
internal fun batteryWords(percent: Int, charging: Boolean?): String =
    stringResource(if (charging == true) R.string.glasses_battery_charging else R.string.band_percent, percent)

@Composable
private fun fitWords(fit: String?): String? = when (fit) {
    "LOOSE" -> stringResource(R.string.band_fit_loose)
    "TIGHT" -> stringResource(R.string.band_fit_tight)
    else -> null
}
