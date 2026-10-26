package com.macedonga.visor.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.AboutKey
import com.macedonga.visor.AdvancedKey
import com.macedonga.visor.AssistantSettingsKey
import com.macedonga.visor.BackupKey
import com.macedonga.visor.DeviceKey
import com.macedonga.visor.EventLogKey
import com.macedonga.visor.FlagsKey
import com.macedonga.visor.GlassesKey
import com.macedonga.visor.LanguageKey
import com.macedonga.visor.LiveSettingsKey
import com.macedonga.visor.OnboardingKey
import com.macedonga.visor.MemoriesKey
import com.macedonga.visor.NotifyAppsKey
import com.macedonga.visor.PhoneAccessKey
import com.macedonga.visor.R
import com.macedonga.visor.SettingsGroupKey
import com.macedonga.visor.TopicKey
import com.macedonga.visor.core.assistant.AssistantConfig
import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.assistant.Provider
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.LiveSettingsStore
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.Control
import com.macedonga.visor.core.settings.GROUPS
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.Kind
import com.macedonga.visor.core.settings.SettingsState
import com.macedonga.visor.core.settings.asBool
import com.macedonga.visor.core.settings.display
import com.macedonga.visor.core.settings.offeredOn
import com.macedonga.visor.features.Feature
import com.macedonga.visor.features.Standing
import com.macedonga.visor.features.featureFace
import com.macedonga.visor.gallery.ImportChoice
import com.macedonga.visor.gallery.ProgressRing
import com.macedonga.visor.gallery.RoundIcon
import com.macedonga.visor.gallery.placesAllowed
import com.macedonga.visor.gallery.words
import com.macedonga.visor.home.StatusPill
import com.macedonga.visor.language.languageOfVoice
import com.macedonga.visor.media.spanWords
import com.macedonga.visor.notify.NotifyApps
import com.macedonga.visor.notify.NotifyChoice
import com.macedonga.visor.stage.Choreography
import com.macedonga.visor.stage.GlassesStage
import com.macedonga.visor.stage.HOME_STRIP
import com.macedonga.visor.stage.HOME_STRIP_SCALE
import com.macedonga.visor.stage.LightPool
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.POOL_Y
import com.macedonga.visor.stage.SHEET_RADIUS
import com.macedonga.visor.stage.STAGE_ASPECT
import com.macedonga.visor.stage.StageSpot
import com.macedonga.visor.stage.closedOf
import com.macedonga.visor.stage.liveliness
import com.macedonga.visor.stage.moodAway
import com.macedonga.visor.stage.sheetShape
import com.macedonga.visor.stage.turnsThePair
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.rememberTrailingFold
import com.macedonga.visor.ui.asHeading
import com.macedonga.visor.ui.lineLimit
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val settings: GlassesSettings,
    val connection: GlassesConnection,
    val importChoice: ImportChoice,
    val notifyChoice: NotifyChoice,
    val apps: NotifyApps,
    val assistant: AssistantConfigStore,
    val live: LiveSettingsStore,
    val pins: Pins,
    val toggles: VisorToggles,
    val backups: Backups,
    val updates: com.macedonga.visor.update.Updates,
    val features: com.macedonga.visor.features.Features,
    val lastSeen: com.macedonga.visor.home.LastSeen,
    val hearing: com.macedonga.visor.core.assistant.Hearing,
    val band: com.macedonga.visor.band.BandLink,
    val bands: com.macedonga.visor.band.Bands,
    val bandPeers: com.macedonga.visor.band.BandPeers,
    val developer: com.macedonga.visor.outside.DeveloperMode,
) : ViewModel()

/**
 * Settings: the glasses on the one stage, lit and posed as the home leaves them, a sheet under them with a search over
 * everything, the switches pinned as tiles, and the settings by what they are about, each saying where it stands. The
 * pair travels here from the home while the sheet rises, and back while it sinks. As the sheet scrolls the stage
 * folds into a strip, as on the home.
 */
@Composable
fun SettingsScreen(open: (NavKey) -> Unit, model: SettingsViewModel = hiltViewModel()) = SettingsHost(seeThrough = true) {
    val context = LocalContext.current
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val state by model.settings.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    val stage = LocalStage.current
    val host = LocalHost.current
    val colors = Visor.colors
    val mood = moodAway(glasses, live = false, last = stage.mood)
    Choreography(glasses, mood, stage.light, stage.pose, onConnected = host.haptics::connected, onShutter = host.haptics::shutter)

    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stageHeight = maxWidth / STAGE_ASPECT
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val strip = top + BAR + STRIP + STRIP_GAP
        val fullPx = with(density) { stageHeight.toPx() }
        val stripPx = with(density) { strip.toPx() }
        val barPx = with(density) { (top + BAR).toPx() }
        val pairPx = with(density) { STRIP.toPx() }
        val screenPx = with(density) { maxHeight.toPx() }
        val scrollFold by rememberTrailingFold(fullPx - stripPx) {
            if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else fullPx
        }
        fun restHeight() = fullPx - scrollFold * (fullPx - stripPx)
        // Folded, the pair sits in the strip under the bar.
        val spot = StageSpot(closedOf(glasses, mood, stage.lastFolded), liveliness(mood)) {
            folding(fullPx, stripPx, scrollFold, STRIP_SCALE, barPx + pairPx / 2)
        }
        val sheetTop = remember {
            derivedStateOf {
                list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "stage" }?.let { (it.offset + it.size).toFloat() } ?: Float.NEGATIVE_INFINITY
            }
        }

        LazyColumn(
            state = list,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val restTop = maxOf(sheetTop.value, restHeight())
                    // Rises from below the screen as the pair arrives, and sinks again as it leaves.
                    translationY = (1f - stage.shown(spot)) * (screenPx - restTop)
                    clip = true
                    shape = sheetShape(restTop, SHEET_RADIUS.toPx())
                }
                .drawBehind { drawRect(colors.surface) },
            contentPadding = PaddingValues(bottom = bottom + 40.dp),
        ) {
            item(key = "stage") { Spacer(Modifier.fillMaxWidth().height(stageHeight)) }
            item(key = "search") {
                SearchField(query, { query = it }, stringResource(R.string.settings_search), Modifier.padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 18.dp))
            }
            if (query.isBlank()) {
                item(key = "tiles") { Tiles(model, open) }
                for ((i, card) in Topic.CARDS.withIndex()) {
                    item(key = "card-$i") {
                        Column(Modifier.padding(top = if (i == 0) 18.dp else 14.dp)) {
                            for (topic in card.filter { it.shownOn(state.product) }) {
                                NavRow(topic.icon, stringResource(topic.title), summary(topic, model, state), onClick = { open(placeOf(topic)) })
                            }
                        }
                    }
                }
                item(key = "advanced") {
                    val all = allKeys(model.settings, state)
                    val changed = all.count { state.changed(it, model.settings.catalogue[it]) }
                    Column(Modifier.padding(top = 14.dp)) {
                        NavRow(VisorIcons.Sliders, stringResource(R.string.advanced_title), stringResource(R.string.advanced_sum, all.size, changed), onClick = { open(AdvancedKey) })
                        val last by model.backups.last.collectAsStateWithLifecycle()
                        NavRow(
                            VisorIcons.Archive, stringResource(R.string.backup_title),
                            last?.let { stringResource(R.string.backup_sum_last, dateWords(it)) } ?: stringResource(R.string.backup_sum_never),
                            onClick = { open(BackupKey) },
                        )
                        val newer by model.updates.newer.collectAsStateWithLifecycle()
                        val version = com.macedonga.visor.BuildConfig.VERSION_NAME
                        NavRow(
                            VisorIcons.Info, stringResource(R.string.about_title),
                            newer?.let { stringResource(R.string.about_sum_update, version, it.version) } ?: stringResource(R.string.about_sum, version),
                            onClick = { open(AboutKey) },
                        )
                    }
                }
            } else {
                searchResults(query, model, state, glasses) { key ->
                    focus.clearFocus()
                    open(key)
                }
            }
        }

        // Where the stage is: a drag scrolls the sheet or turns the pair, a tap opens the Glasses page.
        Box(
            Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val h = restHeight().toInt()
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .scrollable(list, Orientation.Vertical, reverseDirection = true)
                .turnsThePair { host.haptics.tick(0.35f) }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open(GlassesKey) },
        ) {
            val remembered by model.lastSeen.battery.collectAsStateWithLifecycle()
            StatusPill(
                glasses, mood, remembered, paired = glasses.target?.let { true }, allowed = true, importing = false, importProgress = 0f, waiting = 0,
                onImport = {}, onAllow = {}, onSetUp = { open(OnboardingKey()) }, onChangeOwner = { open(OnboardingKey(login = true, address = it)) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp).graphicsLayer { alpha = (1f - (scrollFold * 2.5f).coerceIn(0f, 1f)) * stage.shown(spot) },
            )
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(BAR).padding(horizontal = 8.dp).graphicsLayer { alpha = stage.shown(spot) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(VisorIcons.ChevronLeft, stringResource(R.string.settings_back), LocalBack.current)
            Text(stringResource(R.string.settings_title), style = Visor.type.title, color = colors.textPrimary, modifier = Modifier.asHeading().padding(start = 4.dp))
        }
    }
}

/** Where a topic's row leads. */
fun placeOf(topic: Topic): NavKey = when (topic) {
    Topic.META_AI -> AssistantSettingsKey
    Topic.LANGUAGE -> LanguageKey
    Topic.LIVE -> LiveSettingsKey
    Topic.PHONE -> PhoneAccessKey
    else -> TopicKey(topic.name)
}

/**
 * The pinned switches, three to a row. A tap flips one, or opens its choices; held still, it opens its details;
 * held and moved, it follows the finger and the others make room.
 */
@Composable
private fun Tiles(model: SettingsViewModel, open: (NavKey) -> Unit) {
    val host = LocalHost.current
    val ids by model.pins.ids.collectAsStateWithLifecycle()
    val shown = ids.filter {
        it.startsWith("v:") && model.toggles.byId(it.removePrefix("v:")) != null || it.startsWith("g:") ||
            it.startsWith("f:") && Feature.byId(it.removePrefix("f:")) != null
    }
    // A pinned feature's switches come in the glasses' configuration: read it once they are there.
    val features by model.features.state.collectAsStateWithLifecycle()
    LaunchedEffect(host.ready) {
        val pinned = shown.filter { it.startsWith("f:") }.mapNotNull { Feature.byId(it.removePrefix("f:")) }
        if (host.ready && !features.configRead && !features.busy && pinned.any { it.needsConfig }) model.features.check()
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val gap = 10.dp
        val cellW = (maxWidth - gap * (COLUMNS - 1)) / COLUMNS
        // A large font gets taller tiles, so a tile's name and state still fit.
        val cellH = cellW * 0.86f * LocalDensity.current.fontScale.coerceIn(1f, 1.8f)
        val density = LocalDensity.current
        val stepX = with(density) { (cellW + gap).toPx() }
        val stepY = with(density) { (cellH + gap).toPx() }
        var dragging by remember { mutableStateOf<String?>(null) }
        var drag by remember { mutableStateOf(Offset.Zero) }
        val slots = shown.size + 1
        val rows = (slots + COLUMNS - 1) / COLUMNS
        Box(Modifier.fillMaxWidth().height(cellH * rows + gap * (rows - 1))) {
            // Keyed by id, so a tile being dragged keeps its gesture when it changes place.
            for ((i, id) in shown.withIndex()) key(id) {
                val col = i % COLUMNS
                val row = i / COLUMNS
                val lifted = dragging == id
                val order by rememberUpdatedState(shown)
                // Each tile glides to its slot; the lifted one rides the finger, and dropped, glides home from there.
                val slot = Offset(col * stepX, row * stepY)
                val place = remember { Animatable(slot, Offset.VectorConverter) }
                val following by rememberUpdatedState(slot)
                LaunchedEffect(lifted) {
                    if (lifted) snapshotFlow { following + drag }.collect { place.snapTo(it) }
                }
                LaunchedEffect(slot, lifted) {
                    if (!lifted) place.animateTo(slot, spring(dampingRatio = 0.78f, stiffness = 420f))
                }
                val lift by animateFloatAsState(if (lifted) 1.06f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "lift")
                Box(
                    Modifier
                        .offset { IntOffset(place.value.x.roundToInt(), place.value.y.roundToInt()) }
                        .zIndex(if (lifted || place.value != slot) 1f else 0f)
                        .size(cellW, cellH)
                        .graphicsLayer {
                            scaleX = lift
                            scaleY = lift
                        },
                ) {
                    Tile(
                        id, model, open,
                        onLift = {
                            host.haptics.tick(0.6f)
                            dragging = id
                            drag = Offset.Zero
                        },
                        onMove = { amount ->
                            drag += amount
                            val from = order.indexOf(id)
                            val c = (from % COLUMNS + (drag.x / stepX).roundToInt()).coerceIn(0, COLUMNS - 1)
                            val r = (from / COLUMNS + (drag.y / stepY).roundToInt()).coerceIn(0, (order.size - 1) / COLUMNS)
                            val to = (r * COLUMNS + c).coerceIn(0, order.size - 1)
                            if (to != from) {
                                // The tile takes its new place; the finger keeps its grip on it.
                                drag -= Offset((to % COLUMNS - from % COLUMNS) * stepX, (to / COLUMNS - from / COLUMNS) * stepY)
                                val full = model.pins.ids.value
                                model.pins.move(full.indexOf(id), full.indexOf(order[to]))
                                host.haptics.tick(0.3f)
                            }
                        },
                        onDrop = { moved ->
                            if (moved < STILL_PX) host.openDetails(id)
                            dragging = null
                            drag = Offset.Zero
                        },
                    )
                }
            }
            // Where the next pin goes, saying how to make one.
            val i = shown.size
            Box(Modifier.offset { IntOffset(((i % COLUMNS) * stepX).roundToInt(), ((i / COLUMNS) * stepY).roundToInt()) }.size(cellW, cellH)) {
                HintTile(stringResource(R.string.tiles_hint), Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * One tile. A tap acts on it; held, it lifts ([onLift]) and follows the finger ([onMove]) until it is dropped
 * ([onDrop], with how far it travelled). One detector for all three, so a hold never also counts as a tap.
 */
@Composable
private fun Tile(id: String, model: SettingsViewModel, open: (NavKey) -> Unit, onLift: () -> Unit, onMove: (Offset) -> Unit, onDrop: (Float) -> Unit) {
    val host = LocalHost.current
    val blocked = stringResource(R.string.settings_locked)
    var sheet by remember { mutableStateOf(false) }
    val title: String
    val state: String
    val on: Boolean?
    val locked: Boolean
    val busy: Boolean
    val act: () -> Unit
    if (id.startsWith("v:")) {
        val toggle = model.toggles.byId(id.removePrefix("v:")) ?: return
        val now by toggle.on.collectAsState()
        title = stringResource(toggle.label)
        on = now
        state = stringResource(if (now) R.string.sum_on else R.string.sum_off)
        locked = false
        busy = false
        act = { toggle.set(!now) }
    } else if (id.startsWith("f:")) {
        val face = featureFace(Feature.byId(id.removePrefix("f:")) ?: return, model.features, model.hearing, open)
        title = face.title
        on = face.on
        state = face.state
        locked = !host.ready
        busy = face.busy
        act = face.act
    } else {
        val key = id.removePrefix("g:")
        val info = host.settings.catalogue[key]
        val spec = specOf(host.settings, key)
        val value = host.state.valueOf(key, info)
        title = spec.label
        locked = !host.ready || info?.writable != true
        busy = key in host.state.pending
        on = if (spec.control == Control.Toggle) value?.let { asBool(it) != spec.invert } else null
        state = value?.let { spec.display(it) } ?: stringResource(R.string.settings_not_read)
        act = {
            if (on != null) {
                val bit = !on != spec.invert
                if (!host.settings.write(key, if (info?.kind == Kind.INT) (if (bit) "1" else "0") else bit.toString())) host.notice.show(blocked)
            } else if (spec.control is Control.Choice) {
                sheet = true
            } else {
                host.openDetails(id)
            }
        }
        if (sheet) {
            val c = spec.control as Control.Choice
            ChoiceSheet(spec.label, c.options.filter { !it.hidden || it.value == value }.map { Triple(it.value, it.label, null) }, value?.trim(), onPick = { host.settings.write(key, it) }, onDismiss = { sheet = false })
        }
    }
    val tap by rememberUpdatedState {
        if (locked) {
            host.haptics.tick(0.3f)
            host.notice.show(blocked)
        } else {
            host.haptics.toggle()
            act()
        }
    }
    val lift by rememberUpdatedState(onLift)
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val lit = on == true
    val back by animateColorAsState(if (lit) Color.White else Visor.colors.raised, tween(200), label = "tile")
    val ink = if (lit) Color.Black else Visor.colors.textPrimary
    val dim by animateFloatAsState(if (locked) 0.45f else 1f, tween(200), label = "tile dim")
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = dim }
            .clip(RoundedCornerShape(18.dp))
            .background(back)
            .pointerInput(id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val held = awaitLongPressOrCancellation(down.id)
                    if (held == null) {
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed) {
                            up.consume()
                            tap()
                        }
                        return@awaitEachGesture
                    }
                    lift()
                    var moved = 0f
                    drag(held.id) { change ->
                        val d = change.positionChange()
                        moved += d.getDistance()
                        move(d)
                        change.consume()
                    }
                    drop(moved)
                }
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(tileIcon(id), null, Modifier.size(22.dp), tint = ink)
            Spacer(Modifier.weight(1f))
            // Sent to the glasses and not yet seen back.
            if (busy) ProgressRing(null, Modifier.size(16.dp), color = ink)
        }
        Column {
            Text(title, style = Visor.type.label, color = ink, maxLines = lineLimit(2), overflow = TextOverflow.Ellipsis)
            Text(state, style = Visor.type.label, color = if (lit) Color.Black.copy(alpha = 0.55f) else Visor.colors.textSecondary, maxLines = lineLimit(1), overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A topic in a few words: where its main settings stand now. */
@Composable
private fun summary(topic: Topic, model: SettingsViewModel, state: SettingsState): String {
    val context = LocalContext.current
    val settings = model.settings
    fun shown(key: String): String? = state.valueOf(key, settings.catalogue[key])?.let { specOf(settings, key).display(it) }
    fun isOn(key: String): Boolean? = state.valueOf(key, settings.catalogue[key])?.let { asBool(it) != specOf(settings, key).invert }
    val changed = topic.keys.count { state.changed(it, settings.catalogue[it]) }
    val base = when (topic) {
        Topic.META_AI -> {
            val config by model.assistant.config.collectAsStateWithLifecycle()
            assistantWords(config)
        }
        Topic.LANGUAGE -> {
            val language = languageOfVoice(settings.value("assistant_voice_id"))
            listOfNotNull(language?.nativeName(), language?.voices?.firstOrNull { it.id == settings.value("assistant_voice_id") }?.label()).joinToString(" · ")
        }
        Topic.NOTIFICATIONS -> {
            val on by model.notifyChoice.enabled.collectAsStateWithLifecycle()
            val muted by model.apps.muted.collectAsStateWithLifecycle()
            if (!on) stringResource(R.string.sum_notify_off)
            else listOfNotNull(
                if (muted.isEmpty()) stringResource(R.string.sum_notify_all) else stringResource(R.string.sum_notify_muted, muted.size),
                stringResource(R.string.sum_dnd).takeIf { isOn("sg_do_not_disturb") == true },
            ).joinToString(" · ")
        }
        Topic.CONTROLS -> listOfNotNull(
            shown("capture_mode")?.let { stringResource(R.string.sum_controls_press, it) },
            shown("tap_and_hold_mapping")?.let { stringResource(R.string.sum_controls_hold, it) },
        ).joinToString(" · ")
        Topic.BAND -> {
            // The Display reports the band it holds.
            val glasses by model.connection.state.collectAsStateWithLifecycle()
            val paired by model.bandPeers.bands.collectAsStateWithLifecycle()
            val device = glasses.device
            device?.bandBattery?.let { batteryWords(it, device.bandCharging) }
                ?: if (paired?.isEmpty() == true) stringResource(R.string.band_not_paired) else ""
        }
        Topic.SOUND -> listOfNotNull(
            shown("notification_volume")?.let { stringResource(R.string.sum_sound_volume, it) },
            stringResource(R.string.sum_headphone).takeIf { isOn("sg_headphone_mode_enabled") == true },
        ).joinToString(" · ")
        Topic.HEARING -> {
            val features by model.features.state.collectAsStateWithLifecycle()
            Feature.entries.filter { it.topic == Topic.HEARING && it.on(features.product) && features.standings[it] == Standing.ON }
                .map { stringResource(it.title) }.joinToString(" · ").ifEmpty { stringResource(R.string.sum_all_off) }
        }
        Topic.WEAR -> listOfNotNull(
            stringResource(R.string.sum_wear_detection).takeIf { isOn("wear_detection_enabled") == true },
            stringResource(R.string.sum_battery_saver).takeIf { isOn("battery_saver_mode_enabled") == true },
        ).joinToString(" · ").ifEmpty { stringResource(R.string.sum_all_off) }
        Topic.CAMERA -> listOfNotNull(shown("resolution_and_framerate"), shown("video_duration_ms")).joinToString(" · ")
        Topic.PHOTOS -> {
            val mode by model.importChoice.mode.collectAsStateWithLifecycle()
            var places by remember { mutableStateOf(placesAllowed(context)) }
            LifecycleResumeEffect(Unit) {
                places = placesAllowed(context)
                onPauseOrDispose { }
            }
            listOf(stringResource(R.string.sum_import, mode.words()), stringResource(if (places) R.string.sum_places_on else R.string.sum_places_off)).joinToString(" · ")
        }
        Topic.LIVE -> {
            val live by model.live.settings.collectAsStateWithLifecycle()
            listOf(
                if (live.manual) live.quality.label else stringResource(R.string.live_auto),
                stringResource(R.string.sum_replay, spanWords(live.replaySeconds.toLong())),
            ).joinToString(" · ")
        }
        Topic.PRIVACY -> stringResource(if (isOn("location_global_enabled") == true) R.string.sum_location_on else R.string.sum_location_off)
        Topic.PHONE -> {
            var missing by remember { mutableStateOf(missingNeeds(context).size) }
            LifecycleResumeEffect(Unit) {
                missing = missingNeeds(context).size
                onPauseOrDispose { }
            }
            if (missing == 0) stringResource(R.string.sum_access_all) else stringResource(R.string.sum_access_missing, missing)
        }
    }
    return if (changed > 0) listOf(base, stringResource(R.string.sum_changed, changed)).filter { it.isNotEmpty() }.joinToString(" · ") else base
}

@Composable
internal fun assistantWords(config: AssistantConfig): String {
    val configured = config.apiKey.isNotBlank() || config.provider == Provider.OPENAI_COMPATIBLE
    return when {
        !config.enabled -> stringResource(R.string.sum_ai_off)
        !configured -> stringResource(R.string.sum_ai_unset)
        else -> listOfNotNull(
            modelWords(config.model.ifBlank { if (config.provider == Provider.CLAUDE) DEFAULT_CLAUDE else "" }),
            stringResource(R.string.sum_ai_glasses_voice).takeIf { config.glassesVoice },
        ).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}

/** "claude-sonnet-5-5" as "Sonnet 5.5"; anything else as it is. */
fun modelWords(id: String): String {
    if (!id.startsWith("claude-")) return id
    val parts = id.removePrefix("claude-").split('-')
    val name = parts.firstOrNull { it.any(Char::isLetter) }?.replaceFirstChar { it.uppercase() } ?: return id
    val version = parts.filter { p -> p.all(Char::isDigit) && p.length <= 2 }.joinToString(".")
    return if (version.isEmpty()) name else "$name $version"
}

const val DEFAULT_CLAUDE = "claude-sonnet-5-5"

/** Search over every page, Visor's own switches and every glasses setting; rows work right where they are found. */
private fun androidx.compose.foundation.lazy.LazyListScope.searchResults(
    query: String,
    model: SettingsViewModel,
    state: SettingsState,
    glasses: GlassesState,
    open: (NavKey) -> Unit,
) {
    val needle = query.trim().lowercase()
    item(key = "results") {
        val pages = pageHits().filter { (title, words, _) -> needle in stringResource(title).lowercase() || words.any { needle in it } }
        val toggles = model.toggles.all.filter { t -> needle in stringResource(t.label).lowercase() || (t.note?.let { needle in stringResource(it).lowercase() } ?: false) }
        val display = hasDisplay(glasses.info?.build)
        val keys = remember(needle, state.stored.keys) {
            // The glasses' camera switches follow Visor's own, which the toggles above find.
            allKeys(model.settings, state).filter { key ->
                val spec = specOf(model.settings, key)
                key !in GLASSES_CAMERA_KEYS &&
                    (needle in spec.label.lowercase() || needle.replace(' ', '_') in key || needle in spec.note.orEmpty().lowercase() || needle in spec.group.lowercase())
            }.sortedWith(compareBy({ Topic.of(it) == null || !specOf(model.settings, it).offeredOn(state.product) }, { specOf(model.settings, it).group in DISPLAY_GROUPS && !display }, { specOf(model.settings, it).label }))
                .take(MAX_RESULTS)
        }
        if (pages.isEmpty() && toggles.isEmpty() && keys.isEmpty()) {
            Text(stringResource(R.string.settings_nothing_found), style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(horizontal = SIDE, vertical = 12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (pages.isNotEmpty()) {
                Column { for ((title, _, key) in pages) NavRow(null, stringResource(title), null, onClick = { open(key) }) }
            }
            if (toggles.isNotEmpty()) {
                Rows(*toggles.map { t -> @Composable { VisorToggleRow(t, overline = stringResource(t.topic.title)) } }.toTypedArray())
            }
            if (keys.isNotEmpty()) {
                val advanced = stringResource(R.string.advanced_title)
                Rows(
                    *keys.map { key ->
                        @Composable {
                            // A row the Meta app keeps for other glasses lives in Advanced only.
                            val topic = Topic.of(key)?.takeIf { specOf(model.settings, key).offeredOn(state.product) }
                            GlassesRow(key, overline = topic?.let { stringResource(it.title) } ?: "$advanced · ${specOf(model.settings, key).group}")
                        }
                    }.toTypedArray(),
                )
            }
        }
    }
}

/** The pages search can find by name or by what they hold. */
private fun pageHits(): List<Triple<Int, List<String>, NavKey>> = Topic.entries.map { Triple(it.title, emptyList<String>(), placeOf(it)) } + listOf(
    Triple(R.string.glasses_title, listOf("battery", "batteria", "firmware", "serial", "seriale", "account", "owner", "proprietario", "case", "custodia", "rename", "nome"), GlassesKey),
    Triple(R.string.advanced_title, listOf("all", "tutte", "engineering"), AdvancedKey),
    Triple(R.string.backup_title, listOf("export", "esporta", "import", "restore", "ripristina"), BackupKey),
    Triple(R.string.about_title, listOf("version", "versione", "licen"), AboutKey),
    Triple(R.string.notify_apps_title, listOf("app", "notification", "notifiche", "mute"), NotifyAppsKey),
    Triple(R.string.settings_flags, listOf("flag", "mobileconfig", "gate"), FlagsKey),
    Triple(
        R.string.topic_hearing,
        listOf("conversation focus", "focus", "hearing correction", "hearing aid", "group conversation", "udito", "conversazion", "apparecchio"),
        TopicKey(Topic.HEARING.name),
    ),
    Triple(R.string.settings_diagnostics, listOf("diagnos", "link", "verdict"), DeviceKey),
    Triple(R.string.settings_event_log, listOf("log", "events", "eventi"), EventLogKey),
    Triple(R.string.meta_ai_memories, listOf("memor", "ricord", "remember"), MemoriesKey),
    Triple(R.string.mcp_title, listOf("mcp", "integra", "tool", "strument", "connector", "server"), com.macedonga.visor.IntegrationsKey),
)

private const val MAX_RESULTS = 60
private const val COLUMNS = 3
private const val STILL_PX = 24f
/** The strip the stage folds into under the bar, and the pair in it, never smaller than on the home. */
private val STRIP = 148.dp
/** Under the folded pair, before the sheet. */
private val STRIP_GAP = 20.dp
private const val STRIP_SCALE = HOME_STRIP_SCALE

internal fun timeWords(at: Long): String = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))

internal fun dateWords(at: Long): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))

/** All of a firmware group's keys, for Advanced. */
internal fun groupKeys(settings: GlassesSettings, state: SettingsState, group: String): List<String> =
    allKeys(settings, state).filter { specOf(settings, it).group == group }

internal val ALL_GROUPS = GROUPS + OTHER
