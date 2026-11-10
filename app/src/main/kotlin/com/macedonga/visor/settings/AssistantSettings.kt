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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import com.macedonga.visor.core.assistant.ChatGptPlan
import com.macedonga.visor.core.assistant.ModelInfo
import com.macedonga.visor.core.assistant.ModelsError
import com.macedonga.visor.core.assistant.Provider
import com.macedonga.visor.core.assistant.SPEECH_LANGUAGES
import com.macedonga.visor.core.assistant.TtsPath
import com.macedonga.visor.core.assistant.listModels
import com.macedonga.visor.core.assistant.previewSpeech
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
    val chatGpt: ChatGptPlan,
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
    val voice = settingsState.stored[VOICE_KEY] ?: model.settings.value(VOICE_KEY)
    val partner = isPhoneVoice(voice)
    val sessionVoices by model.store.sessionVoices.collectAsStateWithLifecycle()
    val phase by model.assistant.phase.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var hearing by rememberOnResume { Need.MIC.granted(context) }
    val askHearing = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hearing = granted
        if (!granted && activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) openAppPage(context)
    }

    var check by remember { mutableStateOf<KeyCheck>(KeyCheck.None) }
    LaunchedEffect(config.provider, config.apiKey, config.baseUrl, config.chatGpt) {
        if (!config.hasModel) {
            check = KeyCheck.None
            return@LaunchedEffect
        }
        check = KeyCheck.Checking
        delay(350)
        check = try {
            val models = listModels(config, model.chatGpt)
            // The plan lists only models it answers with, in its own order: the first is the one to start from.
            if (config.provider == Provider.CHATGPT && config.model.isBlank()) {
                models.firstOrNull()?.let { m -> model.store.update { it.copy(model = m.id) } }
            }
            KeyCheck.Works(models)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? ModelsError)?.refused == true) KeyCheck.Refused(e.message.orEmpty()) else KeyCheck.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // Listing the speech service's models checks its key and fills the model list.
    var ttsCheck by remember { mutableStateOf<KeyCheck>(KeyCheck.None) }
    LaunchedEffect(config.tts, config.ttsBaseUrl, config.ttsApiKey) {
        if (config.tts != TtsPath.CLOUD || config.ttsApiKey.isBlank()) {
            ttsCheck = KeyCheck.None
            return@LaunchedEffect
        }
        ttsCheck = KeyCheck.Checking
        delay(350)
        val probe = config.copy(provider = Provider.OPENAI_COMPATIBLE, baseUrl = config.ttsBaseUrl, apiKey = config.ttsApiKey)
        ttsCheck = try {
            KeyCheck.Works(listModels(probe))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? ModelsError)?.refused == true) KeyCheck.Refused(e.message.orEmpty()) else KeyCheck.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    var sheet by remember { mutableStateOf<String?>(null) }

    // Sign in with ChatGPT: the page opens once; back in Visor the plan's first use is said once.
    val signIn by model.chatGpt.signIn.collectAsStateWithLifecycle()
    val limited by model.chatGpt.limited.collectAsStateWithLifecycle()
    val noteShown by model.chatGpt.noteShown.collectAsStateWithLifecycle()
    var opened by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(signIn) {
        val s = signIn
        if (s is ChatGptPlan.SignIn.Waiting && s.url != opened) {
            opened = s.url
            model.chatGpt.pageOpened(s.url)
            openInBrowser(context, s.url)
        }
    }
    // Back from the browser without signing in: the row stops waiting.
    LifecycleResumeEffect(Unit) {
        model.chatGpt.leftBrowser()
        onPauseOrDispose { }
    }
    val plan = config.provider == Provider.CHATGPT
    LaunchedEffect(plan, config.chatGpt, noteShown) {
        if (plan && config.chatGpt.isNotEmpty() && !noteShown) sheet = "plan_note"
    }
    val noSearch by model.store.noSearch.collectAsStateWithLifecycle()

    var trial by remember { mutableStateOf("") }
    var trialError by remember { mutableStateOf<String?>(null) }
    var trying by remember { mutableStateOf(false) }
    var ttsTrying by remember { mutableStateOf(false) }
    var ttsProblem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val phoneSpeaks = phoneSpeaksVoice(voice, sessionVoices) || config.speechLanguage.isNotEmpty()
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
                    { ValueRow(stringResource(R.string.ai_provider), providerName(config.provider), onClick = { sheet = "provider" }) },
                    if (config.provider == Provider.OPENAI_COMPATIBLE) row {
                        ValueRow(stringResource(R.string.ai_base_url), config.baseUrl, onClick = { sheet = "url" })
                    } else null,
                    if (plan) row {
                        ChatGptRow(config.chatGpt, signIn, limited, check, onStart = model.chatGpt::start, onOpen = { sheet = "chatgpt" })
                    } else row {
                        val (sub, problem) = keyWords(check)
                        // Without a key, the way to make one is on the row.
                        val getKey = config.apiKey.isBlank() && config.provider.keys.isNotEmpty()
                        RowFrame(
                            stringResource(R.string.ai_key),
                            onClick = { sheet = "key" },
                            value = masked(config.apiKey) ?: stringResource(R.string.settings_not_set).takeIf { !getKey },
                            sub = sub, problem = problem, busy = check == KeyCheck.Checking,
                        ) {
                            if (getKey) {
                                ActionPill(stringResource(R.string.ai_get_key), { openInBrowser(context, config.provider.keys) }, Modifier.padding(start = 12.dp), quiet = true)
                            } else {
                                Icon(VisorIcons.Chevron, null, Modifier.padding(start = 8.dp).size(16.dp), tint = Visor.colors.textTertiary)
                            }
                        }
                    },
                    {
                        ValueRow(
                            stringResource(R.string.ai_model),
                            modelWords(config.answerModel).ifEmpty { stringResource(R.string.settings_not_set) },
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
                        // When the glasses can't speak a turn, their own voice is no choice; the cloud still is.
                        PillsRow(
                            stringResource(R.string.ai_voice),
                            listOf(
                                TtsPath.GLASSES to stringResource(R.string.ai_voice_glasses),
                                TtsPath.PHONE to stringResource(R.string.ai_voice_phone),
                                TtsPath.CLOUD to stringResource(R.string.ai_tts_cloud),
                            ).filter { (path, _) -> !phoneSpeaks || path != TtsPath.GLASSES },
                            if (phoneSpeaks && config.tts == TtsPath.GLASSES) TtsPath.PHONE else config.tts,
                            onPick = { path -> model.store.update { it.copy(tts = path) } },
                        )
                    },
                    if (config.tts == TtsPath.CLOUD) row {
                        ValueRow(stringResource(R.string.ai_tts_url), config.ttsBaseUrl, onClick = { sheet = "tts_url" })
                    } else null,
                    if (config.tts == TtsPath.CLOUD) row {
                        val (sub, problem) = when (val c = ttsCheck) {
                            KeyCheck.None -> null to null
                            KeyCheck.Checking -> stringResource(R.string.ai_key_checking) to null
                            is KeyCheck.Works -> pluralStringResource(R.plurals.ai_key_works, c.models.size, c.models.size) to null
                            is KeyCheck.Refused -> null to stringResource(R.string.ai_key_refused, c.words)
                            is KeyCheck.Failed -> null to stringResource(R.string.ai_key_failed, c.words)
                        }
                        ValueRow(
                            stringResource(R.string.ai_tts_key),
                            masked(config.ttsApiKey) ?: stringResource(R.string.settings_not_set),
                            onClick = { sheet = "tts_key" }, sub = sub, problem = problem, busy = ttsCheck == KeyCheck.Checking,
                        )
                    } else null,
                    if (config.tts == TtsPath.CLOUD) row {
                        ValueRow(
                            stringResource(R.string.ai_tts_model),
                            modelWords(config.ttsModel).ifEmpty { stringResource(R.string.settings_not_set) },
                            onClick = { sheet = "tts_model" },
                        )
                    } else null,
                    if (config.tts == TtsPath.CLOUD) row {
                        ValueRow(
                            stringResource(R.string.ai_tts_voice),
                            config.ttsVoice.ifEmpty { stringResource(R.string.ai_tts_voice_default) },
                            onClick = { sheet = "tts_voice" },
                        )
                    } else null,
                    if (config.tts == TtsPath.CLOUD) row {
                        // A sample sentence on the phone's speaker: no model and no glasses.
                        val sample = stringResource(R.string.ai_tts_sample)
                        RowFrame(
                            stringResource(R.string.ai_tts_try),
                            onClick = if (!ttsTrying) ({
                                ttsProblem = null
                                ttsTrying = true
                                scope.launch {
                                    try {
                                        previewSpeech(config, sample)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        ttsProblem = e.message ?: e.javaClass.simpleName
                                    } finally {
                                        ttsTrying = false
                                    }
                                }
                            }) else null,
                            sub = if (ttsTrying) stringResource(R.string.ai_tts_trying) else null,
                            problem = ttsProblem,
                            busy = ttsTrying,
                        )
                    } else null,
                    { VisorToggleRow(model.toggles.byId("vision")!!) },
                    if (partner || config.voicePhotos) row { VisorToggleRow(model.toggles.byId("voice_photos")!!) } else null,
                    if (config.provider.search != null) row {
                        val refused = config.webSearch && "${config.provider.name} ${config.answerModel}" in noSearch
                        VisorToggleRow(model.toggles.byId("web")!!, problem = if (refused) stringResource(R.string.ai_web_unavailable) else null)
                    } else null,
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
        "provider" -> ChoiceSheet(
            stringResource(R.string.ai_provider),
            Provider.entries.map { Triple(it, providerName(it), providerDetail(it)) },
            config.provider,
            onPick = model.store::switchProvider,
            onDismiss = { sheet = null },
        )
        "key" -> TextSheet(
            providerName(config.provider) + " · " + stringResource(R.string.ai_key), config.apiKey,
            onSave = { key -> model.store.update { it.copy(apiKey = key.trim()) } },
            onDismiss = { sheet = null }, secret = true, keyboard = KeyboardType.Password,
            extra = { set ->
                ActionPill(stringResource(R.string.ai_paste), { paste(context)?.let { set(it.trim()) } }, quiet = true)
                if (config.provider.keys.isNotEmpty()) {
                    ActionPill(stringResource(R.string.ai_get_key), { openInBrowser(context, config.provider.keys) }, quiet = true)
                }
            },
        )
        "chatgpt" -> VisorSheet({ sheet = null }) {
            SheetBody {
                SheetTitle(
                    if (limited) stringResource(R.string.ai_chatgpt_limit) else config.chatGpt,
                    stringResource(if (limited) R.string.ai_chatgpt_limit_text else R.string.ai_chatgpt_using),
                )
            }
            SheetAnswers(
                stringResource(R.string.ai_chatgpt_manage), { openInBrowser(context, CHATGPT_USAGE) },
                stringResource(R.string.mcp_sign_out), {
                    sheet = null
                    model.chatGpt.signOut()
                },
            )
        }
        "plan_note" -> VisorSheet({ model.chatGpt.noteSeen(); sheet = null }) {
            SheetBody { SheetTitle(stringResource(R.string.ai_chatgpt_plan_title), stringResource(R.string.ai_chatgpt_plan_text)) }
            SheetAnswers(
                stringResource(R.string.ai_chatgpt_got_it), { model.chatGpt.noteSeen(); sheet = null },
                stringResource(R.string.ai_chatgpt_manage), { openInBrowser(context, CHATGPT_USAGE) },
            )
        }
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
        "tts_url" -> TextSheet(
            stringResource(R.string.ai_tts_url), config.ttsBaseUrl, onSave = { url -> model.store.update { it.copy(ttsBaseUrl = url.trim()) } },
            onDismiss = { sheet = null }, keyboard = KeyboardType.Uri, valid = ::isWebAddress,
        )
        "tts_key" -> TextSheet(
            stringResource(R.string.ai_tts_key), config.ttsApiKey, onSave = { key -> model.store.update { it.copy(ttsApiKey = key.trim()) } },
            onDismiss = { sheet = null }, secret = true, keyboard = KeyboardType.Password,
            extra = { set -> ActionPill(stringResource(R.string.ai_paste), { paste(context)?.let { set(it.trim()) } }, quiet = true) },
        )
        "tts_model" -> ModelSheet(
            title = stringResource(R.string.ai_tts_model),
            check = ttsCheck,
            current = config.ttsModel,
            emptyLabel = null,
            onPick = { id -> model.store.update { it.copy(ttsModel = id) } },
            onDismiss = { sheet = null },
        )
        "tts_voice" -> TextSheet(
            stringResource(R.string.ai_tts_voice), config.ttsVoice, onSave = { name -> model.store.update { it.copy(ttsVoice = name.trim()) } },
            onDismiss = { sheet = null }, hint = "voice-id",
        )
        "model", "lines" -> {
            val lines = sheet == "lines"
            ModelSheet(
                title = stringResource(if (lines) R.string.ai_lines_model else R.string.ai_model),
                check = check,
                current = if (lines) config.linesModel else config.model,
                emptyLabel = if (lines) stringResource(R.string.ai_same_model)
                    else config.provider.defaultModel.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.ai_default_model, modelWords(it)) },
                onPick = { id -> model.store.update { if (lines) it.copy(linesModel = id) else it.copy(model = id) } },
                onDismiss = { sheet = null },
            )
        }
    }
}

@Composable
private fun keyWords(check: KeyCheck): Pair<String?, String?> = when (check) {
    KeyCheck.None -> null to null
    KeyCheck.Checking -> stringResource(R.string.ai_key_checking) to null
    is KeyCheck.Works -> pluralStringResource(R.plurals.ai_key_works, check.models.size, check.models.size) to null
    is KeyCheck.Refused -> null to stringResource(R.string.ai_key_refused, check.words)
    is KeyCheck.Failed -> null to stringResource(R.string.ai_key_failed, check.words)
}

@Composable
private fun providerName(p: Provider): String = if (p == Provider.OPENAI_COMPATIBLE) stringResource(R.string.ai_provider_other) else p.title

/** A line under a provider only where its name does not say how it is paid or reached. */
@Composable
private fun providerDetail(p: Provider): String? = when (p) {
    Provider.CHATGPT -> stringResource(R.string.ai_provider_chatgpt_sub)
    Provider.OPENAI -> stringResource(R.string.ai_provider_openai_sub)
    Provider.OPENAI_COMPATIBLE -> stringResource(R.string.ai_openai_compatible)
    else -> null
}

/**
 * The ChatGPT account: Continue with ChatGPT while signed out, with how the sign-in goes; signed in, the account,
 * that answers use its plan, and a reached limit.
 */
@Composable
private fun ChatGptRow(account: String, signIn: ChatGptPlan.SignIn, limited: Boolean, check: KeyCheck, onStart: () -> Unit, onOpen: () -> Unit) {
    val title = stringResource(R.string.ai_chatgpt_account)
    if (account.isNotEmpty()) {
        val problem = keyWords(check).second
        ValueRow(
            title, account, onClick = onOpen,
            sub = stringResource(R.string.ai_chatgpt_using).takeIf { !limited && problem == null },
            problem = if (limited) stringResource(R.string.ai_chatgpt_limit) else problem,
            busy = check == KeyCheck.Checking,
        )
        return
    }
    val failed = signIn as? ChatGptPlan.SignIn.Failed
    RowFrame(
        title, onClick = null, busy = signIn is ChatGptPlan.SignIn.Finishing,
        sub = if (signIn is ChatGptPlan.SignIn.Waiting) stringResource(R.string.mcp_finish_in_browser) else null,
        problem = failed?.let {
            when (it.why) {
                ChatGptPlan.Why.DECLINED -> stringResource(R.string.ai_chatgpt_declined)
                ChatGptPlan.Why.NOT_ELIGIBLE -> stringResource(R.string.ai_chatgpt_not_eligible)
                ChatGptPlan.Why.EXPIRED -> stringResource(R.string.ai_chatgpt_expired)
                ChatGptPlan.Why.OTHER -> stringResource(R.string.ai_chatgpt_failed, it.words)
            }
        },
    ) {
        // Tapped again while the browser is out, it starts over: a closed tab is not a dead end.
        ActionPill(stringResource(R.string.ai_chatgpt_continue), onStart, Modifier.padding(start = 12.dp), enabled = signIn !is ChatGptPlan.SignIn.Finishing)
    }
}

/** Where the person sees and limits what Visor uses of their plan. */
private const val CHATGPT_USAGE = "https://chatgpt.com/settings/usage"

/** The phone speaks a partner-language voice, and a glasses voice that runs session turns (sessionVoices). */
fun phoneSpeaksVoice(voice: String?, sessionVoices: Set<String>): Boolean =
    isPhoneVoice(voice) || (voice != null && voice in sessionVoices)

/** The provider's models, newest first, with the empty choice on top and a way to type an id it does not list. */
@Composable
private fun ModelSheet(title: String, check: KeyCheck, current: String, emptyLabel: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var typing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    if (typing) {
        TextSheet(title, current, onSave = { onPick(it.trim()) }, onDismiss = onDismiss, hint = "model-id")
        return
    }
    VisorSheet(onDismiss) {
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
