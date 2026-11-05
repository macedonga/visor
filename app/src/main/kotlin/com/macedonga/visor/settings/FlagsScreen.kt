package com.macedonga.visor.settings

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.ConfigBusy
import com.macedonga.visor.core.media.ConfigNote
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.ui.VisorIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

@HiltViewModel
class FlagsViewModel @Inject constructor(
    val config: GlassesConfig,
    val connection: GlassesConnection,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _notes = MutableStateFlow<Map<String, FlagNote>>(emptyMap())

    /** What each checked param does. */
    val notes: StateFlow<Map<String, FlagNote>> = _notes.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { _notes.value = runCatching { parseNotes() }.getOrDefault(emptyMap()) }
    }

    private fun parseNotes(): Map<String, FlagNote> {
        val json = JSONObject(context.assets.open("mobileconfig_notes.json").bufferedReader().use { it.readText() })
        return json.keys().asSequence().associateWith { k ->
            json.getJSONObject(k).let { FlagNote(it.getString("product"), it.getString("does"), it.optString("change").ifEmpty { null }) }
        }
    }
}

class FlagNote(val product: String, val does: String, val change: String?)

/** Meta's server-side switches (MobileConfig) on the glasses, one by one. */
@Composable
fun FlagsScreen(model: FlagsViewModel = hiltViewModel()) {
    val state by model.config.state.collectAsStateWithLifecycle()
    val notes by model.notes.collectAsStateWithLifecycle()
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ready = glasses.link == LinkState.READY
    val idle = ready && state.busy == null
    var pending by remember { mutableStateOf(mapOf<String, Any>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var details by remember { mutableStateOf<String?>(null) }
    // Whatever the glasses already hold is not a change.
    val changes = pending.filter { (k, v) -> state.values[k] != v }
    fun value(name: String) = changes[name] ?: state.values[name]
    val checked = notes.keys.filter { state.params[it]?.type == 1 }
        .groupBy { notes.getValue(it).product }
        .toSortedMap(compareBy<String>({ PRODUCT_ORDER.indexOf(it).let { i -> if (i < 0) PRODUCT_ORDER.size else i } }, { it }))
    val needle = query.trim().lowercase().replace(' ', '_')
    val found = if (needle.length >= 2) state.params.values.filter { it.type == 1 && needle in it.name }.map { it.name }.sorted() else null

    SettingsPage(
        stringResource(R.string.settings_flags),
        light = 0.3f,
        actions = {
            if (state.busy != null) Spinner(Modifier.padding(horizontal = 13.dp))
            else if (ready && state.params.isNotEmpty()) RoundIcon(VisorIcons.Refresh, stringResource(R.string.advanced_read_again), { model.config.read() })
        },
        sheet = {
            details?.let { n ->
                VisorSheet({ details = null }) { SheetTitle(n, notes[n]?.let { listOfNotNull(it.does, it.change).joinToString(" ") }) }
            }
        },
    ) {
        item(key = "state") {
            Column {
                state.readAt?.let { at ->
                    val time = DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                    Rows({ ValueRow(stringResource(R.string.flags_last_read), time, onClick = null, dense = true) })
                }
                state.busy?.let { Footnote(busyText(it)) }
                state.note?.let { Footnote(noteText(it)) }
                if (!ready) Footnote(stringResource(R.string.settings_connect_to_change))
            }
        }
        if (state.params.isEmpty()) {
            item(key = "read") {
                Row(Modifier.padding(horizontal = SIDE).padding(top = 14.dp)) {
                    ActionPill(stringResource(R.string.flags_read), { model.config.read() }, enabled = idle)
                }
            }
        } else {
            for ((product, names) in checked) {
                item(key = "product:$product") { SectionLabel(product.replaceFirstChar { it.titlecase(LocalLocale.current.platformLocale) }) }
                cardItems(names.sorted(), { "c:$it" }) { n ->
                    ToggleRow(n.substringAfter('.'), value(n) == true, { on -> pending = pending + (n to on) }, onLongClick = { details = n }, keyLine = n, dense = true)
                }
            }
            item(key = "all") {
                Column {
                    SectionLabel(stringResource(R.string.flags_all))
                    SearchField(query, { query = it }, stringResource(R.string.flags_search), Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
                    if (found != null) {
                        if (found.isEmpty()) Footnote(stringResource(R.string.nothing_found))
                        Rows(found.take(SHOWN)) { n ->
                            ToggleRow(n.substringAfter('.'), value(n) == true, { on -> pending = pending + (n to on) }, onLongClick = { details = n }, keyLine = n, dense = true)
                        }
                        if (found.size > SHOWN) Footnote(pluralStringResource(R.plurals.flags_more, found.size - SHOWN, found.size - SHOWN))
                    }
                }
            }
            if (changes.isNotEmpty()) {
                item(key = "changes") {
                    Column {
                        SectionLabel(stringResource(R.string.flags_to_send))
                        Rows(changes.keys.toList()) { k ->
                            ValueRow(k, stringResource(if (changes[k] == true) R.string.sum_on else R.string.sum_off), onClick = null, dense = true)
                        }
                        Row(Modifier.padding(horizontal = SIDE).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionPill(stringResource(R.string.flags_send), { model.config.apply(changes) }, enabled = idle)
                            ActionPill(stringResource(R.string.flags_discard), { pending = emptyMap() }, quiet = true)
                        }
                    }
                }
            }
            if (state.baseline != null) {
                item(key = "baseline") {
                    Row(Modifier.padding(horizontal = SIDE).padding(top = 28.dp)) {
                        ActionPill(stringResource(R.string.flags_restore), { pending = emptyMap(); model.config.restore() }, quiet = true, enabled = idle)
                    }
                }
            }
        }
    }
}

@Composable
private fun busyText(b: ConfigBusy) = stringResource(if (b == ConfigBusy.READING) R.string.flags_busy_reading else R.string.features_sending)

@Composable
private fun noteText(n: ConfigNote) = when (n.kind) {
    ConfigNote.Kind.NOT_READ -> stringResource(R.string.flags_note_not_read)
    ConfigNote.Kind.SENT -> stringResource(R.string.flags_note_sent)
    ConfigNote.Kind.RESENT -> stringResource(R.string.flags_note_resent)
    ConfigNote.Kind.NOT_SENT -> stringResource(R.string.flags_note_not_sent, n.why)
}

private const val SHOWN = 60

// Which glasses a param is for, as its namespace says; the firmware is one image for all of them.
private val PRODUCT_ORDER = listOf("glasses", "Ray-Ban Meta", "Oakley", "Ray-Ban Display", "neural band", "Meta AI app", "iPhone app")
