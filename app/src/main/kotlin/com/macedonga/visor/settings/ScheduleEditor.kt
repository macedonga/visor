package com.macedonga.visor.settings

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.objects
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.settings.Control
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.Kind
import com.macedonga.visor.core.settings.SettingSpec
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** A number packing several values: a switch per flag, choices for the wider fields. */
@Composable
internal fun BitsControl(spec: SettingSpec, c: Control.Bits, value: String?, enabled: Boolean, write: (String) -> Unit) {
    val now = value?.trim()?.toLongOrNull() ?: 0L
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        ActionPill(stringResource(if (enabled) R.string.adv_edit else R.string.adv_view), { open = true }, quiet = true)
    }
    if (!open) return
    var bits by remember(now) { mutableLongStateOf(now) }
    FullDialog(spec.label, enabled, canSave = bits != now, onDismiss = { open = false }, onSave = {
        open = false
        write(bits.toString())
    }) {
        for (f in c.fields) {
            val mask = ((1L shl f.width) - 1) shl f.shift
            val field = (bits and mask) ushr f.shift
            val options = f.options.map { it.value.toLong() to it.label }
            if (f.width == 1) {
                SwitchLine(f.label, field != 0L, enabled) { bits = if (it) bits or mask else bits and mask.inv() }
            } else if (enabled) {
                Text(f.label, style = Visor.type.body, color = Visor.colors.textPrimary)
                Pills(options, field, on = Visor.colors.control) { v -> bits = (bits and mask.inv()) or (v shl f.shift) }
            } else {
                ShownValue(f.label, options.firstOrNull { it.first == field }?.second ?: stringResource(R.string.adv_value_n, field.toString()))
            }
        }
        EditorNote(stringResource(R.string.adv_stored_as, bits))
    }
}

private data class ProfileSetting(val name: String, val type: String, val arg: Long)
private data class Profile(val name: String, val settings: List<ProfileSetting>)

/** [profile] is an index into the calendar's profiles, -1 for none. */
private data class Window(val day: String, val start: String, val end: String, val profile: Int)

/** [default] is an index into [profiles], -1 for none; the JSON names them. */
private data class Calendar(val version: Long, val default: Int, val profiles: List<Profile>, val schedule: List<Window>)

private fun parseCalendar(text: String?): Calendar? {
    if (text.isNullOrBlank()) return Calendar(1, -1, emptyList(), emptyList())
    return try {
        val o = JSONObject(text)
        val profiles = o.optJSONObject("profiles") ?: JSONObject()
        val schedule = o.optJSONArray("schedule") ?: JSONArray()
        val names = profiles.keys().asSequence().toList()
        Calendar(
            version = o.optLong("version", 1),
            default = names.indexOf(o.optString("default")),
            profiles = names.map { name ->
                val list = profiles.getJSONObject(name).optJSONArray("settings") ?: JSONArray()
                Profile(name, list.objects().map {
                    val type = it.optString("type", "bool")
                    ProfileSetting(it.getString("name"), type, if (type == "bool") (if (it.optBoolean("arg")) 1 else 0) else it.optLong("arg"))
                })
            },
            schedule = schedule.objects().map {
                Window(it.optString("day", "mon"), it.optString("start", "09:00"), it.optString("end", "17:00"), names.indexOf(it.optString("profile")))
            },
        )
    } catch (_: Exception) {
        null
    }
}

private fun Calendar.nameOf(i: Int): String = profiles.getOrNull(i)?.name?.trim().orEmpty()

private fun Calendar.toJson(): String = JSONObject().apply {
    put("version", version)
    put("default", nameOf(default))
    put("profiles", JSONObject().apply {
        for (p in profiles) {
            put(p.name.trim(), JSONObject().put("settings", JSONArray().apply {
                for (s in p.settings) put(JSONObject().put("name", s.name).put("type", s.type).put("arg", if (s.type == "bool") s.arg != 0L else s.arg))
            }))
        }
    })
    put("schedule", JSONArray().apply {
        for (w in schedule) put(JSONObject().put("day", w.day).put("start", w.start).put("end", w.end).put("profile", nameOf(w.profile)))
    })
}.toString()

/** The continuous AI camera calendar: named profiles that set switches and numbers, and weekly windows. */
@Composable
internal fun ScheduleControl(spec: SettingSpec, settings: GlassesSettings, value: String?, enabled: Boolean, write: (String) -> Unit) {
    val start = remember(value) { parseCalendar(value) }
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (start == null) Text(stringResource(R.string.adv_unreadable), style = Visor.type.label, color = Visor.colors.signal, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        ActionPill(stringResource(if (enabled) R.string.adv_edit else R.string.adv_view), { open = true }, quiet = true, enabled = start != null)
    }
    if (!open || start == null) return
    val res = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val days = remember(locale) { DayOfWeek.entries.map { it.name.take(3).lowercase() to it.getDisplayName(TextStyle.SHORT, locale) } }
    var cal by remember(start) { mutableStateOf(start) }
    val names = cal.profiles.map { it.name.trim() }
    val valid = cal.profiles.isEmpty() || (names.all { it.isNotEmpty() } && names.distinct().size == names.size &&
        cal.default in cal.profiles.indices && cal.schedule.all { it.profile in cal.profiles.indices })
    val choices = names.mapIndexed { i, n -> i to n.ifEmpty { res.getString(R.string.adv_profile_n, i + 1) } }
    FullDialog(spec.label, enabled, canSave = valid && cal != start, onDismiss = { open = false }, onSave = {
        open = false
        write(if (cal.profiles.isEmpty()) "" else cal.toJson())
    }) {
        if (!valid) EditorNote(stringResource(R.string.adv_calendar_invalid), Visor.colors.signal)
        if (enabled || cal.profiles.isNotEmpty()) EditorLabel(stringResource(R.string.adv_profiles))
        cal.profiles.forEachIndexed { i, p ->
            EditorCard {
                if (enabled) {
                    EditorField(p.name, { n -> cal = cal.editProfile(i) { it.copy(name = n) } }, Modifier.fillMaxWidth(), hint = stringResource(R.string.details_name))
                } else {
                    Text(p.name, style = Visor.type.body, color = Visor.colors.textPrimary)
                }
                p.settings.forEachIndexed { k, s ->
                    ProfileSettingRow(settings, s, enabled, onChange = { new ->
                        cal = cal.editProfile(i) { q -> q.copy(settings = q.settings.mapIndexed { m, x -> if (m == k) new else x }) }
                    }, onRemove = {
                        cal = cal.editProfile(i) { q -> q.copy(settings = q.settings.filterIndexed { m, _ -> m != k }) }
                    })
                }
                if (enabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingPicker(settings) { key, type -> cal = cal.editProfile(i) { q -> q.copy(settings = q.settings + ProfileSetting(key, type, 0)) } }
                    ActionPill(stringResource(R.string.adv_remove), { cal = cal.withoutProfile(i) }, quiet = true)
                }
            }
        }
        if (enabled) ActionPill(stringResource(R.string.adv_add_profile), {
            val name = generateSequence(1) { it + 1 }.map { res.getString(R.string.adv_profile_n, it) }.first { it !in names }
            cal = cal.copy(profiles = cal.profiles + Profile(name, emptyList()), default = if (cal.default < 0) cal.profiles.size else cal.default)
        }, quiet = true)

        if (choices.isNotEmpty()) {
            EditorLabel(stringResource(R.string.adv_default_profile))
            if (enabled) Pills(choices, cal.default, on = Visor.colors.control) { cal = cal.copy(default = it) } else ShownText(choices, cal.default)
        }

        if (cal.schedule.isNotEmpty() || (enabled && choices.isNotEmpty())) EditorLabel(stringResource(R.string.adv_windows))
        cal.schedule.forEachIndexed { i, w ->
            EditorCard {
                if (enabled) Pills(days, w.day, on = Visor.colors.control) { d -> cal = cal.editWindow(i) { it.copy(day = d) } } else ShownText(days, w.day)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TimeButton(stringResource(R.string.adv_from, w.start), w.start, enabled) { t -> cal = cal.editWindow(i) { it.copy(start = t) } }
                    TimeButton(stringResource(R.string.adv_to, w.end), w.end, enabled) { t -> cal = cal.editWindow(i) { it.copy(end = t) } }
                }
                if (enabled) Pills(choices, w.profile, on = Visor.colors.control) { n -> cal = cal.editWindow(i) { it.copy(profile = n) } } else ShownText(choices, w.profile)
                if (enabled) ActionPill(stringResource(R.string.adv_remove), { cal = cal.copy(schedule = cal.schedule.filterIndexed { j, _ -> j != i }) }, quiet = true)
            }
        }
        if (enabled && choices.isNotEmpty()) ActionPill(stringResource(R.string.adv_add_window), {
            cal = cal.copy(schedule = cal.schedule + Window("mon", "09:00", "17:00", if (cal.default >= 0) cal.default else 0))
        }, quiet = true)

        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.adv_calendar_version), style = Visor.type.body, color = Visor.colors.textPrimary, modifier = Modifier.weight(1f))
            NumberField(cal.version, enabled) { cal = cal.copy(version = it) }
        }
    }
}

private fun Calendar.editProfile(i: Int, edit: (Profile) -> Profile) = copy(profiles = profiles.mapIndexed { j, q -> if (j == i) edit(q) else q })

private fun Calendar.editWindow(i: Int, edit: (Window) -> Window) = copy(schedule = schedule.mapIndexed { j, x -> if (j == i) edit(x) else x })

/** Drops profile [i] and the windows that use it; a default of [i] moves to the first profile left. */
private fun Calendar.withoutProfile(i: Int): Calendar {
    val left = profiles.filterIndexed { j, _ -> j != i }
    fun shift(p: Int) = if (p > i) p - 1 else p
    return copy(
        profiles = left,
        default = if (default == i) (if (left.isEmpty()) -1 else 0) else shift(default),
        schedule = schedule.filter { it.profile != i }.map { it.copy(profile = shift(it.profile)) },
    )
}

@Composable
private fun ProfileSettingRow(settings: GlassesSettings, s: ProfileSetting, enabled: Boolean, onChange: (ProfileSetting) -> Unit, onRemove: () -> Unit) {
    val label = settings.specs[s.name]?.label ?: s.name
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (s.type == "bool") {
            SwitchLine(label, s.arg != 0L, enabled, Modifier.weight(1f), sub = s.name) { onChange(s.copy(arg = if (it) 1 else 0)) }
        } else {
            Column(Modifier.weight(1f)) {
                Text(label, style = Visor.type.body, color = Visor.colors.textPrimary)
                KeyLine(s.name)
            }
            NumberField(s.arg, enabled) { onChange(s.copy(arg = it)) }
        }
        if (enabled) RoundIcon(VisorIcons.Close, stringResource(R.string.adv_remove), onRemove, tint = Visor.colors.textSecondary)
    }
}

/** The chosen one of [options], as words, in an editor opened to view. */
@Composable
private fun <T> ShownText(options: List<Pair<T, String>>, selected: T) {
    Text(options.firstOrNull { it.first == selected }?.second ?: stringResource(R.string.settings_not_set), style = Visor.type.body, color = Visor.colors.textSecondary)
}

/** Add a switch or a number the glasses keep, for a profile to set. */
@Composable
private fun SettingPicker(settings: GlassesSettings, onPick: (String, String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    ActionPill(stringResource(R.string.adv_add_setting), { picking = true }, quiet = true)
    if (!picking) return
    val choices = remember {
        settings.catalogue.values.filter { it.kind == Kind.BOOL || it.kind == Kind.INT }
            .map { Triple(it.key, settings.specs[it.key]?.label ?: it.key, if (it.kind == Kind.BOOL) "bool" else "int") }
            .sortedBy { it.second }
    }
    ChoiceSheet(
        stringResource(R.string.adv_add_setting),
        choices.map { Triple(it.first, it.second, it.first) },
        null,
        onPick = { key -> onPick(key, choices.first { it.first == key }.third) },
        onDismiss = { picking = false },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeButton(label: String, time: String, enabled: Boolean, onSet: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (!enabled) {
        Text(label, style = Visor.type.body, color = Visor.colors.textSecondary)
        return
    }
    ActionPill(label, { picking = true }, quiet = true)
    if (!picking) return
    val (h, m) = time.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: 9) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    val state = rememberTimePickerState(initialHour = h.coerceIn(0, 23), initialMinute = m.coerceIn(0, 59), is24Hour = DateFormat.is24HourFormat(LocalContext.current))
    VisorSheet({ picking = false }) {
        TimePicker(state, Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            ActionPill(stringResource(R.string.cancel), { picking = false }, quiet = true)
            ActionPill(stringResource(R.string.adv_set), {
                picking = false
                onSet(String.format(Locale.ROOT, "%02d:%02d", state.hour, state.minute))
            })
        }
    }
}

/** A full-screen editor: Close, the title and, when [editable], Save in a bar over the content. */
@Composable
internal fun FullDialog(title: String, editable: Boolean, canSave: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Visor.colors.canvas)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(Modifier.fillMaxWidth().height(BAR).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(VisorIcons.Close, stringResource(R.string.close), onDismiss)
                BasicText(
                    title,
                    style = Visor.type.title.copy(color = Visor.colors.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = Visor.type.title.fontSize, stepSize = 1.sp),
                    modifier = Modifier.weight(1f).asHeading().padding(start = 4.dp, end = 8.dp),
                )
                if (editable) ActionPill(stringResource(R.string.settings_save), onSave, Modifier.padding(end = 8.dp), enabled = canSave)
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
    }
}
