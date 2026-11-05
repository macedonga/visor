package com.macedonga.visor.features

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.FocusStyle
import com.macedonga.visor.core.assistant.Hearing
import com.macedonga.visor.core.assistant.HearingCause
import com.macedonga.visor.core.assistant.HearingRefusal
import com.macedonga.visor.core.assistant.HearingRun
import com.macedonga.visor.core.assistant.HearingState
import com.macedonga.visor.settings.LocalHost
import com.macedonga.visor.settings.PillsRow
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SectionLabel
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.settings.SliderRow
import com.macedonga.visor.settings.ToggleRow
import com.macedonga.visor.settings.row
import com.macedonga.visor.settings.specOf
import com.macedonga.visor.settings.words
import com.macedonga.visor.ui.VisorIcons
import kotlin.math.roundToInt

/** Conversation focus: start and stop, sound, Meta's lock and the touchpad shortcut. */
@Composable
fun ConversationFocusScreen(model: FeaturesModel = hiltViewModel()) {
    val state by model.features.state.collectAsStateWithLifecycle()
    val hearing by model.hearing.state.collectAsStateWithLifecycle()
    val tuning by model.features.focus.collectAsStateWithLifecycle()
    val feature = Feature.CONVERSATION_FOCUS
    val standing = state.standings[feature] ?: Standing.UNKNOWN
    val allowed = standing == Standing.ON
    val (style, volume) = tuning

    SettingsPage(stringResource(R.string.feature_cf)) {
        item(key = "now") {
            val host = LocalHost.current
            // The minimum build focus needs is one of Meta's switches: read them once the glasses are there.
            LaunchedEffect(host.ready) {
                if (host.ready && !state.configRead && !state.busy) model.features.check()
            }
            FocusNow(hearing, standing, busy = state.busy, model)
        }
        if (allowed) item(key = "sound") {
            val host = LocalHost.current
            val locked = !host.ready
            Column {
                SectionLabel(stringResource(R.string.cf_sound))
                Rows(
                    row {
                        PillsRow(
                            stringResource(R.string.cf_style),
                            listOf(FocusStyle.STANDARD to stringResource(R.string.cf_style_standard), FocusStyle.BOOSTED to stringResource(R.string.cf_style_boosted)),
                            selected = hearing.style ?: style,
                            onPick = { model.features.tuneFocus(it, hearing.volume ?: volume) },
                            locked = locked,
                        )
                    },
                    row {
                        SliderRow(
                            stringResource(R.string.cf_volume),
                            value = (hearing.volume ?: volume).toFloat(),
                            range = Hearing.MIN_VOLUME.toFloat()..Hearing.MAX_VOLUME.toFloat(),
                            steps = Hearing.MAX_VOLUME - Hearing.MIN_VOLUME - 1,
                            words = { v -> signed(v.roundToInt()) },
                            onDone = { model.features.tuneFocus(hearing.style ?: style, it.roundToInt()); true },
                            locked = locked,
                        )
                    },
                )
            }
        }
        if (allowed) item(key = "start") {
            val host = LocalHost.current
            val holds = model.features.holdStartsFocus(host.state)
            val before = host.settings.value(HOLD_KEY)?.let { specOf(host.settings, HOLD_KEY).words(it) }
            Column {
                SectionLabel(stringResource(R.string.cf_start))
                Rows(
                    row {
                        ToggleRow(
                            stringResource(R.string.cf_hold), holds,
                            onChange = { model.features.setHoldStartsFocus(it) },
                            sub = if (holds) null else before?.let { stringResource(R.string.sum_controls_hold, it) },
                            locked = !host.ready, busy = HOLD_KEY in host.state.pending,
                        )
                    },
                )
            }
        }
        // Allowed, the switch at the foot is how to take it back; until then the card offers Allow.
        if (allowed) item(key = "allow") {
            val host = LocalHost.current
            Column {
                SectionLabel(stringResource(R.string.cf_allow_section))
                Rows(
                    row {
                        ToggleRow(
                            stringResource(R.string.cf_allow), allowed,
                            onChange = { model.features.set(feature, it) },
                            locked = !host.ready, busy = standing == Standing.SENDING || state.busy,
                        )
                    },
                )
            }
        }
    }
}

/** Where conversation focus stands now, in a word and a line, with the one button that changes it. */
@Composable
private fun FocusNow(hearing: HearingState, standing: Standing, busy: Boolean, model: FeaturesModel) {
    val feature = Feature.CONVERSATION_FOCUS
    val allowed = standing == Standing.ON
    val run = hearing.runFor(feature)
    val paused = run.paused
    val title = when {
        standing == Standing.UNKNOWN -> R.string.settings_not_read
        !allowed -> R.string.cf_now_locked
        run == HearingRun.ON -> R.string.cf_now_on
        paused -> R.string.cf_now_paused
        else -> R.string.cf_now_off
    }
    val refusal = hearing.refusal?.takeIf { run != HearingRun.ON }
    val (sub, warn) = when {
        standing == Standing.REFUSED -> stringResource(R.string.features_refused) to true
        !allowed -> null to false
        refusal != null -> stringResource(refusalWords(refusal)) to true
        run == HearingRun.ON -> listOfNotNull(
            hearing.style?.let { stringResource(if (it == FocusStyle.BOOSTED) R.string.cf_style_boosted else R.string.cf_style_standard) },
            hearing.volume?.let { stringResource(R.string.cf_now_volume, signed(it)) },
        ).joinToString(" · ").ifEmpty { null } to false
        paused -> stringResource(causeWords(hearing.cause)) to false
        else -> null to false
    }
    val (label, act) = when {
        standing == Standing.UNKNOWN -> R.string.try_again to model.features::check
        !allowed -> R.string.cf_allow_now to { model.features.set(feature, true) }
        run == HearingRun.ON -> R.string.cf_stop to model.hearing::stop
        paused -> R.string.cf_resume to model.hearing::resume
        else -> R.string.cf_start_now to model.features::startFocus
    }
    NowCard(
        VisorIcons.Ear, lit = run == HearingRun.ON, title = stringResource(title), sub = sub, warn = warn,
        action = label, quiet = run == HearingRun.ON || standing == Standing.UNKNOWN,
        busy = standing == Standing.SENDING || standing == Standing.CHECKING || (busy && !allowed),
        onAction = { act() },
    )
}

internal fun causeWords(cause: HearingCause): Int = when (cause) {
    HearingCause.PHONE_CALL -> R.string.cf_paused_call
    HearingCause.MUSIC, HearingCause.STREAMING_AUDIO -> R.string.cf_paused_audio
    HearingCause.LIVE_STREAMING -> R.string.cf_paused_live
    HearingCause.CAPTURING_VIDEO -> R.string.cf_paused_video
    HearingCause.THERMAL -> R.string.cf_paused_warm
    HearingCause.TIMEOUT -> R.string.cf_paused_timeout
    else -> R.string.cf_paused
}

private fun refusalWords(refusal: HearingRefusal): Int = when (refusal) {
    HearingRefusal.NUX_REQUIRED -> R.string.cf_refused_setup
    HearingRefusal.NO_ACCESS -> R.string.cf_refused_access
    HearingRefusal.GEOGATED -> R.string.cf_refused_region
    HearingRefusal.INVALID_DEVICE -> R.string.cf_refused_device
    HearingRefusal.BT_REQUIRED -> R.string.cf_refused_bluetooth
    else -> R.string.cf_refused
}
