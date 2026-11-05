package com.macedonga.visor.settings

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.settings.Control
import com.macedonga.visor.core.settings.ProtoField
import com.macedonga.visor.core.settings.ProtoSchema
import com.macedonga.visor.core.settings.SettingSpec
import com.macedonga.visor.core.settings.encodeProto
import com.macedonga.visor.core.settings.parseProto
import com.macedonga.visor.core.settings.parseSchema
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import com.macedonga.visor.ui.switchRow

private const val VARINT = 0
private const val LEN = 2

/** An encoded setting, with an editor built from its schema behind Edit. */
@Composable
internal fun StructuredControl(
    spec: SettingSpec,
    c: Control.Structured,
    value: String?,
    enabled: Boolean,
    write: (String) -> Unit,
) {
    val schema = remember(c) { parseSchema(c.schema) }
    val decoded = remember(value) { value?.let { decode(it) } }
    var open by remember { mutableStateOf(false) }
    val problem = when {
        schema == null -> stringResource(R.string.adv_not_mapped)
        value != null && decoded == null -> stringResource(R.string.adv_unreadable)
        else -> null
    }
    val editable = enabled && schema?.readOnly == false
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (problem != null) Text(problem, style = Visor.type.label, color = Visor.colors.signal, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        ActionPill(stringResource(if (editable) R.string.adv_edit else R.string.adv_view), { open = true }, quiet = true, enabled = problem == null)
    }
    if (!open || schema == null) return
    val start = decoded ?: emptyList()
    var fields by remember { mutableStateOf(start) }
    FullDialog(spec.label, editable, canSave = fields !== start, onDismiss = { open = false }, onSave = {
        open = false
        write(encode(fields))
    }) {
        MessageEditor(schema, schema.root, fields, editable) { fields = it }
    }
}

/** Base64 of a protobuf message, as these settings keep it; empty means an empty message. */
private fun decode(text: String): List<ProtoField>? {
    if (text.isBlank()) return emptyList()
    val bytes = try {
        Base64.decode(text.trim(), Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return parseProto(bytes)
}

private fun encode(fields: List<ProtoField>): String = Base64.encodeToString(encodeProto(fields), Base64.NO_WRAP)

/** Every field the schema names for [type], with what the data holds; unknown fields ride along unchanged. */
@Composable
private fun MessageEditor(schema: ProtoSchema, type: String, fields: List<ProtoField>, editable: Boolean, onChange: (List<ProtoField>) -> Unit) {
    val specs = schema.messages[type].orEmpty()
    for (f in specs) {
        val present = fields.filter { it.number == f.number }
        when {
            f.type == "message" && f.repeated -> {
                EditorLabel(f.label)
                present.forEachIndexed { i, item ->
                    EditorCard {
                        val child = parseProto(item.bytes)
                        if (child == null) {
                            EditorNote(stringResource(R.string.adv_entry_unreadable))
                        } else {
                            MessageEditor(schema, f.ref.orEmpty(), child, editable) { updated ->
                                onChange(replaceNth(fields, f.number, i, ProtoField(f.number, LEN, bytes = encodeProto(updated))))
                            }
                        }
                        if (editable) ActionPill(stringResource(R.string.adv_remove), { onChange(removeNth(fields, f.number, i)) }, quiet = true)
                    }
                }
                if (present.isEmpty()) EditorNote(stringResource(R.string.adv_none))
                if (editable) ActionPill(stringResource(R.string.adv_add), { onChange(fields + ProtoField(f.number, LEN)) }, quiet = true)
            }

            f.type == "message" -> {
                EditorLabel(f.label)
                EditorCard {
                    val item = present.firstOrNull()
                    val child = if (item == null) emptyList() else parseProto(item.bytes)
                    if (child == null) {
                        EditorNote(stringResource(R.string.adv_entry_unreadable))
                    } else {
                        MessageEditor(schema, f.ref.orEmpty(), child, editable) { updated ->
                            onChange(setSingle(fields, f.number, if (updated.isEmpty()) null else ProtoField(f.number, LEN, bytes = encodeProto(updated))))
                        }
                    }
                }
            }

            f.type == "bool" -> SwitchLine(f.label, present.firstOrNull()?.varint?.let { it != 0L } ?: false, editable) {
                onChange(setSingle(fields, f.number, if (it) ProtoField(f.number, VARINT, varint = 1) else null))
            }

            f.type == "enum" -> {
                val options = schema.enums[f.ref].orEmpty()
                val now = (present.firstOrNull()?.varint ?: 0L).toString()
                val unnamed = if (options.none { it.value == now }) listOf(now to stringResource(R.string.adv_value_n, now)) else emptyList()
                val choices = options.map { it.value to it.label } + unnamed
                if (editable) {
                    Text(f.label, style = Visor.type.body, color = Visor.colors.textPrimary)
                    Pills(choices, now, on = Visor.colors.control) { v ->
                        if (v != now) {
                            val n = v.toLong()
                            onChange(setSingle(fields, f.number, if (n == 0L) null else ProtoField(f.number, VARINT, varint = n)))
                        }
                    }
                } else {
                    ShownValue(f.label, choices.firstOrNull { it.first == now }?.second ?: now)
                }
            }

            f.type == "int" -> {
                val now = present.firstOrNull()?.varint ?: 0L
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.label, style = Visor.type.body, color = Visor.colors.textPrimary, modifier = Modifier.weight(1f))
                    NumberField(now, editable) { onChange(setSingle(fields, f.number, if (it == 0L) null else ProtoField(f.number, VARINT, varint = it))) }
                }
            }

            else -> {
                val now = present.firstOrNull()?.bytes?.decodeToString().orEmpty()
                var draft by remember(now) { mutableStateOf(now) }
                Text(f.label, style = Visor.type.body, color = Visor.colors.textPrimary)
                if (editable) {
                    EditorField(draft, {
                        draft = it
                        onChange(setSingle(fields, f.number, if (it.isEmpty()) null else ProtoField(f.number, LEN, bytes = it.encodeToByteArray())))
                    }, Modifier.fillMaxWidth())
                } else {
                    EditorNote(if (now.isEmpty()) stringResource(R.string.settings_not_set) else now)
                }
            }
        }
    }
    val unknown = fields.count { field -> specs.none { it.number == field.number } }
    if (unknown > 0) EditorNote(pluralStringResource(R.plurals.adv_unknown_fields, unknown, unknown))
}

/** A grey heading inside an editor. */
@Composable
internal fun EditorLabel(text: String) {
    Text(text, style = Visor.type.label, color = Visor.colors.textSecondary, modifier = Modifier.asHeading().padding(top = 14.dp))
}

/** A grey line inside an editor. */
@Composable
internal fun EditorNote(text: String, color: Color = Visor.colors.textSecondary) {
    Text(text, style = Visor.type.label, color = color)
}

/** A name and what it holds, in an editor opened to view. */
@Composable
internal fun ShownValue(label: String, value: String) {
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Visor.type.body, color = Visor.colors.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(start = 12.dp))
    }
}

/** One entry's card inside an editor. */
@Composable
internal fun EditorCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_RADIUS))
            .background(Visor.colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** A text well inside an editor; [modifier] sets its width. */
@Composable
internal fun EditorField(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, hint: String = "", keyboard: KeyboardType = KeyboardType.Text) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Visor.colors.raised).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, style = Visor.type.body, color = Visor.colors.textTertiary)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = Visor.type.body.copy(color = Visor.colors.textPrimary),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A name and a switch, the whole line taking the tap; On or Off in words when it only shows. [sub] is the firmware's key. */
@Composable
internal fun SwitchLine(label: String, on: Boolean, editable: Boolean, modifier: Modifier = Modifier, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (editable) Modifier.clickable { onChange(!on) }.switchRow(on) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Visor.type.body, color = Visor.colors.textPrimary)
            if (sub != null) KeyLine(sub)
        }
        if (editable) {
            VisorSwitch(on)
        } else {
            Text(stringResource(if (on) R.string.sum_on else R.string.sum_off), style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** A whole number with a step either side; the text may stay unparsed (empty, a lone '-') while it is typed. */
@Composable
internal fun NumberField(value: Long, editable: Boolean, onChange: (Long) -> Unit) {
    if (!editable) {
        Text(value.toString(), style = Visor.type.body, color = Visor.colors.textSecondary)
        return
    }
    var draft by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(VisorIcons.Minus, stringResource(R.string.adv_decrease), { onChange(value - 1) })
        EditorField(draft, { text ->
            draft = text.filter { it.isDigit() || it == '-' }
            draft.toLongOrNull()?.let(onChange)
        }, Modifier.width(96.dp), keyboard = KeyboardType.Number)
        RoundIcon(VisorIcons.Plus, stringResource(R.string.adv_increase), { onChange(value + 1) })
    }
}

/** Replace the field [number] with [field], or drop it when [field] is null (proto3 leaves defaults out). */
private fun setSingle(fields: List<ProtoField>, number: Int, field: ProtoField?): List<ProtoField> {
    val at = fields.indexOfFirst { it.number == number }
    if (at < 0) return if (field == null) fields else fields + field
    // Only the first copy counts; a repeat of a singular field would be merged by the reader anyway.
    val kept = fields.filterIndexed { i, f -> f.number != number || i == at }
    val idx = kept.indexOfFirst { it.number == number }
    return if (field == null) kept.filterIndexed { i, _ -> i != idx } else kept.mapIndexed { i, f -> if (i == idx) field else f }
}

private fun replaceNth(fields: List<ProtoField>, number: Int, n: Int, field: ProtoField): List<ProtoField> {
    var seen = -1
    return fields.map { if (it.number == number && ++seen == n) field else it }
}

private fun removeNth(fields: List<ProtoField>, number: Int, n: Int): List<ProtoField> {
    var seen = -1
    return fields.filterNot { it.number == number && ++seen == n }
}
