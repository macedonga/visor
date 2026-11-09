package com.macedonga.visor.features

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.ConversationFocusKey
import com.macedonga.visor.HearingCorrectionKey
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.Hearing
import com.macedonga.visor.core.assistant.HearingMode
import com.macedonga.visor.core.assistant.HearingRun
import com.macedonga.visor.core.assistant.HearingState
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.ConfigState
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.Card
import com.macedonga.visor.settings.Footnote
import com.macedonga.visor.settings.KeyLine
import com.macedonga.visor.settings.LocalHost
import com.macedonga.visor.settings.Pins
import com.macedonga.visor.settings.RowFrame
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.SectionLabel
import com.macedonga.visor.settings.SheetTitle
import com.macedonga.visor.settings.Spinner
import com.macedonga.visor.settings.Topic
import com.macedonga.visor.settings.SheetBody
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.VisorSwitch
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import com.macedonga.visor.ui.switchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class FeaturesModel @Inject constructor(
    val features: Features,
    val config: GlassesConfig,
    val settings: GlassesSettings,
    val hearing: Hearing,
) : ViewModel()

/** [topic]'s hidden features as rows; a long press opens the switches Visor writes. */
fun LazyListScope.featureSection(topic: Topic, open: (NavKey) -> Unit, heading: Boolean = true) {
    val features = Feature.entries.filter { it.topic == topic }
    if (features.isNotEmpty()) item(key = "feature-rows") { FeatureSection(features, open, heading) }
}

@Composable
private fun FeatureSection(all: List<Feature>, open: (NavKey) -> Unit, heading: Boolean, model: FeaturesModel = hiltViewModel()) {
    val state by model.features.state.collectAsStateWithLifecycle()
    val now by model.hearing.state.collectAsStateWithLifecycle()
    val features = all.filter { it.offeredOn(state.product) }
    if (features.isEmpty()) return
    val host = LocalHost.current
    var details by remember { mutableStateOf<Feature?>(null) }
    // Meta's switches come in a short report: read once the glasses are there.
    LaunchedEffect(host.ready) {
        if (host.ready && !state.configRead && !state.busy && features.any { it.needsConfig }) model.features.check()
    }
    Column {
        if (heading) SectionLabel(stringResource(R.string.features_section))
        Rows(
            *features.map { f ->
                @Composable {
                    FeatureRow(
                        f, state.standings[f] ?: Standing.UNKNOWN, on = state.wanted[f] == true, waits = f in state.restart,
                        running = f.page && now.runFor(f) == HearingRun.ON, configRead = state.configRead, busy = state.busy,
                        open = open, toggle = { model.features.set(f, it) }, read = model.features::check, details = { details = f },
                    )
                }
            }.toTypedArray(),
        )
        state.problem?.let {
            Footnote(stringResource(if (it == FeatureProblem.NOT_READ) R.string.features_not_read else R.string.features_problem), Visor.colors.signal)
        }
    }
    details?.let { FeatureDetails(it, onDismiss = { details = null }) }
}

/** [feature]'s sheet, from its row or its tile. */
@Composable
fun FeatureDetails(feature: Feature, onDismiss: () -> Unit, model: FeaturesModel = hiltViewModel()) {
    val state by model.features.state.collectAsStateWithLifecycle()
    val config by model.config.state.collectAsStateWithLifecycle()
    FeatureSheet(
        feature, feature.gatesFor(state.device), state.standings[feature] ?: Standing.UNKNOWN, on = state.wanted[feature] == true, config = config,
        settings = model.settings, toggle = { model.features.set(feature, it) }, onDismiss = onDismiss,
    )
}

/** A pinned feature as its tile shows it, and what a tap on it does. */
class FeatureFace(val title: String, val state: String, val on: Boolean, val busy: Boolean, val act: () -> Unit)

/** [feature] as a quick tile: focus and correction start and stop, the rest turn on and off. */
@Composable
fun featureFace(feature: Feature, features: Features, hearing: Hearing, open: (NavKey) -> Unit): FeatureFace {
    val host = LocalHost.current
    val state by features.state.collectAsStateWithLifecycle()
    val standing = state.standings[feature] ?: Standing.UNKNOWN
    val title = stringResource(feature.title)
    if (feature.page) {
        val now by hearing.state.collectAsStateWithLifecycle()
        val tested by features.tested.collectAsStateWithLifecycle()
        val focus = feature == Feature.CONVERSATION_FOCUS
        val run = now.runFor(feature)
        val paused = run.paused
        val blocker = when {
            standing == Standing.UNKNOWN -> R.string.settings_not_read
            standing != Standing.ON -> R.string.cf_now_locked
            !focus && !(now.fitted ?: tested) -> R.string.hc_now_untested
            else -> null
        }
        val words = blocker ?: when {
            run == HearingRun.ON -> R.string.sum_on
            paused -> R.string.cf_now_paused
            else -> R.string.sum_off
        }
        return FeatureFace(title, stringResource(words), on = run == HearingRun.ON, busy = false) {
            when {
                blocker != null -> open(pageOf(feature))
                run == HearingRun.ON -> hearing.stop()
                paused -> hearing.resume()
                focus -> features.startFocus()
                else -> hearing.startCorrection()
            }
        }
    }
    val on = state.wanted[feature] == true
    val busy = standing == Standing.SENDING || standing == Standing.CHECKING || (standing == Standing.UNKNOWN && state.busy)
    val words = when (standing) {
        Standing.UNKNOWN -> R.string.settings_not_read
        Standing.PARTLY -> R.string.tile_partly
        Standing.REFUSED -> R.string.tile_refused
        Standing.UNCONFIRMED -> R.string.features_unconfirmed
        Standing.ON -> if (feature in state.restart) R.string.features_restart_title else R.string.sum_on
        else -> if (on) R.string.sum_on else R.string.sum_off
    }
    val connectFirst = stringResource(R.string.settings_locked)
    return FeatureFace(title, stringResource(words), on = standing == Standing.ON, busy) {
        when {
            !features.ready -> host.notice.show(connectFirst)
            // Unread or unconfirmed: the tap reads the glasses again.
            (feature.needsConfig && !state.configRead) || standing == Standing.UNCONFIRMED -> if (!state.busy) features.check()
            !busy -> features.set(feature, !on)
        }
    }
}

/** A feature row: a tap turns it on or off or opens its page, a long press opens its switches. */
@Composable
private fun FeatureRow(
    feature: Feature,
    standing: Standing,
    on: Boolean,
    waits: Boolean,
    running: Boolean,
    configRead: Boolean,
    busy: Boolean,
    open: (NavKey) -> Unit,
    toggle: (Boolean) -> Boolean,
    read: () -> Unit,
    details: () -> Unit,
) {
    val host = LocalHost.current
    val connectFirst = stringResource(R.string.settings_locked)
    val locked = !host.ready || (feature.needsConfig && !configRead)
    val tap: () -> Unit = when {
        feature.page -> ({ open(pageOf(feature)) })
        !host.ready -> ({
            host.haptics.tick(0.3f)
            host.notice.show(connectFirst)
        })
        // Unread or unconfirmed: the tap reads the glasses again.
        (feature.needsConfig && !configRead) || standing == Standing.UNCONFIRMED -> ({
            host.haptics.tick(0.3f)
            if (!busy) read()
        })
        standing == Standing.SENDING || standing == Standing.CHECKING -> ({ host.haptics.tick(0.3f) })
        else -> ({
            host.haptics.toggle()
            if (!toggle(!on)) host.notice.show(connectFirst)
        })
    }
    val (line, warn) = lineOf(feature, standing, waits)
    val spinning = standing == Standing.SENDING || standing == Standing.CHECKING || (standing == Standing.UNKNOWN && busy)
    // Unread or unconfirmed with the glasses there: a read, not a switch.
    val recheck = !feature.page && host.ready && ((feature.needsConfig && !configRead) || standing == Standing.UNCONFIRMED)
    RowFrame(
        stringResource(feature.title),
        onClick = tap,
        onLongClick = { host.haptics.tick(0.5f); details() },
        busy = spinning,
        sub = if (warn) null else line,
        problem = if (warn) line else null,
        dim = locked && !feature.page && !recheck,
        modifier = if (feature.page || recheck) Modifier else Modifier.switchRow(on),
        leading = { FeatureTile(feature.icon, lit = if (feature.page) running else standing == Standing.ON) },
    ) {
        when {
            spinning -> Unit
            feature.page -> Icon(VisorIcons.Chevron, null, Modifier.padding(start = 8.dp).size(16.dp), tint = Visor.colors.textTertiary)
            recheck -> ActionPill(stringResource(R.string.try_again), tap, Modifier.padding(start = 12.dp), quiet = true)
            else -> VisorSwitch(on)
        }
    }
}

/** The feature's icon on a rounded tile, which lights up white while the feature is on. */
@Composable
internal fun FeatureTile(icon: ImageVector, lit: Boolean, size: Int = 40) {
    val back by animateColorAsState(if (lit) Color.White else Visor.colors.control, tween(240), label = "tile")
    val ink by animateColorAsState(if (lit) Color.Black else Visor.colors.textPrimary, tween(240), label = "ink")
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(back),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size((size / 2).dp), tint = ink)
    }
}

/** A hearing feature's status card: its tile, where it stands, and the one pill that changes it, or a spinner while [busy]. */
@Composable
internal fun NowCard(
    icon: ImageVector,
    lit: Boolean,
    title: String,
    sub: String?,
    warn: Boolean,
    @StringRes action: Int,
    quiet: Boolean = false,
    busy: Boolean = false,
    onAction: () -> Unit,
) {
    val host = LocalHost.current
    val connectFirst = stringResource(R.string.settings_locked)
    Card {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FeatureTile(icon, lit = lit, size = 52)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Visor.type.heading, color = Visor.colors.textPrimary, modifier = Modifier.asHeading())
                if (sub != null) Text(sub, style = Visor.type.label, color = if (warn) Visor.colors.signal else Visor.colors.textSecondary)
            }
            if (busy) {
                Spinner(Modifier.padding(start = 12.dp))
            } else {
                Spacer(Modifier.width(12.dp))
                ActionPill(
                    stringResource(action),
                    {
                        if (!host.ready) {
                            host.notice.show(connectFirst)
                        } else {
                            host.haptics.toggle()
                            onAction()
                        }
                    },
                    Modifier.minimumInteractiveComponentSize(),
                    quiet = quiet,
                )
            }
        }
    }
}

/** [feature]'s run: the hearing app runs one mode at a time. */
internal fun HearingState.runFor(feature: Feature): HearingRun {
    val modes = if (feature == Feature.CONVERSATION_FOCUS) setOf(HearingMode.FOCUS, HearingMode.UNSET) else setOf(HearingMode.CORRECTION, HearingMode.CORRECTION_NARROW)
    return if (mode in modes) run else HearingRun.OFF
}

internal fun signed(n: Int) = if (n > 0) "+$n" else "$n"

/** The line under a feature's name, if any, and whether it is bad news. */
@Composable
private fun lineOf(feature: Feature, standing: Standing, waits: Boolean): Pair<String?, Boolean> {
    val note = feature.note?.let { stringResource(it) }
    return when (standing) {
        Standing.UNKNOWN -> stringResource(R.string.settings_not_read) to false
        Standing.OFF -> note to false
        Standing.PARTLY -> stringResource(R.string.tile_partly) to false
        Standing.SENDING -> stringResource(R.string.features_sending) to false
        Standing.CHECKING -> stringResource(R.string.features_checking) to false
        Standing.UNCONFIRMED -> stringResource(R.string.features_unconfirmed) to false
        Standing.REFUSED -> stringResource(R.string.features_refused) to true
        Standing.ON -> (if (waits) stringResource(R.string.features_restart_title) else note) to false
    }
}

/** Where a feature with a page of its own lives. */
private fun pageOf(feature: Feature): NavKey = when (feature) {
    Feature.CONVERSATION_FOCUS -> ConversationFocusKey
    Feature.HEARING_CORRECTION -> HearingCorrectionKey
    else -> error("${feature.id} has no page")
}

/** A feature in full: what it does, how to use it, and every switch Visor writes for it, with where each stands. */
@Composable
private fun FeatureSheet(
    feature: Feature,
    gates: List<Gate>,
    standing: Standing,
    on: Boolean,
    config: ConfigState,
    settings: GlassesSettings,
    toggle: (Boolean) -> Boolean,
    onDismiss: () -> Unit,
) {
    val host = LocalHost.current
    val connectFirst = stringResource(R.string.settings_locked)
    VisorSheet(onDismiss) {
        SheetBody {
            Row(Modifier.padding(horizontal = SIDE).padding(top = 6.dp, bottom = 4.dp)) { FeatureTile(feature.icon, lit = standing == Standing.ON, size = 48) }
            SheetTitle(stringResource(feature.title), stringResource(feature.about))
            Column(Modifier.padding(horizontal = SIDE), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.features_how), style = Visor.type.label, color = Visor.colors.textSecondary)
                Text(stringResource(feature.use), style = Visor.type.body, color = Visor.colors.textPrimary)
                Spacer(Modifier.size(14.dp))
                Text(stringResource(R.string.features_changes), style = Visor.type.label, color = Visor.colors.textSecondary)
                for (gate in gates) KeyLine(gateWords(gate, config, settings))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 22.dp)) {
            val pin = Pins.feature(feature.id)
            ActionPill(stringResource(if (host.pins.has(pin)) R.string.settings_unpin else R.string.settings_pin), { host.pins.toggle(pin); onDismiss() }, quiet = true)
            Spacer(Modifier.weight(1f))
            if (!feature.page) {
                ActionPill(
                    stringResource(if (on) R.string.features_turn_off else R.string.features_turn_on),
                    {
                        host.haptics.toggle()
                        if (toggle(!on)) onDismiss() else host.notice.show(connectFirst)
                    },
                    enabled = host.ready && standing != Standing.SENDING && standing != Standing.CHECKING && (!feature.needsConfig || config.params.isNotEmpty()),
                )
            }
        }
    }
}

/** One switch in a line: where it lives, what the glasses hold, and what turns the feature on. */
private fun gateWords(gate: Gate, config: ConfigState, settings: GlassesSettings): String = when (gate) {
    // A floor holds at any build but -1.
    is Gate.Config -> "${gate.name}  ${config.reported[gate.name] ?: "–"} ${if (gate.floor) "≠ -1" else "→ ${gate.on}"}"
    is Gate.Setting -> "${gate.key}  ${settings.value(gate.key) ?: "–"} → ${gate.on}"
}
