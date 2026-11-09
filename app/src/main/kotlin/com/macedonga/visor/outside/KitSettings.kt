@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.Control
import com.macedonga.visor.core.settings.GROUPS
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.Kind
import com.macedonga.visor.core.settings.SettingsState
import com.macedonga.visor.core.settings.offeredOn
import com.macedonga.visor.kit.KitException
import com.macedonga.visor.kit.protocol.ErrorCode
import com.macedonga.visor.kit.protocol.GlassesSetting
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.SettingKind
import com.macedonga.visor.kit.protocol.SettingOption
import com.macedonga.visor.kit.protocol.SettingRange
import com.macedonga.visor.kit.protocol.SettingsQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The glasses' settings for VDK apps, from Visor's catalogue: all readable, the everyday ones writable. */
@Singleton
class KitSettings @Inject constructor(
    private val connection: GlassesConnection,
    private val settings: GlassesSettings,
) {
    fun list(query: SettingsQuery): List<GlassesSetting> {
        val state = settings.state.value
        return keys(query).map { setting(it, state) }
            .sortedWith(compareBy<GlassesSetting> { GROUPS.indexOf(it.group).let { i -> if (i < 0) GROUPS.size else i } }.thenBy { it.label ?: it.key })
    }

    fun get(key: String): GlassesSetting {
        known(key)
        return setting(key, settings.state.value)
    }

    fun set(key: String, value: String): GlassesSetting {
        known(key)
        val now = setting(key, settings.state.value)
        if (!now.writable) throw KitException(ErrorCode.REFUSED, "Apps may not change $key.", reason = "read_only")
        if (!now.accepts(value)) throw KitException(ErrorCode.INVALID_PARAMS, "$key does not take $value.", reason = "invalid_value")
        if (connection.state.value.link != LinkState.READY) throw KitException(ErrorCode.NOT_CONNECTED, "The glasses are not connected.")
        if (!settings.write(key, value)) throw KitException(ErrorCode.FAILED, "Visor could not send $key to the glasses.")
        return setting(key, settings.state.value)
    }

    /** Each setting of [query] whose value or pending state changed, from the next read on. */
    fun changes(query: SettingsQuery): Flow<GlassesSetting> = flow {
        var before: Map<String, Pair<String?, Boolean>>? = null
        settings.state.collect { state ->
            val keys = keys(query)
            val now = keys.associateWith { (state.stored[it] ?: settings.catalogue[it]?.default) to (it in state.pending) }
            before?.let { old -> for (key in keys) if (old[key] != now[key]) emit(setting(key, state)) }
            before = now
        }
    }

    private fun keys(query: SettingsQuery): List<String> {
        val all = if (query.keys.isEmpty()) settings.catalogue.keys else query.keys.filter { it in settings.catalogue }
        return all.filter { query.group == null || settings.specs[it]?.group == query.group }
    }

    private fun known(key: String) {
        if (key !in settings.catalogue) throw KitException(ErrorCode.INVALID_PARAMS, "No setting $key.", reason = "unknown_key")
    }

    private fun setting(key: String, state: SettingsState): GlassesSetting {
        val info = settings.catalogue.getValue(key)
        val spec = settings.specs[key]
        val control = spec?.control
        val secret = (control as? Control.Text)?.what == "password"
        val product = state.product
        return GlassesSetting(
            key = key,
            kind = when {
                control is Control.Bits || control is Control.Structured || control is Control.Schedule -> SettingKind.OTHER
                else -> when (info.kind) {
                    Kind.BOOL -> SettingKind.BOOL
                    Kind.INT -> SettingKind.INT
                    Kind.FLOAT -> SettingKind.FLOAT
                    Kind.STR -> SettingKind.STRING
                    Kind.OTHER -> SettingKind.OTHER
                }
            },
            value = if (secret) null else state.stored[key] ?: info.default,
            default = if (secret) null else info.default,
            // A switch whose key says "disabled" is labelled the other way round: no label beats a wrong one.
            label = spec?.label?.takeUnless { spec.invert },
            group = spec?.group,
            options = (control as? Control.Choice)?.options.orEmpty()
                .filter { !it.hidden && it.offeredOn(product) }
                .map { SettingOption(it.value, it.label) },
            range = when (control) {
                is Control.Range -> SettingRange(control.min, control.max, control.step, control.unit)
                is Control.Number -> SettingRange(control.min?.toDouble(), control.max?.toDouble(), null, control.unit)
                else -> null
            },
            writable = info.writable && !secret && spec != null && everyday(control) && spec.group !in SYSTEM_GROUPS &&
                !SYSTEM_KEY.containsMatchIn(key),
            pending = key in state.pending,
        )
    }

    // The controls whose value is one plain value an app can set without knowing an encoding.
    private fun everyday(control: Control?) = control is Control.Toggle || control is Control.Choice || control is Control.Range ||
        control is Control.Number || control is Control.Text || control is Control.Locale

    private companion object {
        // Updates, resets, heat protection, developer switches and logging stay with Visor and the person.
        val SYSTEM_GROUPS = setOf("Updates and system", "Developer and tests")
        val SYSTEM_KEY = Regex("ota|update|reset|thermal|developer|logging|devkit")
    }
}
