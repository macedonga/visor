package com.macedonga.visor.device

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SectionLabel
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.settings.ValueRow
import com.macedonga.visor.settings.row
import com.macedonga.visor.ui.temperatureWords
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DeviceViewModel @Inject constructor(val connection: GlassesConnection) : ViewModel()

/** The link and the glasses as the firmware reports them, raw: what the Glasses page does not show. */
@Composable
fun DeviceScreen(model: DeviceViewModel = hiltViewModel()) {
    val state by model.connection.state.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.settings_diagnostics), light = 0.3f) {
        item(key = "link") {
            Rows(
                { ValueRow(stringResource(R.string.diag_stage), state.link.name.lowercase(), onClick = null) },
                state.reason.takeIf { it.isNotBlank() }?.let { r -> row { ValueRow(stringResource(R.string.diag_reason), r, onClick = null) } },
                state.failure?.let { f -> row { ValueRow(stringResource(R.string.diag_failure), f.name.lowercase(), onClick = null) } },
                { ValueRow(stringResource(R.string.diag_verdict), state.diagnosis?.name?.lowercase() ?: "–", onClick = null) },
            )
        }
        val device = state.device
        val info = state.info
        if (info != null || device?.battery != null || device?.worn != null || device?.hinge != null) {
            item(key = "device") {
                Column {
                    SectionLabel(stringResource(R.string.glasses_title))
                    Rows(
                        device?.battery?.let { b -> row { ValueRow(stringResource(R.string.diag_battery_temp), temperatureWords(b.temperatureC, decimals = 1), onClick = null) } },
                        device?.worn?.let { w -> row { ValueRow(stringResource(R.string.diag_worn), w.toString(), onClick = null) } },
                        device?.hinge?.let { h -> row { ValueRow(stringResource(R.string.diag_hinge), h.lowercase(), onClick = null) } },
                        info?.model?.takeIf { it.isNotEmpty() }?.let { m -> row { ValueRow(stringResource(R.string.details_model), m, onClick = null) } },
                        info?.build?.let { b -> row { ValueRow(stringResource(R.string.diag_build), b, onClick = null) } },
                    )
                }
            }
        }
    }
}
