package com.macedonga.visor.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.DeviceKey
import com.macedonga.visor.EventLogKey
import com.macedonga.visor.FlagsKey
import com.macedonga.visor.R
import com.macedonga.visor.SettingsGroupKey
import com.macedonga.visor.gallery.RoundIcon
import com.macedonga.visor.outside.DeveloperMode
import com.macedonga.visor.ui.VisorIcons

/**
 * Every setting the glasses keep, by the firmware's own groups, and the tools for the link. The groups for glasses
 * with a display come last and dimmed, unless the glasses connected have one.
 */
@Composable
fun AdvancedScreen(open: (NavKey) -> Unit, model: SettingsViewModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val state by model.settings.state.collectAsStateWithLifecycle()
    val developer by model.developer.on.collectAsStateWithLifecycle()
    val tokenRequired by model.developer.tokenRequired.collectAsStateWithLifecycle()
    val display = hasDisplay(glasses.info?.build)
    val all = allKeys(model.settings, state)
    val changed = all.count { state.changed(it, model.settings.catalogue[it]) }
    val byGroup = all.groupBy { specOf(model.settings, it).group }
    val (own, others) = ALL_GROUPS.filter { byGroup[it] != null }.partition { display || it !in DISPLAY_GROUPS }

    SettingsPage(
        stringResource(R.string.advanced_title),
        stringResource(R.string.advanced_lede, all.size, changed) + " " + (state.readAt?.let { stringResource(R.string.advanced_read_at, timeWords(it)) } ?: ""),
        light = 0.3f,
        actions = {
            if (state.reading) Spinner(Modifier.padding(horizontal = 13.dp))
            else RoundIcon(VisorIcons.Refresh, stringResource(R.string.advanced_read_again), { model.settings.read(full = true) })
        },
    ) {
        item(key = "groups") {
            Rows(*own.map { group -> @Composable { GroupRow(group, byGroup[group].orEmpty(), state, model, open) } }.toTypedArray())
        }
        if (others.isNotEmpty()) {
            item(key = "others") {
                Column {
                    SectionLabel(stringResource(R.string.advanced_other_models))
                    Rows(*others.map { group -> @Composable { GroupRow(group, byGroup[group].orEmpty(), state, model, open, dim = true) } }.toTypedArray())
                    Footnote(stringResource(R.string.advanced_other_models_note))
                }
            }
        }
        item(key = "tools") {
            Column {
                SectionLabel(stringResource(R.string.advanced_tools))
                Rows(
                    { NavRow(VisorIcons.Flag, stringResource(R.string.settings_flags), stringResource(R.string.settings_flags_sub), onClick = { open(FlagsKey) }, onCard = true) },
                    { NavRow(VisorIcons.Pulse, stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_sub), onClick = { open(DeviceKey) }, onCard = true) },
                    { NavRow(VisorIcons.Lines, stringResource(R.string.settings_event_log), stringResource(R.string.settings_event_log_sub), onClick = { open(EventLogKey) }, onCard = true) },
                    if (display && classicBondOffered) ({ ClassicBondRows() }) else null,
                    { ToggleRow(stringResource(R.string.settings_developer), developer, { model.developer.set(it) }, sub = stringResource(R.string.settings_developer_sub)) },
                    if (developer) ({ TokenRow(model.developer) }) else null,
                    if (developer) ({ ToggleRow(stringResource(R.string.settings_developer_token_required), tokenRequired, { model.developer.requireToken(it) }) }) else null,
                )
            }
        }
    }
}

/** Developer mode's token: a tap copies it, the arrow makes a new one. */
@Composable
private fun TokenRow(developer: DeveloperMode) {
    val token by developer.token.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val host = LocalHost.current
    val copied = stringResource(R.string.mcp_copied)
    RowFrame(
        stringResource(R.string.settings_developer_token),
        onClick = {
            val clip = ClipData.newPlainText("token", token)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
            host.notice.show(copied)
        },
        keyLine = token,
    ) {
        RoundIcon(VisorIcons.Refresh, stringResource(R.string.settings_developer_token_new), { developer.renewToken() }, size = 40.dp)
    }
}

@Composable
private fun GroupRow(group: String, keys: List<String>, state: com.macedonga.visor.core.settings.SettingsState, model: SettingsViewModel, open: (NavKey) -> Unit, dim: Boolean = false) {
    val changed = keys.count { state.changed(it, model.settings.catalogue[it]) }
    NavRow(
        null, group,
        if (changed > 0) stringResource(R.string.advanced_group_sum_changed, keys.size, changed) else stringResource(R.string.advanced_group_sum, keys.size),
        onClick = { open(SettingsGroupKey(group)) }, dim = dim, onCard = true,
    )
}

/** One firmware group, every key in it: what the Meta app shows first, its internal knobs after. */
@Composable
fun SettingsGroupScreen(group: String, model: SettingsViewModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val state by model.settings.state.collectAsStateWithLifecycle()
    val dim = group in DISPLAY_GROUPS && !hasDisplay(glasses.info?.build)
    val keys = groupKeys(model.settings, state, group).sortedBy { specOf(model.settings, it).label }
    val (shown, internal) = keys.partition { specOf(model.settings, it).userFacing }

    SettingsPage(group, if (dim) stringResource(R.string.advanced_other_models_note) else null, light = 0.3f) {
        if (shown.isNotEmpty()) {
            item(key = "shown") {
                Column(Modifier.alpha(if (dim) 0.55f else 1f)) {
                    SectionLabel(stringResource(R.string.advanced_in_meta_app), Modifier.padding(top = 0.dp))
                    Rows(*shown.map { key -> @Composable { GlassesRow(key, engineering = true) } }.toTypedArray())
                }
            }
        }
        if (internal.isNotEmpty()) {
            item(key = "internal") {
                Column(Modifier.alpha(if (dim) 0.55f else 1f)) {
                    SectionLabel(stringResource(R.string.advanced_internal))
                    Rows(*internal.map { key -> @Composable { GlassesRow(key, engineering = true) } }.toTypedArray())
                    Footnote(stringResource(R.string.advanced_internal_note))
                }
            }
        }
    }
}
