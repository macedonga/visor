package com.macedonga.visor.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.DeviceKey
import com.macedonga.visor.EventLogKey
import com.macedonga.visor.FlagsKey
import com.macedonga.visor.R
import com.macedonga.visor.RequestLogKey
import com.macedonga.visor.SettingsGroupKey
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.connection.isDisplayProduct
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GROUPS
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.SettingsState
import com.macedonga.visor.kit.protocol.Scope
import com.macedonga.visor.outside.DeveloperMode
import com.macedonga.visor.outside.KitConsentActivity
import com.macedonga.visor.outside.KitGrants
import androidx.compose.ui.text.input.KeyboardType
import com.macedonga.visor.kit.protocol.Topics
import com.macedonga.visor.outside.KitNetwork
import com.macedonga.visor.outside.NetworkClients
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.copy

/** Every setting by firmware group, other models' groups last and dimmed, then the tools. */
@Composable
fun AdvancedScreen(open: (NavKey) -> Unit, model: SettingsViewModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val state by model.settings.state.collectAsStateWithLifecycle()
    val developer by model.developer.on.collectAsStateWithLifecycle()
    val tokenRequired by model.developer.tokenRequired.collectAsStateWithLifecycle()
    val display = isDisplayProduct(rememberProduct(model.settings, model.lastSeen))
    val byGroup = allKeys(model.settings, state).groupBy { specOf(model.settings, it).group }
    val (own, others) = ALL_GROUPS.filter { byGroup[it] != null }.partition { display || it !in DISPLAY_GROUPS }

    SettingsPage(
        stringResource(R.string.advanced_title),
        light = 0.3f,
        actions = {
            if (state.reading) Spinner(Modifier.padding(horizontal = 13.dp))
            else if (glasses.link == LinkState.READY) RoundIcon(VisorIcons.Refresh, stringResource(R.string.advanced_read_again), { model.settings.read(full = true) })
        },
    ) {
        item(key = "groups") {
            Rows(own) { group -> GroupRow(group, byGroup[group].orEmpty(), state, model, open) }
        }
        if (others.isNotEmpty()) {
            item(key = "others") {
                Column {
                    SectionLabel(stringResource(R.string.advanced_other_models))
                    Rows(others) { group -> GroupRow(group, byGroup[group].orEmpty(), state, model, open, dim = true) }
                }
            }
        }
        item(key = "tools") {
            Column {
                SectionLabel(stringResource(R.string.advanced_tools))
                Rows(
                    { NavRow(VisorIcons.Flag, stringResource(R.string.settings_flags), null, onClick = { open(FlagsKey) }, onCard = true) },
                    { NavRow(VisorIcons.Pulse, stringResource(R.string.settings_diagnostics), null, onClick = { open(DeviceKey) }, onCard = true) },
                    { NavRow(VisorIcons.Lines, stringResource(R.string.settings_event_log), null, onClick = { open(EventLogKey) }, onCard = true) },
                    { NavRow(VisorIcons.Globe, stringResource(R.string.requests_title), null, onClick = { open(RequestLogKey) }, onCard = true) },
                    { BugReportRow(model) },
                )
                Spacer(Modifier.height(12.dp))
                Rows(
                    // The bond needs a Display connected now.
                    if (isDisplayBuild(glasses.info?.build) && classicBondOffered) row { ClassicBondRows() } else null,
                    { ToggleRow(stringResource(R.string.settings_developer), developer, { model.developer.set(it) }) },
                    if (developer) row { TokenRow(model.developer) } else null,
                    if (developer) row { ToggleRow(stringResource(R.string.settings_developer_token_required), tokenRequired, { model.developer.requireToken(it) }) } else null,
                )
                KitApps(model.kitGrants)
                NetworkRows(model.networkClients, model.kitNetwork)
            }
        }
    }
}

/** The apps the person let reach the glasses through the VDK, each with the way to take that back. */
@Composable
private fun KitApps(grants: KitGrants) {
    val all by grants.all.collectAsStateWithLifecycle()
    if (all.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    SectionLabel(stringResource(R.string.settings_kit_apps))
    val remove = stringResource(R.string.settings_kit_remove)
    Rows(all.keys.sorted()) { app ->
        RowFrame(grants.labelOf(app), onClick = null, sub = scopeNames(all.getValue(app).scopes)) {
            RoundIcon(VisorIcons.Close, remove, { grants.set(app, emptySet()) }, size = 40.dp)
        }
    }
}

/** The kit's network door: the switch, the local network, and the clients with their scopes. */
@Composable
private fun NetworkRows(clients: NetworkClients, network: KitNetwork) {
    val on by clients.on.collectAsStateWithLifecycle()
    val lan by clients.lan.collectAsStateWithLifecycle()
    val all by clients.all.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<Pair<String, String>?>(null) }
    val remove = stringResource(R.string.settings_kit_remove)
    Spacer(Modifier.height(12.dp))
    SectionLabel(stringResource(R.string.settings_kit_network))
    Rows(
        { ToggleRow(stringResource(R.string.settings_kit_network_on), on, clients::setOn, keyLine = network.address(lan).takeIf { on }) },
        if (on) row { ToggleRow(stringResource(R.string.settings_kit_network_lan), lan, clients::setLan) } else null,
        if (on) row { NavRow(VisorIcons.Plus, stringResource(R.string.settings_kit_client_add), null, onClick = { adding = true }, onCard = true, chevron = false) } else null,
    )
    if (on && all.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Rows(all) { client ->
            val posts = client.webhook?.let { " · " + stringResource(R.string.settings_kit_client_posts, it) }.orEmpty()
            RowFrame(client.name, onClick = null, sub = scopeNames(client.scopes) + posts) {
                RoundIcon(VisorIcons.Close, remove, { clients.remove(client.name) }, size = 40.dp)
            }
        }
    }
    if (adding) {
        NewClientSheet(
            onDismiss = { adding = false },
            onCreate = { name, scopes, webhook, topics -> made = name to clients.add(name, scopes, webhook, topics) },
        )
    }
    made?.let { (name, token) -> TokenSheet(name, token, onDismiss = { made = null }) }
}

/** A name and the scopes for a new network client. */
@Composable
private fun NewClientSheet(onDismiss: () -> Unit, onCreate: (String, Set<Scope>, String?, List<String>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var scopes by remember { mutableStateOf(setOf<Scope>()) }
    var webhook by remember { mutableStateOf("") }
    var topics by remember { mutableStateOf(setOf<String>()) }
    VisorSheet(onDismiss) {
        SheetTitle(stringResource(R.string.settings_kit_client_new))
        SheetField(name, { name = it }, hint = stringResource(R.string.settings_kit_client_name), focus = true)
        Spacer(Modifier.height(8.dp))
        for (scope in KitConsentActivity.KNOWN) {
            ToggleRow(stringResource(KitConsentActivity.SCOPE_TEXT.getValue(scope)), scope in scopes, { scopes = if (it) scopes + scope else scopes - scope })
        }
        Spacer(Modifier.height(8.dp))
        SheetField(webhook, { webhook = it }, hint = stringResource(R.string.settings_kit_client_webhook), keyboard = KeyboardType.Uri)
        // A webhook posts the events of the topics the client's scopes allow.
        val offered = Topics.all.filter { it.scope != null && it.scope in scopes && it.params.descriptor.elementsCount == 0 }
        if (webhook.isNotBlank()) {
            for (topic in offered) {
                ToggleRow(topic.name, topic.name in topics, { topics = if (it) topics + topic.name else topics - topic.name })
            }
        }
        SheetAnswers(
            stringResource(R.string.settings_kit_client_create),
            {
                onDismiss()
                onCreate(name.trim(), scopes, webhook.trim().ifEmpty { null }, offered.map { it.name }.filter { it in topics })
            },
            stringResource(R.string.cancel),
            onDismiss,
            enabled = name.isNotBlank() && scopes.isNotEmpty(),
        )
    }
}

/** A new client's token, which Visor shows this once. */
@Composable
private fun TokenSheet(name: String, token: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val host = LocalHost.current
    val copied = stringResource(R.string.mcp_copied)
    VisorSheet(onDismiss) {
        SheetTitle(stringResource(R.string.settings_kit_client_token, name), stringResource(R.string.settings_kit_client_token_once))
        Text(token, style = MONO, color = Visor.colors.textPrimary, modifier = Modifier.padding(horizontal = SIDE).padding(bottom = 12.dp))
        SheetAnswers(
            stringResource(R.string.meta_ai_copy),
            {
                copy(context, token, "token", sensitive = true)
                host.notice.show(copied)
            },
            stringResource(R.string.cancel),
            onDismiss,
        )
    }
}

@Composable
private fun scopeNames(scopes: Set<Scope>): String =
    scopes.map { scope -> KitConsentActivity.SCOPE_NAME[scope]?.let { stringResource(it) } ?: scope.id }.joinToString(", ")

/** Asks the glasses for a bug report; it lands in Download/Visor. */
@Composable
private fun BugReportRow(model: SettingsViewModel) {
    val host = LocalHost.current
    val asked = stringResource(R.string.bug_report_asked)
    NavRow(
        VisorIcons.Download, stringResource(R.string.event_log_bug_report), null,
        onClick = {
            model.media.requestBugReport()
            host.notice.show(asked)
        },
        onCard = true, enabled = host.ready, chevron = false,
    )
}

/** Developer mode's token: copy it, or replace it after a confirm. */
@Composable
private fun TokenRow(developer: DeveloperMode) {
    val token by developer.token.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val host = LocalHost.current
    val copied = stringResource(R.string.mcp_copied)
    var renewing by remember { mutableStateOf(false) }
    val copyToken = {
        copy(context, token, "token", sensitive = true)
        host.notice.show(copied)
    }
    RowFrame(stringResource(R.string.settings_developer_token), onClick = copyToken, keyLine = token) {
        RoundIcon(VisorIcons.Copy, stringResource(R.string.meta_ai_copy), copyToken, size = 40.dp)
        RoundIcon(VisorIcons.Refresh, stringResource(R.string.settings_developer_token_new), { renewing = true }, size = 40.dp)
    }
    if (renewing) {
        ConfirmSheet(
            stringResource(R.string.settings_developer_token_new_title), null, stringResource(R.string.settings_developer_token_new),
            onConfirm = developer::renewToken, onDismiss = { renewing = false },
        )
    }
}

@Composable
private fun GroupRow(group: String, keys: List<String>, state: SettingsState, model: SettingsViewModel, open: (NavKey) -> Unit, dim: Boolean = false) {
    val changed = keys.count { state.changed(it, model.settings.catalogue[it]) }
    NavRow(
        null, groupTitle(group),
        if (changed > 0) pluralStringResource(R.plurals.advanced_group_sum_changed, keys.size, keys.size, changed) else pluralStringResource(R.plurals.advanced_group_sum, keys.size, keys.size),
        onClick = { open(SettingsGroupKey(group)) }, dim = dim, onCard = true,
    )
}

/** One firmware group, every key in it. */
@Composable
fun SettingsGroupScreen(group: String, model: SettingsViewModel = hiltViewModel()) {
    val state by model.settings.state.collectAsStateWithLifecycle()
    val dim = group in DISPLAY_GROUPS && !isDisplayProduct(rememberProduct(model.settings, model.lastSeen))
    val keys = groupKeys(model.settings, state, group).sortedBy { specOf(model.settings, it).label }

    SettingsPage(groupTitle(group), light = 0.3f, engineering = true) {
        item(key = "keys") {
            Column(Modifier.alpha(if (dim) 0.55f else 1f)) {
                Rows(keys) { GlassesRow(it) }
            }
        }
    }
}

/** All of a firmware group's keys. */
internal fun groupKeys(settings: GlassesSettings, state: SettingsState, group: String): List<String> =
    allKeys(settings, state).filter { specOf(settings, it).group == group }

internal val ALL_GROUPS = GROUPS + OTHER

// The catalogue's English group names stay the ids.
private val GROUP_TITLES = mapOf(
    "Glasses" to R.string.group_glasses,
    "Sound" to R.string.group_sound,
    "Touch and gestures" to R.string.group_touch,
    "Camera" to R.string.group_camera,
    "Video and live" to R.string.group_video,
    "Meta AI" to R.string.group_meta_ai,
    "Live AI and translation" to R.string.group_live_ai,
    "Calls and messages" to R.string.group_calls,
    "Notifications" to R.string.group_notifications,
    "Music and media" to R.string.group_media,
    "Connectivity" to R.string.group_connectivity,
    "Health and fitness" to R.string.group_health,
    "Accessibility" to R.string.group_accessibility,
    "Privacy and data" to R.string.group_privacy,
    "Updates and system" to R.string.group_updates,
    "Display" to R.string.group_display,
    "Neural band gestures" to R.string.group_band,
    "Developer and tests" to R.string.group_developer,
    OTHER to R.string.group_other,
)

/** A group's name in the phone's language. */
@Composable
internal fun groupTitle(group: String): String = GROUP_TITLES[group]?.let { stringResource(it) } ?: group
