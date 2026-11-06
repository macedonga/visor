package com.macedonga.visor.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.AboutKey
import com.macedonga.visor.AdvancedKey
import com.macedonga.visor.AssistantSettingsKey
import com.macedonga.visor.BackupKey
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.DeviceKey
import com.macedonga.visor.EventLogKey
import com.macedonga.visor.FlagsKey
import com.macedonga.visor.AccountsKey
import com.macedonga.visor.GlassesKey
import com.macedonga.visor.IntegrationsKey
import com.macedonga.visor.WebAppsKey
import com.macedonga.visor.WifiNetworksKey
import com.macedonga.visor.LanguageKey
import com.macedonga.visor.LiveSettingsKey
import com.macedonga.visor.OnboardingKey
import com.macedonga.visor.MemoriesKey
import com.macedonga.visor.NotifyAppsKey
import com.macedonga.visor.PhoneAccessKey
import com.macedonga.visor.R
import com.macedonga.visor.SettingsGroupKey
import com.macedonga.visor.TopicKey
import com.macedonga.visor.band.BandLink
import com.macedonga.visor.band.BandPair
import com.macedonga.visor.band.BandPeers
import com.macedonga.visor.band.BandSetup
import com.macedonga.visor.band.BandUpdate
import com.macedonga.visor.band.Bands
import com.macedonga.visor.connection.GLASSES_CAMERA_KEYS
import com.macedonga.visor.core.assistant.AssistantConfig
import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.assistant.DEFAULT_CLAUDE_MODEL
import com.macedonga.visor.core.assistant.Hearing
import com.macedonga.visor.core.assistant.Provider
import com.macedonga.visor.core.assistant.TtsPath
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.isDisplayProduct
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.Albums
import com.macedonga.visor.core.media.LiveSettingsStore
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.settings.Control
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.SettingsState
import com.macedonga.visor.core.settings.asBool
import com.macedonga.visor.core.settings.offeredOn
import com.macedonga.visor.features.Feature
import com.macedonga.visor.features.Features
import com.macedonga.visor.features.Standing
import com.macedonga.visor.features.featureFace
import com.macedonga.visor.gallery.ImportChoice
import com.macedonga.visor.gallery.placesAllowed
import com.macedonga.visor.gallery.title
import com.macedonga.visor.home.LastSeen
import com.macedonga.visor.home.StatusPill
import com.macedonga.visor.language.VOICE_KEY
import com.macedonga.visor.language.languageOfVoice
import com.macedonga.visor.language.voiceLanguageName
import com.macedonga.visor.media.spanWords
import com.macedonga.visor.notify.NotifyApps
import com.macedonga.visor.notify.NotifyChoice
import com.macedonga.visor.outside.DeveloperMode
import com.macedonga.visor.stage.Choreography
import com.macedonga.visor.stage.HOME_STRIP_SCALE
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.SHEET_RADIUS
import com.macedonga.visor.stage.STAGE_ASPECT
import com.macedonga.visor.stage.closedOf
import com.macedonga.visor.stage.liveliness
import com.macedonga.visor.stage.moodAway
import com.macedonga.visor.stage.sheetShape
import com.macedonga.visor.stage.stageSpot
import com.macedonga.visor.stage.turnsThePair
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.rememberTrailingFold
import com.macedonga.visor.ui.lineLimit
import com.macedonga.visor.update.Updates
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val settings: GlassesSettings,
    val connection: GlassesConnection,
    val importChoice: ImportChoice,
    val albums: Albums,
    val notifyChoice: NotifyChoice,
    val apps: NotifyApps,
    val assistant: AssistantConfigStore,
    val live: LiveSettingsStore,
    val pins: Pins,
    val toggles: VisorToggles,
    val backups: Backups,
    val updates: Updates,
    val features: Features,
    val lastSeen: LastSeen,
    val hearing: Hearing,
    val band: BandLink,
    val bands: Bands,
    val bandPeers: BandPeers,
    val bandPair: BandPair,
    val bandSetup: BandSetup,
    val bandUpdate: BandUpdate,
    val developer: DeveloperMode,
    val media: MediaImport,
) : ViewModel()

/** Settings: the lit pair on the stage, and a sheet with search, pinned tiles and topics that folds the stage as it scrolls. */
@Composable
fun SettingsScreen(open: (NavKey) -> Unit, model: SettingsViewModel = hiltViewModel()) = SettingsHost(seeThrough = true) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val state by model.settings.state.collectAsStateWithLifecycle()
    // So the pair's pages stay while it is away.
    val product = rememberProduct(model.settings, model.lastSeen)
    var query by rememberSaveable { mutableStateOf("") }
    val hits = if (query.isBlank()) null else searchHits(query, model, state, product)
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
        val spot = stageSpot(closedOf(glasses, mood, stage.lastFolded), liveliness(mood)) {
            folding(fullPx, stripPx, scrollFold, HOME_STRIP_SCALE, barPx + pairPx / 2)
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
            if (hits == null) {
                item(key = "tiles") { Tiles(model, open) }
                item(key = "glasses") {
                    Column(Modifier.padding(top = 18.dp)) {
                        NavRow(VisorIcons.Glasses, stringResource(R.string.glasses_title), null, onClick = { open(GlassesKey) })
                    }
                }
                // The Display's own pages, up front rather than inside the Glasses page.
                if (isDisplayProduct(product)) item(key = "display") {
                    Column(Modifier.padding(top = 14.dp)) {
                        NavRow(VisorIcons.Wifi, stringResource(R.string.wifi_networks), null, onClick = { open(WifiNetworksKey) })
                        NavRow(VisorIcons.Globe, stringResource(R.string.webapps_row), null, onClick = { open(WebAppsKey) })
                        NavRow(VisorIcons.People, stringResource(R.string.accounts_title), null, onClick = { open(AccountsKey) })
                    }
                }
                for ((i, card) in Topic.CARDS.withIndex()) {
                    item(key = "card-$i") {
                        Column(Modifier.padding(top = 14.dp)) {
                            for (topic in card.filter { it.shownOn(product) }) {
                                NavRow(topic.icon, stringResource(topic.title), summary(topic, model, state, product), onClick = { open(placeOf(topic)) })
                            }
                        }
                    }
                }
                item(key = "advanced") {
                    val all = allKeys(model.settings, state)
                    val changed = all.count { state.changed(it, model.settings.catalogue[it]) }
                    Column(Modifier.padding(top = 14.dp)) {
                        NavRow(VisorIcons.Code, stringResource(R.string.advanced_title), pluralStringResource(R.plurals.advanced_group_sum_changed, all.size, all.size, changed), onClick = { open(AdvancedKey) })
                        val last by model.backups.last.collectAsStateWithLifecycle()
                        NavRow(
                            VisorIcons.Archive, stringResource(R.string.backup_title),
                            last?.let { stringResource(R.string.backup_sum_last, dateWords(it)) } ?: stringResource(R.string.backup_sum_never),
                            onClick = { open(BackupKey) },
                        )
                        val newer by model.updates.newer.collectAsStateWithLifecycle()
                        val version = BuildConfig.VERSION_NAME
                        NavRow(
                            VisorIcons.Info, stringResource(R.string.about_title),
                            newer?.let { stringResource(R.string.about_sum_update, version, it.version) } ?: stringResource(R.string.about_sum, version),
                            onClick = { open(AboutKey) },
                        )
                    }
                }
            } else {
                searchResults(hits, model, product) { key ->
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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = stringResource(R.string.glasses_title), role = Role.Button,
                ) { open(GlassesKey) },
        ) {
            val remembered by model.lastSeen.battery.collectAsStateWithLifecycle()
            StatusPill(
                glasses, mood, remembered, onChangeOwner = { open(OnboardingKey(login = true, address = it)) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp).graphicsLayer { alpha = (1f - (scrollFold * 2.5f).coerceIn(0f, 1f)) * stage.shown(spot) },
            )
        }
        TopBar(stringResource(R.string.settings_title), modifier = Modifier.graphicsLayer { alpha = stage.shown(spot) })
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

/** The pinned tiles, three to a row: a tap acts, a hold opens details, a hold and drag reorders. */
@Composable
private fun Tiles(model: SettingsViewModel, open: (NavKey) -> Unit) {
    val host = LocalHost.current
    val ids by model.pins.ids.collectAsStateWithLifecycle()
    // A pin the chosen provider cannot use stays stored and shows again once it can.
    val unoffered by model.toggles.unoffered.collectAsStateWithLifecycle()
    val shown = ids.filter {
        when (val p = pinOf(it)) {
            is GlassesPin -> true
            is TogglePin -> model.toggles.byId(p.id) != null && p.id !in unoffered
            is FeaturePin -> Feature.byId(p.id) != null
            null -> false
        }
    }
    // A pinned feature's switches come in the glasses' configuration: read it once they are there.
    val features by model.features.state.collectAsStateWithLifecycle()
    LaunchedEffect(host.ready) {
        val pinned = shown.mapNotNull { (pinOf(it) as? FeaturePin)?.let { p -> Feature.byId(p.id) } }
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

/** One tile, with one gesture detector so a hold never also counts as a tap. */
@Composable
private fun Tile(id: String, model: SettingsViewModel, open: (NavKey) -> Unit, onLift: () -> Unit, onMove: (Offset) -> Unit, onDrop: (Float) -> Unit) {
    val host = LocalHost.current
    val pin = pinOf(id) ?: return
    val restriction = when (pin) {
        is GlassesPin -> languageRestriction(pin.key)
        is TogglePin -> languageRestriction(pin.id)
        is FeaturePin -> null
    }
    val blocked = restriction ?: stringResource(R.string.settings_locked)
    val refused = stringResource(R.string.settings_not_sent)
    var sheet by remember { mutableStateOf(false) }
    val title: String
    val state: String
    val on: Boolean?
    val locked: Boolean
    val lockedWords: String
    val busy: Boolean
    val act: () -> Unit
    when (pin) {
        is TogglePin -> {
            val toggle = model.toggles.byId(pin.id) ?: return
            val now by toggle.on.collectAsState()
            title = stringResource(toggle.label)
            on = now && restriction == null
            state = if (restriction != null) stringResource(R.string.ai_voice_phone) else stringResource(if (now) R.string.sum_on else R.string.sum_off)
            locked = restriction != null
            lockedWords = blocked
            busy = false
            act = { toggle.set(!now) }
        }
        is FeaturePin -> {
            val face = featureFace(Feature.byId(pin.id) ?: return, model.features, model.hearing, open)
            title = face.title
            on = face.on
            state = face.state
            locked = !host.ready
            lockedWords = blocked
            busy = face.busy
            act = face.act
        }
        is GlassesPin -> {
            val key = pin.key
            val info = host.settings.catalogue[key]
            val spec = specOf(host.settings, key)
            val value = host.state.valueOf(key, info)
            title = spec.label
            // A switch not read yet has no side to flip to.
            locked = !host.ready || info?.writable != true || restriction != null || (spec.control == Control.Toggle && value == null)
            lockedWords = when {
                restriction != null || !host.ready -> blocked
                info?.writable != true -> stringResource(R.string.settings_no_way_to_write)
                else -> stringResource(R.string.settings_not_read)
            }
            busy = key in host.state.pending
            on = if (spec.control == Control.Toggle) value?.let { asBool(it) != spec.invert } else null
            state = value?.let { spec.words(it) } ?: stringResource(R.string.settings_not_read)
            // A slider, number or text opens the page that holds its editor.
            val place = Topic.of(key)?.takeIf { spec.offeredOn(host.product) }?.let(::placeOf) ?: SettingsGroupKey(spec.group)
            act = {
                if (on != null) {
                    if (!host.settings.write(key, boolWire(info, !on != spec.invert))) host.notice.show(refused)
                } else if (spec.control is Control.Choice) {
                    sheet = true
                } else {
                    open(place)
                }
            }
            LaunchedEffect(locked) { if (locked) sheet = false }
            val c = spec.control
            if (sheet && !locked && c is Control.Choice) {
                val current = value?.trim().orEmpty()
                ChoiceSheet(
                    spec.label, c.shown(current, host.product).map { Triple(it.value, it.label, it.detail) }, current,
                    onPick = { if (!host.settings.write(key, it)) host.notice.show(refused) }, onDismiss = { sheet = false },
                )
            }
        }
    }
    val tap by rememberUpdatedState {
        if (locked) {
            host.haptics.tick(0.3f)
            host.notice.show(lockedWords)
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
            .clip(RoundedCornerShape(CARD_RADIUS))
            .background(back)
            .semantics(mergeDescendants = true) {
                role = if (on != null) Role.Switch else Role.Button
                if (on != null) toggleableState = ToggleableState(on)
                onClick { tap(); true }
                onLongClick { host.openDetails(id); true }
            }
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
private fun summary(topic: Topic, model: SettingsViewModel, state: SettingsState, product: Int?): String {
    val context = LocalContext.current
    val settings = model.settings
    @Composable fun shown(key: String): String? = state.valueOf(key, settings.catalogue[key])?.let { specOf(settings, key).words(it) }
    fun isOn(key: String): Boolean? = state.valueOf(key, settings.catalogue[key])?.let { asBool(it) != specOf(settings, key).invert }
    val changed = topic.keys.count { state.changed(it, settings.catalogue[it]) }
    val base = when (topic) {
        Topic.META_AI -> {
            val config by model.assistant.config.collectAsStateWithLifecycle()
            val sessionVoices by model.assistant.sessionVoices.collectAsStateWithLifecycle()
            assistantWords(config.copy(tts = if (config.tts == TtsPath.GLASSES && phoneSpeaksVoice(settings.value(VOICE_KEY), sessionVoices)) TtsPath.PHONE else config.tts))
        }
        Topic.LANGUAGE -> {
            val voice = settings.value(VOICE_KEY)
            val language = languageOfVoice(voice)
            listOfNotNull(
                language?.nativeName() ?: voice?.let(::voiceLanguageName),
                if (language?.partner == true) stringResource(R.string.ai_voice_phone)
                else language?.voices?.firstOrNull { it.id == voice }?.label(),
            ).joinToString(" · ")
        }
        Topic.NOTIFICATIONS -> {
            val on by model.notifyChoice.enabled.collectAsStateWithLifecycle()
            val muted by model.apps.muted.collectAsStateWithLifecycle()
            if (!on) stringResource(R.string.sum_notify_off)
            else listOfNotNull(
                if (muted.isEmpty()) stringResource(R.string.sum_notify_all) else pluralStringResource(R.plurals.sum_notify_muted, muted.size, muted.size),
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
            val hearing = Feature.entries.filter { it.topic == Topic.HEARING && it.offeredOn(product) }
            // Nothing read yet says nothing rather than all off.
            val known = hearing.any { (features.standings[it] ?: Standing.UNKNOWN) != Standing.UNKNOWN }
            hearing.filter { features.standings[it] == Standing.ON }
                .map { stringResource(it.title) }.joinToString(" · ").ifEmpty { if (known) stringResource(R.string.sum_all_off) else "" }
        }
        Topic.WEAR -> listOfNotNull(
            stringResource(R.string.sum_wear_detection).takeIf { isOn("wear_detection_enabled") == true },
            stringResource(R.string.sum_battery_saver).takeIf { isOn("battery_saver_mode_enabled") == true },
        ).joinToString(" · ")
        Topic.CAMERA -> listOfNotNull(shown("resolution_and_framerate"), shown("video_duration_ms")).joinToString(" · ")
        Topic.PHOTOS -> {
            val mode by model.importChoice.mode.collectAsStateWithLifecycle()
            val places by rememberOnResume { placesAllowed(context) }
            listOf(stringResource(R.string.sum_import, stringResource(mode.title).lowercase(LocalLocale.current.platformLocale)), stringResource(if (places) R.string.sum_places_on else R.string.sum_places_off)).joinToString(" · ")
        }
        Topic.LIVE -> {
            val live by model.live.settings.collectAsStateWithLifecycle()
            listOf(
                if (live.manual) live.quality.label else stringResource(R.string.live_auto),
                stringResource(R.string.sum_replay, spanWords(live.replaySeconds.toLong())),
            ).joinToString(" · ")
        }
        Topic.PRIVACY -> when (isOn("location_global_enabled")) {
            true -> stringResource(R.string.sum_location_on)
            false -> stringResource(R.string.sum_location_off)
            null -> ""
        }
        Topic.PHONE -> {
            val missing by rememberOnResume { missingNeeds(context).size }
            if (missing == 0) stringResource(R.string.sum_access_all) else pluralStringResource(R.plurals.sum_access_missing, missing, missing)
        }
    }
    val words = if (changed > 0) listOf(base, pluralStringResource(R.plurals.sum_changed, changed, changed)).filter { it.isNotEmpty() }.joinToString(" · ") else base
    // A model id keeps its own case.
    return if (topic == Topic.META_AI) words else words.replaceFirstChar { it.titlecase(LocalLocale.current.platformLocale) }
}

@Composable
internal fun assistantWords(config: AssistantConfig): String {
    val configured = config.hasModel
    return when {
        !config.enabled -> stringResource(R.string.sum_ai_off)
        !configured -> stringResource(R.string.sum_ai_unset)
        else -> listOfNotNull(
            modelWords(config.model.ifBlank { if (config.provider == Provider.CLAUDE) DEFAULT_CLAUDE_MODEL else "" }),
            stringResource(R.string.sum_ai_glasses_voice).takeIf { config.tts == TtsPath.GLASSES },
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

/** What a search finds: pages (title, words, place), Visor's own switches and glasses settings. */
private class SearchHits(val pages: List<Triple<Int, Int?, NavKey>>, val toggles: List<VisorToggle>, val keys: List<String>)

/** Search over every page, Visor's own switches and every glasses setting. */
@Composable
private fun searchHits(query: String, model: SettingsViewModel, state: SettingsState, product: Int?): SearchHits {
    val needle = query.trim().lowercase()
    // A word may be a stem, which then also catches the whole word typed out.
    val pages = pageHits(product).filter { (title, words, _) ->
        needle in stringResource(title).lowercase() ||
            words?.let { stringResource(it).split(',') }.orEmpty().any { needle in it || (it.length >= 4 && it in needle) }
    }
    val unoffered by model.toggles.unoffered.collectAsStateWithLifecycle()
    val toggles = model.toggles.all.filter { t ->
        t.id !in unoffered && (needle in stringResource(t.label).lowercase() || (t.note?.let { needle in stringResource(it).lowercase() } ?: false))
    }
    val display = isDisplayProduct(product)
    val keys = remember(needle, state.stored.keys, product) {
        // The glasses' camera switches follow Visor's own, which the toggles above find.
        allKeys(model.settings, state).filter { key ->
            val spec = specOf(model.settings, key)
            key !in GLASSES_CAMERA_KEYS &&
                (needle in spec.label.lowercase() || needle.replace(' ', '_') in key || needle in spec.note.orEmpty().lowercase() || needle in spec.group.lowercase())
        }.sortedWith(compareBy({ Topic.of(it) == null || !specOf(model.settings, it).offeredOn(product) }, { specOf(model.settings, it).group in DISPLAY_GROUPS && !display }, { specOf(model.settings, it).label }))
            .take(MAX_RESULTS)
    }
    return SearchHits(pages, toggles, keys)
}

/** The hits as rows, one lazy item each; rows work right where they are found. */
private fun LazyListScope.searchResults(
    hits: SearchHits,
    model: SettingsViewModel,
    product: Int?,
    open: (NavKey) -> Unit,
) {
    if (hits.pages.isEmpty() && hits.toggles.isEmpty() && hits.keys.isEmpty()) {
        item(key = "nothing") {
            Text(stringResource(R.string.nothing_found), style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(horizontal = SIDE, vertical = 12.dp))
        }
        return
    }
    items(hits.pages, key = { "p:${it.first}" }) { (title, _, key) -> NavRow(null, stringResource(title), null, onClick = { open(key) }) }
    if (hits.toggles.isNotEmpty()) {
        if (hits.pages.isNotEmpty()) item(key = "toggles-gap") { Spacer(Modifier.height(14.dp)) }
        cardItems(hits.toggles, { "v:" + it.id }) { t -> VisorToggleRow(t, overline = stringResource(t.topic.title)) }
    }
    if (hits.keys.isNotEmpty()) {
        if (hits.pages.isNotEmpty() || hits.toggles.isNotEmpty()) item(key = "keys-gap") { Spacer(Modifier.height(14.dp)) }
        cardItems(hits.keys, { "g:$it" }) { key ->
            // A row the Meta app keeps for other glasses lives in Advanced only.
            val topic = Topic.of(key)?.takeIf { specOf(model.settings, key).offeredOn(product) }
            GlassesRow(key, overline = topic?.let { stringResource(it.title) } ?: "${stringResource(R.string.advanced_title)} · ${groupTitle(specOf(model.settings, key).group)}")
        }
    }
}

private fun displayPages(product: Int?): List<Triple<Int, Int?, NavKey>> = if (!isDisplayProduct(product)) emptyList() else listOf(
    Triple(R.string.wifi_networks, R.string.search_words_wifi, WifiNetworksKey),
    Triple(R.string.webapps_row, R.string.search_words_webapps, WebAppsKey),
    Triple(R.string.accounts_title, R.string.search_words_accounts, AccountsKey),
)

/** The pages search can find by name or by the comma-separated words they hold. */
private fun pageHits(product: Int?): List<Triple<Int, Int?, NavKey>> =
    Topic.entries.filter { it.shownOn(product) }.map { Triple(it.title, if (it == Topic.HEARING) R.string.search_words_hearing else null, placeOf(it)) } +
        displayPages(product) + listOf(
            Triple(R.string.glasses_title, R.string.search_words_glasses, GlassesKey),
            Triple(R.string.advanced_title, R.string.search_words_advanced, AdvancedKey),
            Triple(R.string.backup_title, R.string.search_words_backup, BackupKey),
            Triple(R.string.about_title, R.string.search_words_about, AboutKey),
            Triple(R.string.notify_apps_title, R.string.search_words_notify_apps, NotifyAppsKey),
            Triple(R.string.settings_flags, R.string.search_words_flags, FlagsKey),
            Triple(R.string.settings_diagnostics, R.string.search_words_diagnostics, DeviceKey),
            Triple(R.string.settings_event_log, R.string.search_words_event_log, EventLogKey),
            Triple(R.string.meta_ai_memories, R.string.search_words_memories, MemoriesKey),
            Triple(R.string.mcp_title, R.string.search_words_mcp, IntegrationsKey),
        )

private const val MAX_RESULTS = 60
private const val COLUMNS = 3
private const val STILL_PX = 24f
/** The strip the stage folds into under the bar, and the pair in it, never smaller than on the home. */
private val STRIP = 148.dp
/** Under the folded pair, before the sheet. */
private val STRIP_GAP = 20.dp

internal fun dateWords(at: Long): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
