package com.macedonga.visor.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.settings.Control
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.Kind
import com.macedonga.visor.core.settings.SettingInfo
import com.macedonga.visor.core.settings.SettingSpec
import com.macedonga.visor.core.settings.SettingsState
import com.macedonga.visor.core.settings.asBool
import com.macedonga.visor.core.settings.display
import com.macedonga.visor.core.settings.duration
import com.macedonga.visor.core.settings.number
import com.macedonga.visor.core.settings.offeredOn
import com.macedonga.visor.core.settings.protectedReason
import com.macedonga.visor.core.settings.same
import com.macedonga.visor.core.settings.unitSuffix
import com.macedonga.visor.features.Feature
import com.macedonga.visor.features.FeatureDetails
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

/** Every key Visor knows of: the tables, the mapping, and whatever the glasses hold beyond them. */
internal fun allKeys(settings: GlassesSettings, state: SettingsState): Set<String> =
    settings.catalogue.keys + settings.specs.keys + state.stored.keys

/** The mapping for [key], or a read-only stand-in for a key the glasses hold that nothing maps. */
internal fun specOf(settings: GlassesSettings, key: String): SettingSpec = settings.specs[key] ?: SettingSpec(
    key = key,
    label = key.replace('_', ' ').replaceFirstChar { it.uppercase() },
    note = null,
    group = OTHER,
    control = Control.ReadOnly("Not in the mapping."),
    invert = false,
    userFacing = false,
    confidence = "low",
)

internal const val OTHER = "Other"

/** The value in force: being written, else stored, else the firmware default. */
internal fun SettingsState.valueOf(key: String, info: SettingInfo?): String? = pending[key] ?: stored[key] ?: info?.default

/** Set on the glasses to something other than the default. */
internal fun SettingsState.changed(key: String, info: SettingInfo?): Boolean {
    val have = stored[key] ?: return false
    val default = info?.default ?: return true
    return !same(info.kind, have, default)
}

/** Read the settings when the link comes up, fully the first time and as a delta after. */
@Composable
internal fun ReadSettingsWhenReady(settings: GlassesSettings, ready: Boolean) {
    LaunchedEffect(ready) {
        if (ready) settings.read(full = settings.state.value.readAt == null)
    }
}

/**
 * One glasses setting as its mapping calls for: a switch, pills, a slider, or a value that opens a sheet. Away from
 * the glasses it shows the value last read, locked. [engineering] adds the firmware's key and the stored value.
 */
@Composable
fun GlassesRow(key: String, engineering: Boolean = false, overline: String? = null) {
    val host = LocalHost.current
    val settings = host.settings
    val state = host.state
    val info = settings.catalogue[key]
    val spec = specOf(settings, key)
    val value = state.valueOf(key, info)
    val writable = info?.writable == true && spec.control !is Control.ReadOnly
    val locked = writable && !host.ready
    val changed = state.changed(key, info)
    // Sent and not yet seen back: a ring turns, and after a while a line says the glasses have not answered.
    val busy = key in state.pending
    var slow by remember(key) { mutableStateOf(false) }
    LaunchedEffect(busy) {
        slow = false
        if (busy) {
            delay(SLOW_MS)
            slow = true
        }
    }
    val problem = when {
        key in state.kept -> stringResource(R.string.settings_kept)
        busy && slow -> stringResource(R.string.settings_no_answer)
        else -> null
    }
    val keyLine = if (engineering) "$key = " + (state.stored[key]?.let { "\"$it\"" } ?: stringResource(R.string.settings_raw_default, info?.default ?: "?")) else null
    val dense = engineering
    val long = { host.openDetails(Pins.glasses(key)) }
    val refused = stringResource(R.string.settings_not_sent)
    val write: (String) -> Unit = { if (!settings.write(key, it)) host.notice.show(refused) }
    var sheet by remember { mutableStateOf(false) }

    if (!writable || value == null) {
        ValueRow(
            spec.label, if (value == null) stringResource(R.string.settings_not_read) else spec.display(value), onClick = null, onLongClick = long,
            changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy,
            dim = !host.ready,
        )
        return
    }
    when (val c = spec.control) {
        Control.Toggle -> ToggleRow(
            spec.label, on = asBool(value) != spec.invert,
            onChange = { on ->
                val bit = on != spec.invert
                // Some flags are ints on the wire; those take 1 and 0.
                write(if (info?.kind == Kind.INT) (if (bit) "1" else "0") else bit.toString())
            },
            onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy,
        )

        is Control.Choice -> {
            val current = value.trim()
            // Options the Meta app keeps for other glasses show only in Advanced.
            val shown = c.options.filter { engineering || it.value == current || (!it.hidden && it.offeredOn(state.product)) }
            if (shown.size <= 4 && shown.all { it.label.length <= 16 && it.detail == null } && shown.any { it.value == current }) {
                PillsRow(
                    spec.label, shown.map { it.value to it.label }, current, onPick = write,
                    onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy,
                )
            } else {
                ValueRow(spec.label, spec.display(value), onClick = { sheet = true }, onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy)
                if (sheet) {
                    ChoiceSheet(
                        spec.label, shown.map { Triple(it.value, it.label, it.detail) }, current,
                        onPick = write, onDismiss = { sheet = false }, sub = spec.note,
                    )
                }
            }
        }

        is Control.Range -> {
            val now = value.trim().toDoubleOrNull() ?: c.min
            val notches = ((c.max - c.min) / c.step).roundToLong()
            fun snap(x: Double): Double = (c.min + Math.round((x - c.min) / c.step) * c.step).coerceIn(c.min, c.max)
            SliderRow(
                spec.label, now.toFloat(), c.min.toFloat()..c.max.toFloat(), if (notches in 2..100) (notches - 1).toInt() else 0,
                words = { number(snap(it.toDouble())) + unitSuffix(c.unit) },
                onDone = { x -> val s = snap(x.toDouble()); write(if (info?.kind == Kind.INT) s.roundToLong().toString() else number(s)) },
                onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy,
            )
        }

        is Control.Duration -> {
            val now = value.trim().toLongOrNull()
            if (c.options.isNotEmpty()) {
                val options = c.options.map { it to duration(it, c.unit) }
                if (options.size <= 5 && now in c.options) {
                    PillsRow(spec.label, options, now, onPick = { write(it.toString()) }, onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy)
                } else {
                    ValueRow(spec.label, spec.display(value), onClick = { sheet = true }, onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy)
                    if (sheet) ChoiceSheet(spec.label, options.map { Triple(it.first, it.second, null) }, now, onPick = { write(it.toString()) }, onDismiss = { sheet = false }, sub = spec.note)
                }
            } else {
                val min = c.min ?: 0L
                val max = c.max ?: maxOf(min + 1, (now ?: min) * 2)
                // About a hundred notches across the range, rounded to a whole second or minute where it fits.
                val raw = maxOf(1L, (max - min) / 100)
                val step = listOf(1L, 5L, 10L, 50L, 100L, 500L, 1_000L, 5_000L, 10_000L, 60_000L).lastOrNull { it <= raw } ?: raw
                fun snap(x: Float): Long = (min + ((x - min) / step).roundToLong() * step).coerceIn(min, max)
                SliderRow(
                    spec.label, (now ?: min).toFloat(), min.toFloat()..max.toFloat(), 0,
                    words = { duration(snap(it), c.unit) }, onDone = { write(snap(it).toString()) },
                    onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy,
                )
            }
        }

        is Control.Number -> {
            ValueRow(spec.label, spec.display(value), onClick = { sheet = true }, onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy)
            if (sheet) {
                val integer = info?.kind == Kind.INT
                TextSheet(
                    spec.label, value.trim(), onSave = write, onDismiss = { sheet = false }, sub = spec.note,
                    keyboard = if (integer) KeyboardType.Number else KeyboardType.Decimal,
                    filter = { t -> t.filter { it.isDigit() || it == '-' || (!integer && it == '.') } },
                    valid = { t ->
                        val x = if (integer) t.toLongOrNull()?.toDouble() else t.toDoubleOrNull()
                        x != null && c.min.let { it == null || x >= it } && c.max.let { it == null || x <= it }
                    },
                )
            }
        }

        is Control.Text -> {
            ValueRow(spec.label, value.ifEmpty { stringResource(R.string.settings_not_set) }, onClick = { sheet = true }, onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy)
            if (sheet) {
                val uuid = c.what == "uuid"
                TextSheet(
                    spec.label, value, onSave = { write(it.trim()) }, onDismiss = { sheet = false }, sub = spec.note,
                    singleLine = c.what != "prompt",
                    secret = c.what == "password",
                    keyboard = when (c.what) {
                        "phone number" -> KeyboardType.Phone
                        "password" -> KeyboardType.Password
                        "uri" -> KeyboardType.Uri
                        "build number" -> KeyboardType.Number
                        else -> KeyboardType.Text
                    },
                    filter = { it.take(c.max) },
                    valid = { t ->
                        when {
                            uuid -> t.isEmpty() || UUID_SHAPE.matches(t.trim())
                            c.what == "phone number" -> t.all { it.isDigit() || it in "+ " }
                            else -> true
                        }
                    },
                    extra = if (uuid) { set -> ActionPill(stringResource(R.string.settings_new_uuid), { set(java.util.UUID.randomUUID().toString()) }, quiet = true) } else null,
                )
            }
        }

        is Control.Timestamp -> {
            ValueRow(spec.label, spec.display(value), onClick = { sheet = true }, onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy)
            if (sheet) TimestampPicker(c, value, write) { sheet = false }
        }

        // The rarer encodings keep their own editors, behind their Edit buttons.
        else -> RowFrame(
            spec.label, onClick = null, onLongClick = long, locked = locked, changed = changed, overline = overline,
            sub = spec.display(value).takeIf { c is Control.Locale || c is Control.App },
            keyLine = keyLine, problem = problem, dense = dense, busy = busy,
            below = {
                val bare: @Composable (Modifier, String?) -> Unit = { m, extra ->
                    if (extra != null) Text(extra, style = Visor.type.label, color = Visor.colors.signal, modifier = m) else Spacer(m)
                }
                val enabled = !locked
                when (c) {
                    Control.Locale -> LocaleControl(value, enabled, bare, write)
                    is Control.App -> AppControl(c, value, enabled, bare, write)
                    is Control.PhoneList -> PhoneListControl(c, value, enabled, bare, write)
                    is Control.Bits -> BitsControl(spec, c, value, enabled, bare, write)
                    Control.Schedule -> ScheduleControl(spec, settings, value, enabled, bare, write)
                    is Control.Structured -> StructuredControl(spec, c, value, enabled, bare, write)
                    else -> Unit
                }
            },
        )
    }
}

/** One of Visor's own switches: lives on the phone, so it works with the glasses away. */
@Composable
fun VisorToggleRow(toggle: VisorToggle, overline: String? = null, sub: String? = null) {
    val host = LocalHost.current
    val on by toggle.on.collectAsState()
    ToggleRow(stringResource(toggle.label), on, toggle.set, onLongClick = { host.openDetails(Pins.visor(toggle.id)) }, overline = overline, sub = sub)
}

private const val SLOW_MS = 8_000L

private val UUID_SHAPE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimestampPicker(c: Control.Timestamp, value: String?, write: (String) -> Unit, onDismiss: () -> Unit) {
    val scale = if (c.unit == "s") 1_000L else 1L
    val now = value?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { it * scale }
    var step by remember { mutableStateOf(1) }
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(now ?: System.currentTimeMillis()).atZone(zone)
    val dateState = rememberDatePickerState(initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val timeState = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = true)
    if (step == 1) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { step = 2 }) { Text(stringResource(R.string.settings_next)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.language_cancel)) } },
        ) { DatePicker(dateState) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    val day = Instant.ofEpochMilli(dateState.selectedDateMillis ?: return@TextButton).atZone(ZoneOffset.UTC).toLocalDate()
                    val at = LocalDate.of(day.year, day.month, day.dayOfMonth).atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone)
                    write((at.toInstant().toEpochMilli() / scale).toString())
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.language_cancel)) } },
        )
    }
}

/** Everything about one setting, from a long press: what it does, its default, the pin, the way back. */
@Composable
internal fun SettingDetails(id: String, onDismiss: () -> Unit) {
    val host = LocalHost.current
    val pinned = host.pins.has(id)
    if (id.startsWith("f:")) {
        Feature.byId(id.removePrefix("f:"))?.let { FeatureDetails(it, onDismiss) }
        return
    }
    if (id.startsWith("v:")) {
        val toggle = host.toggles.byId(id.removePrefix("v:")) ?: return
        VisorSheet(onDismiss) {
            SheetTitle(stringResource(toggle.label), toggle.note?.let { stringResource(it) })
            Row(Modifier.padding(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionPill(stringResource(if (pinned) R.string.settings_unpin else R.string.settings_pin), { host.pins.toggle(id); onDismiss() }, quiet = true)
            }
        }
        return
    }
    val key = id.removePrefix("g:")
    val settings = host.settings
    val state = host.state
    val info = settings.catalogue[key]
    val spec = specOf(settings, key)
    val value = state.valueOf(key, info)
    val default = info?.default
    val canRestore = host.ready && info?.writable == true && default != null && state.changed(key, info)
    VisorSheet(onDismiss) {
        SheetTitle(spec.label, spec.note)
        Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Reading(stringResource(R.string.settings_now), value?.let { spec.display(it) } ?: stringResource(R.string.settings_not_read))
            Reading(stringResource(R.string.settings_default), default?.let { spec.display(it) } ?: stringResource(R.string.settings_unknown))
        }
        Row(Modifier.padding(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canRestore) {
                ActionPill(stringResource(R.string.settings_restore), {
                    settings.write(key, default!!)
                    onDismiss()
                })
            }
            ActionPill(stringResource(if (pinned) R.string.settings_unpin else R.string.settings_pin), { host.pins.toggle(id); onDismiss() }, quiet = true)
        }
        Column(Modifier.padding(horizontal = SIDE).padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyLine(key)
            val how = when {
                info == null -> stringResource(R.string.settings_how_nothing)
                info.field != null && !info.viaDrive -> stringResource(R.string.settings_how_field, info.field!!)
                else -> stringResource(R.string.settings_how_drive, info.driveNamespace)
            }
            Text(how, style = Visor.type.label, color = Visor.colors.textSecondary)
            Text(
                stringResource(
                    R.string.settings_mapping,
                    spec.confidence,
                    stringResource(if (spec.userFacing) R.string.settings_in_meta_app else R.string.settings_internal),
                ),
                style = Visor.type.label, color = Visor.colors.textSecondary,
            )
            when {
                info == null -> Unit
                protectedReason(key) != null -> Text(stringResource(R.string.settings_never_written, protectedReason(key)!!), style = Visor.type.label, color = Visor.colors.textSecondary)
                !info.writable -> Text(stringResource(R.string.settings_no_way_to_write), style = Visor.type.label, color = Visor.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun Reading(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Visor.type.label, color = Visor.colors.textSecondary)
        Text(value, style = Visor.type.body, color = Visor.colors.textPrimary)
    }
}
