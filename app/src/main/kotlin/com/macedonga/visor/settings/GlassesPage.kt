package com.macedonga.visor.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.AccountsKey
import com.macedonga.visor.OnboardingKey
import com.macedonga.visor.WebAppsKey
import com.macedonga.visor.WifiNetworksKey
import com.macedonga.visor.R
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.connection.LinkKind
import com.macedonga.visor.firmware.FirmwareUpdate
import com.macedonga.visor.firmware.FirmwareWork
import com.macedonga.visor.home.pairName
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.identity.IdentityBundle
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.home.levelPercent
import com.macedonga.visor.home.ownerFirstName
import com.macedonga.visor.login.MetaLogin
import com.macedonga.visor.stage.LookProblem
import com.macedonga.visor.stage.Looks
import com.macedonga.visor.ui.VisorIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class GlassesPageModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val connection: GlassesConnection,
    val settings: GlassesSettings,
    val login: MetaLogin,
    val firmware: FirmwareUpdate,
    val kind: LinkKind,
    private val store: IdentityStore,
    val looks: Looks,
    val lastSeen: com.macedonga.visor.home.LastSeen,
) : ViewModel() {
    private val _glasses = MutableStateFlow<List<IdentityBundle>>(emptyList())

    /** The glasses set up on this phone. */
    val glasses: StateFlow<List<IdentityBundle>> = _glasses.asStateFlow()

    /** Whose glasses they are, for a pair with no name of its own. */
    val firstName = MutableStateFlow<String?>(null)

    fun refresh() {
        login.refresh()
        viewModelScope.launch { firstName.value = ownerFirstName(context) }
        // The page asks Meta again for a look it does not have yet.
        if (looks.look.value == null && !looks.asking.value) looks.retry()
        viewModelScope.launch(Dispatchers.IO) { _glasses.value = runCatching { store.all() }.getOrDefault(emptyList()) }
    }

    fun forget(serial: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.remove(serial)
            lastSeen.forget(serial)
            _glasses.value = runCatching { store.all() }.getOrDefault(emptyList())
        }
    }
}

/** The glasses themselves: how charged, what they are, how they are linked, whose they are. */
@Composable
fun GlassesPage(open: (NavKey) -> Unit, model: GlassesPageModel = hiltViewModel()) {
    val context = LocalContext.current
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val running by model.connection.running.collectAsStateWithLifecycle()
    val bundles by model.glasses.collectAsStateWithLifecycle()
    val seen by model.lastSeen.seen.collectAsStateWithLifecycle()
    val looks by model.looks.all.collectAsStateWithLifecycle()
    val login by model.login.status.collectAsStateWithLifecycle()
    val le by model.kind.overLe.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        model.refresh()
        onPauseOrDispose { }
    }
    val state by model.settings.state.collectAsStateWithLifecycle()
    val firstName by model.firstName.collectAsStateWithLifecycle()
    val look by model.looks.look.collectAsStateWithLifecycle()
    val lookAsking by model.looks.asking.collectAsStateWithLifecycle()
    val lookProblem by model.looks.problem.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    val stored = state.stored[NAME_KEY] ?: model.settings.value(NAME_KEY)
    val custom = stored?.takeIf { it.isNotBlank() && it != LEFTOVER_NAME }
    val name = custom ?: firstName?.let { stringResource(R.string.glasses_owned, it) }
        ?: look?.styleName ?: stringResource(R.string.glasses_default_name)
    // An old build's write test named the pair; the name goes back to none, unannounced.
    LaunchedEffect(ready, state.stored[NAME_KEY]) {
        if (ready && state.stored[NAME_KEY] == LEFTOVER_NAME) model.settings.writeQuietly(NAME_KEY, "")
    }
    val device = glasses.device
    val info = glasses.info
    val serial = glasses.target?.serial ?: info?.serial
    var renaming by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var firmwareOpen by remember { mutableStateOf(false) }
    var confirmingFirmware by remember { mutableStateOf(false) }
    val fwChecking by model.firmware.checking.collectAsStateWithLifecycle()
    val fwChecked by model.firmware.checked.collectAsStateWithLifecycle()
    val fwOffer by model.firmware.offer.collectAsStateWithLifecycle()
    val fwProblem by model.firmware.problem.collectAsStateWithLifecycle()
    val fwWork by model.firmware.work.collectAsStateWithLifecycle()

    SettingsPage(name, light = 0.6f) {
        item(key = "power") {
            Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                // Away, the last reading the home kept, dimmed.
                val last by model.lastSeen.battery.collectAsStateWithLifecycle()
                val glassesNow = device?.battery?.percent?.toInt()
                val caseNow = device?.case?.levelPercent()
                Readout(
                    stringResource(R.string.glasses_battery),
                    (glassesNow ?: last?.glasses)?.let { "$it%" },
                    device?.battery?.charging == true, fresh = glassesNow != null,
                )
                Readout(
                    stringResource(R.string.glasses_case_label),
                    (caseNow ?: last?.case)?.let { "$it%" },
                    device?.case?.charging == true, fresh = caseNow != null,
                )
            }
        }
        item(key = "identity") {
            Column {
                SectionLabel(stringResource(R.string.glasses_section_these))
                Rows(
                    {
                        ValueRow(
                            stringResource(R.string.glasses_name), name, onClick = { renaming = true }, locked = !ready,
                            busy = NAME_KEY in state.pending && NAME_KEY !in state.quiet,
                            problem = if (NAME_KEY in state.kept && NAME_KEY !in state.quiet) stringResource(R.string.settings_kept) else null,
                        )
                    },
                    // As Meta's records have them; nothing on the glasses says.
                    {
                        ValueRow(
                            stringResource(R.string.glasses_frame),
                            look?.let { it.styleName ?: it.style } ?: if (lookAsking) null else stringResource(R.string.settings_unknown),
                            onClick = if (look == null && !lookAsking && lookProblem != LookProblem.LOGGED_OUT) model.looks::retry else null,
                            busy = lookAsking,
                            sub = if (look != null || lookAsking) null else when (lookProblem) {
                                LookProblem.LOGGED_OUT -> stringResource(R.string.glasses_look_logged_out)
                                LookProblem.NO_RECORD -> stringResource(R.string.glasses_look_no_record)
                                LookProblem.FAILED -> stringResource(R.string.glasses_look_failed)
                                null -> null
                            },
                        )
                    },
                    look?.let { l -> l.colorName ?: l.color }?.let { v -> @Composable { ValueRow(stringResource(R.string.glasses_color), v, onClick = null) } },
                    look?.let { l -> l.lensName ?: l.lens }?.let { v -> @Composable { ValueRow(stringResource(R.string.glasses_lenses), v, onClick = null) } },
                    { ValueRow(stringResource(R.string.glasses_firmware), info?.build?.let(::firmwareWords) ?: stringResource(R.string.settings_unknown), onClick = { firmwareOpen = true }) },
                    { ValueRow(stringResource(R.string.glasses_serial), serial ?: stringResource(R.string.settings_unknown), onClick = null) },
                    device?.thermal?.let { t -> @Composable { ValueRow(stringResource(R.string.glasses_heat), heatWords(t.health, t.throttling), onClick = null) } },
                    device?.storage?.let { s -> @Composable { ValueRow(stringResource(R.string.glasses_storage), stringResource(R.string.glasses_storage_free, gb(s.freeMb), gb(s.totalMb)), onClick = null) } },
                )
            }
        }
        item(key = "link") {
            Column {
                SectionLabel(stringResource(R.string.glasses_section_link))
                Rows(
                    {
                        RowFrame(
                            stringResource(
                                when {
                                    ready && glasses.target?.link is GlassesLink.Wifi -> R.string.glasses_link_wifi
                                    ready -> if (le) R.string.glasses_link_le else R.string.glasses_link_rfcomm
                                    running && glasses.link != LinkState.DISCONNECTED && glasses.link != LinkState.LOST -> R.string.glasses_searching
                                    else -> R.string.glasses_link_off
                                },
                            ),
                            onClick = null,
                            sub = (glasses.target?.link as? GlassesLink.Bluetooth)?.address,
                            busy = running && !ready && glasses.link != LinkState.DISCONNECTED && glasses.link != LinkState.LOST,
                        ) {
                            if (running) {
                                ActionPill(stringResource(R.string.glasses_disconnect), { GlassesService.stop(context) }, quiet = true, modifier = Modifier.padding(start = 12.dp))
                            } else if (serial != null) {
                                ActionPill(stringResource(R.string.glasses_connect), { GlassesService.start(context) }, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    },
                    *bundles.filter { it.serial != serial }.map { b ->
                        @Composable {
                            val other = seen[b.serial]
                            RowFrame(
                                pairName(other, looks[b.serial]), onClick = null,
                                sub = other?.glasses?.let { g -> other.case?.let { c -> stringResource(R.string.glasses_other_batteries, g, c) } ?: "$g%" },
                            ) {
                                ActionPill(stringResource(R.string.glasses_connect), {
                                    GlassesService.start(context, GlassesTarget(GlassesLink.Bluetooth(b.glassesAddress), b.serial))
                                }, quiet = true, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }.toTypedArray(),
                    if (hasDisplay(info?.build)) row { ValueRow(stringResource(R.string.wifi_networks), null, onClick = { open(WifiNetworksKey) }, locked = !ready) } else null,
                    if (hasDisplay(info?.build)) row { ValueRow(stringResource(R.string.webapps_row), null, onClick = { open(WebAppsKey) }, locked = !ready) } else null,
                )
            }
        }
        item(key = "account") {
            Column {
                SectionLabel(stringResource(R.string.glasses_section_account))
                Rows(
                    {
                        RowFrame(
                            stringResource(if (login == MetaLogin.Status.LoggedIn) R.string.glasses_logged_in else R.string.glasses_logged_out),
                            onClick = null, sub = stringResource(R.string.glasses_login_sub),
                        ) {
                            if (login == MetaLogin.Status.LoggedIn) {
                                ActionPill(stringResource(R.string.glasses_log_out), { model.login.logOut() }, quiet = true, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    },
                    { ValueRow(stringResource(R.string.glasses_change_owner), null, onClick = { open(OnboardingKey(login = true, address = (glasses.target?.link as? GlassesLink.Bluetooth)?.address)) }, sub = stringResource(R.string.glasses_change_owner_sub)) },
                    if (hasDisplay(info?.build)) row { ValueRow(stringResource(R.string.accounts_title), null, onClick = { open(AccountsKey) }, locked = !ready) } else null,
                )
            }
        }
        item(key = "setup") {
            Column {
                SectionLabel(stringResource(R.string.glasses_section_setup))
                Rows(
                    { ValueRow(stringResource(R.string.glasses_set_up_new), null, onClick = { open(OnboardingKey()) }) },
                    if (serial != null) row {
                        RowFrame(stringResource(R.string.glasses_forget), onClick = { forgetting = true }, sub = stringResource(R.string.glasses_forget_sub))
                    } else null,
                )
            }
        }
    }

    if (renaming) {
        TextSheet(
            stringResource(R.string.glasses_name), custom.orEmpty(),
            onSave = { model.settings.write(NAME_KEY, it.trim()) },
            onDismiss = { renaming = false },
            valid = { it.isNotBlank() && it.length <= 40 },
        )
    }
    if (forgetting && serial != null) {
        VisorSheet({ forgetting = false }) {
            SheetTitle(stringResource(R.string.glasses_forget_title), stringResource(R.string.glasses_forget_text))
            Row(Modifier.padding(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionPill(stringResource(R.string.glasses_forget), {
                    forgetting = false
                    GlassesService.stop(context)
                    model.forget(serial)
                })
                ActionPill(stringResource(R.string.language_cancel), { forgetting = false }, quiet = true)
            }
        }
    }
    if (firmwareOpen) {
        VisorSheet({ firmwareOpen = false; confirmingFirmware = false; model.firmware.dismiss() }) {
            SheetTitle(stringResource(R.string.firmware_title))
            Rows(
                { ValueRow(stringResource(R.string.firmware_current), info?.build?.let(::firmwareWords) ?: stringResource(R.string.settings_unknown), onClick = null) },
                fwOffer?.let { o -> @Composable { ValueRow(stringResource(R.string.firmware_update), o.displayName.ifEmpty { o.targetVersion }, onClick = null) } },
            )
            val status: String? = when (val w = fwWork) {
                is FirmwareWork.Failed -> stringResource(R.string.firmware_failed, w.reason)
                FirmwareWork.Installed -> stringResource(R.string.firmware_installed)
                is FirmwareWork.Installing -> w.percent?.let { stringResource(R.string.firmware_progress, it) } ?: stringResource(R.string.firmware_installing)
                FirmwareWork.Idle -> when {
                    fwChecking -> stringResource(R.string.firmware_checking)
                    fwProblem.isNotEmpty() -> fwProblem
                    fwChecked && fwOffer == null -> stringResource(R.string.firmware_up_to_date)
                    else -> null
                }
            }
            Column(Modifier.padding(horizontal = SIDE, vertical = 16.dp)) {
                if (status != null) Footnote(status)
                val offer = fwOffer
                val installing = fwWork is FirmwareWork.Installing
                if (confirmingFirmware && offer != null) {
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionPill(stringResource(R.string.firmware_install), { confirmingFirmware = false; model.firmware.install() })
                        ActionPill(stringResource(R.string.language_cancel), { confirmingFirmware = false }, quiet = true)
                    }
                    Footnote(stringResource(R.string.firmware_confirm, offer.displayName.ifEmpty { offer.targetVersion }))
                } else {
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionPill(stringResource(R.string.firmware_check), { model.firmware.check() }, quiet = true, enabled = !fwChecking && !installing)
                        if (offer != null) ActionPill(stringResource(R.string.firmware_install), { confirmingFirmware = true }, enabled = !installing)
                    }
                }
            }
        }
    }
}

private const val NAME_KEY = "device_custom_name"
/** What a write test in an earlier build left as the name. */
private const val LEFTOVER_NAME = "VisorTest"

/** A large reading with its name over it, and a bolt while it charges. */
@Composable
private fun Readout(label: String, value: String?, charging: Boolean, fresh: Boolean) {
    Column(Modifier.alpha(if (fresh) 1f else 0.45f)) {
        Text(label, style = Visor.type.label, color = Visor.colors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value ?: "–", style = Visor.type.readout, color = Visor.colors.textPrimary)
            if (charging) Icon(VisorIcons.Bolt, stringResource(R.string.mood_charging), Modifier.padding(start = 6.dp).size(22.dp), tint = Visor.colors.textPrimary)
        }
    }
}

/** The firmware's own build number, the last but one part of the fingerprint "Meta/hammerhead/...:14/UKQ1.../72387580077200100:user/release-keys". */
internal fun firmwareWords(build: String): String = build.split('/').getOrNull(4)?.substringBefore(':')?.takeIf { it.isNotEmpty() } ?: build

private fun gb(mb: Long): String = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"

@Composable
private fun heatWords(health: String, throttling: Boolean): String {
    val words = when (health.uppercase()) {
        "GOOD", "NORMAL", "OK", "COOL" -> stringResource(R.string.glasses_heat_normal)
        else -> health.lowercase().replace('_', ' ')
    }
    return if (throttling) stringResource(R.string.glasses_heat_throttling, words) else words
}
