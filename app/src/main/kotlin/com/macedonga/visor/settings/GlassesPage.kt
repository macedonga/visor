package com.macedonga.visor.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.macedonga.visor.LoginKey
import com.macedonga.visor.OnboardingKey
import com.macedonga.visor.R
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.connection.LinkKind
import com.macedonga.visor.firmware.FirmwareUpdate
import com.macedonga.visor.firmware.FirmwareWork
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.connection.parseBuild
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.identity.IdentityBundle
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.home.LastSeen
import com.macedonga.visor.home.levelPercent
import com.macedonga.visor.home.pairName
import com.macedonga.visor.login.MetaLogin
import com.macedonga.visor.media.thermalWords
import com.macedonga.visor.pairs.LookProblem
import com.macedonga.visor.pairs.Looks
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.sizeWords
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class GlassesPageModel @Inject constructor(
    val connection: GlassesConnection,
    val settings: GlassesSettings,
    val login: MetaLogin,
    val firmware: FirmwareUpdate,
    val kind: LinkKind,
    private val store: IdentityStore,
    val looks: Looks,
    val lastSeen: LastSeen,
) : ViewModel() {
    private val _glasses = MutableStateFlow<List<IdentityBundle>>(emptyList())

    /** The glasses set up on this phone. */
    val glasses: StateFlow<List<IdentityBundle>> = _glasses.asStateFlow()

    fun refresh() {
        login.refresh()
        // The page asks Meta again for a look it does not have yet.
        if (looks.look.value == null && !looks.asking.value) looks.retry()
        viewModelScope.launch(Dispatchers.IO) { reload() }
    }

    fun forget(serial: String, then: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            store.remove(serial)
            lastSeen.forget(serial)
            reload()
            withContext(Dispatchers.Main) { then() }
        }
    }

    private fun reload() {
        _glasses.value = runCatching { store.all() }.getOrDefault(emptyList())
    }
}

/** The glasses themselves: how charged, what they are, how they are linked, whose they are. */
@Composable
fun GlassesPage(open: (NavKey) -> Unit, model: GlassesPageModel = hiltViewModel()) {
    val context = LocalContext.current
    val back = LocalBack.current
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
    val look by model.looks.look.collectAsStateWithLifecycle()
    val lookAsking by model.looks.asking.collectAsStateWithLifecycle()
    val lookProblem by model.looks.problem.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    val searching = running && !ready && glasses.link != LinkState.DISCONNECTED && glasses.link != LinkState.LOST
    val device = glasses.device
    val info = glasses.info
    val serial = glasses.target?.serial ?: info?.serial
    val name = pairName(serial?.let { seen[it] }, look)
    var renaming by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var firmwareOpen by remember { mutableStateOf(false) }
    val fwChecking by model.firmware.checking.collectAsStateWithLifecycle()
    val fwChecked by model.firmware.checked.collectAsStateWithLifecycle()
    val fwOffer by model.firmware.offer.collectAsStateWithLifecycle()
    val fwProblem by model.firmware.problem.collectAsStateWithLifecycle()
    val fwWork by model.firmware.work.collectAsStateWithLifecycle()
    val fwInstalling = fwWork is FirmwareWork.Installing || fwWork is FirmwareWork.Downloading || fwWork is FirmwareWork.Sending

    SettingsPage(
        name, light = 0.6f,
        sheet = {
            if (renaming) {
                TextSheet(
                    stringResource(R.string.glasses_name), serial?.let { seen[it]?.name }.orEmpty(),
                    onSave = { serial?.let { s -> model.lastSeen.rename(s, it.trim()) } },
                    onDismiss = { renaming = false },
                    hint = pairName(null, look),
                    valid = { it.isNotBlank() },
                    filter = { it.take(LastSeen.NAME_MAX) },
                )
            }
            if (forgetting && serial != null) {
                ConfirmSheet(
                    stringResource(R.string.glasses_forget_title), stringResource(R.string.glasses_forget_text), stringResource(R.string.glasses_forget),
                    onConfirm = {
                        GlassesService.stop(context)
                        model.forget(serial, back)
                    },
                    onDismiss = { forgetting = false },
                )
            }
            if (firmwareOpen) {
                FirmwareSheet(
                    current = info?.build?.let(::firmwareWords) ?: stringResource(R.string.settings_unknown),
                    offer = fwOffer?.let { o -> o.displayName.ifEmpty { o.targetVersion } },
                    status = when (val w = fwWork) {
                        is FirmwareWork.Failed -> stringResource(R.string.firmware_failed)
                        FirmwareWork.Installed -> stringResource(R.string.firmware_installed)
                        is FirmwareWork.Installing -> w.percent?.let { stringResource(R.string.firmware_progress, it) } ?: stringResource(R.string.firmware_installing)
                        is FirmwareWork.Downloading -> stringResource(R.string.firmware_downloading, sizeWords(context, w.done), sizeWords(context, w.total))
                        is FirmwareWork.Sending -> stringResource(R.string.firmware_sending, sizeWords(context, w.done), sizeWords(context, w.total))
                        FirmwareWork.Idle -> when {
                            fwChecking -> stringResource(R.string.firmware_checking)
                            fwProblem.isNotEmpty() -> stringResource(R.string.firmware_check_failed)
                            fwChecked && fwOffer == null -> stringResource(R.string.firmware_up_to_date)
                            else -> null
                        }
                    },
                    failed = fwWork is FirmwareWork.Failed || (fwWork == FirmwareWork.Idle && !fwChecking && fwProblem.isNotEmpty()),
                    installing = fwInstalling,
                    checking = fwChecking,
                    confirm = R.string.firmware_confirm,
                    onCheck = { model.firmware.check() },
                    onInstall = { model.firmware.install() },
                    onCancel = { model.firmware.cancel() },
                    onDismiss = { firmwareOpen = false; model.firmware.dismiss() },
                )
            }
        },
    ) {
        item(key = "power") {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Away, the last reading the home kept, dimmed.
                val last by model.lastSeen.battery.collectAsStateWithLifecycle()
                val glassesNow = device?.battery?.percent?.toInt()
                val caseNow = device?.case?.levelPercent()
                Readout(
                    stringResource(R.string.glasses_battery),
                    (glassesNow ?: last?.glasses)?.let { "$it%" },
                    device?.battery?.charging == true, fresh = glassesNow != null,
                    critical = glassesNow != null && glassesNow <= 10 && device.battery?.charging != true,
                )
                (caseNow ?: last?.case)?.let { case ->
                    Readout(
                        stringResource(R.string.glasses_case_label), "$case%",
                        device?.case?.charging == true, fresh = caseNow != null,
                    )
                }
            }
        }
        item(key = "identity") {
            val heat = device?.thermal?.let { heatWords(it.health, it.throttling) }
            Column {
                SectionLabel(stringResource(R.string.glasses_section_these))
                Rows(
                    {
                        ValueRow(
                            stringResource(R.string.glasses_name), name, onClick = { renaming = true }, locked = !ready,
                            busy = LastSeen.NAME_KEY in state.pending && LastSeen.NAME_KEY !in state.quiet,
                            problem = if (LastSeen.NAME_KEY in state.kept && LastSeen.NAME_KEY !in state.quiet) stringResource(R.string.settings_kept) else null,
                        )
                    },
                    // As Meta's records have them; nothing on the glasses says.
                    {
                        RowFrame(
                            stringResource(R.string.glasses_frame), onClick = null,
                            value = look?.let { it.styleName ?: it.style } ?: if (lookAsking) null else stringResource(R.string.settings_unknown),
                            busy = lookAsking,
                        ) {
                            if (look == null && !lookAsking && lookProblem == LookProblem.FAILED) {
                                ActionPill(stringResource(R.string.try_again), model.looks::retry, quiet = true, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    },
                    look?.let { l -> l.colorName ?: l.color }?.let { v -> @Composable { ValueRow(stringResource(R.string.glasses_color), v, onClick = null) } },
                    look?.let { l -> l.lensName ?: l.lens }?.let { v -> @Composable { ValueRow(stringResource(R.string.glasses_lenses), v, onClick = null) } },
                    {
                        ValueRow(
                            stringResource(R.string.glasses_firmware), info?.build?.let(::firmwareWords) ?: stringResource(R.string.settings_unknown),
                            onClick = { firmwareOpen = true }, locked = !ready && !fwInstalling,
                        )
                    },
                    { ValueRow(stringResource(R.string.glasses_serial), serial ?: stringResource(R.string.settings_unknown), onClick = null) },
                    heat?.let { v -> @Composable { ValueRow(stringResource(R.string.glasses_heat), v, onClick = null) } },
                    device?.storage?.let { s -> @Composable { ValueRow(stringResource(R.string.glasses_storage), stringResource(R.string.glasses_storage_free, sizeWords(context, s.freeMb * MIB), sizeWords(context, s.totalMb * MIB)), onClick = null) } },
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
                                    searching -> R.string.glasses_searching
                                    else -> R.string.glasses_link_off
                                },
                            ),
                            onClick = null,
                            busy = searching,
                        ) {
                            if (running) {
                                ActionPill(stringResource(R.string.glasses_disconnect), { GlassesService.disconnect(context) }, quiet = true, modifier = Modifier.padding(start = 12.dp))
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
                            onClick = null, busy = login == MetaLogin.Status.Working,
                        ) {
                            if (login == MetaLogin.Status.LoggedIn) {
                                ActionPill(stringResource(R.string.glasses_log_out), { model.login.logOut() }, quiet = true, modifier = Modifier.padding(start = 12.dp))
                            } else {
                                // The login page reads the request begin() leaves pending.
                                ActionPill(
                                    stringResource(R.string.onboard_login_button), { model.login.begin(); open(LoginKey) },
                                    modifier = Modifier.padding(start = 12.dp), enabled = login != MetaLogin.Status.Working,
                                )
                            }
                        }
                    },
                    { ValueRow(stringResource(R.string.glasses_change_owner), null, onClick = { open(OnboardingKey(login = true, address = (glasses.target?.link as? GlassesLink.Bluetooth)?.address)) }) },
                )
            }
        }
        item(key = "setup") {
            Column {
                SectionLabel(stringResource(R.string.glasses_section_setup))
                Rows(
                    {
                        ValueRow(
                            stringResource(if (serial == null && bundles.isEmpty()) R.string.home_set_up else R.string.glasses_set_up_new),
                            null, onClick = { open(OnboardingKey()) },
                        )
                    },
                    if (serial != null) row {
                        RowFrame(stringResource(R.string.glasses_forget), onClick = { forgetting = true })
                    } else null,
                )
            }
        }
    }
}

/** A firmware check, then an install after a yes. */
@Composable
internal fun FirmwareSheet(
    current: String,
    offer: String?,
    status: String?,
    failed: Boolean,
    installing: Boolean,
    checking: Boolean,
    @StringRes confirm: Int,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    VisorSheet(onDismiss) {
        SheetTitle(stringResource(R.string.firmware_title))
        Rows(
            { ValueRow(stringResource(R.string.firmware_current), current, onClick = null) },
            offer?.let { o -> @Composable { ValueRow(stringResource(R.string.firmware_update), o, onClick = null) } },
        )
        val asking = confirming && offer != null && !installing
        val line = if (asking) stringResource(confirm, offer.orEmpty()) else status
        Column(Modifier.padding(horizontal = SIDE).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (line != null) Text(line, style = Visor.type.label, color = if (failed && !asking) Visor.colors.signal else Visor.colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    installing -> ActionPill(stringResource(R.string.cancel), onCancel, quiet = true)
                    asking -> {
                        ActionPill(stringResource(R.string.firmware_install), { confirming = false; onInstall() })
                        ActionPill(stringResource(R.string.cancel), { confirming = false }, quiet = true)
                    }
                    else -> {
                        ActionPill(stringResource(R.string.firmware_check), onCheck, quiet = true, enabled = !checking)
                        if (offer != null) ActionPill(stringResource(R.string.firmware_install), { confirming = true })
                    }
                }
            }
        }
    }
}

/** A large reading with its name over it, and a bolt while it charges. */
@Composable
private fun Readout(label: String, value: String?, charging: Boolean, fresh: Boolean, critical: Boolean = false) {
    Column(Modifier.alpha(if (fresh) 1f else 0.45f)) {
        Text(label, style = Visor.type.label, color = Visor.colors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value ?: "–", style = Visor.type.readout, color = if (critical) Visor.colors.signal else Visor.colors.textPrimary)
            if (charging) Icon(VisorIcons.Bolt, stringResource(R.string.mood_charging), Modifier.padding(start = 6.dp).size(22.dp), tint = Visor.colors.textPrimary)
        }
    }
}

/** The firmware's own build number, or the whole fingerprint when it names none. */
internal fun firmwareWords(build: String): String = parseBuild(build)?.firmware?.ifEmpty { null } ?: build

// The glasses count their storage in mebibytes.
private const val MIB = 1_048_576L

@Composable
private fun heatWords(health: String, throttling: Boolean): String? {
    val words = stringResource(thermalWords(health) ?: return null)
    return if (throttling) stringResource(R.string.glasses_heat_throttling, words) else words
}
