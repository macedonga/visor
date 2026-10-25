package com.macedonga.visor.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.pairs.Pairs
import com.macedonga.visor.R
import com.macedonga.visor.settings.TextSheet
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.transport.LeKeeper
import com.macedonga.visor.stage.AnimateLight
import com.macedonga.visor.stage.Choreography
import com.macedonga.visor.stage.GlassesPose
import com.macedonga.visor.stage.GlassesStage
import com.macedonga.visor.stage.HOME_STRIP
import com.macedonga.visor.stage.HOME_STRIP_SCALE
import com.macedonga.visor.stage.LightFrame
import com.macedonga.visor.stage.LightPool
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.Looks
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.stage.modelled
import com.macedonga.visor.stage.POOL_Y
import com.macedonga.visor.stage.SHEET_RADIUS
import com.macedonga.visor.stage.STAGE_ASPECT
import com.macedonga.visor.stage.StageDebug
import com.macedonga.visor.stage.StageLight
import com.macedonga.visor.stage.StagePages
import com.macedonga.visor.stage.StageSpot
import com.macedonga.visor.stage.closedOf
import com.macedonga.visor.stage.liveliness
import com.macedonga.visor.stage.lookOf
import com.macedonga.visor.stage.moodOf
import com.macedonga.visor.stage.sheetShape
import com.macedonga.visor.stage.turnsThePair
import com.macedonga.visor.stage.variantsOf
import com.macedonga.visor.ui.rememberHaptics
import com.macedonga.visor.ui.rememberTrailingFold
import com.macedonga.visor.update.REPO_URL
import com.macedonga.visor.update.StarAsk
import com.macedonga.visor.update.StarSheet
import com.macedonga.visor.update.openLink
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val connection: GlassesConnection,
    val live: LiveStream,
    val media: MediaImport,
    private val store: IdentityStore,
    keeper: LeKeeper,
    val looks: Looks,
    val lastSeen: LastSeen,
    val pairs: Pairs,
    val band: com.macedonga.visor.band.BandLink,
    val bands: com.macedonga.visor.band.Bands,
    val star: StarAsk,
) : ViewModel() {
    /** The LE link is up: the glasses are right here, whatever RFCOMM says. */
    val nearby = keeper.connected
    /** Null until the store has been read. */
    val paired = MutableStateFlow<Boolean?>(null)
    val shots = MutableStateFlow<List<PhoneShot>>(emptyList())
    val firstName = MutableStateFlow<String?>(null)
    val lastBattery = lastSeen.battery
    val lastPosture = lastSeen.posture

    init {
        // Whatever is saved shows up here as soon as it lands.
        viewModelScope.launch { media.saved.collect { shots.value = recentShots(context) } }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { paired.value = runCatching { store.all().isNotEmpty() }.getOrDefault(false) }
        viewModelScope.launch { shots.value = recentShots(context) }
        viewModelScope.launch { firstName.value = ownerFirstName(context) }
        pairs.refresh()
    }
}

/**
 * The glasses' media volume as Android holds it for them, in percent: with AVRCP absolute volume the phone's media
 * volume while it plays to the glasses is theirs. Null when media does not go to these glasses. The glasses send
 * their own only when it changes, and after a setup they have sent none.
 */
fun heldVolume(context: Context, address: String?): Int? {
    address ?: return null
    val audio = context.getSystemService(AudioManager::class.java) ?: return null
    val route = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        audio.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
    } else {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }
    val glasses = route.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.address.equals(address, ignoreCase = true) }
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (!glasses || max <= 0) return null
    return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
}

/** The phone owner's own contact card, first word of the name; null without one or without the permission. */
suspend fun ownerFirstName(context: Context): String? = withContext(Dispatchers.IO) {
    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return@withContext null
    runCatching {
        context.contentResolver.query(
            ContactsContract.Profile.CONTENT_URI, arrayOf(ContactsContract.Profile.DISPLAY_NAME_PRIMARY), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0)?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } else null }
    }.getOrNull()
}

/**
 * The glasses under their light, held in place at the top while the feed scrolls beneath them. As it
 * scrolls the pair draws back a little and the greeting and the status pill fade away.
 */
@Composable
fun HomeScreen(
    onSetUp: () -> Unit,
    onChangeOwner: (address: String?) -> Unit,
    onMedia: () -> Unit,
    onLive: () -> Unit,
    onMetaAi: () -> Unit,
    onSettings: () -> Unit,
    onDisplayTool: (com.macedonga.visor.display.DisplayTool) -> Unit = {},
    model: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by model.connection.state.collectAsStateWithLifecycle()
    val running by model.connection.running.collectAsStateWithLifecycle()
    val live by model.live.running.collectAsStateWithLifecycle()
    val captures by model.media.captures.collectAsStateWithLifecycle()
    val previews by model.media.previews.collectAsStateWithLifecycle()
    val importing by model.media.importing.collectAsStateWithLifecycle()
    val transfers by model.media.transfers.collectAsStateWithLifecycle()
    val problem by model.media.problem.collectAsStateWithLifecycle()
    val overBluetooth by model.media.overBluetooth.collectAsStateWithLifecycle()
    val shots by model.shots.collectAsStateWithLifecycle()
    val lastBattery by model.lastBattery.collectAsStateWithLifecycle()
    val firstName by model.firstName.collectAsStateWithLifecycle()
    val lastPosture by model.lastPosture.collectAsStateWithLifecycle()
    val nearby by model.nearby.collectAsStateWithLifecycle()
    val paired by model.paired.collectAsStateWithLifecycle()
    // Every pair set up here; the stage shows one at a time and a drag goes to the next.
    val pairs by model.pairs.all.collectAsStateWithLifecycle()
    val seen by model.lastSeen.seen.collectAsStateWithLifecycle()
    val looks by model.looks.all.collectAsStateWithLifecycle()
    val pictures by model.looks.pictures.collectAsStateWithLifecycle()
    val peeking by model.pairs.peeking.collectAsStateWithLifecycle()
    val current by model.connection.pair.collectAsStateWithLifecycle()
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
    val lastFacts = (if (elsewhere) shownSeen else current?.let(seen::get))?.facts ?: GlassesFacts(null, null, null)
    var renaming by remember { mutableStateOf<String?>(null) }
    val forced by StageDebug.mood.collectAsStateWithLifecycle()
    val variant by StageDebug.variant.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val stage = LocalStage.current
    HideGestureHandle()
    // Only the launch gets an entrance: coming back from another screen, the home is simply there as it was.
    val firstVisit = remember { !stage.entered }
    LaunchedEffect(Unit) { stage.entered = true }
    val entrance = rememberEntrance(firstVisit)

    var allowed by remember { mutableStateOf(hasBluetooth(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = hasBluetooth(context) }
    // Follow-ups and Live AI are recognised on the phone, whose recogniser wants the microphone permission even
    // though it is handed the glasses' audio. Asked once, at the first launch that lacks it.
    val askHearing = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(allowed) {
        val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (allowed && !granted && !prefs.getBoolean("asked_hearing", false)) {
            prefs.edit().putBoolean("asked_hearing", true).apply()
            askHearing.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LifecycleResumeEffect(Unit) {
        allowed = hasBluetooth(context)
        model.refresh()
        onPauseOrDispose { }
    }
    // The glasses push their battery, and its temperature, only when the percentage moves: while the home is in
    // view a fresh snapshot is asked for every minute, so the numbers are never old.
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
        if (allowed && paired == true && !running) GlassesService.start(context)
    }

    // What an import has to do and how far it is, across files: done captures plus the share of the one in flight,
    // never going back. Importing drops the Bluetooth link on purpose, so until it is back the glasses still count
    // as here, and the temples do not fold and open again.
    val fresh by model.media.fresh.collectAsStateWithLifecycle()
    var importTotal by remember { mutableIntStateOf(0) }
    var importBest by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    // What was waiting when the import began: the page shows it to the end instead of counting down to zero.
    var importSet by remember { mutableStateOf(emptyList<com.macedonga.visor.core.media.Capture>()) }
    // Two seconds at 100% and the import is done as far as anyone looking can tell, relinked or not.
    var finished by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(importing) {
        if (importing) {
            importTotal = fresh.size.coerceAtLeast(1)
            importBest = 0f
            importSet = fresh
            finished = false
        } else if (importTotal > 0) {
            settling = true
            withTimeoutOrNull(SETTLE_MS) { snapshotFlow { model.connection.state.value.link }.first { it == LinkState.READY } }
            settling = false
            importTotal = 0
        }
    }
    val importProgress = if (importing && importTotal > 0) {
        val size = transfers.sumOf { it.size }
        val file = if (size > 0) transfers.sumOf { it.received }.toFloat() / size else 0f
        maxOf(importBest, ((importTotal - fresh.size).coerceIn(0, importTotal) + file) / importTotal).coerceIn(0f, 1f)
    } else 0f
    SideEffect { if (importing) importBest = importProgress }
    LaunchedEffect(importing, importProgress >= 1f) {
        if (importing && importProgress >= 1f) {
            delay(DONE_HOLD_MS)
            finished = true
        }
    }
    val transferring = importing && !finished
    // The waiting page stays while there is something to import or bring over; when it should go, it is first
    // slid out of sight.
    val wantsWaiting = transferring || (!importing && fresh.isNotEmpty())
    var hadWaiting by remember { mutableStateOf(wantsWaiting) }
    LaunchedEffect(wantsWaiting) {
        if (hadWaiting && !wantsWaiting) leaving = true
        hadWaiting = wantsWaiting
    }
    val gate = com.macedonga.visor.gallery.rememberImportGate()
    val onImport = { haptics.toggle(); gate { model.media.importAllNew() } }

    // Sticky: the first unanswered try sets it and only a ready link clears it. The session clears its own
    // failure at every retry, which made the pill say "looking" between "away"s.
    var unanswered by remember { mutableStateOf(false) }
    LaunchedEffect(state.link, state.failure) {
        if (state.link == LinkState.READY) unanswered = false
        else if (state.failure != null || state.link == LinkState.LOST) unanswered = true
    }
    // The Live view pauses Bluetooth too; until it is back the glasses are as present as they were on Wi-Fi.
    val backFromLive = remember { stage.fromLive.also { stage.fromLive = false } }
    var liveSettling by remember { mutableStateOf(backFromLive) }
    var wasLive by remember { mutableStateOf(live || backFromLive) }
    LaunchedEffect(live) {
        if (wasLive && !live) {
            liveSettling = true
            withTimeoutOrNull(SETTLE_MS) { snapshotFlow { model.connection.state.value.link }.first { it == LinkState.READY } }
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
        (importing || settling || liveSettling) && state.link != LinkState.READY -> Mood.CONNECTED
        // Once a try has gone unanswered, the reconnect ladder keeps flipping between trying and failing; the home
        // keeps one word, from how the glasses were when they went quiet.
        state.link != LinkState.READY && state.target != null && unanswered -> when {
            lastPosture.inCase -> Mood.IN_CASE
            lastPosture.folded || nearby -> Mood.ASLEEP
            else -> Mood.FAR
        }
        else -> moodOf(state, live)
    }
    val colors = Visor.colors
    // At launch the light comes up from dark.
    LaunchedEffect(stage) { if (firstVisit) stage.light.dark() }
    StageLight(lookOf(mood, colors))
    val look by model.looks.look.collectAsStateWithLifecycle()
    val shownLook = if (other) shown?.let { looks[it.serial] } else look
    stage.pose.variants = variant?.let(::listOf) ?: variantsOf(shownLook)
    // The model is a Wayfarer: another frame, or a pair not connected, shows as Meta pictures it, once Meta has.
    val currentPicture = current?.takeIf { !modelled(looks[it]) }?.let(pictures::get)
    val picture = when {
        other -> shown?.let { pictures[it.serial] }
        !modelled(shownLook) -> currentPicture
        else -> null
    }
    SideEffect {
        stage.picture = picture
        stage.vacant = adding
    }
    // Other screens show the pair Visor is for.
    val leavingPicture = rememberUpdatedState(currentPicture)
    DisposableEffect(Unit) {
        onDispose {
            stage.picture = leavingPicture.value
            stage.vacant = false
        }
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
    remember {
        if (stage.homeToTop) {
            stage.homeToTop = false
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
        val spot = StageSpot(if (elsewhere) closedOf(GlassesState(), mood, shownSeen?.folded ?: true) else closedOf(state, mood, lastPosture.folded), liveliness(mood)) {
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
            // The sheet: its rounded top rides on the cards until it reaches the stage, then stays docked under the
            // stage's edge, and the cards scroll away beneath that rounded edge instead of under the stage.
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
            // The open stage's room, as the first item: the feed moves only with the finger, never with the
            // fold, and cards that load later go in below it instead of pushing the list down.
            item(key = "stage") { Spacer(Modifier.fillMaxWidth().height(stageHeight)) }
            // At rest the latest moments and the four numbers fill the screen under the stage exactly, with air at the
            // bottom; the rest of the feed waits below.
            val memories = moments(shots)
            val waitingPage: (@Composable (Modifier) -> Unit)? = if (wantsWaiting || leaving) {
                { m ->
                    val shown = if (importing || leaving) importSet else fresh
                    WaitingCard(shown, previews, transferring || leaving, if (leaving) 1f else importProgress, onImport, m, problem, overBluetooth)
                }
            } else null
            item(key = "hero") {
                val here = (state.link == com.macedonga.visor.core.session.LinkState.READY && state.device != null) || importing
                val lastCapture = listOfNotNull(shots.firstOrNull()?.takenAt, captures.mapNotNull { it.takenAtMillis }.maxOrNull()).maxOrNull()
                val pages = memories.isNotEmpty() || waitingPage != null
                // With no moments yet, the first waiting page grows the block to the screen and fades in, rather than
                // appearing; the last one going shrinks it back.
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
                            waitingCount = fresh.size, leaving = leaving, onLeft = { leaving = false },
                        )
                    }
                    Facts(lastFacts, live = here && !elsewhere, lastCapture = lastCapture, modifier = Modifier.padding(top = GAP).appear(entrance, 2))
                    Spacer(Modifier.height(FOOT))
                }
            }
            // The Display's tools, for a Display: known by its build while connected, by its band while away.
            val bandsHere = model.bands.all.value
            if (!elsewhere && (com.macedonga.visor.settings.hasDisplay(state.info?.build) || bandsHere.any { it.glasses == current })) {
                item(key = "display") { com.macedonga.visor.display.DisplayShelf(onDisplayTool, Modifier.padding(top = 4.dp, bottom = 8.dp).appear(entrance, 4)) }
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
                Greeting(firstName, Modifier)
                // Which pair this is; a tap names it.
                if (shown != null) {
                    Text(
                        pairName(shownSeen, looks[shown.serial]),
                        style = Visor.type.label,
                        color = Visor.colors.textSecondary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { renaming = shown.serial }
                            .padding(vertical = 4.dp),
                    )
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (pairs.size > 1) 12.dp else 26.dp).then(fade).appear(entrance, 0),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (adding) {
                    Pill(stringResource(R.string.glasses_set_up_new), onSetUp)
                } else if (other && shown != null) {
                    OtherPairPill(
                        mood, shownSeen, peeking = peeking == shown.serial,
                        onConnect = if (live || importing) null else ({
                            haptics.toggle()
                            model.pairs.connect(shown.serial)
                        }),
                        modifier = Modifier,
                    )
                } else {
                    val band by model.band.state.collectAsStateWithLifecycle()
                    val bands by model.bands.all.collectAsStateWithLifecycle()
                    val ours = band.takeIf { b -> b.battery != null && bands.any { it.serial == b.serial && it.glasses == current } }
                    StatusPill(
                        state, mood, lastBattery, paired, allowed, transferring, importProgress, waiting = if (importing) 0 else fresh.size, onImport = onImport,
                        onAllow = { ask.launch(wanted()) }, onSetUp = onSetUp, onChangeOwner = onChangeOwner, modifier = Modifier,
                        band = state.device?.bandBattery ?: ours?.battery,
                        bandCharging = state.device?.bandCharging ?: (ours?.charging == true),
                    )
                }
                if (pairs.isNotEmpty()) PairDots(pairs.size + 1, shownIndex, Modifier.padding(top = 12.dp))
            }
            renaming?.let { serial ->
                TextSheet(
                    stringResource(R.string.glasses_name), seen[serial]?.name.orEmpty(),
                    onSave = { model.lastSeen.rename(serial, it.trim()) },
                    onDismiss = { renaming = null },
                    hint = pairName(null, looks[serial]),
                    valid = { it.isNotBlank() && it.trim().length <= 40 },
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
            onNo = model.star::later,
            onNever = model.star::never,
        )
    }
}

/**
 * The home runs to the bottom edge with no gesture handle over it. A swipe from the edge brings the
 * handle back for a moment; leaving the home brings it back for good.
 */
@Composable
private fun HideGestureHandle() {
    val view = LocalView.current
    LifecycleResumeEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.navigationBars())
        onPauseOrDispose { bars?.show(WindowInsetsCompat.Type.navigationBars()) }
    }
}

private fun wanted(): Array<String> = buildList {
    add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    add(Manifest.permission.READ_CONTACTS)
}.toTypedArray()

private fun hasBluetooth(context: Context) =
    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED


private const val STAGE_FADE_FROM_MS = 60f
private const val STAGE_FADE_MS = 900f
/** The air between blocks on the sheet, now that they have no headings. */
private val GAP = 16.dp
/** The air under the four numbers when they close the screen. */
private val FOOT = 20.dp
/** How long after an import the glasses count as here while the Bluetooth link comes back. */
private const val SETTLE_MS = 25_000L
private const val DONE_HOLD_MS = 2_000L
private const val REFRESH_MS = 60_000L

/** The home's page after the last pair, for setting up another. */
private const val ADD_PAGE = "+"
private const val STAR_DELAY_MS = 4_000L
