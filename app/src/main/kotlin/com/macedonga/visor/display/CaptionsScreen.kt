package com.macedonga.visor.display

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.Captions
import com.macedonga.visor.core.connection.CaptionLine
import com.macedonga.visor.core.connection.CaptionsMode
import com.macedonga.visor.core.connection.CaptionsPhase
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.gallery.RoundIcon
import com.macedonga.visor.settings.ChoiceSheet
import com.macedonga.visor.settings.PillsRow
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SectionLabel
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.settings.Spinner
import com.macedonga.visor.settings.ValueRow
import com.macedonga.visor.settings.row
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Captions or translation, and the languages, as last chosen. */
data class CaptionsChoice(val mode: CaptionsMode = CaptionsMode.CAPTIONS, val self: String = "en", val other: String = "en") {
    /** The other side's language: for captions there is one. */
    val heard: String get() = if (mode == CaptionsMode.CAPTIONS) self else other
}

@Singleton
class CaptionsChoices @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("captions", Context.MODE_PRIVATE)
    private val _choice = MutableStateFlow(
        CaptionsChoice(
            runCatching { CaptionsMode.valueOf(prefs.getString(MODE, null).orEmpty()) }.getOrDefault(CaptionsMode.CAPTIONS),
            prefs.getString(SELF, null) ?: "en",
            prefs.getString(OTHER, null) ?: "en",
        ),
    )
    val choice: StateFlow<CaptionsChoice> = _choice.asStateFlow()

    fun choose(choice: CaptionsChoice) {
        _choice.value = choice
        prefs.edit().putString(MODE, choice.mode.name).putString(SELF, choice.self).putString(OTHER, choice.other).apply()
    }

    private companion object {
        const val MODE = "mode"
        const val SELF = "self"
        const val OTHER = "other"
    }
}

@HiltViewModel
class CaptionsModel @Inject constructor(
    val choices: CaptionsChoices,
    val captions: Captions,
    val connection: GlassesConnection,
    val packs: CaptionPacks,
) : ViewModel() {
    /** Keep [choice]; while the glasses caption, they stop and start again with it. */
    fun choose(choice: CaptionsChoice) {
        choices.choose(choice)
        if (captions.state.value.phase != CaptionsPhase.LIVE) return
        viewModelScope.launch {
            captions.stop()
            captions.state.first { it.phase == CaptionsPhase.IDLE }
            captions.start(choice.mode, choice.self, choice.heard)
        }
    }
}

/** Captions and translation on the Display: what and in which languages, then what the glasses hear, turn by turn. */
@Composable
fun CaptionsScreen(model: CaptionsModel = hiltViewModel()) {
    val choice by model.choices.choice.collectAsStateWithLifecycle()
    val state by model.captions.state.collectAsStateWithLifecycle()
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    val feature = CaptionPacks.featureFor(choice.mode, choice.self, choice.heard)
    val installed by model.packs.installed.collectAsStateWithLifecycle()
    val offers by model.packs.offers.collectAsStateWithLifecycle()
    val work by model.packs.work.collectAsStateWithLifecycle()
    LaunchedEffect(feature, ready) { if (ready) model.packs.look(feature) }
    var picking by remember { mutableStateOf<Side?>(null) }
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val list = rememberLazyListState()
    val transcript = state.earlier + state.lines
    val last = state.lines.lastOrNull()
    // Follow the newest turn, unless the transcript was scrolled back to read.
    LaunchedEffect(state.lines.size, last?.text) {
        val info = list.layoutInfo
        val end = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (end >= info.totalItemsCount - 3) list.animateScrollToItem(info.totalItemsCount - 1)
    }
    SettingsPage(
        stringResource(R.string.captions_title),
        list = list,
        actions = {
            if (transcript.isNotEmpty()) RoundIcon(VisorIcons.Share, stringResource(R.string.transcript_share), { share(context, transcript) })
        },
        sheet = {
            CaptionsBar(
                ready = ready,
                phase = state.phase,
                refusal = state.refusal,
                pack = PackView(feature in installed, offers[feature]?.size, work.takeIf { it !is PackWork.Idle }),
                onGet = { haptics.done(); model.packs.install(feature) },
                onCancel = { model.packs.cancel() },
                onStart = { haptics.done(); model.captions.start(choice.mode, choice.self, choice.heard) },
                onStop = { haptics.toggle(); model.captions.stop() },
            )
            picking?.let { side ->
                ChoiceSheet(
                    stringResource(if (side == Side.SELF) if (choice.mode == CaptionsMode.CAPTIONS) R.string.captions_language else R.string.captions_self else R.string.captions_other),
                    languageOptions(),
                    if (side == Side.SELF) choice.self else choice.other,
                    { model.choose(if (side == Side.SELF) choice.copy(self = it) else choice.copy(other = it)) },
                    { picking = null },
                )
            }
        },
    ) {
        item(key = "choice") {
            Rows(
                row {
                    PillsRow(
                        stringResource(R.string.captions_mode),
                        listOf(CaptionsMode.CAPTIONS to stringResource(R.string.captions_mode_captions), CaptionsMode.TRANSLATION to stringResource(R.string.captions_mode_translation)),
                        choice.mode, { model.choose(choice.copy(mode = it)) },
                    )
                },
                row {
                    ValueRow(
                        stringResource(if (choice.mode == CaptionsMode.CAPTIONS) R.string.captions_language else R.string.captions_self),
                        languageName(choice.self), { picking = Side.SELF },
                    )
                },
                if (choice.mode == CaptionsMode.TRANSLATION) row {
                    ValueRow(stringResource(R.string.captions_other), languageName(choice.other), { picking = Side.OTHER })
                } else null,
            )
        }
        if (state.lines.isNotEmpty() || state.phase == CaptionsPhase.LIVE) {
            item(key = "heard") { SectionLabel(stringResource(R.string.captions_heard)) }
        }
        if (state.lines.isEmpty() && state.phase == CaptionsPhase.LIVE) {
            item(key = "listening") { Turn(stringResource(R.string.captions_listening), null, current = false) }
        }
        items(state.lines, key = { "line-" + it.id }) { line -> Turn(line.text, line.source, current = line === last && state.phase == CaptionsPhase.LIVE) }
        item(key = "foot") { Spacer(Modifier.height(BAR_ROOM)) }
    }
}

private enum class Side { SELF, OTHER }

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** Plain text, one turn per line as `HH:mm  said`; a translation goes on the line under it. */
private fun share(context: Context, lines: List<CaptionLine>) {
    val text = lines.joinToString("\n") { line ->
        val time = CLOCK.format(Instant.ofEpochMilli(line.time).atZone(ZoneId.systemDefault()))
        if (line.source == null) "$time  ${line.text}" else "$time  ${line.source}\n${line.text}"
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

/** One turn: the newest one bright while the glasses listen, what was said under a translation. */
@Composable
private fun Turn(text: String, source: String?, current: Boolean) {
    val colors = Visor.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)) {
        Text(text, style = Visor.type.body, color = if (current) colors.textPrimary else colors.textSecondary)
        if (source != null) Text(source, style = Visor.type.label, color = colors.textTertiary, modifier = Modifier.padding(top = 2.dp))
    }
}

/** At the foot of the page, over a fade: whether the glasses are captioning, and Start or Stop. */
@Composable
private fun CaptionsBar(
    ready: Boolean,
    phase: CaptionsPhase,
    refusal: Long?,
    pack: PackView,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = Visor.colors
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to colors.canvas))
                .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = bottom + 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val work = pack.work
            val line = when {
                work is PackWork.Working -> stringResource(
                    when (work.step) {
                        PackStep.DOWNLOADING -> R.string.language_downloading
                        PackStep.SENDING -> R.string.language_sending
                        PackStep.INSTALLING -> R.string.captions_installing
                    },
                ) + if (work.total > 0) " · ${megabytes(work.done)} / ${megabytes(work.total)}" else ""
                work is PackWork.Failed -> stringResource(R.string.captions_pack_failed, work.reason)
                !ready -> stringResource(R.string.captions_not_connected)
                refusal != null && phase == CaptionsPhase.IDLE -> refusalWords(refusal)
                phase == CaptionsPhase.LIVE -> stringResource(R.string.captions_live)
                else -> null
            }
            if (line != null) {
                val bad = work is PackWork.Failed || refusal != null && ready && phase == CaptionsPhase.IDLE
                Text(line, style = Visor.type.label, color = if (bad) colors.signal else colors.textSecondary, modifier = Modifier.padding(bottom = 12.dp))
            }
            when {
                work is PackWork.Working -> WidePill(stringResource(R.string.language_cancel), onCancel, Modifier.fillMaxWidth(), quiet = true)
                !ready -> Unit
                phase == CaptionsPhase.IDLE && !pack.installed -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WidePill(stringResource(R.string.captions_start_anyway), onStart, Modifier.weight(1f), quiet = true)
                    WidePill(
                        pack.size?.let { stringResource(R.string.captions_get_size, megabytes(it)) } ?: stringResource(R.string.language_download),
                        onGet, Modifier.weight(1f),
                    )
                }
                phase == CaptionsPhase.STARTING || phase == CaptionsPhase.STOPPING -> Box(Modifier.height(52.dp), contentAlignment = Alignment.Center) { Spinner() }
                phase == CaptionsPhase.LIVE -> WidePill(stringResource(R.string.captions_stop), onStop, Modifier.fillMaxWidth(), quiet = true)
                else -> WidePill(stringResource(R.string.captions_start), onStart, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Whether this phone sent the choice's pack, how big Meta's is, and a download or send under way. */
private data class PackView(val installed: Boolean, val size: Long?, val work: PackWork?)

private fun megabytes(bytes: Long) = "${(bytes + 500_000) / 1_000_000} MB"

/** The card's line: the language, or the two of a translation. */
@Composable
fun captionsSummary(choice: CaptionsChoice): String =
    if (choice.mode == CaptionsMode.CAPTIONS) languageName(choice.self)
    else stringResource(R.string.captions_pair, languageName(choice.other), languageName(choice.self))

private fun languageName(code: String): String =
    Locale.forLanguageTag(code).let { it.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } }

private fun languageOptions(): List<Triple<String, String, String?>> =
    LANGUAGES.map { Triple(it, languageName(it), null) }.sortedBy { it.second }

// The Display's own list, from its Captions app; packs beyond English come from Meta.
private val LANGUAGES = listOf(
    "en", "es", "it", "fr", "de", "pt", "zh", "ja", "ko", "hi", "te", "bn", "nl", "tl", "gu", "pa", "kn", "ml", "da",
    "fi", "sv", "ru", "vi", "tr", "he", "pl", "hu", "cs", "hr", "el", "ms", "ta", "mr", "ar", "id", "th", "ur",
)
private val BAR_ROOM = 120.dp
