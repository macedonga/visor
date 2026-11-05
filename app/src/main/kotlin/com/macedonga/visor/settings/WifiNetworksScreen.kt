package com.macedonga.visor.settings

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.macedonga.visor.core.connection.WIFI_STATUS_OK
import com.macedonga.visor.core.connection.WIFI_STATUS_WRONG_PASSWORD
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.session.LinkState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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

    // True once a read of the list has finished, so a failed one shows only after it.
    private val _tried = MutableStateFlow(false)
    val tried: StateFlow<Boolean> = _tried.asStateFlow()

    /** The network the phone is on, when Android gives its name. */
    fun phoneNetwork(): String? {
        val info = context.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
        return info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    fun refresh() = task {
        _networks.value = wifi.saved() ?: _networks.value
        _tried.value = true
    }

    /** Saves [ssid], as an open network when [passphrase] is empty; [onDone] hears the outcome on the main thread. */
    fun save(ssid: String, passphrase: String, onDone: (WifiResult) -> Unit) = task {
        val status = try {
            wifi.save(ssid, passphrase, open = passphrase.isEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val r = when (status) {
            WIFI_STATUS_OK -> WifiResult.SAVED
            WIFI_STATUS_WRONG_PASSWORD -> WifiResult.WRONG_PASSWORD
            else -> WifiResult.FAILED
        }
        if (r == WifiResult.SAVED) _result.value = r
        onDone(r)
        wifi.saved()?.let { _networks.value = it }
    }

    fun remove(ssid: String) = task {
        _result.value = if (wifi.remove(ssid) == WIFI_STATUS_OK) WifiResult.REMOVED else WifiResult.FAILED
        wifi.saved()?.let { _networks.value = it }
    }

    fun clearResult() {
        _result.value = null
    }

    private fun task(work: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _result.value = WifiResult.FAILED
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
    val tried by model.tried.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ready) { if (ready) model.refresh() }

    SettingsPage(stringResource(R.string.wifi_networks_title), light = 0f, sheet = {
        if (adding) {
            val phone = remember { model.phoneNetwork().orEmpty() }
            val nameFirst = remember { phone.isEmpty() }
            var name by remember { mutableStateOf(phone) }
            var password by remember { mutableStateOf("") }
            var open by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf<WifiResult?>(null) }
            VisorSheet({ adding = false }) {
                SheetTitle(stringResource(R.string.wifi_add))
                SheetField(name, { name = it; failed = null }, hint = stringResource(R.string.wifi_network_name), focus = nameFirst)
                Box(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 10.dp)) {
                    Pills(listOf(false to stringResource(R.string.wifi_password), true to stringResource(R.string.wifi_open)), open) { open = it; failed = null }
                }
                if (!open) {
                    SheetField(password, { password = it; failed = null }, hint = stringResource(R.string.wifi_password_hint), secret = true, focus = !nameFirst)
                }
                failed?.let { r ->
                    Text(
                        stringResource(if (r == WifiResult.WRONG_PASSWORD) R.string.wifi_wrong_password else R.string.wifi_failed),
                        style = Visor.type.label,
                        color = Visor.colors.signal,
                        modifier = Modifier.padding(horizontal = SIDE).padding(top = 8.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) Spinner(Modifier.padding(end = 12.dp))
                    ActionPill(stringResource(R.string.settings_save), {
                        model.save(name.trim(), if (open) "" else password) { r -> if (r == WifiResult.SAVED) adding = false else failed = r }
                    }, enabled = name.isNotBlank() && (open || password.length >= 8) && !busy)
                }
            }
        }

        removing?.let { ssid ->
            ConfirmSheet(ssid, null, stringResource(R.string.wifi_remove), onConfirm = { model.remove(ssid) }, onDismiss = { removing = null })
        }
    }) {
        item(key = "networks") {
            val host = LocalHost.current
            val savedWords = stringResource(R.string.wifi_saved)
            val removedWords = stringResource(R.string.wifi_removed)
            LaunchedEffect(result) {
                val words = when (result) {
                    WifiResult.SAVED -> savedWords
                    WifiResult.REMOVED -> removedWords
                    else -> null
                } ?: return@LaunchedEffect
                host.notice.show(words)
                model.clearResult()
            }
            Column {
                val saved = networks.orEmpty()
                Rows(
                    *saved.map { ssid ->
                        @Composable {
                            RowFrame(ssid, onClick = null, locked = !ready) {
                                ActionPill(
                                    stringResource(R.string.wifi_remove), { removing = ssid },
                                    quiet = true, modifier = Modifier.padding(start = 12.dp), enabled = ready && !busy,
                                )
                            }
                        }
                    }.toTypedArray(),
                    { ValueRow(stringResource(R.string.wifi_add), null, onClick = { adding = true }, locked = !ready, busy = busy) },
                )
                if (result == WifiResult.FAILED) Footnote(stringResource(R.string.wifi_failed), color = Visor.colors.signal)
                if (networks != null && saved.isEmpty()) Footnote(stringResource(R.string.wifi_none))
                if (tried && networks == null && ready && !busy) {
                    Footnote(stringResource(R.string.wifi_unread))
                    ActionPill(stringResource(R.string.try_again), { model.refresh() }, Modifier.padding(start = 32.dp, top = 10.dp), quiet = true)
                }
            }
        }
    }
}
