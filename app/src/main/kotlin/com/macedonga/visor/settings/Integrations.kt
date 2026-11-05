package com.macedonga.visor.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.IntegrationKey
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.mcp.AuthKind
import com.macedonga.visor.core.assistant.mcp.McpHub
import com.macedonga.visor.core.assistant.mcp.McpServer
import com.macedonga.visor.core.assistant.mcp.McpStatus
import com.macedonga.visor.core.assistant.mcp.McpTool
import com.macedonga.visor.core.assistant.mcp.OAuthRedirects
import com.macedonga.visor.core.assistant.mcp.Probe
import com.macedonga.visor.core.assistant.mcp.SignIn
import com.macedonga.visor.core.assistant.mcp.Transport
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.allows
import com.macedonga.visor.ui.copy
import com.macedonga.visor.ui.lineLimit
import com.macedonga.visor.ui.paste
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class IntegrationsViewModel @Inject constructor(val hub: McpHub) : ViewModel()

/**
 * The servers whose tools the assistant uses: each with its state in one line, and the way to add another. A row
 * opens the server's own page.
 */
@Composable
fun IntegrationsScreen(open: (NavKey) -> Unit, model: IntegrationsViewModel = hiltViewModel()) {
    val servers by model.hub.store.servers.collectAsStateWithLifecycle()
    val statuses by model.hub.status.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    // Coming back here, a server that could not be reached is tried again.
    LifecycleResumeEffect(Unit) {
        servers.filter { it.enabled && statuses[it.id]?.phase != McpStatus.Phase.READY }.forEach { model.hub.check(it.id) }
        onPauseOrDispose { }
    }
    SettingsPage(stringResource(R.string.mcp_title)) {
        item(key = "servers") {
            Rows(
                *servers.map { s -> @Composable { ServerRow(s, statuses[s.id]) { open(IntegrationKey(s.id)) } } }.toTypedArray(),
                { NavRow(VisorIcons.Plus, stringResource(R.string.mcp_add), null, onClick = { adding = true }, onCard = true) },
            )
        }
    }
    if (adding) AddServerSheet(model.hub, onDismiss = { adding = false }, onAdded = { id -> adding = false; open(IntegrationKey(id)) })
}

/** One server: its mark with a dot for how it is, its name, and its state or what it needs. */
@Composable
private fun ServerRow(server: McpServer, status: McpStatus?, onClick: () -> Unit) {
    val colors = Visor.colors
    val problem = server.enabled && status?.isProblem() == true
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServerMark(server, status, 40, onCard = true)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(server.name, style = Visor.type.body, color = colors.textPrimary, maxLines = lineLimit(1), overflow = TextOverflow.Ellipsis)
            Text(
                statusLine(server, status),
                style = Visor.type.label,
                color = if (problem) colors.signal else colors.textSecondary,
                maxLines = lineLimit(1),
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (status?.busy == true) Spinner(Modifier.padding(start = 12.dp))
        Icon(VisorIcons.Chevron, null, Modifier.padding(start = 8.dp).size(16.dp), tint = colors.textTertiary)
    }
}

/**
 * The server's icon, or its initial, on a tile, and a dot at its corner: white when connected, red when it needs
 * something. On a card the tile takes the control colour, so it does not vanish into the card.
 */
@Composable
private fun ServerMark(server: McpServer, status: McpStatus?, size: Int, onCard: Boolean = false) {
    val colors = Visor.colors
    val bytes = status?.icon
    val icon = remember(bytes) { bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    Box(Modifier.size(size.dp)) {
        Box(
            Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(if (onCard) colors.control else colors.raised),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    icon, null,
                    Modifier.padding((size * 0.2f).dp).fillMaxSize().clip(RoundedCornerShape((size * 0.12f).dp)).alpha(if (server.enabled) 1f else 0.4f),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    server.name.trim().firstOrNull()?.uppercase() ?: "·",
                    style = if (size > 44) Visor.type.heading else Visor.type.body,
                    color = if (server.enabled) colors.textPrimary else colors.textTertiary,
                )
            }
        }
        val dot = when {
            !server.enabled || status == null -> null
            status.phase == McpStatus.Phase.READY -> Color.White
            status.isProblem() -> colors.signal
            else -> null
        }
        if (dot != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(12.dp).clip(CircleShape).background(if (onCard) colors.raised else colors.surface).padding(2.5.dp)
                    .clip(CircleShape).background(dot),
            )
        }
    }
}

private fun McpStatus.isProblem() = phase !in setOf(McpStatus.Phase.READY, McpStatus.Phase.IDLE, McpStatus.Phase.OFF)

@Composable
private fun phaseWords(phase: McpStatus.Phase): String = stringResource(
    when (phase) {
        McpStatus.Phase.IDLE -> R.string.mcp_phase_idle
        McpStatus.Phase.OFF -> R.string.mcp_phase_off
        McpStatus.Phase.READY -> R.string.mcp_phase_ready
        McpStatus.Phase.SIGN_IN -> R.string.mcp_phase_sign_in
        McpStatus.Phase.NEEDS_KEY -> R.string.mcp_phase_needs_key
        McpStatus.Phase.REFUSED -> R.string.mcp_phase_refused
        McpStatus.Phase.LOCAL_NETWORK -> R.string.mcp_phase_local
        McpStatus.Phase.UNREACHABLE -> R.string.mcp_phase_unreachable
        McpStatus.Phase.NOT_MCP -> R.string.mcp_phase_not_mcp
        McpStatus.Phase.FAILED -> R.string.mcp_phase_failed
    },
)

/** "Connected · 14 tools", "Sign in to use it", "Off". */
@Composable
private fun statusLine(server: McpServer, status: McpStatus?): String = when {
    !server.enabled -> stringResource(R.string.mcp_phase_off)
    status == null || (status.phase == McpStatus.Phase.IDLE && status.busy) -> stringResource(R.string.mcp_phase_checking)
    status.phase == McpStatus.Phase.READY -> stringResource(R.string.mcp_works, toolsWords(status.tools.count { it.name !in server.off }))
    else -> phaseWords(status.phase)
}

@Composable
private fun toolsWords(n: Int) = pluralStringResource(R.plurals.mcp_tools, n, n)

/** For the assistant's settings: what the integrations come to, in a line. */
@Composable
fun integrationsSummary(hub: McpHub): String? {
    val servers by hub.store.servers.collectAsStateWithLifecycle()
    val statuses by hub.status.collectAsStateWithLifecycle()
    return when (servers.size) {
        0 -> null
        1 -> servers[0].name + " · " + statusLine(servers[0], statuses[servers[0].id])
        else -> servers.count { it.enabled && statuses[it.id]?.phase == McpStatus.Phase.READY }.let { pluralStringResource(R.plurals.mcp_connected, it, it) }
    }
}

/**
 * Adding a server: the address, tried at once. Visor finds out from the answer whether it opens freely, asks for a key,
 * or signs in with an account, and asks only for what is missing.
 */
@Composable
private fun AddServerSheet(hub: McpHub, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf(AuthKind.NONE) }
    var secret by remember { mutableStateOf("") }
    var header by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<Probe?>(null) }
    val address = url.trim()
    val valid = isWebAddress(address)
    fun server(kind: AuthKind) = McpServer(name = name.trim().ifEmpty { hostOf(address) }, url = address, auth = kind, secret = secret.trim(), header = header.trim())
    fun save(kind: AuthKind, signIn: Boolean = false) {
        val s = server(kind)
        hub.store.add(s)
        if (signIn) hub.signIn(s.id)
        onAdded(s.id)
    }
    fun connect() {
        probing = true
        probe = null
        scope.launch {
            val found = hub.probe(address, auth, secret.trim(), header.trim())
            probing = false
            probe = found
            when (found) {
                is Probe.Works -> save(auth)
                is Probe.NeedsKey -> if (auth == AuthKind.NONE) auth = AuthKind.TOKEN
                else -> Unit
            }
        }
    }
    VisorSheet(onDismiss, full = true) {
        SheetTitle(stringResource(R.string.mcp_add))
        FieldLabel(stringResource(R.string.mcp_address), first = true)
        SheetField(url, { url = it.trim(); probe = null }, hint = "https://…", keyboard = KeyboardType.Uri, focus = true)
        FieldLabel(stringResource(R.string.mcp_name))
        SheetField(name, { name = it }, hint = hostOf(address))
        AnimatedVisibility(auth != AuthKind.NONE, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column {
                Box(Modifier.padding(horizontal = 16.dp).padding(top = 20.dp)) {
                    Pills(listOf(AuthKind.TOKEN to stringResource(R.string.mcp_token), AuthKind.HEADER to stringResource(R.string.mcp_header)), auth) { auth = it; probe = null }
                }
                if (auth == AuthKind.HEADER) {
                    FieldLabel(stringResource(R.string.mcp_header_name))
                    SheetField(header, { header = it.trim(); probe = null }, hint = "X-API-Key")
                }
                FieldLabel(stringResource(if (auth == AuthKind.HEADER) R.string.mcp_header_value else R.string.mcp_token))
                SheetField(secret, { secret = it; probe = null }, secret = true, keyboard = KeyboardType.Password)
            }
        }
        val found = probe
        Box(Modifier.padding(horizontal = 32.dp).padding(top = 14.dp)) {
            when {
                probing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner()
                    Text(stringResource(R.string.mcp_phase_checking), style = Visor.type.label, color = Visor.colors.textSecondary, modifier = Modifier.padding(start = 10.dp))
                }
                found is Probe.Failed -> Text(phaseWords(found.phase), style = Visor.type.label, color = Visor.colors.signal)
                found is Probe.NeedsKey && secret.isNotBlank() -> Text(stringResource(R.string.mcp_phase_refused), style = Visor.type.label, color = Visor.colors.signal)
                else -> Unit
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (found is Probe.Failed) {
                ActionPill(stringResource(R.string.mcp_save_anyway), { save(auth) }, quiet = true)
            }
            Spacer(Modifier.weight(1f))
            if (found is Probe.SignIn) {
                ActionPill(stringResource(R.string.mcp_sign_in), { save(AuthKind.OAUTH, signIn = true) })
            } else {
                ActionPill(stringResource(R.string.mcp_connect), ::connect, enabled = valid && !probing)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String, first: Boolean = false) {
    // In line with the text inside the field, as section labels are with the rows inside a card.
    Text(
        text, style = Visor.type.label, color = Visor.colors.textSecondary,
        modifier = Modifier.padding(horizontal = 32.dp).padding(top = if (first) 0.dp else 16.dp, bottom = 8.dp),
    )
}

/**
 * One server: how it is and what it needs first, then whether the assistant uses it, how it signs in, each of its tools,
 * and the server itself.
 */
@Composable
fun IntegrationScreen(id: String, onGone: () -> Unit, model: IntegrationsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val hub = model.hub
    val servers by hub.store.servers.collectAsStateWithLifecycle()
    val statuses by hub.status.collectAsStateWithLifecycle()
    val signIn by hub.signIn.collectAsStateWithLifecycle()
    val server = servers.firstOrNull { it.id == id }
    if (server == null) {
        LaunchedEffect(Unit) { onGone() }
        return
    }
    val status = statuses[id]
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    // A sign-in page is opened once; coming back from it does not open it again.
    var opened by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(signIn) {
        val s = signIn
        if (s is SignIn.InBrowser && s.id == id && s.url != opened) {
            opened = s.url
            openInBrowser(context, s.url)
        }
    }
    val activity = LocalActivity.current
    var lanAllowed by remember { mutableStateOf(localNetworkAllowed(context)) }
    val askLan = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        lanAllowed = it
        if (it) hub.check(id)
        else if (Build.VERSION.SDK_INT >= 37 && activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK) == false) openAppPage(context)
    }
    // A grant made in the system's own page counts on the way back.
    LifecycleResumeEffect(Unit) {
        val now = localNetworkAllowed(context)
        if (now && !lanAllowed) hub.check(id)
        lanAllowed = now
        onPauseOrDispose { }
    }
    val needsLan = !lanAllowed && isLocalAddress(server.url)
    val mine = signIn.takeIf { it.id == id }
    val update: ((McpServer) -> McpServer) -> Unit = { change -> hub.store.update(id, change) }

    val signing = mine is SignIn.Preparing || mine is SignIn.Finishing
    SettingsPage(
        server.name,
        actions = {
            when {
                status?.busy == true || signing -> Spinner(Modifier.padding(horizontal = 13.dp))
                server.enabled -> RoundIcon(VisorIcons.Refresh, stringResource(R.string.mcp_check_again), { hub.check(id) })
            }
        },
        sheet = {
            val host = LocalHost.current
            val done = stringResource(R.string.mcp_signed_in)
            LaunchedEffect(mine) {
                if (mine is SignIn.Done) {
                    host.notice.show(done)
                    hub.clearSignIn()
                }
            }
        },
    ) {
        item(key = "head") { ServerHead(server, status) }
        item(key = "use") {
            Column(Modifier.padding(top = 24.dp)) {
                Rows(
                    { ToggleRow(stringResource(R.string.mcp_use), server.enabled, onChange = { on -> update { it.copy(enabled = on) } }) },
                    if (needsLan) row {
                        RowFrame(stringResource(R.string.mcp_local_network), onClick = null) {
                            ActionPill(stringResource(R.string.allow), { if (Build.VERSION.SDK_INT >= 37) askLan.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }, modifier = Modifier.padding(start = 12.dp))
                        }
                    } else null,
                )
            }
        }
        item(key = "access") {
            Column {
                SectionLabel(stringResource(R.string.mcp_section_access))
                val wantsAccount = status?.phase == McpStatus.Phase.SIGN_IN
                Rows(*accessRows(server, mine, wantsAccount, onSheet = { sheet = it }, onSignIn = { hub.signIn(id) }, onSignOut = { hub.signOut(id) }, context).toTypedArray())
                if ((mine as? SignIn.Failed)?.needsClient == true) Footnote(stringResource(R.string.mcp_needs_client), color = Visor.colors.signal)
            }
        }
        item(key = "tools") {
            ToolsSection(server, status?.tools.orEmpty(), update)
        }
        item(key = "server") {
            Column {
                SectionLabel(stringResource(R.string.mcp_section_server))
                Rows(
                    { ValueRow(stringResource(R.string.mcp_name), server.name, onClick = { sheet = "name" }) },
                    { ValueRow(stringResource(R.string.mcp_address), null, onClick = { sheet = "url" }, keyLine = server.url) },
                    { RowFrame(stringResource(R.string.mcp_remove), onClick = { sheet = "remove" }) },
                )
            }
        }
    }

    when (sheet) {
        "method" -> ChoiceSheet(
            stringResource(R.string.mcp_method),
            AuthKind.entries.map { Triple(it, methodWords(it), null) },
            server.auth,
            onPick = { kind ->
                update { it.copy(auth = kind) }
                if (kind == AuthKind.OAUTH && server.oauth?.signedIn != true) hub.signIn(id)
            },
            onDismiss = { sheet = null },
        )
        "secret" -> TextSheet(
            stringResource(if (server.auth == AuthKind.HEADER) R.string.mcp_header_value else R.string.mcp_token), server.secret,
            onSave = { t -> update { it.copy(secret = t.trim()) } }, onDismiss = { sheet = null },
            secret = true, keyboard = KeyboardType.Password,
            extra = { set -> ActionPill(stringResource(R.string.ai_paste), { paste(context)?.let { set(it.trim()) } }, quiet = true) },
        )
        "header" -> TextSheet(stringResource(R.string.mcp_header_name), server.header, onSave = { h -> update { it.copy(header = h.trim()) } }, onDismiss = { sheet = null }, hint = "X-API-Key")
        "client" -> TextSheet(
            stringResource(R.string.mcp_client_id), server.clientId, onSave = { c -> update { it.copy(clientId = c.trim()) } }, onDismiss = { sheet = null },
        )
        "clientSecret" -> TextSheet(
            stringResource(R.string.mcp_client_secret), server.clientSecret, onSave = { c -> update { it.copy(clientSecret = c.trim()) } }, onDismiss = { sheet = null },
            secret = true, keyboard = KeyboardType.Password,
        )
        "redirect" -> ChoiceSheet(
            stringResource(R.string.mcp_redirect),
            listOf(
                Triple(OAuthRedirects.APP, stringResource(R.string.mcp_redirect_app), OAuthRedirects.APP),
                Triple(OAuthRedirects.LOOPBACK, stringResource(R.string.mcp_redirect_loopback), OAuthRedirects.LOOPBACK),
            ),
            server.redirect.ifEmpty { OAuthRedirects.APP },
            onPick = { r -> update { it.copy(redirect = r) } },
            onDismiss = { sheet = null },
        )
        "name" -> TextSheet(stringResource(R.string.mcp_name), server.name, onSave = { n -> update { it.copy(name = n.trim().ifEmpty { it.name }) } }, onDismiss = { sheet = null })
        "url" -> TextSheet(
            stringResource(R.string.mcp_address), server.url, onSave = { u -> update { it.copy(url = u.trim(), transport = Transport.AUTO) } }, onDismiss = { sheet = null },
            keyboard = KeyboardType.Uri, valid = ::isWebAddress,
        )
        "remove" -> ConfirmSheet(
            stringResource(R.string.mcp_remove_title, server.name), null, stringResource(R.string.mcp_remove_confirm),
            onConfirm = { hub.store.remove(id) },
            onDismiss = { sheet = null },
        )
    }
}

/** The page's head: the mark, how the server is in a word, and who it says it is. */
@Composable
private fun ServerHead(server: McpServer, status: McpStatus?) {
    val colors = Visor.colors
    val problem = server.enabled && status?.isProblem() == true
    Column(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServerMark(server, status, 56)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(statusLine(server, status), style = Visor.type.heading, color = if (problem) colors.signal else colors.textPrimary)
                val info = status?.info
                val about = info?.let { "${it.name} ${it.version}".trim() }.orEmpty()
                if (about.isNotEmpty()) Text(about, style = Visor.type.label, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** How the server signs Visor in, with the fields that way needs. */
private fun accessRows(
    server: McpServer,
    signIn: SignIn?,
    wantsAccount: Boolean,
    onSheet: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    context: Context,
): List<@Composable () -> Unit> {
    val rows = ArrayList<@Composable () -> Unit>()
    rows += { ValueRow(stringResource(R.string.mcp_method), methodWords(server.auth), onClick = { onSheet("method") }) }
    when (server.auth) {
        AuthKind.TOKEN -> rows += { ValueRow(stringResource(R.string.mcp_token), masked(server.secret) ?: stringResource(R.string.settings_not_set), onClick = { onSheet("secret") }) }
        AuthKind.HEADER -> {
            rows += { ValueRow(stringResource(R.string.mcp_header_name), server.header.ifEmpty { stringResource(R.string.settings_not_set) }, onClick = { onSheet("header") }) }
            rows += { ValueRow(stringResource(R.string.mcp_header_value), masked(server.secret) ?: stringResource(R.string.settings_not_set), onClick = { onSheet("secret") }) }
        }
        AuthKind.OAUTH, AuthKind.NONE -> if (server.auth == AuthKind.OAUTH || wantsAccount) {
            rows += {
                val grant = server.oauth
                val busy = signIn is SignIn.Preparing || signIn is SignIn.Finishing
                RowFrame(
                    stringResource(R.string.mcp_account),
                    onClick = null,
                    sub = when {
                        signIn is SignIn.InBrowser -> stringResource(R.string.mcp_finish_in_browser)
                        busy -> stringResource(R.string.mcp_signing_in)
                        grant?.signedIn == true -> stringResource(R.string.mcp_signed_in_at, hostOf(grant.issuer))
                        else -> stringResource(R.string.mcp_not_signed_in)
                    },
                    busy = busy,
                    problem = if (signIn is SignIn.Failed && !signIn.needsClient) stringResource(R.string.mcp_sign_in_failed) else null,
                ) {
                    if (grant?.signedIn == true) {
                        ActionPill(stringResource(R.string.mcp_sign_out), onSignOut, modifier = Modifier.padding(start = 12.dp), quiet = true)
                    } else {
                        ActionPill(
                            stringResource(if (signIn is SignIn.Failed) R.string.mcp_sign_in_again else R.string.mcp_sign_in),
                            onSignIn, modifier = Modifier.padding(start = 12.dp), enabled = !busy,
                        )
                    }
                }
            }
            // Asked for only when the server cannot register Visor itself.
            if (server.clientId.isNotEmpty() || (signIn as? SignIn.Failed)?.needsClient == true) {
                rows += { ValueRow(stringResource(R.string.mcp_client_id), server.clientId.ifEmpty { stringResource(R.string.settings_not_set) }, onClick = { onSheet("client") }) }
            }
            if (server.clientId.isNotEmpty()) {
                rows += { ValueRow(stringResource(R.string.mcp_client_secret), masked(server.clientSecret) ?: stringResource(R.string.settings_not_set), onClick = { onSheet("clientSecret") }) }
                rows += {
                    val host = LocalHost.current
                    val copied = stringResource(R.string.mcp_copied)
                    val redirect = server.redirect.ifEmpty { OAuthRedirects.APP }
                    RowFrame(
                        stringResource(R.string.mcp_redirect),
                        onClick = { onSheet("redirect") },
                        value = stringResource(if (redirect == OAuthRedirects.APP) R.string.mcp_redirect_app else R.string.mcp_redirect_loopback),
                        keyLine = redirect,
                    ) {
                        ActionPill(stringResource(R.string.meta_ai_copy), {
                            copy(context, redirect, "redirect")
                            host.notice.show(copied)
                        }, quiet = true, modifier = Modifier.padding(start = 12.dp))
                        Icon(VisorIcons.Chevron, null, Modifier.padding(start = 8.dp).size(16.dp), tint = Visor.colors.textTertiary)
                    }
                }
            }
        }
    }
    return rows
}

/** Its tools, each on or off for the assistant, with what it does in the server's own words. */
@Composable
private fun ToolsSection(server: McpServer, tools: List<McpTool>, update: ((McpServer) -> McpServer) -> Unit) {
    if (tools.isEmpty()) return
    Column {
        val on = tools.count { it.name !in server.off }
        SectionLabel(stringResource(R.string.mcp_section_tools) + " · " + stringResource(R.string.mcp_tools_on, on, tools.size))
        Rows(
            if (tools.size > 1) row {
                ToggleRow(
                    stringResource(R.string.mcp_all_tools), on == tools.size,
                    onChange = { all -> update { it.copy(off = if (all) emptySet() else tools.map { t -> t.name }.toSet()) } },
                )
            } else null,
            *tools.map { t ->
                @Composable {
                    ToggleRow(
                        t.title.ifEmpty { readable(t.name) },
                        t.name !in server.off,
                        onChange = { enabled -> update { it.copy(off = if (enabled) it.off - t.name else it.off + t.name) } },
                        sub = firstSentences(t.description),
                        dense = true,
                    )
                }
            }.toTypedArray(),
        )
    }
}

@Composable
private fun methodWords(kind: AuthKind) = stringResource(
    when (kind) {
        AuthKind.NONE -> R.string.mcp_method_none
        AuthKind.TOKEN -> R.string.mcp_method_token
        AuthKind.HEADER -> R.string.mcp_method_header
        AuthKind.OAUTH -> R.string.mcp_method_oauth
    },
)

/** "turnOn" as "Turn on", "list_issues" as "List issues". */
private fun readable(name: String): String {
    return name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ').trim().lowercase().replaceFirstChar { it.uppercase() }
}

/** A description cut to what a row can carry, at a sentence where it can be. */
private fun firstSentences(text: String, max: Int = 160): String? {
    val t = text.trim().replace(Regex("\\s+"), " ").ifEmpty { return null }
    if (t.length <= max) return t
    val cut = t.lastIndexOf(". ", max)
    return if (cut > 40) t.substring(0, cut + 1) else t.take(max).trimEnd() + "…"
}

private fun hostOf(url: String): String = runCatching { Uri.parse(url.trim()).host.orEmpty() }.getOrDefault("").removePrefix("www.")

/** The sign-in page in a Custom Tab, or in any browser there is. */
private fun openInBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    runCatching {
        CustomTabsIntent.Builder().setShowTitle(true).setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK).build().launchUrl(context, uri)
    }.onFailure {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

private fun localNetworkAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < 37 || context.allows(Manifest.permission.ACCESS_LOCAL_NETWORK)

/** An address on the home network: private ranges, link-local, .local and the like, a bare host name. */
private fun isLocalAddress(url: String): Boolean {
    val host = runCatching { Uri.parse(url.trim()).host }.getOrNull()?.lowercase() ?: return false
    if (host == "localhost" || host.startsWith("127.")) return false
    // Uri keeps an IPv6 literal's brackets.
    if (':' in host) {
        val v6 = host.trim('[', ']')
        return v6.startsWith("fe80") || v6.startsWith("fc") || v6.startsWith("fd")
    }
    val v4 = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }
    if (v4 != null) {
        return v4[0] == 10 || (v4[0] == 192 && v4[1] == 168) || (v4[0] == 172 && v4[1] in 16..31) || (v4[0] == 169 && v4[1] == 254)
    }
    return '.' !in host || listOf(".local", ".lan", ".home", ".home.arpa", ".internal").any { host.endsWith(it) }
}
