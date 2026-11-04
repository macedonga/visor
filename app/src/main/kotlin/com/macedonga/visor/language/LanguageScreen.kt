package com.macedonga.visor.language

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.settings.Card
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.Spinner
import com.macedonga.visor.settings.SheetTitle
import com.macedonga.visor.settings.ChoiceMark
import com.macedonga.visor.settings.RowFrame
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.Footnote
import com.macedonga.visor.settings.GlassesRow
import com.macedonga.visor.settings.Hairline
import com.macedonga.visor.settings.ReadSettingsWhenReady
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SectionLabel
import com.macedonga.visor.settings.SettingsPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LanguageViewModel @Inject constructor(
    val installer: LanguageInstaller,
    val settings: GlassesSettings,
    val connection: GlassesConnection,
) : ViewModel()

/** The glasses' language: the one in use and its voices, the others to switch to or fetch from Meta. */
@Composable
fun LanguageScreen(model: LanguageViewModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val state by model.settings.state.collectAsStateWithLifecycle()
    val work by model.installer.work.collectAsStateWithLifecycle()
    val installed by model.installer.installed.collectAsStateWithLifecycle()
    val offers by model.installer.offers.collectAsStateWithLifecycle()
    val offersProblem by model.installer.offersProblem.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    ReadSettingsWhenReady(model.settings, ready)
    LaunchedEffect(glasses.info != null) { if (glasses.info != null && offers.isEmpty()) model.installer.refreshOffers() }

    val voiceId = state.stored["assistant_voice_id"] ?: model.settings.value("assistant_voice_id")
    val current = languageOfVoice(voiceId)
    // A switch sent and not yet seen back on the glasses, and one they refused.
    val switching = state.pending["assistant_voice_id"]
    val refused = "assistant_voice_id" in state.kept
    val busy = work is LanguageWork.Working || switching != null
    var asking by remember { mutableStateOf<GlassesLanguage?>(null) }

    SettingsPage(stringResource(R.string.topic_language), stringResource(R.string.language_hero_sub)) {
        item(key = "current") {
            Column {
                SectionLabel(stringResource(R.string.language_in_use), Modifier.padding(top = 0.dp))
                Rows({
                    RowFrame(
                        current?.nativeName() ?: voiceId ?: stringResource(R.string.settings_not_read), onClick = null,
                        value = if (current?.partner == true) stringResource(R.string.ai_voice_phone) else current?.voices?.firstOrNull { it.id == voiceId }?.label(),
                        sub = current?.localName()?.takeIf { it != current.nativeName() },
                        busy = switching != null,
                        problem = if (refused) stringResource(R.string.language_kept) else null,
                    )
                })
            }
        }
        item(key = "work") { WorkCard(work, model.installer::cancel, model.installer::dismiss) }
        if (!ready) item(key = "away") { Footnote(stringResource(R.string.language_need_glasses)) }
        if (current != null && !current.partner) item(key = "voices") {
            Column {
                SectionLabel(stringResource(R.string.language_voice))
                Card {
                    current.voices.forEachIndexed { i, voice ->
                        if (i > 0) Hairline()
                        VoiceRow(voice, voice.id == voiceId, waiting = switching == voice.id, enabled = ready && !busy) { model.installer.use(voice) }
                    }
                }
            }
        }
        if ("tts_speak_speed" in model.settings.specs) {
            item(key = "speed") {
                Column {
                    SectionLabel(stringResource(R.string.language_speech))
                    Rows({ GlassesRow("tts_speak_speed") })
                }
            }
        }
        item(key = "languages") {
            Column {
                SectionLabel(stringResource(R.string.language_languages))
                val others = GLASSES_LANGUAGES.filter { it != current }.sortedWith(compareBy({ it.locale !in installed }, { it.nativeName() }))
                Card {
                    others.forEachIndexed { i, language ->
                        if (i > 0) Hairline()
                        val on = language.locale in installed
                        val size = language.pack?.let { offers[it] }?.size
                        val waiting = (switching != null && language.voices.any { it.id == switching }) ||
                            (work as? LanguageWork.Working)?.language == language
                        LanguageRow(
                            language,
                            detail = when {
                                language.pack == null -> stringResource(R.string.language_built_in)
                                on -> stringResource(R.string.language_on_glasses)
                                size != null -> mb(size)
                                else -> ""
                            },
                            action = if (on) stringResource(R.string.language_use) else stringResource(R.string.language_download),
                            enabled = ready && !busy,
                            waiting = waiting,
                        ) {
                            if (on) model.installer.use(language.voices.first { it.id == language.defaultVoice }) else asking = language
                        }
                    }
                }
                if (offersProblem.isNotEmpty()) Footnote(stringResource(R.string.language_offers_problem, offersProblem))
                Footnote(stringResource(R.string.language_footnote))
            }
        }
        item(key = "partner_languages") {
            Column {
                SectionLabel(stringResource(R.string.language_partner))
                Footnote(stringResource(R.string.language_partner_sub))
                Card {
                    PARTNER_LANGUAGES.filter { it != current }.sortedBy { it.nativeName() }.forEachIndexed { i, language ->
                        if (i > 0) Hairline()
                        LanguageRow(
                            language,
                            detail = stringResource(R.string.ai_voice_phone),
                            action = stringResource(R.string.language_use),
                            enabled = ready && !busy,
                            waiting = switching == language.defaultVoice || (work as? LanguageWork.Working)?.language == language,
                        ) { model.installer.use(language.voices.first()) }
                    }
                }
            }
        }
    }

    asking?.let { language ->
        val size = language.pack?.let { offers[it] }?.size
        VisorSheet({ asking = null }) {
            SheetTitle(
                stringResource(R.string.language_confirm_title, language.nativeName()),
                stringResource(R.string.language_confirm_text, size?.let(::mb) ?: stringResource(R.string.language_unknown_size)),
            )
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionPill(stringResource(R.string.language_download), {
                    asking = null
                    model.installer.install(language)
                })
                ActionPill(stringResource(R.string.language_cancel), { asking = null }, quiet = true)
            }
        }
    }
}

@Composable
private fun WorkCard(work: LanguageWork, onCancel: () -> Unit, onDismiss: () -> Unit) {
    AnimatedVisibility(work !is LanguageWork.Idle) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Visor.colors.raised).padding(20.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (work) {
                is LanguageWork.Working -> {
                    Text(work.language.nativeName(), style = Visor.type.heading, color = Visor.colors.textPrimary)
                    val step = when (work.step) {
                        LanguageWork.Step.CHECKING_PHONE -> stringResource(R.string.language_checking_phone)
                        LanguageWork.Step.ASKING -> stringResource(R.string.language_asking)
                        LanguageWork.Step.DOWNLOADING -> stringResource(R.string.language_downloading)
                        LanguageWork.Step.SENDING -> stringResource(R.string.language_sending)
                        LanguageWork.Step.SWITCHING -> stringResource(R.string.language_switching)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(step, style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.weight(1f))
                        if (work.total > 0) Text("${mb(work.done)} / ${mb(work.total)}", style = Visor.type.readoutSmall, color = Visor.colors.textSecondary)
                    }
                    StepBar(work)
                    if (work.step != LanguageWork.Step.SWITCHING) Text(
                        stringResource(R.string.language_cancel),
                        style = Visor.type.label,
                        color = Visor.colors.textPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Visor.colors.control).clickable(onClick = onCancel).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                is LanguageWork.Finished -> {
                    Done(stringResource(if (work.language.partner) R.string.language_partner_done else R.string.language_done, work.language.nativeName()), Visor.colors.textPrimary, onDismiss)
                    if (!work.recognitionChecked) Text(stringResource(R.string.language_recognition_unknown), style = Visor.type.body, color = Visor.colors.textSecondary)
                }
                is LanguageWork.Failed -> Done(stringResource(if (work.language.partner) R.string.language_partner_failed else R.string.language_failed, work.language.nativeName(), work.reason), Visor.colors.signal, onDismiss)
                LanguageWork.Idle -> Unit
            }
        }
    }
}

/** Four steps in one bar: asking and switching are short, the download and the transfer fill it by bytes. */
@Composable
private fun StepBar(work: LanguageWork.Working) {
    val within = if (work.total > 0) (work.done.toFloat() / work.total).coerceIn(0f, 1f) else 0f
    val target = when (work.step) {
        LanguageWork.Step.CHECKING_PHONE -> 0.02f
        LanguageWork.Step.ASKING -> 0.02f
        LanguageWork.Step.DOWNLOADING -> 0.02f + 0.48f * within
        LanguageWork.Step.SENDING -> 0.5f + 0.48f * within
        LanguageWork.Step.SWITCHING -> 0.99f
    }
    val shown by animateFloatAsState(target, tween(400), label = "language progress")
    LinearProgressIndicator(
        progress = { shown },
        modifier = Modifier.fillMaxWidth().height(6.dp),
        color = Color.White,
        trackColor = Visor.colors.control,
        strokeCap = StrokeCap.Round,
        drawStopIndicator = {},
    )
}

@Composable
private fun Done(text: String, color: Color, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = Visor.type.body, color = color, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.language_ok),
            style = Visor.type.label,
            color = Color.Black,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun VoiceRow(voice: GlassesVoice, selected: Boolean, waiting: Boolean, enabled: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().semantics { this.selected = selected }.clickable(enabled = enabled && !selected, onClick = onPick).padding(horizontal = 16.dp, vertical = 13.dp)
            .alpha(if (enabled || selected || waiting) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(voice.label(), style = Visor.type.body, color = Visor.colors.textPrimary)
            if (voice.name != null) Text(voice.tone(), style = Visor.type.label, color = Visor.colors.textSecondary)
        }
        if (waiting) Spinner() else ChoiceMark(selected, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun LanguageRow(language: GlassesLanguage, detail: String, action: String, enabled: Boolean, waiting: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp)
            .alpha(if (enabled || waiting) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(language.nativeName(), style = Visor.type.body, color = Visor.colors.textPrimary)
            val sub = listOf(language.localName().takeIf { it != language.nativeName() }, detail.ifEmpty { null }).filterNotNull().joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = Visor.type.label, color = Visor.colors.textSecondary)
        }
        if (waiting) {
            Spinner()
        } else {
            ActionPill(action, onClick, quiet = true, enabled = enabled)
        }
    }
}
