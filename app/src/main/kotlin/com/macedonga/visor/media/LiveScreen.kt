package com.macedonga.visor.media

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.HINGE_CLOSED
import com.macedonga.visor.core.media.LivePhase
import com.macedonga.visor.core.media.LiveProblem
import com.macedonga.visor.core.media.wifiDirectAllowed
import com.macedonga.visor.core.media.wifiDirectPermissions
import com.macedonga.visor.core.media.wifiIsOn
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.settings.ConfirmSheet
import com.macedonga.visor.settings.openAppPage
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.STAGE_ASPECT
import com.macedonga.visor.stage.stageSpot
import com.macedonga.visor.ui.rememberHaptics
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The glasses' camera, live: it blooms out of the lens; a tap steps back to the controls. [onClose] says whether a stream showed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveScreen(onClose: (streamed: Boolean) -> Unit, onSettings: () -> Unit, onOpen: () -> Unit, model: LiveViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val activity = LocalActivity.current as? ComponentActivity
    val haptics = rememberHaptics()
    val stage = LocalStage.current
    val status by model.live.status.collectAsStateWithLifecycle()
    val running by model.live.running.collectAsStateWithLifecycle()
    val decoded by model.live.decodedSize.collectAsStateWithLifecycle()
    val recording by model.outputs.recording.collectAsStateWithLifecycle()
    val broadcast by model.outputs.broadcast.state.collectAsStateWithLifecycle()
    val pip = rememberInPip(activity)

    var allowed by remember { mutableStateOf(wifiDirectAllowed(context)) }
    // Refused for good, Allow opens Visor's page in the phone's settings.
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        allowed = wifiDirectAllowed(context)
        refused = answers.any { (name, granted) -> !granted && activity?.shouldShowRequestPermissionRationale(name) == false }
        if (allowed) model.start()
    }
    // Coming back to a Live already running (from the notification, say), the picture is simply there.
    val alreadyLive = remember { model.live.status.value.phase == LivePhase.RUNNING || model.live.status.value.phase == LivePhase.RESTARTING }
    // Opening the screen starts the Live, and so does coming back to it after the screen was off and it stopped.
    var asked by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (model.live.status.value.phase != LivePhase.IDLE || model.live.running.value) return@LifecycleEventEffect
        allowed = wifiDirectAllowed(context)
        if (allowed) {
            model.start()
        } else if (!asked) {
            asked = true
            ask.launch(wifiDirectPermissions)
        }
    }
    // Back from the Wi-Fi panel with Wi-Fi on, the Live starts again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val s = model.live.status.value
        if (s.phase == LivePhase.FAILED && s.problem == LiveProblem.WIFI_OFF && wifiIsOn(context)) model.start()
    }

    val video = rememberLiveVideo(model.live)
    var shown by remember { mutableStateOf(alreadyLive) }
    var closing by remember { mutableStateOf(false) }
    DisposableEffect(video) {
        video.onShown = { if (!shown) shown = true }
        onDispose { video.onShown = {} }
    }
    // A failed start goes back to the glasses, and a new one blooms again.
    LaunchedEffect(status.phase) { if (status.phase == LivePhase.WIFI && !closing) shown = false }

    val bloom = remember { Animatable(if (alreadyLive) 1f else 0f) }
    var lens by remember { mutableStateOf(Offset(0.62f, 0.45f)) }
    LaunchedEffect(shown) {
        if (closing) return@LaunchedEffect
        if (shown && bloom.value < 1f) {
            stage.renderer.cameraOnScreen()?.let { (x, y) -> lens = Offset(x, y) }
            haptics.connected()
            bloom.animateTo(1f, tween(BLOOM_MS, easing = BLOOM))
        } else if (!shown) {
            bloom.snapTo(0f)
        }
    }
    LaunchedEffect(status.phase) { if (status.phase == LivePhase.FAILED) { stage.light.flicker(); haptics.error() } }
    // The temples as the glasses report them; streaming means open.
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val closed = when {
        shown || status.phase == LivePhase.RUNNING || status.phase == LivePhase.RESTARTING -> false
        glasses.link == LinkState.READY -> glasses.device?.hinge?.let { it == HINGE_CLOSED } ?: stage.lastFolded
        else -> stage.lastFolded
    }

    var stepped by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var broadcastSheet by rememberSaveable { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LiveWatchers(model)

    // Leaving runs the entrance backwards before the screen goes.
    val leave: () -> Unit = {
        if (!closing) {
            closing = true
            sheet = false
            broadcastSheet = false
            model.stop()
            scope.launch {
                if (bloom.value > 0f) {
                    stage.renderer.cameraOnScreen()?.let { (x, y) -> lens = Offset(x, y) }
                    bloom.animateTo(0f, tween(LEAVE_MS, easing = LEAVE))
                }
                onClose(shown)
            }
        }
    }
    // On air, leaving asks first: it takes the broadcast down.
    var confirmEnd by remember { mutableStateOf(false) }
    val close: () -> Unit = { if (!closing && model.outputs.broadcast.running) confirmEnd = true else leave() }
    val step by animateFloatAsState(if (stepped && !pip && shown && !closing) 1f else 0f, spring(dampingRatio = 0.84f, stiffness = 280f), label = "step")
    BackHandler {
        when {
            confirmEnd -> confirmEnd = false
            sheet -> sheet = false
            broadcastSheet -> broadcastSheet = false
            stepped -> stepped = false
            else -> close()
        }
    }

    // Leaving the app goes to picture in picture; with the screen off it goes on only if something is being made of it.
    LaunchedEffect(activity, running, shown) {
        activity?.setPictureInPictureParams(
            PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).setAutoEnterEnabled(running && shown).setSeamlessResizeEnabled(true).build(),
        )
    }
    DisposableEffect(activity) {
        onDispose { activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isInPictureInPictureMode != true && !model.keepsGoing()) model.stop()
    }
    Immersive(activity, hidden = !closing)
    // The glasses' microphones play only while the Live is on screen.
    val settings by model.settings.settings.collectAsStateWithLifecycle()
    LifecycleStartEffect(settings.listen) {
        model.outputs.listening = settings.listen
        onStopOrDispose { model.outputs.listening = false }
    }

    // The picture's colours, for the light around the window.
    var glow by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(shown, stepped) {
        while (shown) {
            video.sample(GLOW_W, GLOW_H)?.let { glow = it.asImageBitmap() }
            delay(if (stepped) 300 else 1_200)
        }
    }

    val density = LocalDensity.current
    val controls = stringResource(R.string.live_controls)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // The one stage shows through until the picture has bloomed over it.
            .drawBehind { drawRect(Color.Black.copy(alpha = bloom.value)) }
            .pointerInput(shown, pip) {
                if (shown && !pip) detectTapGestures(onTap = { stepped = !stepped; haptics.tick(0.4f) })
            }
            .semantics { if (shown && !pip) onClick(label = controls) { stepped = !stepped; true } },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // The bars' room whether they show or not: a sheet bringing them back must not move the window.
        val top = WindowInsets.statusBarsIgnoringVisibility.getTop(density).toFloat()
        val bottom = WindowInsets.navigationBarsIgnoringVisibility.getBottom(density).toFloat()
        val aspect = decoded?.takeIf { it.height > 0 }?.let { it.width.toFloat() / it.height } ?: (9f / 16f)
        val cover = coverRect(w, h, aspect)
        val window = with(density) { windowRect(w, h, aspect, top + 64.dp.toPx(), bottom + 236.dp.toPx(), 20.dp.toPx()) }
        val placed = lerp(cover, window, step)
        val visible = lerp(Rect(0f, 0f, w, h), window, step)
        val radius = with(density) { 26.dp.toPx() } * step

        // The stage at the top: the pair grows into the camera and fades as the picture blooms.
        val stageH = w / STAGE_ASPECT
        stageSpot(closed, liveliness = 0.8f) {
            val b = bloom.value
            val dive = b * b * (3 - 2 * b)
            this.top = 0f
            this.height = h
            this.scale = 1f + 5f * dive
            this.pivotX = lens.x
            this.pivotY = lens.y
            this.shift = 0f
            this.alpha = (1f - b * 1.5f).coerceIn(0f, 1f)
        }
        val lensPx = Offset(lens.x * w, lens.y * stageH)
        val reach = max(max(hypot(lensPx.x, lensPx.y), hypot(w - lensPx.x, lensPx.y)), max(hypot(lensPx.x, h - lensPx.y), hypot(w - lensPx.x, h - lensPx.y)))

        // Kept while the picture shows, so its colours are already settled when the window opens.
        if (shown) LiveGlow(glow, window, step)
        if (bloom.value < 1f) {
            Arrival(
                status, allowed, stageH, bloom.value, closing,
                onAllow = { if (refused) openAppPage(context) else ask.launch(wifiDirectPermissions) },
                onWifi = { runCatching { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) } },
                onRetry = model::start,
                onClose = close,
            )
        }
        LiveFrame(
            video = video,
            cover = cover,
            placed = placed,
            visible = visible,
            radius = radius,
            bloomCenter = lensPx,
            bloomRadius = if (bloom.value < 1f) reach * bloomReach(bloom.value) else -1f,
        )
        if (!pip && shown && !closing) {
            LiveOverlays(model, visible, step, bloom.value)
            Captions(model, visible, ((bloom.value - 0.75f) / 0.25f).coerceIn(0f, 1f))
            if (step > 0.01f) {
                SteppedChrome(
                    model = model,
                    window = window,
                    step = step,
                    onClose = close,
                    onMore = { sheet = true },
                    onBroadcast = { broadcastSheet = true },
                    onSnapshot = {
                        scope.launch {
                            flash.snapTo(0.7f)
                            flash.animateTo(0f, tween(360))
                        }
                        model.snapshot(video)
                    },
                    onGlassesPhoto = model::glassesPhoto,
                )
            }
            // The shutter: the window goes white for a blink.
            if (flash.value > 0f) {
                Box(
                    Modifier
                        .offset { IntOffset(visible.left.roundToInt(), visible.top.roundToInt()) }
                        .size(with(density) { visible.width.toDp() }, with(density) { visible.height.toDp() })
                        .graphicsLayer { alpha = flash.value }
                        .clip(RoundedCornerShape(with(density) { radius.toDp() }))
                        .background(Color.White),
                )
            }
        } else if (pip && (recording != null || broadcast.airing)) {
            PipBadge(recording != null)
        }
        // Going to the gallery leaves the Live, unless something is still being made of it.
        if (!pip && !closing) NoticeIsland(model) {
            if (!model.keepsGoing()) model.stop()
            onOpen()
        }
    }

    if (sheet) LiveSheet(model, onSettings = { sheet = false; onSettings() }, onDismiss = { sheet = false })
    if (broadcastSheet) BroadcastSheet(model, onDismiss = { broadcastSheet = false })
    if (confirmEnd) ConfirmSheet(stringResource(R.string.live_broadcast), null, stringResource(R.string.live_broadcast_end), onConfirm = leave, onDismiss = { confirmEnd = false })
}

/** Where the whole picture goes to fill the screen, cut at the sides or the top. */
private fun coverRect(w: Float, h: Float, aspect: Float): Rect = if (w / h < aspect) {
    val width = h * aspect
    Rect((w - width) / 2, 0f, (w + width) / 2, h)
} else {
    val height = w / aspect
    Rect(0f, (h - height) / 2, w, (h + height) / 2)
}

/** The lit window: as large as fits between the top bar and the controls, centred. */
private fun windowRect(w: Float, h: Float, aspect: Float, above: Float, below: Float, side: Float): Rect {
    val maxW = w - 2 * side
    val maxH = (h - above - below).coerceAtLeast(maxW / aspect * 0.4f)
    val width = minOf(maxW, maxH * aspect)
    val height = width / aspect
    return Rect((w - width) / 2, above, (w + width) / 2, above + height)
}

/** The bloom's circle starts slow, so the glass seems to open before the picture rushes out. */
private fun bloomReach(t: Float): Float = (t * t * (3 - 2 * t)).let { it * it }

@Composable
private fun rememberInPip(activity: ComponentActivity?): Boolean {
    var pip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { pip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return pip
}

/** Full screen and awake, upright, with the bars out of the way until a swipe brings them; back while [hidden] is false. */
@Composable
private fun Immersive(activity: ComponentActivity?, hidden: Boolean) {
    val view = LocalView.current
    LaunchedEffect(hidden) {
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        if (hidden) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.statusBars())
    }
    DisposableEffect(activity, view) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        view.keepScreenOn = true
        val orientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
            if (orientation != null) activity.requestedOrientation = orientation
        }
    }
}

private const val BLOOM_MS = 1_150
private val BLOOM = CubicBezierEasing(0.55f, 0f, 0.2f, 1f)
private const val LEAVE_MS = 620
private val LEAVE = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
