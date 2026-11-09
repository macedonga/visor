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
import com.macedonga.visor.connection.WebApps
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.identity.appstore.CatalogueApp
import com.macedonga.visor.core.identity.appstore.fetchAppCatalogue
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.login.MetaLogin
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {
    val apps: StateFlow<List<WebApp>> = webApps.apps

    /** The origins allowed to scan for Bluetooth devices. */
    val bluetoothOrigins: StateFlow<Set<String>> = bluetooth.allowed

    // The origin whose Bluetooth choice is on its way to the glasses, and why the last one did not get there.
    private val _bluetoothBusy = MutableStateFlow<String?>(null)
    val bluetoothBusy: StateFlow<String?> = _bluetoothBusy.asStateFlow()
    private val _bluetoothProblem = MutableStateFlow<Pair<String, String>?>(null)
    val bluetoothProblem: StateFlow<Pair<String, String>?> = _bluetoothProblem.asStateFlow()

    fun setBluetooth(app: WebApp, on: Boolean) {
        val origin = WebAppBluetooth.originOf(app.url) ?: return
        if (_bluetoothBusy.value != null) return
        _bluetoothBusy.value = origin
        _bluetoothProblem.value = null
        viewModelScope.launch {
            val why = bluetooth.allow(app.url, on)
            _bluetoothProblem.value = why?.let { origin to it }
            _bluetoothBusy.value = null
        }
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

    fun remove(key: String) {
        _note.value = if (webApps.remove(key)) null else R.string.connect_first
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

    SettingsPage(stringResource(R.string.webapps_title), light = 0f, sheet = {
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
            ConfirmSheet(app.name, app.url, stringResource(R.string.webapps_remove), onConfirm = { model.remove(app.key) }, onDismiss = { removing = null })
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
                            RowFrame(app.name, onClick = null, sub = app.url) {
                                ActionPill(
                                    stringResource(R.string.webapps_open), { model.open(app) }, quiet = true,
                                    modifier = Modifier.padding(start = 12.dp), enabled = ready,
                                )
                                ActionPill(
                                    stringResource(R.string.webapps_remove), { removing = app }, quiet = true,
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
        if (apps.isNotEmpty()) item(key = "bluetooth") {
            val allowed by model.bluetoothOrigins.collectAsStateWithLifecycle()
            val busy by model.bluetoothBusy.collectAsStateWithLifecycle()
            val problem by model.bluetoothProblem.collectAsStateWithLifecycle()
            Column {
                SectionLabel(stringResource(R.string.webapps_bluetooth))
                Rows(
                    *apps.map { app ->
                        @Composable {
                            val origin = WebAppBluetooth.originOf(app.url)
                            ToggleRow(
                                app.name, origin != null && origin in allowed, { on -> model.setBluetooth(app, on) },
                                sub = origin,
                                busy = origin != null && busy == origin,
                                locked = origin == null || !ready,
                                problem = problem?.takeIf { it.first == origin }?.second?.let { stringResource(R.string.webapps_bluetooth_failed, it) },
                            )
                        }
                    }.toTypedArray(),
                )
                Footnote(stringResource(R.string.webapps_bluetooth_note))
            }
        }
    }
}
