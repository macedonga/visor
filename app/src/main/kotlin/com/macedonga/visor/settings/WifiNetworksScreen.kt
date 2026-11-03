package com.macedonga.visor.settings

import android.content.Context
import android.net.wifi.WifiManager
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
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesWifi
import com.macedonga.visor.core.session.LinkState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class WifiResult { SAVED, WRONG_PASSWORD, REMOVED, FAILED }

@HiltViewModel
class WifiNetworksModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val connection: GlassesConnection,
    private val wifi: GlassesWifi,
) : ViewModel() {

    // Null until the glasses answer.
    private val _networks = MutableStateFlow<List<String>?>(null)
    val networks: StateFlow<List<String>?> = _networks.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _result = MutableStateFlow<WifiResult?>(null)
    val result: StateFlow<WifiResult?> = _result.asStateFlow()

    /** The network the phone is on, when Android gives its name. */
    fun phoneNetwork(): String? {
        val info = context.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
        return info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    fun refresh() = task {
        _networks.value = wifi.saved() ?: _networks.value
    }

    fun save(ssid: String, passphrase: String) = task {
        _result.value = when (wifi.save(ssid, passphrase, open = false)) {
            0 -> WifiResult.SAVED
            6 -> WifiResult.WRONG_PASSWORD
            else -> WifiResult.FAILED
        }
        wifi.saved()?.let { _networks.value = it }
    }

    fun remove(ssid: String) = task {
        _result.value = if (wifi.remove(ssid) == 0) WifiResult.REMOVED else WifiResult.FAILED
        wifi.saved()?.let { _networks.value = it }
    }

    private fun task(work: suspend () -> Unit) {
        _busy.value = true
        viewModelScope.launch {
            try {
                work()
            } finally {
                _busy.value = false
            }
        }
    }
}

/** The Wi-Fi networks a Display's glasses keep, with the means to add and remove them. */
@Composable
fun WifiNetworksScreen(model: WifiNetworksModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    val networks by model.networks.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready) model.refresh() }

    SettingsPage(stringResource(R.string.wifi_networks_title), light = 0f) {
        item(key = "networks") {
            Column {
                val saved = networks.orEmpty()
                Rows(
                    *saved.map { ssid ->
                        @Composable {
                            RowFrame(ssid, onClick = null, locked = !ready) {
                                ActionPill(
                                    stringResource(R.string.wifi_remove), { model.remove(ssid) },
                                    quiet = true, modifier = Modifier.padding(start = 12.dp), enabled = ready && !busy,
                                )
                            }
                        }
                    }.toTypedArray(),
                    { ValueRow(stringResource(R.string.wifi_add), null, onClick = { adding = true }, locked = !ready, busy = busy) },
                )
                result?.let { r ->
                    Footnote(
                        stringResource(
                            when (r) {
                                WifiResult.SAVED -> R.string.wifi_saved
                                WifiResult.WRONG_PASSWORD -> R.string.wifi_wrong_password
                                WifiResult.REMOVED -> R.string.wifi_removed
                                WifiResult.FAILED -> R.string.wifi_failed
                            },
                        ),
                    )
                }
                if (networks != null && saved.isEmpty()) Footnote(stringResource(R.string.wifi_none))
                if (networks == null && ready && !busy) Footnote(stringResource(R.string.wifi_unread))
            }
        }
    }

    if (adding) {
        val phone = remember { model.phoneNetwork().orEmpty() }
        var name by remember { mutableStateOf(phone) }
        var password by remember { mutableStateOf("") }
        VisorSheet({ adding = false }) {
            SheetTitle(stringResource(R.string.wifi_add))
            SheetField(name, { name = it }, hint = stringResource(R.string.wifi_network_name), focus = name.isEmpty())
            Spacer(Modifier.height(10.dp))
            SheetField(password, { password = it }, hint = stringResource(R.string.wifi_password), secret = true, focus = name.isNotEmpty())
            Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                ActionPill(stringResource(R.string.settings_save), {
                    model.save(name.trim(), password)
                    adding = false
                }, enabled = name.isNotBlank() && password.isNotEmpty())
            }
        }
    }
}
