package com.macedonga.visor.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.macedonga.visor.connection.OPEN_GATES
import com.macedonga.visor.connection.OpenGate
import com.macedonga.visor.connection.WebApp
import com.macedonga.visor.connection.WebApps
import com.macedonga.visor.connection.gateOf
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.session.LinkState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class WebAppNote { SAVED_ON_PHONE, OPENED, NOT_OPENED, RESTART, SWITCH_NOT_SENT, NOT_HTTPS, FULL }

@HiltViewModel
class WebAppsModel @Inject constructor(
    private val webApps: WebApps,
    val connection: GlassesConnection,
    private val config: GlassesConfig,
) : ViewModel() {
    val apps: StateFlow<List<WebApp>> = webApps.apps
    val gate: StateFlow<OpenGate> = config.state.map { gateOf(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, gateOf(config.state.value))

    private val _note = MutableStateFlow<WebAppNote?>(null)
    val note: StateFlow<WebAppNote?> = _note.asStateFlow()

    fun sync() {
        webApps.push()
    }

    fun add(name: String, url: String) {
        _note.value = when (webApps.add(name, url)) {
            AddResult.ADDED, AddResult.EXISTS -> if (webApps.push()) null else WebAppNote.SAVED_ON_PHONE
            AddResult.NOT_HTTPS -> WebAppNote.NOT_HTTPS
            AddResult.FULL -> WebAppNote.FULL
        }
    }

    fun remove(key: String) {
        webApps.remove(key)
        _note.value = if (webApps.push()) null else WebAppNote.SAVED_ON_PHONE
    }

    fun open(app: WebApp) {
        viewModelScope.launch {
            _note.value = if (webApps.open(app)) WebAppNote.OPENED else WebAppNote.NOT_OPENED
        }
    }

    /** Switch the two gates on by MobileConfig: the glasses need a restart to use them. */
    fun turnOn() {
        viewModelScope.launch {
            _note.value = if (config.send(OPEN_GATES.associateWith { true }) == null) WebAppNote.RESTART else WebAppNote.SWITCH_NOT_SENT
        }
    }
}

/** The web apps Visor lists for the Display, and opening one on the glasses. */
@Composable
fun WebAppsScreen(model: WebAppsModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    val apps by model.apps.collectAsStateWithLifecycle()
    val gate by model.gate.collectAsStateWithLifecycle()
    val note by model.note.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready) model.sync() }

    SettingsPage(stringResource(R.string.webapps_title), light = 0f) {
        item(key = "apps") {
            Column {
                Rows(
                    *apps.map { app ->
                        @Composable {
                            RowFrame(app.name, onClick = null, sub = app.url) {
                                ActionPill(
                                    stringResource(R.string.webapps_open), { model.open(app) }, quiet = true,
                                    modifier = Modifier.padding(start = 12.dp), enabled = ready && gate == OpenGate.ON,
                                )
                                ActionPill(
                                    stringResource(R.string.webapps_remove), { model.remove(app.key) }, quiet = true,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }.toTypedArray(),
                    { ValueRow(stringResource(R.string.webapps_add), null, onClick = { adding = true }) },
                    if (gate == OpenGate.OFF) row { ValueRow(stringResource(R.string.webapps_turn_on), null, onClick = { model.turnOn() }, locked = !ready) } else null,
                )
                when (gate) {
                    OpenGate.UNAVAILABLE -> Footnote(stringResource(R.string.webapps_unavailable))
                    OpenGate.UNREAD -> Footnote(stringResource(R.string.webapps_unread))
                    OpenGate.OFF, OpenGate.ON -> Unit
                }
                note?.let { Footnote(stringResource(noteText(it))) }
                if (apps.isEmpty()) Footnote(stringResource(R.string.webapps_none))
            }
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
    WebAppNote.SAVED_ON_PHONE -> R.string.webapps_saved_on_phone
    WebAppNote.OPENED -> R.string.webapps_opened
    WebAppNote.NOT_OPENED -> R.string.webapps_not_opened
    WebAppNote.RESTART -> R.string.webapps_restart
    WebAppNote.SWITCH_NOT_SENT -> R.string.webapps_switch_not_sent
    WebAppNote.NOT_HTTPS -> R.string.webapps_not_https
    WebAppNote.FULL -> R.string.webapps_full
}
