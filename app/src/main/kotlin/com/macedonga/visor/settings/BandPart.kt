package com.macedonga.visor.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.band.BandSetup
import com.macedonga.visor.core.session.LinkState
import kotlinx.coroutines.launch

/** The Neural Band that goes with this Display: set it up, or its battery and the wrist it is on. */
@Composable
internal fun BandPart(model: SettingsViewModel) {
    val pair by model.connection.pair.collectAsStateWithLifecycle()
    val all by model.bands.all.collectAsStateWithLifecycle()
    val band by model.band.state.collectAsStateWithLifecycle()
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    // The Display reports its band; the phone's own reading is for when the Display is away.
    val device = glasses.device
    val battery = device?.bandBattery ?: band.battery
    val charging = device?.bandCharging ?: band.charging
    val setup by model.bandSetup.status.collectAsStateWithLifecycle()
    val running = setup is BandSetup.Status.Running
    val problem = (setup as? BandSetup.Status.Failed)?.let { failWords(it) }
    val scope = rememberCoroutineScope()
    var refused by remember { mutableStateOf(false) }
    @Composable
    fun SetUpRow(title: Int) = ValueRow(
        stringResource(title),
        if (running) stringResource(R.string.band_setting_up) else null,
        onClick = if (running) null else ({ model.bandSetup.start() }),
        busy = running,
        problem = problem,
    )
    if (all.none { it.glasses == pair }) {
        Rows({ SetUpRow(R.string.band_set_up) })
        return
    }
    Rows(
        { ValueRow(stringResource(R.string.band_battery), battery?.let { batteryWords(it, charging) }, onClick = null) },
        fitWords(device?.bandFit)?.let { fit ->
            row {
                ValueRow(
                    stringResource(R.string.band_fit), fit, onClick = null,
                    problem = if (device?.bandOrientation == "INCORRECT") stringResource(R.string.band_wrong_way) else null,
                )
            }
        },
        {
            PillsRow(
                stringResource(R.string.band_wrist),
                listOf(true to stringResource(R.string.band_left), false to stringResource(R.string.band_right)),
                band.leftHanded,
                onPick = { model.band.setLeftHanded(it) },
            )
        },
        {
            ValueRow(
                stringResource(R.string.band_buzz), null,
                onClick = { scope.launch { refused = !model.band.vibrate() } },
                problem = if (refused) stringResource(R.string.band_buzz_refused) else null,
            )
        },
        { SetUpRow(R.string.band_set_up_again) },
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

@Composable
internal fun wristWords(left: Boolean): String = stringResource(if (left) R.string.band_left else R.string.band_right)

@Composable
private fun failWords(failed: BandSetup.Status.Failed): String = when (failed.reason) {
    BandSetup.Reason.NO_GLASSES -> stringResource(R.string.band_fail_no_glasses)
    BandSetup.Reason.NOT_PAIRED -> stringResource(R.string.band_fail_not_paired)
    BandSetup.Reason.NO_ANSWER -> stringResource(R.string.band_fail_no_answer)
    BandSetup.Reason.WRONG_OWNER -> stringResource(R.string.band_fail_wrong_owner)
    BandSetup.Reason.FAILED -> stringResource(R.string.band_fail, failed.detail)
}
