package com.macedonga.visor.setup

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.R
import com.macedonga.visor.band.BandErrand
import com.macedonga.visor.band.BandLink
import com.macedonga.visor.band.BandPair
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.design.VisorColors
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.provision.ProvisionStep
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import com.macedonga.visor.settings.CARD_RADIUS
import com.macedonga.visor.settings.Hairline
import com.macedonga.visor.settings.Need
import com.macedonga.visor.settings.Pills
import com.macedonga.visor.settings.ProgressBar
import com.macedonga.visor.settings.Spinner
import com.macedonga.visor.settings.WidePill
import com.macedonga.visor.settings.errandProblem
import com.macedonga.visor.settings.openAppPage
import com.macedonga.visor.settings.openListenerPage
import com.macedonga.visor.settings.pairWords
import com.macedonga.visor.stage.LightLook
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.stage.SETTLE_EASE
import com.macedonga.visor.stage.STAGE_ASPECT
import com.macedonga.visor.stage.StageDebug
import com.macedonga.visor.stage.StageLight
import com.macedonga.visor.stage.lookOf
import com.macedonga.visor.stage.nod
import com.macedonga.visor.stage.shake
import com.macedonga.visor.stage.stageSpot
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import com.macedonga.visor.ui.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The setup's pages, in order. */
enum class Page { WELCOME, ACCESS, LOGIN, PAIRING, LINKING, BAND }

/** What the setup asks of the phone, in order. Nearby Wi-Fi goes with nearby devices: Android asks for both at once. */
private val ASKED = listOf(Need.NEARBY, Need.NOTIFY, Need.LISTENER, Need.CONTACTS, Need.MIC, Need.LOCATION, Need.LOCATION_ALWAYS)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val login: MetaLogin,
    val provisioner: Provisioner,
    private val nearby: NearbyGlasses,
    private val store: IdentityStore,
    private val connection: GlassesConnection,
    val bandPair: BandPair,
    val bandLink: BandLink,
    private val log: EventLog,
) : ViewModel() {
    var page by mutableStateOf(Page.WELCOME)
        private set
    private val history = mutableListOf<Page>()

    /** Needs already asked for once on the access page: the walk moves past them, granted or not. */
    var asked by mutableStateOf(emptySet<Need>())
    val heard = MutableStateFlow<List<NearbyPair>>(emptyList())
    var bonded by mutableStateOf(emptyList<NearbyPair>())
        private set
    var setUpHere by mutableStateOf(emptySet<String>())
        private set
    var bluetoothOff by mutableStateOf(false)
        private set
    var chosen by mutableStateOf<NearbyPair?>(null)
        private set
    var bonding by mutableStateOf(false)
        private set
    private var bondJob: Job? = null
    var bondFailure by mutableStateOf<BondFailed.Why?>(null)
        private set
    /** Meta's login page was opened from here: once logged in, the setup goes on by itself. */
    var loginOpened by mutableStateOf(false)
    private var watching: Job? = null
    private var firstRun = false
    // Setting a known pair up again: it is taken as soon as it is found, bonded or heard, with nothing to pick.
    private var wanted: String? = null

    /** True once [begin] has run on this model. */
    var begun = false
        private set

    /** A fresh setup: from the welcome on the first run, otherwise from the first page with something to do. */
    fun begin(welcome: Boolean, changeOwner: Boolean, address: String? = null) {
        begun = true
        wanted = address
        history.clear()
        asked = emptySet()
        loginOpened = false
        chosen = null
        bondFailure = null
        provisioner.reset()
        login.refresh()
        firstRun = welcome
        page = when {
            welcome -> Page.WELCOME
            // Changing owner starts at the login, whoever is logged in now.
            changeOwner -> Page.LOGIN
            else -> after(Page.WELCOME)
        }
    }

    fun next() = go(after(page))

    fun go(to: Page) {
        if (to == page) return
        // A known pair this phone is bonded to needs no pairing page: straight to linking it.
        val known = if (to == Page.PAIRING && chosen == null) wanted?.let { a ->
            nearby.bonded().firstOrNull { it.address.equals(a, ignoreCase = true) }
        } else null
        history += page
        if (known != null) {
            history += Page.PAIRING
            chosen = known
            page = Page.LINKING
            link()
            return
        }
        page = to
    }

    /** One page back; false when there is none, and the setup should close. */
    fun back(): Boolean {
        page = history.removeLastOrNull() ?: return false
        return true
    }

    private fun after(from: Page): Page = when {
        from == Page.WELCOME -> if (firstRun || !Need.NEARBY.granted(context)) Page.ACCESS else after(Page.ACCESS)
        from == Page.ACCESS && login.status.value != MetaLogin.Status.LoggedIn -> Page.LOGIN
        from.ordinal < Page.PAIRING.ordinal -> Page.PAIRING
        else -> Page.LINKING
    }

    /** Listens for glasses while the pairing page is up. */
    fun watch() {
        watching?.cancel()
        watching = viewModelScope.launch {
            bonded = withContext(Dispatchers.IO) { nearby.bonded() }
            setUpHere = withContext(Dispatchers.IO) { runCatching { store.all().map { it.glassesAddress.uppercase() }.toSet() }.getOrDefault(emptySet()) }
            takeWanted(bonded)
            var lastError: String? = null
            while (true) {
                if (!nearby.enabled) {
                    bluetoothOff = true
                    heard.value = emptyList()
                    delay(1_500)
                    continue
                }
                if (bluetoothOff) bonded = withContext(Dispatchers.IO) { nearby.bonded() }
                bluetoothOff = false
                try {
                    nearby.scan().collect { found ->
                        heard.value = found
                        takeWanted(found)
                    }
                } catch (stop: CancellationException) {
                    throw stop
                } catch (failed: Exception) {
                    if (failed.message != lastError) {
                        lastError = failed.message
                        log.add("Setup: scan failed: ${failed.message}")
                    }
                    if (nearby.enabled) delay(3_000)
                }
            }
        }
    }

    private fun takeWanted(found: List<NearbyPair>) {
        val known = wanted?.let { a -> found.firstOrNull { it.address.equals(a, ignoreCase = true) } } ?: return
        if (chosen == null && page == Page.PAIRING) choose(known)
    }

    fun stopWatching() {
        watching?.cancel()
        watching = null
    }

    fun choose(pair: NearbyPair) {
        chosen = pair
        go(Page.LINKING)
        link()
    }

    /** The pair is owned by another account: log out and back to the login, with back going to the pairing page. */
    fun otherAccount() {
        provisioner.reset()
        login.logOut()
        chosen = null
        page = Page.LOGIN
    }

    /** The pair just set up, connected at once so a Display can be told apart and its band paired. */
    fun connect(target: GlassesTarget) = GlassesService.start(context, target)

    /** True for a Display, false for other glasses, null until the session with [serial] is up. */
    fun display(serial: String): Flow<Boolean?> = connection.state.map { s ->
        val build = s.info?.build
        if (s.target?.serial == serial && s.link == LinkState.READY && build != null) isDisplayBuild(build) else null
    }.distinctUntilChanged()

    fun toBand() {
        bandPair.reset()
        go(Page.BAND)
    }

    /** Bond the chosen pair if it is not, then make this phone its owner. */
    fun link() {
        val pair = chosen ?: return
        bondFailure = null
        provisioner.reset()
        bondJob = viewModelScope.launch {
            bonding = true
            val address = try {
                nearby.bond(pair.address)
            } catch (failed: BondFailed) {
                bondFailure = failed.why
                return@launch
            } finally {
                bonding = false
            }
            provisioner.start(address)
        }
    }

    /** Gives up a bond still waiting on the glasses. */
    fun stopBond() {
        if (!bonding) return
        bondJob?.cancel()
        chosen?.let { nearby.cancelBond(it.address) }
    }
}

/** The setup over the shared stage: welcome, access, login, pairing, then the change of owner. */
@Composable
fun OnboardingScreen(
    welcome: Boolean,
    login: Boolean,
    address: String? = null,
    onLogin: () -> Unit,
    onEventLog: () -> Unit,
    onDone: (GlassesTarget) -> Unit,
    onLeave: () -> Unit,
    model: OnboardingViewModel = hiltViewModel(),
) {
    // Once per model: back from the login page it goes on where it was.
    if (!model.begun) model.begin(welcome, login, address)
    val debugPage by StageDebug.onboardingPage.collectAsStateWithLifecycle()
    LaunchedEffect(debugPage) { debugPage?.let { p -> Page.entries.firstOrNull { it.name == p }?.let(model::go) } }

    val page = model.page
    val stage = LocalStage.current
    val colors = Visor.colors
    val haptics = rememberHaptics()
    val status by model.provisioner.status.collectAsStateWithLifecycle()
    val band by model.bandPair.status.collectAsStateWithLifecycle()
    val busy = page == Page.LINKING && status is Provisioner.Status.Running ||
        page == Page.BAND && band is BandPair.Status.Running
    val done = status as? Provisioner.Status.Done
    // Back does nothing mid-handover, which it would cut half done; once the pair is owned it finishes, as Open does.
    BackHandler {
        when {
            busy -> Unit
            done != null && (page == Page.LINKING || page == Page.BAND) -> {
                model.bandPair.reset()
                model.provisioner.reset()
                onDone(done.target)
            }
            else -> {
                model.stopBond()
                if (!model.back()) onLeave()
            }
        }
    }

    // The welcome opens on the mark: at launch it is already playing, opened later it plays again from here.
    val withMark = remember { welcome && page == Page.WELCOME }
    val replay = remember { (withMark && !stage.intro.showing).also { if (it) stage.intro.arm() } }
    LaunchedEffect(Unit) { if (replay) stage.intro.play { haptics.tick(0.4f) } }

    val demo = page == Page.PAIRING
    val story = rememberPairingStory(demo)

    val context = LocalContext.current
    var accessTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        accessTick++
        onPauseOrDispose { }
    }
    val granted = remember(accessTick) { ASKED.count { it.granted(context) } }
    val failed = page == Page.LINKING && (model.bondFailure != null || status is Provisioner.Status.Failed)
    val closedDemo by remember { derivedStateOf { story.clock.floatValue in Demo.FOLD..Demo.OPEN } }
    val closed = when (page) {
        Page.PAIRING -> closedDemo
        Page.LINKING -> done == null
        else -> false
    }
    StageLight(pageLook(page, granted, done != null, failed, band, colors))
    LaunchedEffect(Unit) { if (welcome && page == Page.WELCOME) stage.light.dark() }
    LaunchedEffect(done != null) {
        if (done != null) {
            haptics.connected()
            stage.light.flash()
            delay(500)
            stage.pose.nod()
        }
    }
    LaunchedEffect(failed) {
        if (failed) {
            haptics.error()
            stage.light.flicker()
            stage.pose.shake()
        }
    }
    LaunchedEffect(granted) {
        if (granted > 0 && page == Page.ACCESS) {
            haptics.tick(0.6f)
            stage.pose.nod()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val topPx = with(density) { top.toPx() }
        val stagePx = widthPx / STAGE_ASPECT
        val placed = page.placement(heightPx, topPx, stagePx)
        val centre by animateFloatAsState(placed.centre, spring(dampingRatio = 0.9f, stiffness = 40f), label = "centre")
        val scale by animateFloatAsState(placed.scale, spring(dampingRatio = 0.9f, stiffness = 40f), label = "scale")
        val liveliness = when (page) {
            Page.PAIRING -> 0.12f
            Page.LINKING -> if (done != null) 1f else 1.3f
            else -> 0.6f
        }
        stageSpot(closed, liveliness) {
            val s = scale * story.zoom.floatValue
            val h = stagePx * max(s, 1f)
            this.top = centre - h / 2
            height = h
            this.scale = s
            pivotX = story.pivot.floatValue
            pivotY = 0.5f
            shift = (h - stagePx) / 2
            alpha = 1f
        }
        if (demo) Marks(story.clock)

        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                (fadeIn(tween(520, delayMillis = 160)) + slideInVertically(tween(620, delayMillis = 160, easing = SETTLE_EASE)) { it / 14 })
                    .togetherWith(fadeOut(tween(200)))
            },
            label = "page",
        ) { shown ->
            val contentTop = with(density) { shown.placement(heightPx, topPx, stagePx).contentTop.toDp() }
            val frame = Modifier.fillMaxSize().padding(top = top, bottom = bottom + 20.dp).padding(horizontal = 24.dp).padding(top = contentTop)
            when (shown) {
                Page.WELCOME -> WelcomePage(welcome, frame, before = { if (withMark) stage.intro.awaitLetGo() }) { model.next() }
                Page.ACCESS -> AccessPage(model, accessTick, { accessTick++ }, frame)
                Page.LOGIN -> LoginPage(model, onLogin, onEventLog, frame)
                Page.PAIRING -> PairingPage(model, story.clock, frame)
                Page.LINKING -> LinkingPage(model, status, onDone, frame)
                Page.BAND -> BandPage(model, status, onDone, frame)
            }
        }
    }
}

private class PairingStory(val clock: MutableFloatState, val zoom: MutableFloatState, val pivot: MutableFloatState)

/** The pairing page's story runs on one clock; elsewhere it rests at 0 and the pair turns home. */
@Composable
private fun rememberPairingStory(demo: Boolean): PairingStory {
    val stage = LocalStage.current
    val clock = remember { mutableFloatStateOf(0f) }
    val zoom = remember { mutableFloatStateOf(1f) }
    val pivot = remember { mutableFloatStateOf(0.5f) }
    val debugTime by StageDebug.demoAt.collectAsStateWithLifecycle()
    LaunchedEffect(demo) { if (demo) clock.floatValue = 0f }
    val demoNow by rememberUpdatedState(demo)
    val debugNow by rememberUpdatedState(debugTime)
    LaunchedEffect(stage) {
        var last = 0L
        val pose = stage.pose
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                val on = demoNow
                if (on) clock.floatValue = debugNow ?: ((clock.floatValue + dt) % Demo.LOOP)
                val t = clock.floatValue
                val k = 1f - exp(-dt / FOLLOW_SECONDS)
                pose.viewYaw += ((if (on) Demo.yaw(t) else 0f) - pose.viewYaw) * k
                pose.viewPitch += ((if (on) Demo.pitch(t) else 0f) - pose.viewPitch) * k
                pose.button = if (on) Demo.button(t) else (pose.button - dt * 4f).coerceAtLeast(0f)
                pose.innerLed = if (on) Demo.led(t) else (pose.innerLed - dt * 4f).coerceAtLeast(0f)
                val slow = 1f - exp(-dt / CAMERA_SECONDS)
                zoom.floatValue += ((if (on) Demo.zoom(t) else 1f) - zoom.floatValue) * slow
                val towards = (if (on) Demo.focus(t) else null)?.let { stage.renderer.partOnScreen(it)?.first } ?: 0.5f
                pivot.floatValue += (towards.coerceIn(0.15f, 0.85f) - pivot.floatValue) * slow
                stage.renderer.invalidate()
            }
        }
    }
    DisposableEffect(stage) {
        onDispose {
            stage.pose.viewYaw = 0f
            stage.pose.viewPitch = 0f
            stage.pose.button = 0f
            stage.pose.innerLed = 0f
        }
    }
    return remember { PairingStory(clock, zoom, pivot) }
}

/** The stage light on each page; on the access page it warms as Visor gets what it asks for. */
private fun pageLook(page: Page, granted: Int, done: Boolean, failed: Boolean, band: BandPair.Status, colors: VisorColors): LightLook = when (page) {
    Page.WELCOME -> lookOf(Mood.CONNECTED, colors)
    Page.ACCESS -> {
        val f = granted / ASKED.size.toFloat()
        LightLook(lerp(colors.lightCold, colors.lightNeutral, f), lerp(colors.lightCold, colors.lightWarm, f), level = 0.3f + 0.65f * f, spread = 0.8f + 0.2f * f, breathSeconds = 9f, breathDepth = 0.06f)
    }
    Page.LOGIN -> lookOf(Mood.CONNECTED, colors).copy(level = 0.8f)
    Page.PAIRING -> lookOf(Mood.SEARCHING, colors)
    Page.LINKING -> when {
        done -> lookOf(Mood.CONNECTED, colors)
        failed -> lookOf(Mood.FAR, colors)
        else -> lookOf(Mood.SEARCHING, colors).copy(breathSeconds = 2.2f)
    }
    Page.BAND -> when (band) {
        is BandPair.Status.Running -> lookOf(Mood.SEARCHING, colors).copy(breathSeconds = 2.2f)
        is BandPair.Status.Failed -> lookOf(Mood.FAR, colors)
        else -> lookOf(Mood.CONNECTED, colors)
    }
}

/** Where a page wants the pair (the height of its centre on the window, and its size) and where its content starts. */
private class Placement(val centre: Float, val scale: Float, val contentTop: Float)

private fun Page.placement(heightPx: Float, topPx: Float, stagePx: Float): Placement = when (this) {
    Page.WELCOME -> Placement(heightPx * 0.38f, 1.05f, 0f)
    Page.ACCESS -> Placement(topPx + stagePx * 0.2f, 0.5f, stagePx * 0.42f)
    Page.LOGIN -> Placement(topPx + stagePx * 0.36f, 0.78f, stagePx * 0.7f)
    Page.PAIRING -> Placement(topPx + stagePx * 0.4f, 1f, stagePx * 0.8f)
    Page.LINKING -> Placement(heightPx * 0.36f, 1f, 0f)
    Page.BAND -> Placement(heightPx * 0.3f, 0.8f, 0f)
}

/** The welcome: after [before], the pair comes up out of the dark and opens, then the words. */
@Composable
private fun WelcomePage(firstRun: Boolean, modifier: Modifier, before: suspend () -> Unit, onBegin: () -> Unit) {
    val rise = remember { Animatable(if (firstRun) 0f else 1f) }
    LaunchedEffect(Unit) {
        before()
        delay(1_300)
        rise.animateTo(1f, tween(900, easing = SETTLE_EASE))
    }
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        Column(Modifier.graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 28.dp.toPx() }) {
            Text(stringResource(R.string.app_name), style = Visor.type.display, color = Visor.colors.textPrimary, modifier = Modifier.asHeading())
            Spacer(Modifier.height(36.dp))
            WidePill(stringResource(R.string.onboard_begin), onBegin, Modifier.fillMaxWidth())
        }
    }
}

/** What the phone is asked for, one at a time, each with its reason; the light grows as Visor gets what it needs. */
@Composable
private fun AccessPage(model: OnboardingViewModel, tick: Int, onAnswered: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val given = remember(tick) { ASKED.filter { it.granted(context) }.toSet() }
    // Nearby devices stays first until it is given: nothing works without it. All the time comes after location, or not at all.
    val next = ASKED.firstOrNull {
        it !in given && (it == Need.NEARBY || it !in model.asked) && (it != Need.LOCATION_ALWAYS || Need.LOCATION in given)
    }
    var asking by remember { mutableStateOf<Need?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val need = asking
        // Refused for good, Android asks no more: Visor's own page is the way left.
        val refused = need == Need.NEARBY && Need.NEARBY.ask(context).none { result[it] == true }
        if (refused && activity?.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN) == false) openAppPage(context)
        onAnswered()
    }
    fun ask(need: Need) {
        model.asked += need
        asking = need
        val wanted = need.ask(context) + if (need == Need.NEARBY) Need.WIFI.ask(context) else emptyArray()
        if (wanted.isEmpty()) openListenerPage(context) else launcher.launch(wanted)
    }

    Column(modifier) {
        Text(stringResource(R.string.onboard_access_title), style = Visor.type.title, color = Visor.colors.textPrimary, modifier = Modifier.asHeading())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 20.dp)) {
            for (need in ASKED) AccessRow(need, need in given, need == next, onClick = { ask(need) })
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // "Not now" slides in beside Allow for what can wait, and out again when only Continue is left.
            AnimatedVisibility(
                visible = next != null && next != Need.NEARBY,
                enter = fadeIn(tween(240)) + expandHorizontally(tween(320, easing = SETTLE_EASE)),
                exit = fadeOut(tween(160)) + shrinkHorizontally(tween(280, easing = SETTLE_EASE)),
            ) {
                Row {
                    WidePill(stringResource(R.string.onboard_skip), { next?.let { model.asked += it } }, quiet = true)
                    Spacer(Modifier.width(12.dp))
                }
            }
            if (next != null) WidePill(stringResource(R.string.allow), { ask(next) }, Modifier.weight(1f))
            else WidePill(stringResource(R.string.onboard_continue), { model.next() }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AccessRow(need: Need, given: Boolean, current: Boolean, onClick: () -> Unit) {
    val colors = Visor.colors
    val titleColour by animateColorAsState(if (given || current) colors.textPrimary else colors.textSecondary, tween(300), label = "title")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !given, onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val fill by animateFloatAsState(if (given) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 380f), label = "check")
        Box(
            Modifier.padding(top = 1.dp).size(22.dp)
                .border(1.5.dp, if (current) Color.White else colors.control, CircleShape)
                .graphicsLayer { scaleX = 0.6f + 0.4f * fill; scaleY = scaleX }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = fill)),
            contentAlignment = Alignment.Center,
        ) {
            if (given) Icon(VisorIcons.Check, stringResource(R.string.access_allowed), Modifier.size(16.dp), tint = Color.Black)
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(need.title), style = Visor.type.body, color = titleColour)
                if (need == Need.NEARBY && !given) {
                    Text(stringResource(R.string.onboard_needed), style = Visor.type.label, color = colors.textTertiary, modifier = Modifier.padding(start = 8.dp))
                }
            }
            AnimatedVisibility(current) {
                Text(stringResource(need.why), style = Visor.type.label, color = colors.textSecondary, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** The Meta login, on Meta's own page; back here it goes on by itself. */
@Composable
private fun LoginPage(model: OnboardingViewModel, onLogin: () -> Unit, onEventLog: () -> Unit, modifier: Modifier) {
    val status by model.login.status.collectAsStateWithLifecycle()
    LaunchedEffect(status, model.loginOpened) {
        if (model.loginOpened && status == MetaLogin.Status.LoggedIn) {
            delay(700)
            model.loginOpened = false
            model.next()
        }
    }
    val open = {
        model.loginOpened = true
        model.login.begin()
        onLogin()
    }
    Column(modifier) {
        Text(stringResource(R.string.onboard_login_title), style = Visor.type.title, color = Visor.colors.textPrimary, modifier = Modifier.asHeading())
        val line = when (status) {
            MetaLogin.Status.LoggedIn -> stringResource(R.string.onboard_login_done)
            MetaLogin.Status.Failed -> stringResource(R.string.onboard_login_failed)
            MetaLogin.Status.Working, MetaLogin.Status.LoggedOut -> null
        }
        if (line != null) Text(line, style = Visor.type.body, color = Visor.colors.textPrimary, modifier = Modifier.padding(top = 20.dp))
        Spacer(Modifier.weight(1f))
        if (status == MetaLogin.Status.LoggedIn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WidePill(stringResource(R.string.onboard_login_other), {
                    model.login.logOut()
                    open()
                }, quiet = true)
                Spacer(Modifier.width(12.dp))
                WidePill(stringResource(R.string.onboard_continue), { model.next() }, Modifier.weight(1f))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A login that failed or came back undone can be read in the log.
                if (status == MetaLogin.Status.Failed || (model.loginOpened && status == MetaLogin.Status.LoggedOut)) {
                    WidePill(stringResource(R.string.onboard_login_log), onEventLog, quiet = true)
                    Spacer(Modifier.width(12.dp))
                }
                WidePill(stringResource(R.string.onboard_login_button), open, Modifier.weight(1f), busy = status == MetaLogin.Status.Working)
            }
        }
    }
}

/** Pairing mode shown on the pair, then the glasses heard nearby. */
@Composable
private fun PairingPage(model: OnboardingViewModel, clock: MutableFloatState, modifier: Modifier) {
    DisposableEffect(model) {
        model.watch()
        onDispose { model.stopWatching() }
    }
    val heard by model.heard.collectAsStateWithLifecycle()
    val pairs = remember(heard, model.bonded) {
        val byAddress = heard.associateBy { it.address }
        // First heard first, so rows do not swap under the finger as signal strength moves.
        heard.sortedByDescending { it.pairing } +
            model.bonded.filter { it.address !in byAddress }
    }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(HELP_AFTER_MS)
        waited = true
    }
    val context = LocalContext.current
    val line by remember { derivedStateOf { Demo.line(clock.floatValue) } }
    val seconds by remember { derivedStateOf { Demo.secondsLeft(clock.floatValue) } }

    Column(modifier) {
        Text(stringResource(R.string.onboard_pair_title), style = Visor.type.title, color = Visor.colors.textPrimary, modifier = Modifier.asHeading())
        Column(Modifier.padding(top = 16.dp)) {
            val lines = listOf(R.string.onboard_pair_fold, R.string.onboard_pair_hold, R.string.onboard_pair_blink)
            lines.forEachIndexed { i, text ->
                StepLine(i + 1, stringResource(text), i == line, if (i == 1) seconds else null) { clock.floatValue = Demo.LINE_STARTS[i] }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 18.dp)) {
            when {
                model.bluetoothOff -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.onboard_bluetooth_off), style = Visor.type.body, color = Visor.colors.textPrimary, modifier = Modifier.weight(1f))
                    WidePill(stringResource(R.string.onboard_turn_on), {
                        runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, Modifier.width(140.dp))
                }
                pairs.isEmpty() -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spinner()
                        Text(stringResource(R.string.onboard_looking), style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(start = 12.dp))
                    }
                    AnimatedVisibility(waited, enter = fadeIn(tween(600))) {
                        Text(stringResource(R.string.onboard_not_found), style = Visor.type.label, color = Visor.colors.textTertiary, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                else -> {
                    Text(stringResource(R.string.onboard_nearby), style = Visor.type.label, color = Visor.colors.textSecondary, modifier = Modifier.asHeading().padding(bottom = 8.dp))
                    Column(Modifier.clip(RoundedCornerShape(CARD_RADIUS)).background(Visor.colors.raised)) {
                        pairs.forEachIndexed { i, pair ->
                            if (i > 0) Hairline()
                            PairRow(pair, pair.address.uppercase() in model.setUpHere) { model.choose(pair) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepLine(index: Int, text: String, current: Boolean, seconds: Int?, onClick: () -> Unit) {
    val colors = Visor.colors
    val colour by animateColorAsState(if (current) colors.textPrimary else colors.textTertiary, tween(400), label = "line")
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text("$index", style = Visor.type.label, color = colour, modifier = Modifier.width(22.dp).padding(top = 3.dp))
        Text(text, style = Visor.type.body, color = colour, modifier = Modifier.weight(1f))
        if (seconds != null && current) {
            Text(stringResource(R.string.onboard_pair_seconds, seconds), style = Visor.type.label, color = colors.textPrimary, modifier = Modifier.padding(start = 10.dp, top = 3.dp))
        }
    }
}

@Composable
private fun PairRow(pair: NearbyPair, setUpHere: Boolean, onClick: () -> Unit) {
    val colors = Visor.colors
    val state = when {
        setUpHere -> R.string.onboard_set_up_here
        pair.pairing -> R.string.onboard_ready_to_pair
        pair.bonded -> R.string.onboard_paired_here
        else -> R.string.onboard_not_pairing
    }
    val dim = !pair.pairing && !pair.bonded && !setUpHere
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp).alpha(if (dim) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(pair.name, style = Visor.type.body, color = colors.textPrimary)
            Text(stringResource(state), style = Visor.type.label, color = if (state == R.string.onboard_ready_to_pair) colors.textPrimary else colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        pair.rssi?.let { SignalBars(it) }
        Icon(VisorIcons.Chevron, null, Modifier.padding(start = 10.dp).size(16.dp), tint = colors.textTertiary)
    }
}

/** Three bars for how loud the glasses are heard. */
@Composable
private fun SignalBars(rssi: Int) {
    val level = when {
        rssi > -60 -> 3
        rssi > -75 -> 2
        else -> 1
    }
    val on = Visor.colors.textPrimary
    val off = Visor.colors.control
    Canvas(Modifier.size(16.dp)) {
        val w = size.width / 5
        for (i in 0 until 3) {
            val h = size.height * (0.4f + 0.3f * i)
            drawRoundRect(
                if (i < level) on else off,
                topLeft = Offset(i * 2 * w, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

/** The bond and the change of owner, then the pair opens and nods; or why it stopped. */
@Composable
private fun LinkingPage(model: OnboardingViewModel, status: Provisioner.Status, onDone: (GlassesTarget) -> Unit, modifier: Modifier) {
    val colors = Visor.colors
    val bondFailure = model.bondFailure
    val done = status as? Provisioner.Status.Done
    val failure = status as? Provisioner.Status.Failed
    val reached = (status as? Provisioner.Status.Running)?.reached.orEmpty()
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == Activity.RESULT_OK) model.link() }
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        when {
            done != null -> {
                Text(stringResource(R.string.onboard_ready), style = Visor.type.display, color = colors.textPrimary, modifier = Modifier.asHeading())
                Text(
                    stringResource(R.string.onboard_ready_sub, model.chosen?.name ?: stringResource(R.string.glasses_default_name)),
                    style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp),
                )
                Spacer(Modifier.height(32.dp))
                // A Display goes on to its band, which can wait; other glasses are done here.
                LaunchedEffect(done.target) { model.connect(done.target) }
                val display by remember(done.target.serial) { model.display(done.target.serial) }.collectAsStateWithLifecycle(null)
                var waited by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(MODEL_WAIT_MS)
                    waited = true
                }
                val open = {
                    model.provisioner.reset()
                    onDone(done.target)
                }
                when {
                    display == true -> Row(verticalAlignment = Alignment.CenterVertically) {
                        WidePill(stringResource(R.string.onboard_band_later), open, quiet = true)
                        Spacer(Modifier.width(12.dp))
                        WidePill(stringResource(R.string.onboard_band_next), model::toBand, Modifier.weight(1f))
                    }
                    display == null && !waited -> Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spinner()
                        Text(stringResource(R.string.onboard_band_connecting), style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(start = 12.dp))
                    }
                    else -> WidePill(stringResource(R.string.onboard_open), open, Modifier.fillMaxWidth())
                }
            }
            bondFailure != null || failure != null -> {
                Text(stringResource(R.string.onboard_failed), style = Visor.type.title, color = colors.textPrimary, modifier = Modifier.asHeading())
                val reason = when (bondFailure) {
                    BondFailed.Why.REFUSED -> stringResource(R.string.onboard_bond_refused)
                    BondFailed.Why.NO_ANSWER -> stringResource(R.string.onboard_bond_no_answer)
                    BondFailed.Why.BLUETOOTH_OFF -> stringResource(R.string.onboard_bluetooth_off)
                    null -> if (failure?.wrongOwner == true) {
                        stringResource(R.string.onboard_wrong_owner)
                    } else {
                        failure?.reached?.lastOrNull()?.let { stringResource(R.string.onboard_stopped_at, stringResource(it.label())) }.orEmpty()
                    }
                }
                if (reason.isNotEmpty()) Text(reason, style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(32.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WidePill(stringResource(R.string.onboard_choose_again), {
                        model.provisioner.reset()
                        model.back()
                    }, quiet = true)
                    Spacer(Modifier.width(12.dp))
                    when {
                        failure?.wrongOwner == true -> WidePill(stringResource(R.string.onboard_login_other), model::otherAccount, Modifier.weight(1f))
                        bondFailure == BondFailed.Why.BLUETOOTH_OFF -> WidePill(stringResource(R.string.onboard_turn_on), {
                            runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        }, Modifier.weight(1f))
                        else -> WidePill(stringResource(R.string.try_again), { model.link() }, Modifier.weight(1f))
                    }
                }
            }
            else -> {
                val bonding = model.bonding || status == Provisioner.Status.Idle
                Text(
                    stringResource(if (bonding) R.string.onboard_linking_title else R.string.onboard_owning_title),
                    style = Visor.type.title, color = colors.textPrimary, modifier = Modifier.asHeading(),
                )
                AnimatedContent(
                    targetState = if (bonding) R.string.onboard_confirm else reached.lastOrNull()?.label() ?: R.string.onboard_step_connect,
                    transitionSpec = { fadeIn(tween(300, delayMillis = 120)) togetherWith fadeOut(tween(120)) },
                    label = "step",
                ) { text -> Text(stringResource(text), style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp)) }
                val progress by animateFloatAsState(if (bonding) 0.04f else (reached.size + 0.5f) / ProvisionStep.entries.size, tween(600, easing = SETTLE_EASE), label = "progress")
                ProgressBar(progress, Modifier.padding(top = 22.dp))
                Text(stringResource(R.string.onboard_keep_close), style = Visor.type.label, color = colors.textTertiary, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
}

/** The Display's band: owned by this phone, paired with the glasses, then the wrist. Later leaves it for Settings. */
@Composable
private fun BandPage(model: OnboardingViewModel, status: Provisioner.Status, onDone: (GlassesTarget) -> Unit, modifier: Modifier) {
    val colors = Visor.colors
    val band by model.bandPair.status.collectAsStateWithLifecycle()
    val target = (status as? Provisioner.Status.Done)?.target
    val scope = rememberCoroutineScope()
    var wrist by remember { mutableStateOf<Boolean?>(null) }
    var wristBusy by remember { mutableStateOf(false) }
    var wristProblem by remember { mutableStateOf<BandErrand?>(null) }
    val finish: () -> Unit = {
        model.bandPair.reset()
        model.provisioner.reset()
        if (target != null) onDone(target)
    }
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        when (val s = band) {
            BandPair.Status.Done -> {
                Text(stringResource(R.string.onboard_band_done), style = Visor.type.title, color = colors.textPrimary, modifier = Modifier.asHeading())
                Text(stringResource(R.string.onboard_band_wrist), style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp))
                fun pick(left: Boolean) {
                    if (wristBusy) return
                    wristBusy = true
                    wristProblem = null
                    scope.launch {
                        val outcome = model.bandLink.setWrist(left)
                        if (outcome == BandErrand.DONE) wrist = left else wristProblem = outcome
                        wristBusy = false
                    }
                }
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pills(listOf(true to stringResource(R.string.band_left), false to stringResource(R.string.band_right)), wrist, on = Visor.colors.raised) { left -> pick(left) }
                    if (wristBusy) Spinner()
                }
                errandProblem(wristProblem)?.let {
                    Text(it, style = Visor.type.label, color = colors.textTertiary, modifier = Modifier.padding(top = 10.dp))
                }
                Spacer(Modifier.height(32.dp))
                WidePill(stringResource(R.string.onboard_open), finish, Modifier.fillMaxWidth(), enabled = !wristBusy)
            }
            is BandPair.Status.Failed -> {
                Text(stringResource(R.string.onboard_band_failed), style = Visor.type.title, color = colors.textPrimary, modifier = Modifier.asHeading())
                Text(pairWords(s).orEmpty(), style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(32.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WidePill(stringResource(R.string.onboard_band_later), finish, quiet = true)
                    Spacer(Modifier.width(12.dp))
                    WidePill(stringResource(R.string.try_again), model.bandPair::start, Modifier.weight(1f))
                }
            }
            is BandPair.Status.Running -> {
                Text(stringResource(R.string.onboard_band_title), style = Visor.type.title, color = colors.textPrimary, modifier = Modifier.asHeading())
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner()
                    Text(pairWords(s).orEmpty(), style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(start = 12.dp))
                }
                Text(stringResource(R.string.onboard_band_keep_close), style = Visor.type.label, color = colors.textTertiary, modifier = Modifier.padding(top = 14.dp))
            }
            BandPair.Status.Idle -> {
                Text(stringResource(R.string.onboard_band_title), style = Visor.type.title, color = colors.textPrimary, modifier = Modifier.asHeading())
                Text(stringResource(R.string.onboard_band_body), style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(32.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WidePill(stringResource(R.string.onboard_band_later), finish, quiet = true)
                    Spacer(Modifier.width(12.dp))
                    WidePill(stringResource(R.string.onboard_band_pair), model.bandPair::start, Modifier.weight(1f))
                }
            }
        }
    }
}

private fun ProvisionStep.label(): Int = when (this) {
    ProvisionStep.TOKEN -> R.string.onboard_step_meta
    ProvisionStep.CONNECT, ProvisionStep.SECURE_LINK, ProvisionStep.IDENTITY, ProvisionStep.SKIP_CHALLENGE -> R.string.onboard_step_connect
    ProvisionStep.PAIR_REQUEST, ProvisionStep.START_CHANGE_OWNER, ProvisionStep.PAIR, ProvisionStep.FINISH_CHANGE_OWNER -> R.string.onboard_step_finish
}

/** The capture button's light and hold ring, and the inner LED, drawn over the pair. */
@Composable
private fun Marks(clock: MutableFloatState) {
    val stage = LocalStage.current
    val cold = Visor.colors.lightCold
    Canvas(Modifier.fillMaxSize()) {
        stage.light.tick.longValue
        val t = clock.floatValue
        val pose = stage.pose
        if (pose.button > 0.01f) stage.renderer.partOnScreen("button")?.let { (fx, fy) ->
            buttonMark(stage.onWindow(fx, fy, size.width), pose.button, t, Demo.ring(t), cold)
        }
        if (pose.innerLed > 0.01f) stage.renderer.partOnScreen("inner_led")?.let { (fx, fy) ->
            val c = stage.onWindow(fx, fy, size.width)
            val l = pose.innerLed
            drawCircle(Brush.radialGradient(listOf(LED_BLUE.copy(alpha = 0.55f * l), LED_BLUE.copy(alpha = 0.18f * l), Color.Transparent), c, 44.dp.toPx()), 44.dp.toPx(), c)
            drawCircle(Color.White.copy(alpha = 0.85f * l), 2.dp.toPx(), c)
        }
    }
}

private fun DrawScope.buttonMark(c: Offset, b: Float, t: Float, ring: Float, cold: Color) {
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.3f * b), cold.copy(alpha = 0.16f * b), Color.Transparent), c, 64.dp.toPx()), 64.dp.toPx(), c)
    val r = 24.dp.toPx()
    rotate(t * 110f, c) {
        drawCircle(
            Brush.sweepGradient(listOf(Color.Transparent, cold.copy(alpha = 0.1f * b), cold.copy(alpha = 0.9f * b), Color.White.copy(alpha = b), Color.Transparent), c),
            r, c, style = Stroke(1.5.dp.toPx()),
        )
    }
    val arc = 33.dp.toPx()
    drawCircle(Color.White.copy(alpha = 0.12f * b), arc, c, style = Stroke(2.dp.toPx()))
    if (ring > 0f) {
        drawArc(
            Color.White.copy(alpha = b), -90f, 360f * ring, false,
            topLeft = Offset(c.x - arc, c.y - arc), size = Size(arc * 2, arc * 2), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** The pairing page's story, in seconds on one loop. */
private object Demo {
    const val FOLD = 0.8f
    const val TO_BUTTON = 2.2f
    const val HOLD_FROM = 3.6f
    const val HOLD_SECONDS = 5f
    const val HOLD_TO = HOLD_FROM + HOLD_SECONDS
    const val TO_INSIDE = HOLD_TO + 0.2f
    const val BLINK_FROM = TO_INSIDE + 1.4f
    const val TO_FRONT = BLINK_FROM + 5f
    const val OPEN = TO_FRONT + 1.4f
    const val LOOP = OPEN + 1.8f
    val LINE_STARTS = floatArrayOf(0f, TO_BUTTON, TO_INSIDE)

    // From above, where the folded right arm shows its button; then from behind, where the LED sits by that hinge.
    private const val BUTTON_YAW = 35f
    private const val BUTTON_PITCH = 48f
    private const val INSIDE_YAW = 190f
    private const val INSIDE_PITCH = 56f
    private const val CLOSE_UP = 1.4f
    private const val TURN = 1.3f
    private val EASE = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

    private fun ramp(t: Float, from: Float, seconds: Float) = EASE.transform(((t - from) / seconds).coerceIn(0f, 1f))

    private fun pose(t: Float, button: Float, inside: Float): Float = when {
        t < TO_INSIDE -> button * ramp(t, TO_BUTTON, TURN)
        t < TO_FRONT -> button + (inside - button) * ramp(t, TO_INSIDE, TURN)
        else -> inside * (1f - ramp(t, TO_FRONT, TURN))
    }

    fun yaw(t: Float) = pose(t, BUTTON_YAW, INSIDE_YAW)
    fun pitch(t: Float) = pose(t, BUTTON_PITCH, INSIDE_PITCH)

    /** The camera moving in on the button and the LED, and back out for the front. */
    fun zoom(t: Float): Float = 1f + (CLOSE_UP - 1f) * when {
        t < TO_FRONT -> ramp(t, TO_BUTTON + 0.4f, 1.6f)
        else -> 1f - ramp(t, TO_FRONT, TURN)
    }

    /** Which part the camera moves in on: the button, then the LED. */
    fun focus(t: Float): String? = when {
        t in TO_BUTTON..TO_INSIDE -> "button"
        t in TO_INSIDE..TO_FRONT -> "inner_led"
        else -> null
    }

    fun button(t: Float): Float = when {
        t < HOLD_FROM - 0.4f -> 0f
        t < HOLD_FROM -> (t - (HOLD_FROM - 0.4f)) / 0.4f
        t < HOLD_TO -> 1f
        t < HOLD_TO + 0.4f -> 1f - (t - HOLD_TO) / 0.4f
        else -> 0f
    }

    fun ring(t: Float) = ((t - HOLD_FROM) / HOLD_SECONDS).coerceIn(0f, 1f)

    /** Blinking blue, about once a second, with soft edges. */
    fun led(t: Float): Float {
        if (t < BLINK_FROM || t > TO_FRONT) return 0f
        val phase = (t - BLINK_FROM) % BLINK_SECONDS / BLINK_SECONDS
        val edge = 0.08f
        return when {
            phase < edge -> phase / edge
            phase < 0.5f -> 1f
            phase < 0.5f + edge -> 1f - (phase - 0.5f) / edge
            else -> 0f
        }
    }

    fun line(t: Float) = when {
        t < TO_BUTTON -> 0
        t < TO_INSIDE -> 1
        else -> 2
    }

    fun secondsLeft(t: Float): Int? = if (t in HOLD_FROM..HOLD_TO) ceil(HOLD_TO - t).toInt().coerceAtLeast(1) else null

    private const val BLINK_SECONDS = 0.9f
}

private val LED_BLUE = Color(0xFF2F6BFF)
/** How closely the pair follows the story's turns: a camera with a little weight. */
private const val FOLLOW_SECONDS = 0.18f
private const val CAMERA_SECONDS = 0.5f
private const val HELP_AFTER_MS = 25_000L

// How long the done page waits for the new pair to say whether it is a Display.
private const val MODEL_WAIT_MS = 20_000L
