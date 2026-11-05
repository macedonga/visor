package com.macedonga.visor.settings

import android.content.Context
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.AssistantConfig
import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.assistant.Provider
import com.macedonga.visor.core.media.LiveSettingsStore
import com.macedonga.visor.notify.NotifyChoice
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The quick tiles, in order: "g:<key>" for a glasses setting, "v:<id>" for one of Visor's own, "f:<id>" for a feature. */
@Singleton
class Pins @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("pins", Context.MODE_PRIVATE)
    private val _ids = MutableStateFlow(prefs.getString(KEY, null)?.split(',')?.filter { it.isNotEmpty() } ?: DEFAULT)
    val ids: StateFlow<List<String>> = _ids.asStateFlow()

    fun has(id: String) = id in _ids.value

    fun toggle(id: String) = save(if (has(id)) _ids.value - id else _ids.value + id)

    fun move(from: Int, to: Int) {
        val list = _ids.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        save(list)
    }

    fun set(ids: List<String>) = save(ids)

    private fun save(ids: List<String>) {
        _ids.value = ids
        prefs.edit().putString(KEY, ids.joinToString(",")).apply()
    }

    companion object {
        private const val KEY = "ids"
        val DEFAULT = listOf("g:sg_do_not_disturb", "g:wakeword_enabled", "v:notifications", "g:battery_saver_mode_enabled", "g:wear_detection_enabled")

        fun glasses(key: String) = "g:$key"
        fun visor(id: String) = "v:$id"
        fun feature(id: String) = "f:$id"
    }
}

/** A pin id taken apart. */
sealed interface Pin

data class GlassesPin(val key: String) : Pin

data class TogglePin(val id: String) : Pin

data class FeaturePin(val id: String) : Pin

fun pinOf(id: String): Pin? = when {
    id.startsWith("g:") -> GlassesPin(id.drop(2))
    id.startsWith("v:") -> TogglePin(id.drop(2))
    id.startsWith("f:") -> FeaturePin(id.drop(2))
    else -> null
}

/** One of Visor's own switches, as a tile or a search result can show it. */
class VisorToggle(
    val id: String,
    val label: Int,
    val note: Int?,
    val topic: Topic,
    val on: StateFlow<Boolean>,
    val set: (Boolean) -> Unit,
)

/** Visor's own switches, the ones that live on the phone and work with the glasses away. */
@Singleton
class VisorToggles @Inject constructor(
    notify: NotifyChoice,
    assistant: AssistantConfigStore,
    live: LiveSettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun <A> StateFlow<A>.on(read: (A) -> Boolean): StateFlow<Boolean> = map(read).stateIn(scope, SharingStarted.Eagerly, read(value))

    val all: List<VisorToggle> = listOf(
        VisorToggle("notifications", R.string.settings_notifications, R.string.settings_notifications_sub, Topic.NOTIFICATIONS, notify.enabled, notify::set),
        VisorToggle(
            "assistant", R.string.ai_answer, R.string.ai_answer_sub, Topic.META_AI,
            assistant.config.on { it.enabled }, { on -> assistant.update { it.copy(enabled = on) } },
        ),
        VisorToggle(
            "vision", R.string.ai_vision, null, Topic.META_AI,
            assistant.config.on { it.vision }, { on -> assistant.update { it.copy(vision = on) } },
        ),
        VisorToggle(
            "voice_photos", R.string.ai_voice_photos, null, Topic.META_AI,
            assistant.config.on { it.voicePhotos }, { on -> assistant.update { it.copy(voicePhotos = on) } },
        ),
        VisorToggle(
            "glasses_voice", R.string.ai_glasses_voice, R.string.ai_glasses_voice_sub, Topic.META_AI,
            assistant.config.on { it.glassesVoice }, { on -> assistant.update { it.copy(glassesVoice = on) } },
        ),
        VisorToggle(
            "quiet_actions", R.string.ai_quiet_actions, null, Topic.META_AI,
            assistant.config.on { it.quietAfterActions }, { on -> assistant.update { it.copy(quietAfterActions = on) } },
        ),
        VisorToggle(
            "web", R.string.ai_web, null, Topic.META_AI,
            assistant.config.on { it.webSearch }, { on -> assistant.update { it.copy(webSearch = on) } },
        ),
        VisorToggle(
            "live_auto", R.string.live_auto_quality, null, Topic.LIVE,
            live.settings.on { !it.manual }, { auto -> live.update { it.copy(manual = !auto) } },
        ),
    )

    /** The switches the chosen provider has no use for: web search is Claude's alone. */
    val unoffered: StateFlow<Set<String>> = assistant.config.map(::unofferedOf).stateIn(scope, SharingStarted.Eagerly, unofferedOf(assistant.config.value))

    private fun unofferedOf(c: AssistantConfig): Set<String> = if (c.provider == Provider.CLAUDE) emptySet() else setOf("web")

    fun byId(id: String): VisorToggle? = all.firstOrNull { it.id == id }
}
