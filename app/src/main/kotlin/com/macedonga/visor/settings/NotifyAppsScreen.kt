package com.macedonga.visor.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A phone app that can reach the glasses, with its name and icon. */
private class PhoneApp(val pkg: String, val name: String, val icon: ImageBitmap?)

/** Which phone apps reach the glasses: recent senders first, the rest behind "Show every app". */
@Composable
fun NotifyAppsScreen(model: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val muted by model.apps.muted.collectAsStateWithLifecycle()
    val seen by model.apps.seen.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<PhoneApp>?>(null) }
    var every by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchable(context, model.apps.seen.value.keys) } }

    SettingsPage(stringResource(R.string.notify_apps_title), light = 0.35f) {
        val all = apps
        if (all == null) {
            item(key = "loading") {
                Row(Modifier.padding(horizontal = SIDE, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner()
                    Text(stringResource(R.string.notify_apps_loading), style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(start = 12.dp))
                }
            }
            return@SettingsPage
        }
        val byPkg = all.associateBy { it.pkg }
        val recent = seen.entries.sortedByDescending { it.value }.mapNotNull { byPkg[it.key] }
        val rest = all.filter { it.pkg !in seen }
        item(key = "actions") {
            Row(Modifier.padding(horizontal = SIDE).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionPill(stringResource(R.string.notify_apps_all_on), { model.apps.setAll(all.map { it.pkg }, true) }, quiet = true, enabled = all.any { it.pkg in muted })
                ActionPill(stringResource(R.string.notify_apps_all_off), { model.apps.setAll(all.map { it.pkg }, false) }, quiet = true, enabled = all.any { it.pkg !in muted })
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent") {
                SectionLabel(stringResource(R.string.notify_apps_recent))
                Rows(*recent.map { app -> @Composable { AppRow(app, app.pkg !in muted) { on -> model.apps.set(app.pkg, on) } } }.toTypedArray())
            }
        }
        if (!every && recent.isNotEmpty()) {
            item(key = "every") {
                Box(Modifier.padding(top = 12.dp)) {
                    Rows({ ValueRow(stringResource(R.string.notify_apps_every), pluralStringResource(R.plurals.notify_apps_count, rest.size, rest.size), onClick = { every = true }) })
                }
            }
        } else {
            item(key = "rest") {
                SectionLabel(stringResource(if (recent.isEmpty()) R.string.notify_apps_installed else R.string.notify_apps_others))
                Rows(*rest.map { app -> @Composable { AppRow(app, app.pkg !in muted) { on -> model.apps.set(app.pkg, on) } } }.toTypedArray())
            }
        }
    }
}

@Composable
private fun AppRow(app: PhoneApp, on: Boolean, onChange: (Boolean) -> Unit) {
    ToggleRow(app.name, on, onChange, leading = { AppIcon(app) })
}

@Composable
private fun AppIcon(app: PhoneApp) {
    if (app.icon != null) Image(app.icon, null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
    else Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Visor.colors.control))
}

/** Every app with a launcher entry and every other app that has [sent] something, by name, but Visor itself. */
private fun launchable(context: Context, sent: Set<String>): List<PhoneApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val listed = pm.queryIntentActivities(intent, 0).map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
    val others = (sent - listed.map { it.packageName }.toSet()).map { pkg ->
        runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()?.let { phoneApp(pm, it) } ?: PhoneApp(pkg, pkg, null)
    }
    return (listed.map { phoneApp(pm, it) } + others)
        .filter { it.pkg != context.packageName }
        .sortedBy { it.name.lowercase() }
}

private fun phoneApp(pm: PackageManager, info: ApplicationInfo): PhoneApp {
    val icon = runCatching { pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()
    return PhoneApp(info.packageName, pm.getApplicationLabel(info).toString(), icon)
}

private const val ICON_PX = 96
