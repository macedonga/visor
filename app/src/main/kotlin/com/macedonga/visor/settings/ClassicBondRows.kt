package com.macedonga.visor.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.connection.ClassicBond
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ClassicBondModel @Inject constructor(val bond: ClassicBond) : ViewModel()

/** Bonding over BR/EDR alone became public in Android 17. */
val classicBondOffered: Boolean get() = Build.VERSION.SDK_INT >= 37

/** A Display's classic bond for calls: a tap checks, and once a re-pair can help, a tap asks before it runs. */
@Composable
internal fun ClassicBondRows(model: ClassicBondModel = hiltViewModel()) {
    val status by model.bond.status.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf<ClassicBond.Action?>(null) }
    val running = status is ClassicBond.Status.Running
    val canRepair = (status as? ClassicBond.Status.Probed)?.verdict == ClassicBond.Verdict.READY
    val undoable by model.bond.undoable.collectAsStateWithLifecycle()
    val words = classicWords(status)
    val failed = status is ClassicBond.Status.Failed
    ValueRow(
        stringResource(R.string.classic_bond_title), words.takeIf { !failed },
        onClick = if (running) null else ({ if (canRepair) { asking = ClassicBond.Action.REBOND } else { model.bond.start(ClassicBond.Action.PROBE) } }),
        busy = running,
        problem = words.takeIf { failed },
    )
    if (undoable && !running) {
        Hairline()
        ValueRow(stringResource(R.string.classic_bond_undo), null, onClick = { asking = ClassicBond.Action.UNDO })
    }
    val action = asking ?: return
    ConfirmSheet(
        stringResource(if (action == ClassicBond.Action.UNDO) R.string.classic_bond_undo_title else R.string.classic_bond_confirm_title),
        stringResource(R.string.classic_bond_confirm_text),
        stringResource(R.string.classic_bond_confirm),
        onConfirm = { model.bond.start(action) },
        onDismiss = { asking = null },
    )
}

@Composable
private fun classicWords(status: ClassicBond.Status): String? = when (status) {
    ClassicBond.Status.Idle -> null
    is ClassicBond.Status.Running -> if (status.repairing) stringResource(R.string.classic_bond_running) else null
    is ClassicBond.Status.Probed -> stringResource(
        when (status.verdict) {
            ClassicBond.Verdict.CLASSIC_PRESENT -> R.string.classic_bond_present
            ClassicBond.Verdict.CALLS_CONNECTED -> R.string.classic_bond_calls_on
            ClassicBond.Verdict.UNREACHABLE -> R.string.classic_bond_unreachable
            ClassicBond.Verdict.READY -> R.string.classic_bond_ready
        },
    )
    is ClassicBond.Status.Done -> stringResource(if (status.calls) R.string.classic_bond_calls_on else R.string.classic_bond_calls_off)
    is ClassicBond.Status.Failed -> when (status.reason) {
        ClassicBond.Reason.REFUSED -> stringResource(R.string.classic_bond_fail_refused, status.detail)
        ClassicBond.Reason.FAILED -> stringResource(R.string.classic_bond_fail, status.detail)
        ClassicBond.Reason.NOT_CONNECTED -> stringResource(R.string.connect_first)
        ClassicBond.Reason.BLUETOOTH_OFF -> stringResource(R.string.bluetooth_off)
        ClassicBond.Reason.BUSY -> stringResource(R.string.classic_bond_fail_busy)
        ClassicBond.Reason.NO_COMPANION -> stringResource(R.string.classic_bond_fail_companion)
        ClassicBond.Reason.UNREACHABLE -> stringResource(R.string.classic_bond_unreachable)
        ClassicBond.Reason.UNPAIR_FAILED -> stringResource(R.string.classic_bond_fail_unpair)
        ClassicBond.Reason.RESTORED -> stringResource(R.string.classic_bond_fail_restored)
        ClassicBond.Reason.LOST -> stringResource(R.string.classic_bond_fail_lost)
        ClassicBond.Reason.UNSUPPORTED, ClassicBond.Reason.NO_ADDRESS -> stringResource(R.string.classic_bond_fail_unsupported)
    }
}
