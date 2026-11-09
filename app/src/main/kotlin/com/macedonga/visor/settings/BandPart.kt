package com.macedonga.visor.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.band.BandErrand
import com.macedonga.visor.band.BandPair
import com.macedonga.visor.band.BandSetup
import com.macedonga.visor.band.ShareProblem
import com.macedonga.visor.band.ShareState
import com.macedonga.visor.core.session.LinkState
import kotlinx.coroutines.launch

/** The Neural Band as the Display holds it, and for a band set up here its wrist, buzz and firmware. */
@Composable
internal fun BandPart(model: SettingsViewModel) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val paired by model.bandPeers.bands.collectAsStateWithLifecycle()
    val pairing by model.bandPair.status.collectAsStateWithLifecycle()
    val setup by model.bandSetup.status.collectAsStateWithLifecycle()
    val all by model.bands.all.collectAsStateWithLifecycle()
    val pair by model.connection.pair.collectAsStateWithLifecycle()
    val own by model.band.state.collectAsStateWithLifecycle()
    val shared by model.band.shared.collectAsStateWithLifecycle()
    val share by model.band.shareState.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    LaunchedEffect(ready) { if (ready) model.bandPeers.refresh() }
    val device = glasses.device
    val band = paired?.firstOrNull()
    // Set up here: the phone owns the band and can reach it.
    val owned = all.any { it.glasses == pair }
    val running = pairing is BandPair.Status.Running
    val showPair = running || (ready && paired != null && (band == null || !band.connected))
    val setupRunning = setup is BandSetup.Status.Running && !running
    val showSetUp = setupRunning || (!running && !owned && ready && band?.connected == true)
    val battery = device?.bandBattery?.let { it to device.bandCharging } ?: own.battery?.takeIf { owned }?.let { it to own.charging }
    val scope = rememberCoroutineScope()
    var wristBusy by remember { mutableStateOf(false) }
    var wristOutcome by remember { mutableStateOf<BandErrand?>(null) }
    var buzzing by remember { mutableStateOf(false) }
    var buzzOutcome by remember { mutableStateOf<BandErrand?>(null) }
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
        if (showPair) row {
            val words = pairWords(pairing)
            ValueRow(
                stringResource(R.string.band_pair), words.takeIf { running },
                onClick = if (running) null else model.bandPair::start,
                busy = running,
                problem = words.takeIf { pairing is BandPair.Status.Failed },
            )
        } else null,
        if (showSetUp) row {
            RowFrame(
                stringResource(R.string.band_set_up),
                onClick = if (setupRunning) null else model.bandSetup::start,
                value = if (setupRunning) stringResource(R.string.band_pair_setting_up) else null,
                busy = setupRunning,
                problem = setupProblem(setup),
            )
        } else null,
        battery?.let { (percent, charging) -> row { ValueRow(stringResource(R.string.band_battery), batteryWords(percent, charging), onClick = null) } },
        fitWords(device?.bandFit)?.let { fit ->
            row {
                ValueRow(
                    stringResource(R.string.band_fit), fit, onClick = null,
                    problem = if (device?.bandOrientation == "INCORRECT") stringResource(R.string.band_wrong_way) else null,
                )
            }
        },
        if (owned) row {
            PillsRow(
                stringResource(R.string.band_wrist),
                listOf(true to stringResource(R.string.band_left), false to stringResource(R.string.band_right)),
                selected = own.leftHanded,
                onPick = { left ->
                    if (!wristBusy) scope.launch {
                        wristBusy = true
                        wristOutcome = null
                        wristOutcome = model.band.setWrist(left)
                        wristBusy = false
                    }
                },
                busy = wristBusy,
                problem = errandProblem(wristOutcome),
            )
        } else null,
        if (owned) row {
            ToggleRow(
                stringResource(R.string.band_share), shared, model.band::share,
                sub = stringResource(
                    when (share) {
                        ShareState.On -> R.string.band_share_on
                        ShareState.Connecting -> R.string.band_share_connecting
                        ShareState.Waiting -> R.string.band_share_waiting
                        ShareState.Off, is ShareState.Stopped -> R.string.band_share_sub
                    },
                ),
                busy = share == ShareState.Connecting,
                problem = (share as? ShareState.Stopped)?.let {
                    stringResource(
                        when (it.problem) {
                            ShareProblem.BAND_UNREACHABLE -> R.string.band_share_stopped_unreachable
                            ShareProblem.DISPLAY_SILENT -> R.string.band_share_stopped_silent
                            ShareProblem.KEPT_DROPPING -> R.string.band_share_stopped_dropping
                        },
                    )
                },
            )
        } else null,
        if (owned) row {
            RowFrame(
                stringResource(R.string.band_vibrate),
                onClick = {
                    if (!buzzing) scope.launch {
                        buzzing = true
                        buzzOutcome = null
                        buzzOutcome = model.band.vibrate()
                        buzzing = false
                    }
                },
                busy = buzzing,
                problem = errandProblem(buzzOutcome),
            )
        } else null,
        if (owned) row {
            ValueRow(
                stringResource(R.string.glasses_firmware), own.firmware?.toString() ?: stringResource(R.string.settings_unknown),
                onClick = null,
            )
        } else null,
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
internal fun errandProblem(outcome: BandErrand?): String? = when (outcome) {
    BandErrand.NO_ANSWER -> stringResource(R.string.band_pair_fail_no_answer)
    BandErrand.NOT_CHANGED -> stringResource(R.string.band_not_changed)
    BandErrand.REFUSED -> stringResource(R.string.band_refused)
    BandErrand.DONE, null -> null
}

@Composable
private fun setupProblem(status: BandSetup.Status): String? {
    val failed = status as? BandSetup.Status.Failed ?: return null
    return when (failed.reason) {
        BandSetup.Reason.NO_GLASSES -> stringResource(R.string.connect_first)
        BandSetup.Reason.NOT_PAIRED -> stringResource(R.string.band_pair_fail_no_band)
        BandSetup.Reason.NO_ANSWER -> stringResource(R.string.band_pair_fail_no_answer)
        BandSetup.Reason.REFUSED -> stringResource(R.string.band_pair_fail_band_refused)
        BandSetup.Reason.WRONG_OWNER -> stringResource(R.string.band_pair_fail_wrong_owner)
        BandSetup.Reason.BLUETOOTH_OFF -> stringResource(R.string.bluetooth_off)
        BandSetup.Reason.FAILED -> stringResource(R.string.band_failed)
    }
}

@Composable
fun pairWords(status: BandPair.Status): String? = when (status) {
    is BandPair.Status.Running -> stringResource(
        when (status.step) {
            BandPair.Step.SETUP -> R.string.band_pair_setting_up
            BandPair.Step.BAND -> R.string.band_pair_reaching
            BandPair.Step.PAIRING -> R.string.band_pair_pairing
            BandPair.Step.CONNECTING -> R.string.band_pair_connecting
        },
    )
    is BandPair.Status.Failed -> when (status.reason) {
        BandPair.Reason.NOT_CONNECTED -> stringResource(R.string.connect_first)
        BandPair.Reason.NO_BAND -> stringResource(R.string.band_pair_fail_no_band)
        BandPair.Reason.NO_ANSWER -> stringResource(R.string.band_pair_fail_no_answer)
        BandPair.Reason.BAND_REFUSED -> stringResource(R.string.band_pair_fail_band_refused)
        BandPair.Reason.WRONG_OWNER -> stringResource(R.string.band_pair_fail_wrong_owner)
        BandPair.Reason.UNSUPPORTED -> stringResource(R.string.band_pair_fail_unsupported)
        BandPair.Reason.REFUSED -> stringResource(R.string.band_refused)
        BandPair.Reason.FAILED -> stringResource(R.string.band_failed)
    }
    else -> null
}
