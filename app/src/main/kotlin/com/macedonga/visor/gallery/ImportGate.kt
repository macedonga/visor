package com.macedonga.visor.gallery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.macedonga.visor.R
import com.macedonga.visor.core.media.ImportProblem
import com.macedonga.visor.core.media.wifiDirectAllowed
import com.macedonga.visor.core.media.wifiDirectPermissions
import com.macedonga.visor.core.media.wifiIsOn
import com.macedonga.visor.settings.openAppPage

/** Asks for the import permissions and Wi-Fi, then runs the import. */
@Composable
fun rememberImportGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val activity = context as? Activity
    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterWifi by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Still off when the panel closes, the import itself says so.
    val wifiPanel = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val go = afterWifi
        afterWifi = null
        go?.invoke()
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        val go = waiting
        waiting = null
        val refusedForGood = answers.any { (name, granted) -> !granted && name in wifiDirectPermissions && activity?.shouldShowRequestPermissionRationale(name) == false }
        when {
            !wifiDirectAllowed(context) && refusedForGood -> openAppPage(context)
            !wifiIsOn(context) -> {
                afterWifi = go
                runCatching { wifiPanel.launch(Intent(Settings.Panel.ACTION_WIFI)) }
            }
            else -> go?.invoke()
        }
    }
    return { go ->
        waiting = go
        ask.launch(importPermissions())
    }
}

/** Wi-Fi Direct's own, the local network the session runs on, and Bluetooth, which asks the glasses to join. */
private fun importPermissions(): Array<String> = buildSet {
    addAll(wifiDirectPermissions)
    if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    add(Manifest.permission.BLUETOOTH_SCAN)
}.toTypedArray()

/** Why the last import fell short, in the phone's language. */
@Composable
fun importProblemText(problem: ImportProblem): String = when (problem.kind) {
    ImportProblem.Kind.WIFI_STUCK -> stringResource(R.string.import_problem_stuck)
    ImportProblem.Kind.WIFI_FAILED -> stringResource(R.string.import_problem_wifi)
    ImportProblem.Kind.GLASSES_DID_NOT_JOIN -> stringResource(R.string.import_problem_join)
    ImportProblem.Kind.NO_WIFI_SESSION -> stringResource(R.string.import_problem_session)
    ImportProblem.Kind.WIFI_OFF -> stringResource(R.string.import_problem_wifi_off)
    ImportProblem.Kind.NOT_ALLOWED -> stringResource(R.string.import_problem_not_allowed)
    ImportProblem.Kind.FAILED -> stringResource(R.string.import_problem_failed)
    ImportProblem.Kind.CUT_OFF -> stringResource(R.string.import_problem_cut_off)
    ImportProblem.Kind.DAMAGED -> stringResource(R.string.import_problem_damaged)
    ImportProblem.Kind.NOT_SAVED -> stringResource(R.string.import_problem_not_saved)
}
