package com.macedonga.visor.settings

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.R
import com.macedonga.visor.connection.AddResult
import com.macedonga.visor.connection.WebApp
import com.macedonga.visor.connection.WebAppBluetooth
import com.macedonga.visor.connection.WebAppTools
import com.macedonga.visor.kit.protocol.Scope
import com.macedonga.visor.outside.KitConsentActivity
import com.macedonga.visor.outside.KitWebApps
import com.macedonga.visor.connection.WebApps
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.ConfigBusy
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.identity.appstore.CatalogueApp
import com.macedonga.visor.core.identity.appstore.fetchAppCatalogue
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.login.MetaLogin
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CatalogueState {
    data object Loading : CatalogueState
    data class Loaded(val apps: List<CatalogueApp>) : CatalogueState
    data object Failed : CatalogueState
}

@HiltViewModel
class WebAppsModel @Inject constructor(
    private val webApps: WebApps,
    val connection: GlassesConnection,
    private val login: MetaLogin,
    private val bluetooth: WebAppBluetooth,
    private val kit: KitWebApps,
    private val tools: WebAppTools,
) : ViewModel() {
    /** Whether web apps' tools reach the assistant; null before the glasses' configuration was read. */
    val toolsOn: StateFlow<Boolean?> = tools.on.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _toolsSaving = MutableStateFlow(false)
    val toolsSaving: StateFlow<Boolean> = _toolsSaving.asStateFlow()
    private val _toolsProblem = MutableStateFlow<String?>(null)
    val toolsProblem: StateFlow<String?> = _toolsProblem.asStateFlow()

    fun setTools(on: Boolean) {
        if (_toolsSaving.value) return
        _toolsSaving.value = true
        _toolsProblem.value = null
        viewModelScope.launch {
            try {
                _toolsProblem.value = tools.set(on)
            } finally {
                _toolsSaving.value = false
            }
        }
    }

    val apps: StateFlow<List<WebApp>> = webApps.apps

    /** The VDK scopes each web app's origin holds. */
    val access: StateFlow<Map<String, Set<Scope>>> = kit.grants

    /** The origins allowed to scan for Bluetooth devices. */
    val bluetoothOrigins: StateFlow<Set<String>> = bluetooth.allowed

    /** What the glasses' configuration is busy with while a save waits on it. */
    val configBusy: StateFlow<ConfigBusy?> = bluetooth.configBusy.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    // Why the last save did not reach the glasses.
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /**
     * Saves what [app] may do: Visor access reaches Visor through Bluetooth scans, so any scope turns Bluetooth on too.
     * [onSaved] hears when the glasses have it. It runs on in the model, so a sheet closed meanwhile cuts no write short.
     */
    fun save(app: WebApp, scan: Boolean, scopes: Set<Scope>, onSaved: () -> Unit) {
        val origin = WebAppBluetooth.originOf(app.url) ?: return
        if (_saving.value) return
        _saving.value = true
        _problem.value = null
        viewModelScope.launch {
            try {
                val why = bluetooth.allow(app.url, scan || scopes.isNotEmpty())
                if (why != null) {
                    _problem.value = why
                    return@launch
                }
                kit.setScopes(origin, scopes)
                onSaved()
            } finally {
                _saving.value = false
            }
        }
    }

    fun clearProblem() {
        _problem.value = null
    }

    // The words for what the last action came to, as a string id.
    private val _note = MutableStateFlow<Int?>(null)
    val note: StateFlow<Int?> = _note.asStateFlow()

    private val _catalogue = MutableStateFlow<CatalogueState>(CatalogueState.Loading)
    val catalogue: StateFlow<CatalogueState> = _catalogue.asStateFlow()

    fun add(name: String, url: String) {
        _note.value = noteOf(webApps.add(name, url))
    }

    fun clearNote() {
        _note.value = null
    }

    /** Fetch Meta's list for these glasses, with the user's Meta login. */
    fun browse() {
        _note.value = null
        val info = connection.state.value.info
        if (info == null || info.wearablesRelease.isEmpty()) {
            _catalogue.value = CatalogueState.Failed
            return
        }
        _catalogue.value = CatalogueState.Loading
        viewModelScope.launch {
            _catalogue.value = runCatching {
                withContext(Dispatchers.IO) { fetchAppCatalogue(login.source.get(), info.serial, info.wearablesRelease) }
            }.fold({ CatalogueState.Loaded(it) }, { CatalogueState.Failed })
        }
    }

    fun addFromCatalogue(app: CatalogueApp) {
        _note.value = noteOf(webApps.add(app.name, app.url, app.manifest))
    }

    /** Takes [app] off the glasses, and what it was allowed with it once no other app shares its origin. */
    fun remove(app: WebApp) {
        if (!webApps.remove(app.key)) {
            _note.value = R.string.connect_first
            return
        }
        _note.value = null
        val origin = WebAppBluetooth.originOf(app.url) ?: return
        if (apps.value.any { it.key != app.key && WebAppBluetooth.originOf(it.url) == origin }) return
        kit.setScopes(origin, emptySet())
        if (origin in bluetooth.allowed.value) viewModelScope.launch { bluetooth.allow(app.url, false) }
    }

    private fun noteOf(result: AddResult): Int? = when (result) {
        AddResult.ADDED, AddResult.EXISTS -> null
        AddResult.NOT_HTTPS -> R.string.webapps_not_https
        AddResult.FULL -> R.string.webapps_full
        AddResult.NOT_CONNECTED -> R.string.connect_first
        AddResult.UNREADABLE -> R.string.webapps_unreadable
    }

    fun open(app: WebApp) {
        viewModelScope.launch {
            _note.value = if (webApps.open(app)) R.string.webapps_opened else R.string.webapps_not_opened
        }
    }
}

/** The Display's web apps, as the glasses list them, and opening one on the glasses. */
@Composable
fun WebAppsScreen(model: WebAppsModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    val apps by model.apps.collectAsStateWithLifecycle()
    val note by model.note.collectAsStateWithLifecycle()
    val catalogue by model.catalogue.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<WebApp?>(null) }
    var selected by remember { mutableStateOf<WebApp?>(null) }
    val access by model.access.collectAsStateWithLifecycle()
    val scanning by model.bluetoothOrigins.collectAsStateWithLifecycle()

    SettingsPage(stringResource(R.string.webapps_title), light = 0f, sheet = {
        selected?.let { app ->
            AppSheet(
                app, model, ready,
                onRemove = {
                    selected = null
                    removing = app
                },
                onDismiss = {
                    selected = null
                    model.clearProblem()
                },
            )
        }
        if (browsing) {
            VisorSheet({ browsing = false }, full = true) {
                SheetTitle(stringResource(R.string.webapps_browse))
                when (val c = catalogue) {
                    CatalogueState.Loading -> Spinner(Modifier.padding(SIDE).padding(top = 16.dp))
                    CatalogueState.Failed -> {
                        Text(stringResource(R.string.webapps_catalogue_failed), style = Visor.type.label, color = Visor.colors.signal, modifier = Modifier.padding(horizontal = SIDE))
                        ActionPill(stringResource(R.string.try_again), model::browse, Modifier.padding(start = SIDE, top = 12.dp), quiet = true)
                    }
                    is CatalogueState.Loaded -> if (c.apps.isEmpty()) {
                        Text(stringResource(R.string.webapps_catalogue_none), style = Visor.type.label, color = Visor.colors.textSecondary, modifier = Modifier.padding(horizontal = SIDE))
                    } else {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            Rows(
                                *c.apps.map { app ->
                                    @Composable {
                                        val on = apps.any { it.url == app.url }
                                        RowFrame(app.name, onClick = null, sub = app.url, value = if (on) stringResource(R.string.webapps_on_glasses) else null) {
                                            if (!on) ActionPill(stringResource(R.string.webapps_add), { model.addFromCatalogue(app) }, quiet = true, modifier = Modifier.padding(start = 12.dp))
                                        }
                                    }
                                }.toTypedArray(),
                            )
                        }
                    }
                }
                note?.takeIf { it != R.string.webapps_opened }?.let { Footnote(stringResource(it), color = Visor.colors.signal) }
            }
        }

        if (adding) {
            var name by remember { mutableStateOf("") }
            var url by remember { mutableStateOf("https://") }
            VisorSheet({ adding = false }) {
                SheetTitle(stringResource(R.string.webapps_add))
                SheetField(name, { name = it }, hint = stringResource(R.string.webapps_name), focus = true)
                Spacer(Modifier.height(10.dp))
                SheetField(url, { url = it }, hint = stringResource(R.string.webapps_url))
                Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    ActionPill(stringResource(R.string.settings_save), {
                        model.add(name.trim(), url.trim())
                        adding = false
                    }, enabled = Uri.parse(url.trim()).let { it.scheme == "https" && !it.host.isNullOrEmpty() })
                }
            }
        }

        removing?.let { app ->
            ConfirmSheet(app.name, app.url, stringResource(R.string.webapps_remove), onConfirm = { model.remove(app) }, onDismiss = { removing = null })
        }
    }) {
        item(key = "apps") {
            val host = LocalHost.current
            val opened = stringResource(R.string.webapps_opened)
            LaunchedEffect(note) {
                if (note == R.string.webapps_opened) {
                    host.notice.show(opened)
                    model.clearNote()
                }
            }
            Column {
                Rows(
                    *apps.map { app ->
                        @Composable {
                            val origin = WebAppBluetooth.originOf(app.url)
                            val held = origin?.let { access[it] }.orEmpty()
                            val summary = when {
                                held.isNotEmpty() -> stringResource(R.string.webapps_summary_visor, scopeList(held))
                                origin != null && origin in scanning -> stringResource(R.string.webapps_summary_bluetooth)
                                else -> null
                            }
                            RowFrame(app.name, onClick = { selected = app }, sub = listOfNotNull(app.url, summary).joinToString(" · ")) {
                                ActionPill(
                                    stringResource(R.string.webapps_open), { model.open(app) }, quiet = true,
                                    modifier = Modifier.padding(start = 12.dp), enabled = ready,
                                )
                            }
                        }
                    }.toTypedArray(),
                    {
                        ValueRow(stringResource(R.string.webapps_add), null, onClick = {
                            model.clearNote()
                            adding = true
                        }, locked = !ready)
                    },
                    { ValueRow(stringResource(R.string.webapps_browse), null, onClick = { browsing = true; model.browse() }, locked = !ready) },
                )
                note?.takeIf { it != R.string.webapps_opened }?.let { Footnote(stringResource(it), color = Visor.colors.signal) }
                if (apps.isEmpty()) Footnote(stringResource(R.string.webapps_none))
            }
        }
        item(key = "tools") {
            val on by model.toolsOn.collectAsStateWithLifecycle()
            val saving by model.toolsSaving.collectAsStateWithLifecycle()
            val busy by model.configBusy.collectAsStateWithLifecycle()
            val problem by model.toolsProblem.collectAsStateWithLifecycle()
            Column(Modifier.padding(top = 14.dp)) {
                Rows({
                    ToggleRow(
                        stringResource(R.string.webapps_tools), on == true, model::setTools,
                        sub = when {
                            saving && busy == ConfigBusy.READING -> stringResource(R.string.webapps_saving_read)
                            saving -> stringResource(R.string.webapps_saving_send)
                            else -> stringResource(R.string.webapps_tools_sub)
                        },
                        busy = saving,
                        locked = !ready || saving,
                        problem = problem?.let { stringResource(R.string.webapps_bluetooth_failed, it) },
                    )
                })
            }
        }
    }
}

/**
 * One web app: what it may do, saved to the glasses in one go, and its removal. A save that has to read the glasses'
 * configuration first says so, because that takes a minute the first time.
 */
@Composable
private fun AppSheet(app: WebApp, model: WebAppsModel, ready: Boolean, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val origin = WebAppBluetooth.originOf(app.url)
    val access by model.access.collectAsStateWithLifecycle()
    val scanning by model.bluetoothOrigins.collectAsStateWithLifecycle()
    val saving by model.saving.collectAsStateWithLifecycle()
    val busy by model.configBusy.collectAsStateWithLifecycle()
    val problem by model.problem.collectAsStateWithLifecycle()
    var scan by remember(app.key) { mutableStateOf(origin != null && origin in scanning) }
    var scopes by remember(app.key) { mutableStateOf(origin?.let { access[it] }.orEmpty()) }
    VisorSheet(onDismiss) {
        SheetTitle(app.name, origin ?: app.url)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            ToggleRow(
                stringResource(R.string.webapps_bluetooth), scan || scopes.isNotEmpty(), { scan = it },
                sub = stringResource(R.string.webapps_bluetooth_sub),
                // Visor access travels over the scans, so they stay on with it.
                locked = scopes.isNotEmpty() || saving,
            )
            SectionLabel(stringResource(R.string.webapps_access))
            // Raw DataX stays with Developer mode's own clients.
            for (s in KitConsentActivity.KNOWN - Scope.RAW) {
                ToggleRow(
                    stringResource(KitConsentActivity.SCOPE_TEXT.getValue(s)), s in scopes,
                    { scopes = if (it) scopes + s else scopes - s },
                    locked = saving,
                )
            }
            Footnote(stringResource(R.string.webapps_access_note))
        }
        if (saving) {
            Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 12.dp)) {
                Spinner()
                Text(
                    stringResource(if (busy == ConfigBusy.READING) R.string.webapps_saving_read else R.string.webapps_saving_send),
                    style = Visor.type.label, color = Visor.colors.textSecondary, modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        problem?.let { Footnote(stringResource(R.string.webapps_bluetooth_failed, it), color = Visor.colors.signal) }
        SheetAnswers(
            stringResource(R.string.settings_save),
            { model.save(app, scan, scopes, onDismiss) },
            stringResource(R.string.webapps_remove),
            onRemove,
            enabled = ready && origin != null && !saving,
        )
    }
}

@Composable
private fun scopeList(scopes: Set<Scope>): String =
    scopes.map { scope -> KitConsentActivity.SCOPE_NAME[scope]?.let { stringResource(it) } ?: scope.id }.joinToString(", ")
