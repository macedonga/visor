package com.macedonga.visor.settings

import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val ready = (status as? ClassicBond.Status.Probed)?.verdict == ClassicBond.Verdict.READY
    val undoable by model.bond.undoable.collectAsStateWithLifecycle()
    ValueRow(
        stringResource(R.string.finish_hfp_title), classicWords(status),
        onClick = if (running) null else ({ if (ready) { asking = ClassicBond.Action.REBOND } else { model.bond.start(ClassicBond.Action.PROBE) } }),
        busy = running,
    )
    if (undoable && !running) {
        Hairline()
        ValueRow(stringResource(R.string.finish_hfp_undo), null, onClick = { asking = ClassicBond.Action.UNDO })
    }
    val action = asking ?: return
    AlertDialog(
        onDismissRequest = { asking = null },
        title = { Text(stringResource(if (action == ClassicBond.Action.UNDO) R.string.finish_hfp_undo_title else R.string.finish_hfp_confirm_title)) },
        text = { Text(stringResource(R.string.finish_hfp_confirm_text)) },
        confirmButton = {
            TextButton(onClick = {
                asking = null
                model.bond.start(action)
            }) { Text(stringResource(R.string.finish_hfp_confirm)) }
        },
        dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.language_cancel)) } },
    )
}

@Composable
private fun classicWords(status: ClassicBond.Status): String? = when (status) {
    ClassicBond.Status.Idle -> null
    is ClassicBond.Status.Running -> if (status.repairing) stringResource(R.string.finish_hfp_running) else null
    is ClassicBond.Status.Probed -> stringResource(
        when (status.verdict) {
            ClassicBond.Verdict.CLASSIC_PRESENT -> R.string.finish_hfp_present
            ClassicBond.Verdict.CALLS_CONNECTED -> R.string.finish_hfp_calls_on
            ClassicBond.Verdict.UNREACHABLE -> R.string.finish_hfp_unreachable
            ClassicBond.Verdict.READY -> R.string.finish_hfp_ready
        },
    )
    is ClassicBond.Status.Done -> stringResource(if (status.calls) R.string.finish_hfp_calls_on else R.string.finish_hfp_calls_off)
    is ClassicBond.Status.Failed -> when (status.reason) {
        ClassicBond.Reason.REFUSED -> stringResource(R.string.finish_hfp_fail_refused, status.detail)
        ClassicBond.Reason.FAILED -> stringResource(R.string.finish_hfp_fail, status.detail)
        else -> stringResource(
            when (status.reason) {
                ClassicBond.Reason.NOT_CONNECTED -> R.string.finish_hfp_fail_connect
                ClassicBond.Reason.BLUETOOTH_OFF -> R.string.finish_hfp_fail_off
                ClassicBond.Reason.BUSY -> R.string.finish_hfp_fail_busy
                ClassicBond.Reason.NO_COMPANION -> R.string.finish_hfp_fail_companion
                ClassicBond.Reason.UNREACHABLE -> R.string.finish_hfp_unreachable
                ClassicBond.Reason.UNPAIR_FAILED -> R.string.finish_hfp_fail_unpair
                ClassicBond.Reason.RESTORED -> R.string.finish_hfp_fail_restored
                ClassicBond.Reason.LOST -> R.string.finish_hfp_fail_lost
                else -> R.string.finish_hfp_fail_unsupported
            },
        )
    }
}
