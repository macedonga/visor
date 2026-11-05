package com.macedonga.visor.settings

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.IntegrationsKey
import com.macedonga.visor.MemoriesKey
import com.macedonga.visor.R
import com.macedonga.visor.WakeWordKey
import com.macedonga.visor.features.featureSection
import com.macedonga.visor.core.assistant.ANSWER_WAITS
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.assistant.AssistantPhase
import com.macedonga.visor.core.assistant.DEFAULT_CLAUDE_MODEL
import com.macedonga.visor.core.assistant.ModelInfo
import com.macedonga.visor.core.assistant.ModelsError
import com.macedonga.visor.core.assistant.Provider
import com.macedonga.visor.core.assistant.SPEECH_LANGUAGES
import com.macedonga.visor.core.assistant.listModels
import com.macedonga.visor.core.assistant.mcp.McpHub
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.language.VOICE_KEY
import com.macedonga.visor.language.isPhoneVoice
import com.macedonga.visor.language.ownName
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.paste
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltViewModel
class AssistantViewModel @Inject constructor(
    val assistant: Assistant,
    val store: AssistantConfigStore,
    val toggles: VisorToggles,
    val settings: GlassesSettings,
    val hub: McpHub,
) : ViewModel()

/** What came of asking the provider for its models, which is also the test of the key. */
private sealed interface KeyCheck {
    data object None : KeyCheck
    data object Checking : KeyCheck
    data class Works(val models: List<ModelInfo>) : KeyCheck
    data class Refused(val words: String) : KeyCheck
    data class Failed(val words: String) : KeyCheck
}

/**
 * Meta AI: whether Visor answers "Hey Meta", with which provider, key and model (listed live from the provider,
 * which also proves the key), how it answers, and the glasses' own assistant settings under them.
 */
@Composable
fun AssistantSettingsScreen(open: (NavKey) -> Unit, model: AssistantViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val config by model.store.config.collectAsStateWithLifecycle()
    val settingsState by model.settings.state.collectAsStateWithLifecycle()
    val partner = isPhoneVoice(settingsState.stored[VOICE_KEY] ?: model.settings.value(VOICE_KEY))
    val phase by model.assistant.phase.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var hearing by rememberOnResume { Need.MIC.granted(context) }
    val askHearing = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hearing = granted
        if (!granted && activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) openAppPage(context)
    }

    var check by remember { mutableStateOf<KeyCheck>(KeyCheck.None) }
    LaunchedEffect(config.provider, config.apiKey, config.baseUrl) {
        if (!config.hasModel) {
            check = KeyCheck.None
            return@LaunchedEffect
        }
        check = KeyCheck.Checking
        delay(350)
        check = try {
            KeyCheck.Works(listModels(config))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? ModelsError)?.refused == true) KeyCheck.Refused(e.message.orEmpty()) else KeyCheck.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    var sheet by remember { mutableStateOf<String?>(null) }
    var trial by remember { mutableStateOf("") }
    var trialError by remember { mutableStateOf<String?>(null) }
    var trying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val claude = config.provider == Provider.CLAUDE
    val phoneSpeaks = partner || config.speechLanguage.isNotEmpty()
    val known = model.settings.specs.keys

    SettingsPage(stringResource(R.string.topic_meta_ai)) {
        item(key = "main") {
            Rows(
                { VisorToggleRow(model.toggles.byId("assistant")!!, sub = phase.words()) },
                if (!hearing) row {
                    RowFrame(stringResource(R.string.access_mic), onClick = null) {
                        ActionPill(stringResource(R.string.allow), { askHearing.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.padding(start = 12.dp))
                    }
                } else null,
            )
        }
        item(key = "model") {
            Column {
                SectionLabel(stringResource(R.string.ai_section_model))
                Rows(
                    {
                        PillsRow(
                            stringResource(R.string.ai_provider),
                            listOf(Provider.CLAUDE to "Claude", Provider.OPENAI_COMPATIBLE to stringResource(R.string.ai_openai_compatible)),
                            config.provider,
                            onPick = { p -> model.store.update { it.copy(provider = p) } },
                        )
                    },
                    if (!claude) row {
                        ValueRow(stringResource(R.string.ai_base_url), config.baseUrl, onClick = { sheet = "url" })
                    } else null,
                    {
                        val (sub, problem) = when (val c = check) {
                            KeyCheck.None -> (if (claude) stringResource(R.string.ai_key_needed) else null) to null
                            KeyCheck.Checking -> stringResource(R.string.ai_key_checking) to null
                            is KeyCheck.Works -> pluralStringResource(R.plurals.ai_key_works, c.models.size, c.models.size) to null
                            is KeyCheck.Refused -> null to stringResource(R.string.ai_key_refused, c.words)
                            is KeyCheck.Failed -> null to stringResource(R.string.ai_key_failed, c.words)
                        }
                        ValueRow(
                            stringResource(R.string.ai_key),
                            masked(config.apiKey) ?: stringResource(R.string.settings_not_set),
                            onClick = { sheet = "key" }, sub = sub, problem = problem, busy = check == KeyCheck.Checking,
                        )
                    },
                    {
                        ValueRow(
                            stringResource(R.string.ai_model),
                            modelWords(config.model.ifBlank { if (claude) DEFAULT_CLAUDE_MODEL else "" }).ifEmpty { stringResource(R.string.settings_not_set) },
                            onClick = { sheet = "model" },
                        )
                    },
                    {
                        ValueRow(
                            stringResource(R.string.ai_lines_model),
                            if (config.linesModel.isBlank()) stringResource(R.string.ai_same_model) else modelWords(config.linesModel),
                            onClick = { sheet = "lines" },
                        )
                    },
                    {
                        // Tests the key and the model: the answer, or why there is none, comes under the row.
                        val ready = check !is KeyCheck.Refused && config.hasModel
                        val prompt = stringResource(R.string.ai_try_prompt)
                        RowFrame(
                            stringResource(R.string.ai_try),
                            dim = !ready,
                            onClick = if (ready && !trying) ({
                                trial = ""
                                trialError = null
                                trying = true
                                scope.launch {
                                    runCatching { model.assistant.ask(prompt) }
                                        .onSuccess { trial = it }
                                        .onFailure { trialError = it.message.orEmpty() }
                                    trying = false
                                }
                            }) else null,
                            sub = trial.ifEmpty { null },
                            problem = trialError?.let { stringResource(R.string.ai_try_failed, it) },
                            busy = trying,
                        )
                    },
                )
            }
        }
        item(key = "behaviour") {
            Column {
                SectionLabel(stringResource(R.string.ai_section_answers))
                Rows(
                    if (!partner) row {
                        ValueRow(
                            stringResource(R.string.ai_speech_language),
                            config.speechLanguage.takeIf { it.isNotEmpty() }?.let(::languageName) ?: stringResource(R.string.ai_speech_language_glasses),
                            onClick = { sheet = "speech" },
                        )
                    } else null,
                    {
                        if (phoneSpeaks) ValueRow(stringResource(R.string.ai_voice), stringResource(R.string.ai_voice_phone), onClick = null, dim = true)
                        else PillsRow(
                            stringResource(R.string.ai_voice),
                            listOf(true to stringResource(R.string.ai_voice_glasses), false to stringResource(R.string.ai_voice_phone)),
                            config.glassesVoice,
                            onPick = { on -> model.store.update { it.copy(glassesVoice = on) } },
                        )
                    },
                    { VisorToggleRow(model.toggles.byId("vision")!!) },
                    if (partner || config.voicePhotos) row { VisorToggleRow(model.toggles.byId("voice_photos")!!) } else null,
                    if (claude) row { VisorToggleRow(model.toggles.byId("web")!!) } else null,
                    { VisorToggleRow(model.toggles.byId("quiet_actions")!!) },
                    {
                        PillsRow(
                            stringResource(R.string.ai_memory_window),
                            listOf(5, 10, 20, 30).map { it to stringResource(R.string.ai_minutes, it) },
                            config.memoryMinutes,
                            onPick = { m -> model.store.update { it.copy(memoryMinutes = m) } },
                        )
                    },
                    {
                        PillsRow(
                            stringResource(R.string.ai_answer_wait),
                            ANSWER_WAITS.map { it to stringResource(R.string.ai_seconds, it) },
                            config.answerWaitSeconds,
                            onPick = { s -> model.store.update { it.copy(answerWaitSeconds = s) } },
                        )
                    },
                    {
                        ValueRow(
                            stringResource(R.string.ai_instructions),
                            config.instructions.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ai_instructions_none),
                            onClick = { sheet = "instructions" },
                        )
                    },
                )
            }
        }
        item(key = "places") {
            Column(Modifier.padding(top = 28.dp)) {
                Rows(
                    { NavRow(VisorIcons.Plug, stringResource(R.string.mcp_title), integrationsSummary(model.hub), onClick = { open(IntegrationsKey) }, onCard = true) },
                    { NavRow(VisorIcons.Memory, stringResource(R.string.meta_ai_memories), null, onClick = { open(MemoriesKey) }, onCard = true) },
                    { WakeWordRow(onClick = { open(WakeWordKey) }) },
                )
            }
        }
        item(key = "glasses") {
            Column {
                SectionLabel(stringResource(R.string.section_on_glasses))
                Rows(*Topic.META_AI.keys.filter { it in known }.map { key -> @Composable { GlassesRow(key) } }.toTypedArray())
            }
        }
        featureSection(Topic.META_AI, open)
    }

    when (sheet) {
        "key" -> TextSheet(
            stringResource(R.string.ai_key), config.apiKey, onSave = { key -> model.store.update { it.copy(apiKey = key.trim()) } },
            onDismiss = { sheet = null }, secret = true, keyboard = KeyboardType.Password,
            extra = { set -> ActionPill(stringResource(R.string.ai_paste), { paste(context)?.let { set(it.trim()) } }, quiet = true) },
        )
        "url" -> TextSheet(
            stringResource(R.string.ai_base_url), config.baseUrl, onSave = { url -> model.store.update { it.copy(baseUrl = url.trim()) } },
            onDismiss = { sheet = null }, keyboard = KeyboardType.Uri, valid = ::isWebAddress,
        )
        "instructions" -> TextSheet(
            stringResource(R.string.ai_instructions), config.instructions, onSave = { text -> model.store.update { it.copy(instructions = text) } },
            onDismiss = { sheet = null }, singleLine = false,
        )
        "speech" -> ChoiceSheet(
            stringResource(R.string.ai_speech_language),
            listOf(Triple("", stringResource(R.string.ai_speech_language_glasses), null)) + SPEECH_LANGUAGES.map { Triple(it, languageName(it), null) },
            config.speechLanguage,
            onPick = { tag -> model.store.update { it.copy(speechLanguage = tag) } },
            onDismiss = { sheet = null },
        )
        "model", "lines" -> {
            val lines = sheet == "lines"
            ModelSheet(
                title = stringResource(if (lines) R.string.ai_lines_model else R.string.ai_model),
                check = check,
                current = if (lines) config.linesModel else config.model,
                emptyLabel = if (lines) stringResource(R.string.ai_same_model) else if (claude) stringResource(R.string.ai_default_model, modelWords(DEFAULT_CLAUDE_MODEL)) else null,
                onPick = { id -> model.store.update { if (lines) it.copy(linesModel = id) else it.copy(model = id) } },
                onDismiss = { sheet = null },
            )
        }
    }
}

/** The provider's models, newest first, with the empty choice on top and a way to type an id it does not list. */
@Composable
private fun ModelSheet(title: String, check: KeyCheck, current: String, emptyLabel: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var typing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    if (typing) {
        TextSheet(title, current, onSave = { onPick(it.trim()) }, onDismiss = onDismiss, hint = "model-id")
        return
    }
    VisorSheet(onDismiss, full = true) {
        SheetTitle(title)
        val models = (check as? KeyCheck.Works)?.models.orEmpty()
        if (models.size > 10) SearchField(query, { query = it }, stringResource(R.string.settings_filter), Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
        when (check) {
            KeyCheck.Checking -> Row(Modifier.padding(horizontal = SIDE, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Text(stringResource(R.string.ai_key_checking), style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(start = 12.dp))
            }
            is KeyCheck.Refused -> Text(stringResource(R.string.ai_key_refused, check.words), style = Visor.type.body, color = Visor.colors.signal, modifier = Modifier.padding(horizontal = SIDE, vertical = 8.dp))
            is KeyCheck.Failed -> Text(stringResource(R.string.ai_key_failed, check.words), style = Visor.type.body, color = Visor.colors.signal, modifier = Modifier.padding(horizontal = SIDE, vertical = 8.dp))
            else -> Unit
        }
        val needle = query.trim().lowercase()
        val shown = models.filter { needle.isEmpty() || needle in it.id.lowercase() || needle in it.name.lowercase() }
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            if (emptyLabel != null && needle.isEmpty()) item { ModelRow(emptyLabel, null, current.isBlank()) { onPick(""); onDismiss() } }
            items(shown.size) { i ->
                val m = shown[i]
                ModelRow(m.name, m.id.takeIf { it != m.name }, m.id == current) { onPick(m.id); onDismiss() }
            }
            item { ModelRow(stringResource(R.string.ai_other_model), null, false) { typing = true } }
        }
    }
}

@Composable
private fun ModelRow(name: String, id: String?, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().semantics { selected = on }.clickable(onClick = onClick).padding(horizontal = SIDE, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = Visor.type.body, color = Visor.colors.textPrimary)
            if (id != null) KeyLine(id)
        }
        if (on) Icon(VisorIcons.Check, null, Modifier.padding(start = 12.dp).size(22.dp), tint = Color.White)
    }
}

/** A language's own name: "Русский (Россия)". */
private fun languageName(tag: String): String = Locale.forLanguageTag(tag).ownName()

@Composable
private fun AssistantPhase.words(): String? = when (this) {
    AssistantPhase.OFF -> null
    AssistantPhase.WAITING -> stringResource(R.string.ai_phase_waiting)
    AssistantPhase.REGISTERING -> stringResource(R.string.ai_phase_registering)
    AssistantPhase.LISTENING -> stringResource(R.string.ai_phase_listening)
    AssistantPhase.THINKING -> stringResource(R.string.ai_phase_thinking)
    AssistantPhase.SPEAKING -> stringResource(R.string.ai_phase_speaking)
}
