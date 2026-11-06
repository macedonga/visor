package com.macedonga.visor.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.provider.ContactsContract
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.tappableElementIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.R
import com.macedonga.visor.band.BandLink
import com.macedonga.visor.band.Bands
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.connection.isDisplayProduct
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.Albums
import com.macedonga.visor.core.media.Capture
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.transport.LeKeeper
import com.macedonga.visor.display.DisplayShelf
import com.macedonga.visor.display.DisplayTool
import com.macedonga.visor.gallery.rememberImportGate
import com.macedonga.visor.pairs.Looks
import com.macedonga.visor.pairs.Pairs
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.Need
import com.macedonga.visor.settings.TextSheet
import com.macedonga.visor.settings.openAppPage
import com.macedonga.visor.stage.Choreography
import com.macedonga.visor.stage.HOME_STRIP
import com.macedonga.visor.stage.HOME_STRIP_SCALE
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.stage.SETTLE_EASE
import com.macedonga.visor.stage.SHEET_RADIUS
import com.macedonga.visor.stage.STAGE_ASPECT
import com.macedonga.visor.stage.StageDebug
import com.macedonga.visor.stage.StageLight
import com.macedonga.visor.stage.StagePages
import com.macedonga.visor.stage.closedOf
import com.macedonga.visor.stage.ghostLook
import com.macedonga.visor.stage.liveliness
import com.macedonga.visor.stage.lookOf
import com.macedonga.visor.stage.modelled
import com.macedonga.visor.stage.moodOf
import com.macedonga.visor.stage.sheetShape
import com.macedonga.visor.stage.stageSpot
import com.macedonga.visor.stage.turnsThePair
import com.macedonga.visor.stage.variantsOf
import com.macedonga.visor.ui.allows
import com.macedonga.visor.ui.rememberHaptics
import com.macedonga.visor.ui.rememberTrailingFold
import com.macedonga.visor.update.REPO_URL
import com.macedonga.visor.update.StarAsk
import com.macedonga.visor.update.StarSheet
import com.macedonga.visor.update.openLink
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** An import as the home shows it: what was waiting when it began, how far it is, and the wait for Bluetooth after. */
data class ImportView(val set: List<Capture> = emptyList(), val progress: Float = 0f, val finished: Boolean = false, val settling: Boolean = false)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val albums: Albums,
    val connection: GlassesConnection,
    val live: LiveStream,
    val media: MediaImport,
    keeper: LeKeeper,
    val looks: Looks,
    val lastSeen: LastSeen,
    val pairs: Pairs,
    val band: BandLink,
    val bands: Bands,
    val star: StarAsk,
) : ViewModel() {
    /** The LE link is up: the glasses are right here, whatever RFCOMM says. */
    val nearby = keeper.connected
    /** Null until the store has been read. */
    val paired: StateFlow<Boolean?> = pairs.stored.map { it?.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, pairs.stored.value?.isNotEmpty())
    val shots = MutableStateFlow<List<PhoneShot>>(emptyList())
    val firstName = MutableStateFlow<String?>(null)
    val importView = MutableStateFlow(ImportView())

    init {
        // Whatever is saved shows up here as soon as it lands.
        viewModelScope.launch { media.saved.collect { shots.value = recentShots(context, albums) } }
        viewModelScope.launch {
            var imported = false
            media.importing.collectLatest { on ->
                if (on) {
                    imported = true
                    val set = media.fresh.value
                    val total = set.size.coerceAtLeast(1)
                    importView.value = ImportView(set)
                    // Done captures plus the share of the one in flight, never going back.
                    combine(media.fresh, media.transfers) { fresh, transfers ->
                        val size = transfers.sumOf { it.size }
                        val file = if (size > 0) transfers.sumOf { it.received }.toFloat() / size else 0f
                        (((total - fresh.size).coerceIn(0, total) + file) / total).coerceIn(0f, 1f)
                    }.first { p ->
                        importView.update { it.copy(progress = maxOf(it.progress, p)) }
                        importView.value.progress >= 1f
                    }
                    delay(DONE_HOLD_MS)
                    importView.update { it.copy(finished = true) }
                } else if (imported) {
                    // Importing drops Bluetooth on purpose: until it is back the glasses still count as here.
                    importView.update { it.copy(progress = 0f, finished = false, settling = true) }
                    withTimeoutOrNull(SETTLE_MS) { connection.state.first { it.link == LinkState.READY } }
                    importView.update { it.copy(settling = false) }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { shots.value = recentShots(context, albums) }
        viewModelScope.launch { firstName.value = ownerFirstName(context) }
        pairs.refresh()
    }
}

/** How long after an import or a Live the glasses count as here while Bluetooth comes back. */
private const val SETTLE_MS = 25_000L
/** Held at 100% this long, the import is done as far as anyone looking can tell, relinked or not. */
private const val DONE_HOLD_MS = 2_000L

/** The phone owner's own contact card, first word of the name; null without one or without the permission. */
suspend fun ownerFirstName(context: Context): String? = withContext(Dispatchers.IO) {
    if (!context.allows(Manifest.permission.READ_CONTACTS)) return@withContext null
    runCatching {
        context.contentResolver.query(
            ContactsContract.Profile.CONTENT_URI, arrayOf(ContactsContract.Profile.DISPLAY_NAME_PRIMARY), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0)?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } else null }
    }.getOrNull()
}

/** What the home reads coming back to it, set by the screens it opened. */
class HomeReturn {
    /** The home has played its entrance in this process; coming back to it plays nothing. */
    var entered = false
    /** The Live view left the pair where the open home has it: the home comes back scrolled to the top. */
    var toTop = false
    /** A Live stream has just closed: the home counts the glasses as here while Bluetooth comes back. */
    var fromLive = false
}

/** The glasses under their light, held at the top while the feed scrolls beneath them and the pair draws back. */
@Composable
fun HomeScreen(
    returning: HomeReturn,
    onSetUp: () -> Unit,
    onChangeOwner: (address: String?) -> Unit,
    onMedia: () -> Unit,
    onLive: () -> Unit,
    onMetaAi: () -> Unit,
    onSettings: () -> Unit,
    onDisplayTool: (DisplayTool) -> Unit = {},
    model: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by model.connection.state.collectAsStateWithLifecycle()
    val running by model.connection.running.collectAsStateWithLifecycle()
    val live by model.live.running.collectAsStateWithLifecycle()
    val captures by model.media.captures.collectAsStateWithLifecycle()
    val previews by model.media.previews.collectAsStateWithLifecycle()
    val importing by model.media.importing.collectAsStateWithLifecycle()
    val problem by model.media.problem.collectAsStateWithLifecycle()
    val overBluetooth by model.media.overBluetooth.collectAsStateWithLifecycle()
    val shots by model.shots.collectAsStateWithLifecycle()
    val lastBattery by model.lastSeen.battery.collectAsStateWithLifecycle()
    val firstName by model.firstName.collectAsStateWithLifecycle()
    val lastPosture by model.lastSeen.posture.collectAsStateWithLifecycle()
    val nearby by model.nearby.collectAsStateWithLifecycle()
    val paired by model.paired.collectAsStateWithLifecycle()
    // Every pair set up here; the stage shows one at a time and a drag goes to the next.
    val pairs by model.pairs.all.collectAsStateWithLifecycle()
    val seen by model.lastSeen.seen.collectAsStateWithLifecycle()
    val looks by model.looks.all.collectAsStateWithLifecycle()
    val pictures by model.looks.pictures.collectAsStateWithLifecycle()
    val peeking by model.pairs.peeking.collectAsStateWithLifecycle()
    val current by model.connection.pair.collectAsStateWithLifecycle()
    val selectedProduct by model.lastSeen.product.collectAsStateWithLifecycle()
    val band by model.band.state.collectAsStateWithLifecycle()
    val bands by model.bands.all.collectAsStateWithLifecycle()
    val ours = band.takeIf { b -> b.battery != null && bands.any { it.serial == b.serial && it.glasses == current } }
    // The page shown: a pair's serial, null for the pair Visor is for, or ADD_PAGE after the last pair.
    var page by remember { mutableStateOf<String?>(null) }
    val adding = page == ADD_PAGE && pairs.isNotEmpty()
    val shownIndex = if (adding) pairs.size else pairs.indexOfFirst { it.serial == (page ?: current) }
    val shown = pairs.getOrNull(shownIndex)
    // A pair that is not the one Visor is for: it shows as last heard from, and can be connected.
    val other = shown != null && current != null && shown.serial != current
    // Away from the pair Visor is for, its Live, Meta AI and settings are not this page's.
    val elsewhere = other || adding
    val shownSeen = shown?.let { seen[it.serial] }
    val lastFacts = (if (elsewhere) shownSeen else current?.let(seen::get))?.facts ?: GlassesFacts()
    var renaming by remember { mutableStateOf<String?>(null) }
    val forced by StageDebug.mood.collectAsStateWithLifecycle()
    val variant by StageDebug.variant.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val stage = LocalStage.current
    HideGestureHandle()
    // Only the launch gets an entrance: coming back from another screen, the home is simply there as it was.
    val firstVisit = remember { !returning.entered }
    LaunchedEffect(Unit) { returning.entered = true }
    val entrance = rememberEntrance(firstVisit)

    var allowed by remember { mutableStateOf(context.allows(Manifest.permission.BLUETOOTH_CONNECT)) }
    val activity = LocalActivity.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        allowed = context.allows(Manifest.permission.BLUETOOTH_CONNECT)
        // Refused for good, Android asks no more: Visor's own page is the way left.
        val connect = Manifest.permission.BLUETOOTH_CONNECT
        if (!allowed && result[connect] == false && activity?.shouldShowRequestPermissionRationale(connect) == false) openAppPage(context)
    }
    LifecycleResumeEffect(Unit) {
        allowed = context.allows(Manifest.permission.BLUETOOTH_CONNECT)
        model.refresh()
        onPauseOrDispose { }
    }
    // The glasses push their battery only when the percentage moves, so the home in view asks for it every minute.
    val ready = state.link == LinkState.READY
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(ready) {
        val job = if (ready) scope.launch {
            while (true) {
                model.connection.refreshDeviceState()
                delay(REFRESH_MS)
            }
        } else null
        onPauseOrDispose { job?.cancel() }
    }
    // Reach the glasses as soon as there are glasses to reach.
    LaunchedEffect(allowed, paired, running) {
        if (allowed && paired == true && !running && !GlassesService.stoppedByUser) GlassesService.start(context)
    }
    // Disconnected by hand: the pill offers Connect.
    val disconnected = allowed && paired == true && !running && GlassesService.stoppedByUser

    val fresh by model.media.fresh.collectAsStateWithLifecycle()
    val imp by model.importView.collectAsStateWithLifecycle()
    var leaving by remember { mutableStateOf(false) }
    val transferring = importing && !imp.finished
    // The waiting page stays while there is something to import; before it goes it slides out of sight.
    val wantsWaiting = transferring || (!importing && fresh.isNotEmpty())
    var hadWaiting by remember { mutableStateOf(wantsWaiting) }
    LaunchedEffect(wantsWaiting) {
        if (hadWaiting && !wantsWaiting) leaving = true
        hadWaiting = wantsWaiting
    }
    // Only the connected pair's page slides the waiting page out; elsewhere it is simply gone.
    LaunchedEffect(elsewhere, leaving) { if (elsewhere && leaving) leaving = false }
    val gate = rememberImportGate()
    val onImport = { haptics.toggle(); gate { model.media.importAllNew() } }

    // Sticky: the session clears its failure at every retry, so only a ready link clears this.
    var unanswered by remember { mutableStateOf(false) }
    LaunchedEffect(state.link, state.failure) {
        if (state.link == LinkState.READY) unanswered = false
        else if (state.failure != null || state.link == LinkState.LOST) unanswered = true
    }
    // The Live view pauses Bluetooth too; until it is back the glasses are as present as they were on Wi-Fi.
    val backFromLive = remember { returning.fromLive.also { returning.fromLive = false } }
    var liveSettling by remember { mutableStateOf(backFromLive) }
    var wasLive by remember { mutableStateOf(live || backFromLive) }
    LaunchedEffect(live) {
        if (wasLive && !live) {
            liveSettling = true
            withTimeoutOrNull(SETTLE_MS) { model.connection.state.first { it.link == LinkState.READY } }
            liveSettling = false
        }
        wasLive = live
    }
    val mood = forced ?: when {
        paired == false || adding -> Mood.ABSENT
        other -> when {
            peeking == shown?.serial -> Mood.SEARCHING
            shownSeen?.inCase == true -> Mood.IN_CASE
            shownSeen?.folded == true -> Mood.ASLEEP
            else -> Mood.FAR
        }
        transferring -> Mood.IMPORTING
        live && state.link != LinkState.READY -> Mood.RECORDING
        (importing || imp.settling || liveSettling) && state.link != LinkState.READY -> Mood.CONNECTED
        disconnected -> restingMood(lastPosture, nearby)
        // Once a try goes unanswered, the home keeps one word, from how the glasses were when they went quiet.
        state.link != LinkState.READY && state.target != null && unanswered -> restingMood(lastPosture, nearby)
        else -> moodOf(state, live)
    }
    val colors = Visor.colors
    // At launch the light comes up from dark.
    LaunchedEffect(stage) { if (firstVisit) stage.light.dark() }
    StageLight(if (adding) ghostLook(colors) else lookOf(mood, colors))
    val look by model.looks.look.collectAsStateWithLifecycle()
    val shownLook = if (other) shown?.let { looks[it.serial] } else look
    // The model is a Wayfarer: another frame, or a pair not connected, shows as Meta pictures it, once Meta has.
    val currentPicture = current?.takeIf { !modelled(looks[it]) }?.let(pictures::get)
    val picture = when {
        other -> shown?.let { pictures[it.serial] }
        !modelled(shownLook) -> currentPicture
        else -> null
    }
    SideEffect {
        // The ghost is any new pair: the plain frame, not this pair's.
        stage.pose.variants = variant?.let(::listOf) ?: variantsOf(shownLook.takeIf { !adding })
        stage.picture = picture.takeIf { !adding }
    }
    // Other screens show the pair Visor is for; from the add page, a new pair's setup, where the ghost fills in as the model.
    val leavingPicture = rememberUpdatedState(currentPicture.takeIf { !adding })
    DisposableEffect(Unit) {
        onDispose { stage.picture = leavingPicture.value }
    }
    // A pair shown that is not connected gets a quick look at its batteries.
    LaunchedEffect(shown?.serial, other) {
        if (other && shown != null) model.pairs.peek(shown.serial)
    }
    DisposableEffect(Unit) { onDispose { model.pairs.stopPeek() } }
    // Other screens that show the pair take its mood and hinge from here.
    SideEffect {
        stage.mood = mood
        stage.lastFolded = lastPosture.folded
    }
    Choreography(if (elsewhere) GlassesState() else state, mood, stage.light, stage.pose, haptics::connected, haptics::shutter)

    val list = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (returning.toTop) {
            returning.toTop = false
            list.requestScrollToItem(0)
        }
    }
    val density = LocalDensity.current
    // The pair is the one stage's, under this screen: the home leaves it a hole at the top and says where it goes.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stageHeight = maxWidth / STAGE_ASPECT
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val strip = top + HOME_STRIP
        val fullPx = with(density) { stageHeight.toPx() }
        val stripPx = with(density) { strip.toPx() }
        val topPx = with(density) { top.toPx() }
        val screenPx = with(density) { maxHeight.toPx() }
        // How folded the stage is: it trails the feed and settles after it.
        val fold by rememberTrailingFold(fullPx - stripPx) {
            if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else fullPx
        }
        // Folded, the pair sits small in the middle of the strip, clear of the camera cut-out; at launch it fades in.
        val spot = stageSpot(
            closed = when {
                adding -> false
                elsewhere -> closedOf(GlassesState(), mood, shownSeen?.folded ?: true)
                else -> closedOf(state, mood, lastPosture.folded)
            },
            liveliness = if (adding) GHOST_LIVELINESS else liveliness(mood),
            ghost = adding,
        ) {
            folding(fullPx, stripPx, fold, HOME_STRIP_SCALE, topPx + (stripPx - topPx) / 2)
            alpha = entranceAlpha(entrance, STAGE_FADE_FROM_MS, STAGE_FADE_MS)
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val sheetTop = remember {
            derivedStateOf {
                list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "stage" }?.let { (it.offset + it.size).toFloat() } ?: Float.NEGATIVE_INFINITY
            }
        }
        LazyColumn(
            state = list,
            // The sheet: its rounded top rides on the cards up to the stage, then docks under the stage's edge.
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val restTop = maxOf(sheetTop.value, fullPx - fold * (fullPx - stripPx))
                    // Down while another screen has the pair, coming up as it comes back here.
                    translationY = (1f - stage.shown(spot)) * (screenPx - restTop)
                    clip = true
                    shape = sheetShape(restTop, SHEET_RADIUS.toPx())
                }
                .drawBehind { drawRect(colors.surface) },
            contentPadding = PaddingValues(bottom = bottom + 24.dp),
        ) {
            // The open stage's room as the first item, so the feed moves only with the finger, never with the fold.
            item(key = "stage") { Spacer(Modifier.fillMaxWidth().height(stageHeight)) }
            // At rest the moments and the four numbers fill the screen under the stage; the rest of the feed waits below.
            val memories = moments(shots)
            // The waiting captures are the connected pair's: not on another pair's page.
            val waitingPage: (@Composable (Modifier) -> Unit)? = if (!elsewhere && (wantsWaiting || leaving)) {
                { m ->
                    val waiting = if (importing || leaving) imp.set else fresh
                    WaitingCard(waiting, previews, transferring || leaving, if (leaving) 1f else imp.progress, onImport, m, problem, overBluetooth)
                }
            } else null
            item(key = "hero") {
                val here = (ready && state.device != null) || importing
                val lastCapture = if (elsewhere) null else listOfNotNull(shots.firstOrNull()?.takenAt, captures.mapNotNull { it.takenAtMillis }.maxOrNull()).maxOrNull()
                val pages = memories.isNotEmpty() || waitingPage != null
                // With no moments yet, the first waiting page grows the block to the screen; the last one going shrinks it back.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 220f))
                        .then(if (pages) Modifier.height(maxHeight - stageHeight) else Modifier),
                ) {
                    AnimatedVisibility(
                        visible = pages,
                        modifier = Modifier.weight(1f, fill = false),
                        enter = fadeIn(tween(420, delayMillis = 120)) + expandVertically(spring(dampingRatio = 0.9f, stiffness = 220f)),
                        exit = fadeOut(tween(200)) + shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 220f)),
                    ) {
                        Memories(
                            memories, onMedia, Modifier.fillMaxSize().padding(top = GAP).appear(entrance, 1), waitingPage,
                            waitingCount = if (elsewhere) 0 else fresh.size, leaving = leaving && !elsewhere, onLeft = { leaving = false },
                        )
                    }
                    // Nothing to describe with no pair set up, or on the page for adding one.
                    AnimatedVisibility(
                        visible = paired != false && !adding,
                        enter = fadeIn(tween(220)) + expandVertically(spring(dampingRatio = 0.9f, stiffness = 220f)),
                        exit = fadeOut(tween(160)) + shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 220f)),
                    ) {
                        Facts(lastFacts, live = here && !elsewhere, lastCapture = lastCapture, modifier = Modifier.padding(top = GAP).appear(entrance, 2))
                    }
                    Spacer(Modifier.height(FOOT))
                }
            }
            // The Display's tools, for a Display: known by its build while connected, by what it last said while away.
            if (!elsewhere && (isDisplayBuild(state.info?.build) || isDisplayProduct(selectedProduct))) {
                item(key = "display") { DisplayShelf(onDisplayTool, Modifier.padding(top = 4.dp, bottom = 8.dp).appear(entrance, 4)) }
            }
            item(key = "places") { Places(onMedia, onLive, onMetaAi, onSettings, Modifier.appear(entrance, 5), forThisPair = !elsewhere) }
        }


        // Where the stage is: the feed's room above the sheet, where a drag scrolls the feed or turns the pair.
        Box(
            Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val h = (fullPx - fold * (fullPx - stripPx)).toInt()
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .scrollable(list, Orientation.Vertical, reverseDirection = true)
                .turnsThePair(
                    // The pairs, then the page for adding one.
                    pages = if (pairs.isNotEmpty() && shownIndex >= 0) StagePages(
                        can = { d -> shownIndex + d in 0..pairs.size },
                        go = { d -> page = pairs.getOrNull(shownIndex + d)?.serial ?: ADD_PAGE },
                    ) else null,
                ) { haptics.tick(0.35f) },
        ) {
            val fade = Modifier.graphicsLayer { alpha = (1f - (fold * 2.5f).coerceIn(0f, 1f)) * stage.shown(spot) }
            Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp).then(fade).appear(entrance, 0)) {
                Greeting(firstName)
                // Which pair this is; a tap names it.
                if (shown != null) {
                    PairName(pairName(shownSeen, looks[shown.serial]), { renaming = shown.serial }, Modifier.padding(top = 2.dp))
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (pairs.isNotEmpty()) 0.dp else 26.dp).then(fade).appear(entrance, 0),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Paging, the old page's capsule gives way to the new one from the side the swipe came from.
                AnimatedContent(
                    targetState = if (adding) ADD_PAGE else shown?.serial,
                    transitionSpec = {
                        val order = { key: String? -> if (key == ADD_PAGE) pairs.size else pairs.indexOfFirst { it.serial == key } }
                        val step = if (order(targetState) >= order(initialState)) 1 else -1
                        (fadeIn(tween(260, delayMillis = 60)) + slideInHorizontally(tween(340, easing = SETTLE_EASE)) { step * it / 4 })
                            .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(220, easing = SETTLE_EASE)) { -step * it / 4 })
                            .using(SizeTransform(clip = false))
                    },
                    contentAlignment = Alignment.BottomCenter,
                    label = "pair page",
                ) { key ->
                    val pair = pairs.firstOrNull { it.serial == key }
                    when {
                        key == ADD_PAGE && pairs.isNotEmpty() -> ActionPill(stringResource(R.string.glasses_set_up_new), onSetUp)
                        pair != null && current != null && pair.serial != current -> OtherPairPill(
                            if (pair.serial == shown?.serial) mood else Mood.FAR, seen[pair.serial], peeking = peeking == pair.serial,
                            onConnect = if (live || importing) null else ({
                                haptics.toggle()
                                model.pairs.connect(pair.serial)
                            }),
                        )
                        disconnected -> ActionPill(stringResource(R.string.glasses_connect), {
                            haptics.toggle()
                            GlassesService.start(context)
                        })
                        else -> StatusPill(
                            state, mood, lastBattery, paired = paired, allowed = allowed,
                            onAllow = { ask.launch(Need.NEARBY.ask(context)) }, onSetUp = onSetUp, onChangeOwner = onChangeOwner,
                            band = state.device?.bandBattery ?: ours?.battery,
                            bandCharging = state.device?.bandCharging ?: (ours?.charging == true),
                        )
                    }
                }
                if (pairs.isNotEmpty()) {
                    val names = pairs.map { pairName(seen[it.serial], looks[it.serial]) }
                    PairPager(names, shownIndex, onGo = { i -> page = pairs.getOrNull(i)?.serial ?: ADD_PAGE })
                }
            }
            renaming?.let { serial ->
                TextSheet(
                    stringResource(R.string.glasses_name), seen[serial]?.name.orEmpty(),
                    onSave = { model.lastSeen.rename(serial, it.trim()) },
                    onDismiss = { renaming = null },
                    hint = pairName(null, looks[serial]),
                    valid = { it.isNotBlank() },
                    filter = { it.take(LastSeen.NAME_MAX) },
                )
            }
        }
    }

    // Once in a while, a few seconds into Home, the ask for a star.
    val starDue by model.star.due.collectAsStateWithLifecycle()
    var starShown by remember { mutableStateOf(false) }
    LaunchedEffect(paired) { if (paired == true) model.star.opened() }
    LaunchedEffect(starDue) {
        if (starDue) {
            delay(STAR_DELAY_MS)
            starShown = true
        }
    }
    if (starShown && starDue) {
        StarSheet(
            onOk = {
                openLink(context, REPO_URL)
                model.star.never()
            },
            onLater = model.star::later,
            onNever = model.star::never,
        )
    }
}

/** Under gesture navigation the home runs to the bottom edge with no handle; leaving it brings the handle back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HideGestureHandle() {
    val view = LocalView.current
    val density = LocalDensity.current
    // A bar at the bottom that takes no taps is the gesture handle; the 3-button bar takes them.
    val gestures = WindowInsets.navigationBarsIgnoringVisibility.getBottom(density) > 0 &&
        WindowInsets.tappableElementIgnoringVisibility.getBottom(density) == 0
    LifecycleResumeEffect(view, gestures) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        if (gestures) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.navigationBars())
        }
        onPauseOrDispose { bars?.show(WindowInsetsCompat.Type.navigationBars()) }
    }
}


private const val STAGE_FADE_FROM_MS = 60f
private const val STAGE_FADE_MS = 900f
/** Space between blocks on the sheet. */
private val GAP = 16.dp
/** The air under the four numbers when they close the screen. */
private val FOOT = 20.dp
private const val REFRESH_MS = 60_000L

/** The home's page after the last pair, for setting up another. */
private const val ADD_PAGE = "+"
// The ghost on the add page sways more than a pair at rest.
private const val GHOST_LIVELINESS = 1f
private const val STAR_DELAY_MS = 4_000L
