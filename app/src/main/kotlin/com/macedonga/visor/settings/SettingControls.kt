package com.macedonga.visor.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.settings.Control
import com.macedonga.visor.core.settings.DisplayWords
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.Kind
import com.macedonga.visor.core.settings.SettingInfo
import com.macedonga.visor.core.settings.SettingSpec
import com.macedonga.visor.core.settings.SettingsState
import com.macedonga.visor.core.settings.asBool
import com.macedonga.visor.core.settings.display
import com.macedonga.visor.core.settings.isProtected
import com.macedonga.visor.core.settings.number
import com.macedonga.visor.core.settings.offeredOn
import com.macedonga.visor.core.settings.same
import com.macedonga.visor.core.settings.unitSuffix
import com.macedonga.visor.features.Feature
import com.macedonga.visor.features.FeatureDetails
import com.macedonga.visor.language.VOICE_KEY
import com.macedonga.visor.language.isPhoneVoice
import com.macedonga.visor.language.unavailableLanguageSettings
import kotlin.math.roundToInt
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
    control = Control.ReadOnly,
    invert = false,
    confidence = "low",
)

internal const val OTHER = "Other"

/** The value in force: being written, else stored, else the firmware default once the glasses have been read. */
internal fun SettingsState.valueOf(key: String, info: SettingInfo?): String? = pending[key] ?: stored[key] ?: info?.default?.takeIf { readAt != null }

/** A unitless range as a share of its span. */
internal fun Control.Range.percent(x: Double): String = "${((x - min) / (max - min) * 100).roundToInt()}%"

/** A range with no unit reads as a share of its span. */
internal val Control.Range.share: Boolean get() = unit.isEmpty() && min >= 0 && max > min

/** Set on the glasses to something other than the default. */
internal fun SettingsState.changed(key: String, info: SettingInfo?): Boolean {
    val have = stored[key] ?: return false
    val default = info?.default ?: return true
    return !same(info.kind, have, default)
}

/** A page opened with the glasses ready reads what moved since the last read, all of it the first time. */
@Composable
internal fun ReadSettingsWhenReady(settings: GlassesSettings, ready: Boolean) {
    LaunchedEffect(Unit) {
        if (ready) settings.read(full = settings.state.value.readAt == null)
    }
}

/** A switch as the wire takes it: ints as 1 and 0. */
internal fun boolWire(info: SettingInfo?, on: Boolean) = if (info?.kind == Kind.INT) (if (on) "1" else "0") else on.toString()

/** The options a row offers; the ones the Meta app keeps for other glasses show only in Advanced. */
internal fun Control.Choice.shown(current: String, product: Int?, engineering: Boolean = false) =
    options.filter { engineering || it.value == current || (!it.hidden && it.offeredOn(product)) }

@Composable
internal fun languageRestriction(key: String): String? {
    val host = LocalHost.current
    val voice = host.settings.value(VOICE_KEY)
    return when {
        key == "glasses_voice" && isPhoneVoice(voice) -> stringResource(R.string.language_partner_voice)
        key in unavailableLanguageSettings(voice) -> stringResource(R.string.language_partner_speed)
        else -> null
    }
}

@Composable
internal fun displayWords() = DisplayWords(stringResource(R.string.sum_on), stringResource(R.string.sum_off), stringResource(R.string.settings_not_set))

/** A value in the phone's language. */
@Composable
internal fun SettingSpec.words(value: String): String {
    val c = control
    val words = displayWords()
    return when {
        value.isBlank() && c != Control.Toggle && (c !is Control.Choice || c.options.none { it.value == value.trim() }) -> words.notSet
        c is Control.Range && c.share -> value.trim().toDoubleOrNull()?.let { c.percent(it) } ?: display(value, words)
        else -> display(value, words)
    }
}

/** One glasses setting as its mapping calls for; an engineering page adds the key and the stored value. */
@Composable
fun GlassesRow(key: String, overline: String? = null) {
    val host = LocalHost.current
    val engineering = host.engineering
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
    var sheet by remember(key) { mutableStateOf(false) }

    // A phone language speaks with the phone's voice, so its speed is set in Android's text-to-speech settings.
    if (languageRestriction(key) != null) {
        val context = LocalContext.current
        ValueRow(
            spec.label, stringResource(R.string.settings_in_phone_settings),
            onClick = { runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            onLongClick = long, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy,
        )
        return
    }

    if (!writable || value == null) {
        ValueRow(
            spec.label, if (value == null) stringResource(R.string.settings_not_read) else spec.words(value), onClick = null, onLongClick = long,
            locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy,
            dim = !host.ready,
        )
        return
    }
    when (val c = spec.control) {
        Control.Toggle -> ToggleRow(
            spec.label, on = asBool(value) != spec.invert,
            onChange = { on -> write(boolWire(info, on != spec.invert)) },
            onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy,
        )

        is Control.Choice -> {
            val current = value.trim()
            val shown = c.shown(current, host.product, engineering)
            if (shown.size <= 4 && shown.all { it.label.length <= 16 && it.detail == null } && shown.any { it.value == current }) {
                PillsRow(
                    spec.label, shown.map { it.value to it.label }, current, onPick = write,
                    onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy,
                )
            } else {
                ValueRow(spec.label, spec.words(value), onClick = { sheet = true }, onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy)
                if (sheet) {
                    ChoiceSheet(
                        spec.label, shown.map { Triple(it.value, it.label, it.detail) }, current,
                        onPick = write, onDismiss = { sheet = false }, sub = spec.note.takeIf { engineering },
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
                words = {
                    val s = snap(it.toDouble())
                    if (!engineering && c.share) c.percent(s) else number(s) + unitSuffix(c.unit)
                },
                onDone = { x ->
                    val s = snap(x.toDouble())
                    settings.write(key, if (info?.kind == Kind.INT) s.roundToLong().toString() else number(s)).also { if (!it) host.notice.show(refused) }
                },
                onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy,
            )
        }

        is Control.Number -> {
            ValueRow(spec.label, spec.words(value), onClick = { sheet = true }, onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy)
            if (sheet) {
                val integer = info?.kind == Kind.INT
                TextSheet(
                    spec.label, value.trim(), onSave = write, onDismiss = { sheet = false }, sub = spec.note.takeIf { engineering },
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
                TextSheet(
                    spec.label, value, onSave = { write(it.trim()) }, onDismiss = { sheet = false }, sub = spec.note.takeIf { engineering },
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
                    valid = { t -> c.what != "phone number" || t.all { it.isDigit() || it in "+ " } },
                )
            }
        }

        Control.Locale, is Control.App -> {
            val current = value.trim()
            val options = phoneOptions(c)
            val shown = options.firstOrNull { it.value == current }?.label?.takeIf { c is Control.App } ?: spec.words(value)
            ValueRow(spec.label, shown, onClick = { sheet = true }, onLongClick = long, locked = locked, changed = changed, overline = overline, keyLine = keyLine, problem = problem, dense = dense, busy = busy)
            if (sheet) {
                ChoiceSheet(
                    spec.label, options.map { Triple(it.value, it.label, it.value.takeIf { v -> v.isNotEmpty() && v != it.label }) }, current,
                    onPick = { if (it != current) write(it) }, onDismiss = { sheet = false },
                )
            }
        }

        // The rarer encodings keep their own editors.
        else -> RowFrame(
            spec.label, onClick = null, onLongClick = long, locked = locked, changed = changed, overline = overline,
            keyLine = keyLine, problem = problem, dense = dense, busy = busy,
            below = {
                val enabled = !locked
                when (c) {
                    is Control.PhoneList -> PhoneListControl(c, value, enabled, write)
                    is Control.Bits -> BitsControl(spec, c, value, enabled, write)
                    Control.Schedule -> ScheduleControl(spec, settings, value, enabled, write)
                    is Control.Structured -> StructuredControl(spec, c, value, enabled, write)
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
    val restriction = languageRestriction(toggle.id)
    if (restriction != null) ValueRow(
        stringResource(toggle.label), stringResource(R.string.ai_voice_phone), onClick = null,
        onLongClick = { host.openDetails(Pins.visor(toggle.id)) }, overline = overline, sub = restriction, dim = true,
    ) else ToggleRow(stringResource(toggle.label), on, toggle.set, onLongClick = { host.openDetails(Pins.visor(toggle.id)) }, overline = overline, sub = sub)
}

private const val SLOW_MS = 8_000L

/** Everything about one setting, from a long press: what it does, its default, the pin, the way back. */
@Composable
internal fun SettingDetails(id: String, onDismiss: () -> Unit) {
    val host = LocalHost.current
    val pinned = host.pins.has(id)
    val key = when (val pin = pinOf(id)) {
        is FeaturePin -> {
            Feature.byId(pin.id)?.let { FeatureDetails(it, onDismiss) }
            return
        }
        is TogglePin -> {
            val toggle = host.toggles.byId(pin.id) ?: return
            VisorSheet(onDismiss) {
                SheetTitle(stringResource(toggle.label), languageRestriction(toggle.id))
                Row(Modifier.padding(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionPill(stringResource(if (pinned) R.string.settings_unpin else R.string.settings_pin), { host.pins.toggle(id); onDismiss() }, quiet = true)
                }
            }
            return
        }
        is GlassesPin -> pin.key
        null -> return
    }
    val settings = host.settings
    val state = host.state
    val info = settings.catalogue[key]
    val spec = specOf(settings, key)
    val value = state.valueOf(key, info)
    val default = info?.default
    val restriction = languageRestriction(key)
    val canRestore = restriction == null && host.ready && info?.writable == true && spec.control !is Control.ReadOnly && state.changed(key, info)
    val refused = stringResource(R.string.settings_not_sent)
    VisorSheet(onDismiss) {
        SheetBody {
            SheetTitle(spec.label, restriction ?: spec.note.takeIf { host.engineering })
            Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Reading(stringResource(R.string.settings_now), value?.let { spec.words(it) } ?: stringResource(R.string.settings_not_read))
                Reading(stringResource(R.string.settings_default), default?.let { spec.words(it) } ?: stringResource(R.string.settings_unknown))
            }
            Row(Modifier.padding(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (canRestore && default != null) {
                    ActionPill(stringResource(R.string.settings_restore), {
                        if (!settings.write(key, default)) host.notice.show(refused)
                        onDismiss()
                    })
                }
                ActionPill(stringResource(if (pinned) R.string.settings_unpin else R.string.settings_pin), { host.pins.toggle(id); onDismiss() }, quiet = true)
            }
            Column(Modifier.padding(horizontal = SIDE).padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (host.engineering) {
                    KeyLine(key)
                    val how = when {
                        info == null -> stringResource(R.string.settings_how_nothing)
                        else -> info.field?.takeIf { !info.viaDrive }?.let { stringResource(R.string.settings_how_field, it) }
                            ?: stringResource(R.string.settings_how_drive, info.driveNamespace)
                    }
                    Text(how, style = Visor.type.label, color = Visor.colors.textSecondary)
                    Text(stringResource(R.string.settings_mapping, spec.confidence), style = Visor.type.label, color = Visor.colors.textSecondary)
                }
                when {
                    info == null -> Unit
                    isProtected(key) -> Text(stringResource(R.string.settings_never_written), style = Visor.type.label, color = Visor.colors.textSecondary)
                    !info.writable -> Text(stringResource(R.string.settings_no_way_to_write), style = Visor.type.label, color = Visor.colors.textSecondary)
                }
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
