package com.macedonga.visor.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import com.macedonga.visor.connection.WebApps
import com.macedonga.visor.core.connection.GlassesConnection
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

enum class WebAppNote { NOT_CONNECTED, UNREADABLE, OPENED, NOT_OPENED, NOT_HTTPS, FULL }

sealed interface CatalogueState {
    data object Idle : CatalogueState
    data object Loading : CatalogueState
    data class Loaded(val apps: List<CatalogueApp>) : CatalogueState
    data object Failed : CatalogueState
}

@HiltViewModel
class WebAppsModel @Inject constructor(
    private val webApps: WebApps,
    val connection: GlassesConnection,
    private val login: MetaLogin,
) : ViewModel() {
    val apps: StateFlow<List<WebApp>> = webApps.apps

    private val _note = MutableStateFlow<WebAppNote?>(null)
    val note: StateFlow<WebAppNote?> = _note.asStateFlow()

    private val _catalogue = MutableStateFlow<CatalogueState>(CatalogueState.Idle)
    val catalogue: StateFlow<CatalogueState> = _catalogue.asStateFlow()

    fun add(name: String, url: String) {
        _note.value = noteOf(webApps.add(name, url))
    }

    /** Fetch Meta's list for these glasses, with the user's Meta login. */
    fun browse() {
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
        _note.value = if (webApps.remove(key)) null else WebAppNote.NOT_CONNECTED
    }

    private fun noteOf(result: AddResult): WebAppNote? = when (result) {
        AddResult.ADDED, AddResult.EXISTS -> null
        AddResult.NOT_HTTPS -> WebAppNote.NOT_HTTPS
        AddResult.FULL -> WebAppNote.FULL
        AddResult.NOT_CONNECTED -> WebAppNote.NOT_CONNECTED
        AddResult.UNREADABLE -> WebAppNote.UNREADABLE
    }

    fun open(app: WebApp) {
        viewModelScope.launch {
            _note.value = if (webApps.open(app)) WebAppNote.OPENED else WebAppNote.NOT_OPENED
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

    SettingsPage(stringResource(R.string.webapps_title), light = 0f) {
        item(key = "apps") {
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
                                    stringResource(R.string.webapps_remove), { model.remove(app.key) }, quiet = true,
                                    modifier = Modifier.padding(start = 12.dp), enabled = ready,
                                )
                            }
                        }
                    }.toTypedArray(),
                    { ValueRow(stringResource(R.string.webapps_add), null, onClick = { adding = true }, locked = !ready) },
                    { ValueRow(stringResource(R.string.webapps_browse), null, onClick = { browsing = true; model.browse() }, locked = !ready) },
                )
                note?.let { Footnote(stringResource(noteText(it))) }
                if (apps.isEmpty()) Footnote(stringResource(R.string.webapps_none))
            }
        }
    }

    if (browsing) {
        VisorSheet({ browsing = false }, full = true) {
            SheetTitle(stringResource(R.string.webapps_browse))
            when (val c = catalogue) {
                CatalogueState.Idle, CatalogueState.Loading -> Spinner(Modifier.padding(SIDE).padding(top = 16.dp))
                CatalogueState.Failed -> Footnote(stringResource(R.string.webapps_catalogue_failed))
                is CatalogueState.Loaded -> if (c.apps.isEmpty()) {
                    Footnote(stringResource(R.string.webapps_catalogue_none))
                } else {
                    Rows(
                        *c.apps.map { app ->
                            @Composable {
                                val on = apps.any { it.url == app.url }
                                RowFrame(app.name, onClick = null, sub = app.url) {
                                    if (on) {
                                        ActionPill(stringResource(R.string.webapps_on_glasses), {}, quiet = true, modifier = Modifier.padding(start = 12.dp), enabled = false)
                                    } else {
                                        ActionPill(stringResource(R.string.webapps_add), { model.addFromCatalogue(app) }, quiet = true, modifier = Modifier.padding(start = 12.dp))
                                    }
                                }
                            }
                        }.toTypedArray(),
                    )
                }
            }
            note?.let { Footnote(stringResource(noteText(it))) }
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
                }, enabled = url.isNotBlank())
            }
        }
    }
}

private fun noteText(note: WebAppNote): Int = when (note) {
    WebAppNote.NOT_CONNECTED -> R.string.webapps_not_connected
    WebAppNote.UNREADABLE -> R.string.webapps_unreadable
    WebAppNote.OPENED -> R.string.webapps_opened
    WebAppNote.NOT_OPENED -> R.string.webapps_not_opened
    WebAppNote.NOT_HTTPS -> R.string.webapps_not_https
    WebAppNote.FULL -> R.string.webapps_full
}
