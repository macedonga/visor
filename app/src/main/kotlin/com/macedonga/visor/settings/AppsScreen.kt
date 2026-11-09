package com.macedonga.visor.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.isDisplayProduct
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.kit.protocol.Scope
import com.macedonga.visor.kit.protocol.Topics
import com.macedonga.visor.outside.DeveloperMode
import com.macedonga.visor.outside.KitConsentActivity
import com.macedonga.visor.outside.KitGrants
import com.macedonga.visor.outside.KitNetwork
import com.macedonga.visor.outside.NetworkClients
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import com.macedonga.visor.ui.copy
import com.macedonga.visor.ui.lineLimit

const val DEVELOPERS_URL = "https://visor.macedonga.com/developers/"

/** The VDK's home in Visor: what uses the glasses through it, what each may do, and the developers' tools. */
@Composable
fun AppsScreen(model: SettingsViewModel = hiltViewModel()) {
    val grants by model.kitGrants.all.collectAsStateWithLifecycle()
    val display = isDisplayProduct(rememberProduct(model.settings, model.lastSeen))
    var shown by remember { mutableStateOf<String?>(null) }
    val apps = grants.keys.sortedBy { model.kitGrants.labelOf(it).lowercase() }

    SettingsPage(
        stringResource(R.string.apps_title),
        light = 0.45f,
        sheet = { shown?.let { app -> AppSheet(app, model.kitGrants, onDismiss = { shown = null }) } },
    ) {
        item(key = "phone") {
            Column {
                SectionLabel(stringResource(R.string.apps_on_phone))
                if (apps.isEmpty()) {
                    EmptyApps()
                } else {
                    Rows(apps) { app -> AppRow(app, model.kitGrants.labelOf(app), scopeNames(grants.getValue(app).scopes)) { shown = app } }
                }
            }
        }
        if (display) {
            item(key = "webapps") { WebAppsSection() }
        }
        item(key = "programs") { ProgramRows(model.networkClients, model.kitNetwork) }
        item(key = "developers") { DeveloperRows(model.developer) }
    }
}

/** What the main settings say about the apps: how many apps, web apps and programs, or that there are none yet. */
@Composable
fun appsSummary(model: SettingsViewModel): String {
    val apps by model.kitGrants.all.collectAsStateWithLifecycle()
    val programs by model.networkClients.all.collectAsStateWithLifecycle()
    val open by model.networkClients.on.collectAsStateWithLifecycle()
    val web by model.webApps.apps.collectAsStateWithLifecycle()
    val parts = buildList {
        if (apps.isNotEmpty()) add(pluralStringResource(R.plurals.apps_count, apps.size, apps.size))
        if (web.isNotEmpty()) add(pluralStringResource(R.plurals.apps_webapps_count, web.size, web.size))
        if (open && programs.isNotEmpty()) add(pluralStringResource(R.plurals.apps_programs_count, programs.size, programs.size))
    }
    return if (parts.isEmpty()) stringResource(R.string.apps_sum_none) else parts.joinToString(", ")
}

/** No app yet. */
@Composable
private fun EmptyApps() {
    val colors = Visor.colors
    Card {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(colors.control), contentAlignment = Alignment.Center) {
                Icon(VisorIcons.Apps, null, Modifier.size(26.dp), tint = colors.textPrimary)
            }
            Text(stringResource(R.string.apps_empty_title), style = Visor.type.body, color = colors.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/** An app with access: its own icon, its name and what it may use. */
@Composable
private fun AppRow(app: String, name: String, scopes: String, onClick: () -> Unit) {
    val colors = Visor.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app, 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, style = Visor.type.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(scopes, style = Visor.type.label, color = colors.textSecondary, maxLines = lineLimit(2), overflow = TextOverflow.Ellipsis)
        }
        Icon(VisorIcons.Chevron, null, Modifier.padding(start = 8.dp).size(16.dp), tint = colors.textTertiary)
    }
}

/** The app's launcher icon, or the apps glyph when Visor cannot see the app any more. */
@Composable
private fun AppIcon(app: String, size: Dp) {
    val context = LocalContext.current
    val icon: ImageBitmap? = remember(app) {
        runCatching { context.packageManager.getApplicationIcon(app).toBitmap(192, 192).asImageBitmap() }.getOrNull()
    }
    if (icon != null) {
        Image(icon, null, Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Visor.colors.control), contentAlignment = Alignment.Center) {
            Icon(VisorIcons.Apps, null, Modifier.size(size / 2), tint = Visor.colors.textPrimary)
        }
    }
}

/** One app: each thing it may do, to take back one by one, or all at once. */
@Composable
private fun AppSheet(app: String, grants: KitGrants, onDismiss: () -> Unit) {
    val all by grants.all.collectAsStateWithLifecycle()
    val scopes = all[app]?.scopes.orEmpty()
    val colors = Visor.colors
    VisorSheet(onDismiss) {
        Row(Modifier.padding(horizontal = SIDE).padding(top = 6.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, 52.dp)
            Column(Modifier.padding(start = 16.dp)) {
                Text(grants.labelOf(app), style = Visor.type.heading, color = colors.textPrimary, modifier = Modifier.asHeading())
                Text(app, style = MONO, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (scopes.isEmpty()) {
            Text(stringResource(R.string.apps_sheet_none), style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = SIDE).padding(bottom = 8.dp))
        }
        for (scope in KitConsentActivity.KNOWN.filter { it in scopes }) {
            ToggleRow(scopeName(scope), true, { grants.set(app, scopes - scope) })
        }
        Spacer(Modifier.height(8.dp))
        SheetAnswers(
            stringResource(R.string.settings_kit_remove),
            {
                onDismiss()
                grants.set(app, emptySet())
            },
            stringResource(R.string.apps_done),
            onDismiss,
            enabled = scopes.isNotEmpty(),
        )
    }
}

/** Programs and agents on the network door: the switch, the local network, and each program with its scopes. */
@Composable
private fun ProgramRows(clients: NetworkClients, network: KitNetwork) {
    val on by clients.on.collectAsStateWithLifecycle()
    val lan by clients.lan.collectAsStateWithLifecycle()
    val all by clients.all.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<Pair<String, String>?>(null) }
    val remove = stringResource(R.string.settings_kit_remove)
    Column {
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
    }
    if (adding) {
        NewClientSheet(
            onDismiss = { adding = false },
            onCreate = { name, scopes, webhook, topics -> made = name to clients.add(name, scopes, webhook, topics) },
        )
    }
    made?.let { (name, token) -> TokenSheet(name, token, onDismiss = { made = null }) }
}

/** Developer mode for the `visor` command and automations, its token, and where to start building. */
@Composable
private fun DeveloperRows(developer: DeveloperMode) {
    val on by developer.on.collectAsStateWithLifecycle()
    val tokenRequired by developer.tokenRequired.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    Column {
        SectionLabel(stringResource(R.string.apps_developers))
        Rows(
            { ToggleRow(stringResource(R.string.settings_developer), on, developer::set) },
            if (on) row { TokenRow(developer) } else null,
            if (on) row { ToggleRow(stringResource(R.string.settings_developer_token_required), tokenRequired, developer::requireToken) } else null,
        )
        Spacer(Modifier.height(12.dp))
        Rows({ NavRow(VisorIcons.Code, stringResource(R.string.apps_build), null, onClick = { uri.openUri(DEVELOPERS_URL) }, onCard = true) })
    }
}

/** A name and the scopes for a new program. */
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
            ToggleRow(scopeName(scope), scope in scopes, { scopes = if (it) scopes + scope else scopes - scope })
        }
        Spacer(Modifier.height(8.dp))
        SheetField(webhook, { webhook = it }, hint = stringResource(R.string.settings_kit_client_webhook), keyboard = KeyboardType.Uri)
        // A webhook posts the events of the topics the program's scopes allow.
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

/** A new program's token, which Visor shows this once. */
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
private fun scopeName(scope: Scope): String = KitConsentActivity.SCOPE_NAME[scope]?.let { stringResource(it) } ?: scope.id

@Composable
private fun scopeNames(scopes: Set<Scope>): String = KitConsentActivity.KNOWN.filter { it in scopes }.map { scopeName(it) }.joinToString(", ")
