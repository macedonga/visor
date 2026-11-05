package com.macedonga.visor.features

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.HearingTestKey
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.CorrectionTone
import com.macedonga.visor.core.assistant.Hearing
import com.macedonga.visor.core.assistant.HearingRefusal
import com.macedonga.visor.core.assistant.HearingRun
import com.macedonga.visor.core.assistant.HearingState
import com.macedonga.visor.settings.LocalHost
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SectionLabel
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.settings.SliderRow
import com.macedonga.visor.settings.ToggleRow
import com.macedonga.visor.settings.ValueRow
import com.macedonga.visor.settings.row
import com.macedonga.visor.ui.VisorIcons
import kotlin.math.roundToInt

/** Hearing correction: start and stop, sound once fitted, the hearing test and Meta's lock. */
@Composable
fun HearingCorrectionScreen(open: (NavKey) -> Unit, model: FeaturesModel = hiltViewModel()) {
    val state by model.features.state.collectAsStateWithLifecycle()
    val hearing by model.hearing.state.collectAsStateWithLifecycle()
    val tested by model.features.tested.collectAsStateWithLifecycle()
    val feature = Feature.HEARING_CORRECTION
    val standing = state.standings[feature] ?: Standing.UNKNOWN
    val allowed = standing == Standing.ON
    // The glasses' word when they give it, else what the last test here left.
    val fitted = hearing.fitted ?: tested

    SettingsPage(stringResource(R.string.feature_hc)) {
        item(key = "now") {
            val host = LocalHost.current
            // Both switches are Meta's: read them once the glasses are there.
            LaunchedEffect(host.ready) {
                if (host.ready && !state.configRead && !state.busy) model.features.check()
            }
            CorrectionNow(hearing, standing, busy = state.busy, fitted, model, open)
        }
        if (fitted && allowed) {
            item(key = "sound") {
                val host = LocalHost.current
                val tone = hearing.correction ?: CorrectionTone()
                val locked = !host.ready
                val range = Hearing.MIN_TONE.toFloat()..Hearing.MAX_TONE.toFloat()
                val steps = Hearing.MAX_TONE - Hearing.MIN_TONE - 1
                val plus: (Float) -> String = { v -> signed(v.roundToInt()) }
                val left = stringResource(R.string.hc_balance_left)
                val right = stringResource(R.string.hc_balance_right)
                val centre = stringResource(R.string.hc_balance_centre)
                Column {
                    SectionLabel(stringResource(R.string.cf_sound))
                    Rows(
                        row {
                            SliderRow(stringResource(R.string.hc_amplification), tone.volume.toFloat(), range, steps, plus, { model.hearing.tuneCorrection(tone.copy(volume = it.roundToInt())); true }, locked = locked)
                        },
                        row {
                            SliderRow(stringResource(R.string.hc_sharpness), tone.sharpness.toFloat(), range, steps, plus, { model.hearing.tuneCorrection(tone.copy(sharpness = it.roundToInt())); true }, locked = locked)
                        },
                        row {
                            SliderRow(
                                stringResource(R.string.hc_balance), tone.balance.toFloat(), range, steps,
                                { v -> v.roundToInt().let { if (it < 0) "$left ${-it}" else if (it > 0) "$right $it" else centre } },
                                { model.hearing.tuneCorrection(tone.copy(balance = it.roundToInt())); true }, locked = locked,
                            )
                        },
                        row {
                            ToggleRow(
                                stringResource(R.string.hc_noisy), tone.noisy,
                                onChange = { model.hearing.tuneCorrection(tone.copy(noisy = it)) },
                                locked = locked,
                            )
                        },
                        row { ValueRow(stringResource(R.string.hct_title), null, onClick = { open(HearingTestKey) }, locked = locked) },
                    )
                }
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
                            stringResource(R.string.hc_allow), allowed,
                            onChange = { model.features.set(feature, it) },
                            locked = !host.ready, busy = standing == Standing.SENDING || state.busy,
                        )
                    },
                )
            }
        }
    }
}

/** Where hearing correction stands, in a word and a line, with the one button that changes it. */
@Composable
private fun CorrectionNow(hearing: HearingState, standing: Standing, busy: Boolean, fitted: Boolean, model: FeaturesModel, open: (NavKey) -> Unit) {
    val feature = Feature.HEARING_CORRECTION
    val allowed = standing == Standing.ON
    val run = hearing.runFor(feature)
    val paused = run.paused
    val title = when {
        standing == Standing.UNKNOWN -> R.string.settings_not_read
        !allowed -> R.string.cf_now_locked
        !fitted -> R.string.hc_now_untested
        run == HearingRun.ON -> R.string.cf_now_on
        paused -> R.string.cf_now_paused
        else -> R.string.cf_now_off
    }
    val (sub, warn) = when {
        standing == Standing.REFUSED -> R.string.features_refused to true
        !allowed || !fitted || run == HearingRun.ON -> null to false
        paused -> causeWords(hearing.cause) to false
        // USECASE_ENABLE_FAILURE: the glasses' audio chip took the left ear's profile and refused the right's.
        hearing.refusal == HearingRefusal.ENABLE_FAILED -> R.string.hc_refused_chip to true
        hearing.refusal != null -> R.string.cf_refused to true
        else -> null to false
    }
    val (label, act) = when {
        standing == Standing.UNKNOWN -> R.string.try_again to model.features::check
        !allowed -> R.string.cf_allow_now to { model.features.set(feature, true) }
        !fitted -> R.string.hc_test_take to { open(HearingTestKey) }
        run == HearingRun.ON -> R.string.cf_stop to model.hearing::stop
        paused -> R.string.cf_resume to model.hearing::resume
        else -> R.string.cf_start_now to { model.hearing.startCorrection() }
    }
    NowCard(
        VisorIcons.HearingAid, lit = run == HearingRun.ON, title = stringResource(title), sub = sub?.let { stringResource(it) }, warn = warn,
        action = label, quiet = run == HearingRun.ON || standing == Standing.UNKNOWN,
        busy = standing == Standing.SENDING || standing == Standing.CHECKING || (busy && !allowed),
        onAction = { act() },
    )
}
