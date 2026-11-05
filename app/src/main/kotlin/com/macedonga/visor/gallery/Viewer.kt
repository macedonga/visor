package com.macedonga.visor.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.CaptureType
import com.macedonga.visor.core.media.Edit
import com.macedonga.visor.core.media.coverScale
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.ProgressRing
import com.macedonga.visor.settings.RoundIcon
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import com.macedonga.visor.ui.rememberHaptics
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One capture at a time, grown from its tile; down closes, up shows details. */
@Composable
fun Viewer(
    items: List<GalleryItem>,
    startKey: String,
    previews: Map<String, File>,
    progress: (GalleryItem) -> Float?,
    arriving: (GalleryItem) -> Boolean,
    canImport: Boolean,
    origin: (String) -> Rect?,
    originals: (GalleryItem) -> File?,
    onPage: (GalleryItem) -> Unit,
    onClose: () -> Unit,
    onFavorite: (GalleryItem) -> Unit,
    onShare: (GalleryItem) -> Unit,
    onDelete: (GalleryItem) -> Unit,
    onImport: (GalleryItem) -> Unit,
    onEdit: (GalleryItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val start = remember { items.indexOfFirst { it.key == startKey }.coerceAtLeast(0) }
    val pager = rememberPagerState(start) { items.size }
    val current = items.getOrNull(pager.currentPage)
    val shownNow by rememberUpdatedState(current)
    val originNow by rememberUpdatedState(origin)
    val opening = remember { Animatable(0f) }
    val dismiss = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    var chrome by remember { mutableStateOf(true) }
    var closing by remember { mutableStateOf(false) }
    // The drag as the finger left it, when closing began.
    var leftAt by remember { mutableFloatStateOf(0f) }
    var zoomed by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf(origin(startKey)) }
    var playback by remember { mutableStateOf<Playback?>(null) }
    val page by rememberUpdatedState(onPage)

    LaunchedEffect(Unit) { opening.animateTo(1f, OPEN) }
    LaunchedEffect(pager.settledPage, items.size) {
        items.getOrNull(pager.settledPage)?.let(page)
    }
    // The last one deleted: nothing left to show.
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onClose() }

    fun close() {
        if (closing) return
        closing = true
        leftAt = dismiss.value
        from = shownNow?.let { originNow(it.key) }
        scope.launch {
            launch { reveal.animateTo(0f, tween(200)) }
            opening.animateTo(0f, CLOSE)
            onClose()
        }
    }
    BackHandler {
        if (reveal.value > 0.5f) scope.launch { reveal.animateTo(0f, SETTLE) } else close()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val drag = if (closing) leftAt * opening.value else dismiss.value
        val dragFraction = (drag / (screen.height * 0.45f)).coerceIn(0f, 1f)
        val backdrop = opening.value * (1f - dragFraction)
        val panelHeight = screen.height * PANEL
        val statusBar = WindowInsets.statusBars.getTop(density).toFloat()
        val room = (statusBar + with(density) { 12.dp.toPx() })..(screen.height - panelHeight - with(density) { 16.dp.toPx() })
        // Between the date on top and the actions and the video bar below: a tall picture stays clear of both.
        val navigationBar = WindowInsets.navigationBars.getBottom(density).toFloat()
        val clear = (statusBar + with(density) { CHROME_TOP.toPx() })..(screen.height - navigationBar - with(density) { CHROME_BOTTOM.toPx() })

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = backdrop }.background(Color.Black))
        val glow by rememberThumb(current, previews)
        PhotoLight(
            glow,
            Modifier.fillMaxSize().graphicsLayer { alpha = backdrop * (1f - 0.6f * reveal.value) },
            level = 0.78f,
            center = Offset(0.5f, 0.46f - 0.2f * reveal.value),
            radius = 0.72f,
        )

        // A tap anywhere, on the picture or the light around it, shows or hides the controls, or lowers the details.
        val toggle = {
            if (reveal.value > 0f) scope.launch { reveal.animateTo(0f, SETTLE) } else chrome = !chrome
            Unit
        }
        HorizontalPager(
            state = pager,
            key = { items[it].key },
            verticalAlignment = Alignment.Top,
            beyondViewportPageCount = 1,
            userScrollEnabled = opening.value > 0.99f && !closing && dismiss.value == 0f && reveal.value == 0f && !zoomed,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { toggle() }) }
                .pointerInput(zoomed, screen) {
                    if (zoomed) return@pointerInput
                    // Down pulls the moment away; up lifts it over its details.
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            // At once, in order: a step that ran late would cancel the settle that follows the finger.
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                if (reveal.value > 0f || (dismiss.value == 0f && dy < 0f)) {
                                    reveal.snapTo((reveal.value - dy / panelHeight).coerceIn(0f, 1f))
                                } else {
                                    dismiss.snapTo(max(0f, dismiss.value + dy))
                                }
                            }
                        },
                        onDragEnd = {
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                when {
                                    dismiss.value > screen.height * 0.12f -> close()
                                    dismiss.value > 0f -> dismiss.animateTo(0f, SETTLE)
                                    else -> reveal.animateTo(if (reveal.value > 0.3f) 1f else 0f, SETTLE)
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                launch { dismiss.animateTo(0f, SETTLE) }
                                reveal.animateTo(if (reveal.value > 0.3f) 1f else 0f, SETTLE)
                            }
                        },
                    )
                },
        ) { index ->
            val item = items[index]
            val isCurrent = index == pager.currentPage
            Page(
                item = item,
                previews = previews,
                screen = screen,
                clear = clear,
                active = index == pager.settledPage && opening.value > 0.99f,
                hero = if (isCurrent) Hero(opening.value, from, drag, dragFraction, reveal.value, room, if (from != null && from == origin(STAGE_KEY)) PHOTO_RADIUS else 0.dp) else null,
                arriving = arriving(item),
                canImport = canImport,
                progress = progress(item),
                onTap = toggle,
                onZoom = { zoomed = it },
                onImport = { onImport(item) },
                onPlayback = { if (isCurrent) playback = it },
            )
        }

        val chromeAlpha by animateFloatAsState(if (chrome) 1f else 0f, tween(180), label = "chrome")
        val shown = chromeAlpha * opening.value * (1f - dragFraction * 2f).coerceIn(0f, 1f)
        if (current != null && shown > 0f) {
            // Gone once it has faded, so a tap at the top lowers the details.
            if (reveal.value < 0.5f) TopChrome(current, Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = shown * (1f - reveal.value * 2f).coerceIn(0f, 1f) }) { close() }
            BottomChrome(
                current,
                playback = playback.takeIf { current.type == CaptureType.VIDEO },
                modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = shown * (1f - reveal.value) },
                onFavorite = { onFavorite(current) },
                onShare = { onShare(current) },
                onDelete = { onDelete(current) },
                onEdit = { onEdit(current) },
                onDetails = { scope.launch { reveal.animateTo(1f, SETTLE) } },
            )
        }
        if (current != null && reveal.value > 0f) {
            Details(
                current,
                originals(current),
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { panelHeight.toDp() })
                    .graphicsLayer { translationY = (1f - reveal.value) * panelHeight },
            )
        }
    }
}

/** Where the current page's picture is on its way between the tile and the full screen. */
private class Hero(
    val open: Float,
    val origin: Rect?,
    val dismiss: Float,
    val dragFraction: Float,
    val reveal: Float,
    /** The room left above the details: from under the status bar to the panel's edge. */
    val room: ClosedFloatingPointRange<Float>,
    /** The corners of what it was opened from. */
    val corner: Dp,
)

@Composable
private fun Page(
    item: GalleryItem,
    previews: Map<String, File>,
    screen: Size,
    clear: ClosedFloatingPointRange<Float>,
    active: Boolean,
    hero: Hero?,
    arriving: Boolean,
    canImport: Boolean,
    progress: Float?,
    onTap: () -> Unit,
    onZoom: (Boolean) -> Unit,
    onImport: () -> Unit,
    onPlayback: (Playback?) -> Unit,
) {
    val density = LocalDensity.current
    val fit = fitRect(screen, item.aspect, clear)
    val picture by rememberPicture(item, previews, max(screen.width, screen.height).roundToInt())
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(active) {
        if (!active && zoom != 1f) {
            zoom = 1f
            pan = Offset.Zero
            onZoom(false)
        }
    }

    val video = item.phone?.video == true
    val live = item.type == CaptureType.LIVE && !video && (item.kept?.clipBytes ?: 0) > 0
    val source = when {
        !active || item.phone == null -> null
        video -> ClipSource(item.phone.uri)
        live -> ClipSource(item.phone.uri, item.kept!!.clipBytes)
        else -> null
    }
    // A video plays; a live photo moves only while it is held, and goes back to its still when let go.
    val playback = rememberPlayback(source, autoplay = video)
    val haptics = rememberHaptics()
    LaunchedEffect(playback, active) { if (active && video) onPlayback(playback) else if (active) onPlayback(null) }
    val stillAlpha by animateFloatAsState(if (source != null && playback.showing && (playback.playing || video)) 0f else 1f, tween(if (live) 180 else 1), label = "still")

    Box(
        Modifier
            .offset { IntOffset(fit.left.roundToInt(), fit.top.roundToInt()) }
            .size(with(density) { fit.width.toDp() }, with(density) { fit.height.toDp() })
            .graphicsLayer {
                if (hero == null) return@graphicsLayer
                val r = hero.origin
                val target = if (r != null) lerp(r, fit, hero.open) else fit
                val cover = max(target.width / fit.width, target.height / fit.height)
                val grow = if (r == null) 0.9f + 0.1f * hero.open else 1f
                val drag = 1f - 0.3f * hero.dragFraction
                // Lifted over its details, the picture fits the room above them.
                val roomScale = ((hero.room.endInclusive - hero.room.start) / fit.height).coerceAtMost(1f)
                val lift = 1f + (roomScale - 1f) * hero.reveal
                val s = cover * grow * drag * lift
                scaleX = s
                scaleY = s
                translationX = target.center.x - fit.center.x
                val roomCentre = (hero.room.start + hero.room.endInclusive) / 2f
                translationY = target.center.y - fit.center.y + hero.dismiss * 0.9f + (roomCentre - fit.center.y) * hero.reveal
                alpha = if (r == null) hero.open else 1f
                transformOrigin = TransformOrigin.Center
                // Opened from a square tile, the picture shows as much of itself as the tile did, then the rest.
                val visible = Size(target.width / cover, target.height / cover)
                clip = true
                shape = cropShape(visible, lerp(hero.corner, 0.dp, hero.open).toPx() / s)
            }
            .pointerInput(item.key, video, live, item.onGlassesOnly, playback) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at ->
                        if (video || item.onGlassesOnly) return@detectTapGestures
                        scope.launch {
                            val to = if (zoom > 1f) 1f else DOUBLE_TAP_ZOOM
                            val fromZoom = zoom
                            val fromPan = pan
                            val toPan = if (to == 1f) Offset.Zero else (Offset(size.width / 2f, size.height / 2f) - at) * (to - 1f)
                            onZoom(to > 1f)
                            animate(0f, 1f, animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f)) { t, _ ->
                                zoom = fromZoom + (to - fromZoom) * t
                                pan = clampPan(fromPan + (toPan - fromPan) * t, zoom, size.width.toFloat(), size.height.toFloat())
                            }
                        }
                    },
                    onPress = {
                        if (!live) return@detectTapGestures
                        var held = false
                        val hold = scope.launch {
                            delay(HOLD_MS)
                            held = true
                            haptics.tick(0.5f)
                            playback.play(fromStart = true)
                        }
                        tryAwaitRelease()
                        hold.cancel()
                        if (held) playback.pause()
                    },
                    // A hold is not a tap: letting go of a live photo leaves the chrome as it was.
                    onLongPress = if (live) ({ }) else null,
                )
            }
            .pointerInput(item.key, video, item.onGlassesOnly) {
                if (video || item.onGlassesOnly) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || zoom > 1f) {
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            pan = clampPan(pan + event.calculatePan(), zoom, size.width.toFloat(), size.height.toFloat())
                            onZoom(zoom > 1.01f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (zoom < 1.05f) {
                        zoom = 1f
                        pan = Offset.Zero
                        onZoom(false)
                    }
                }
            },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
            },
        ) {
            if (source != null) {
                val cut = item.kept?.edit?.takeIf { live && !it.isOriginal }
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    PlaybackSurface(playback, if (cut != null) Modifier.cutLike(cut) else Modifier.fillMaxSize())
                }
            }
            picture?.let {
                Image(it, spoken(item), Modifier.fillMaxSize().graphicsLayer { alpha = stillAlpha }, contentScale = ContentScale.Crop)
            }
            if (item.onGlassesOnly) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = GLASSES_VEIL)))
                Box(Modifier.align(Alignment.Center)) {
                    if (arriving) ProgressRing(progress, Modifier.size(52.dp))
                    else ActionPill(stringResource(R.string.gallery_import), onImport, enabled = canImport)
                }
            }
        }
    }
}

@Composable
private fun TopChrome(item: GalleryItem, modifier: Modifier, onClose: () -> Unit) {
    val colors = Visor.colors
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(VisorIcons.ChevronDown, stringResource(R.string.close), onClose, size = 48.dp)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(dayTitle(item.date), style = Visor.type.heading, color = colors.textPrimary, modifier = Modifier.asHeading())
            Text(clockTime(item.takenAt) + " · " + kindWord(item.type), style = Visor.type.label, color = colors.textSecondary)
        }
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun BottomChrome(
    item: GalleryItem,
    playback: Playback?,
    modifier: Modifier,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onDetails: () -> Unit,
) {
    val colors = Visor.colors
    val pill = colors.raised.copy(alpha = 0.78f)
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (playback != null) VideoBar(playback, Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp))
        if (item.onGlassesOnly) return@Column
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundIcon(if (item.favorite) VisorIcons.HeartFilled else VisorIcons.Heart, stringResource(R.string.gallery_favorite), onFavorite, size = 52.dp, background = pill, selected = item.favorite)
            RoundIcon(VisorIcons.Share, stringResource(R.string.gallery_share), onShare, size = 52.dp, background = pill)
            if (item.kept != null && item.type != CaptureType.VIDEO) RoundIcon(VisorIcons.Crop, stringResource(R.string.gallery_edit), onEdit, size = 52.dp, background = pill)
            RoundIcon(VisorIcons.Info, stringResource(R.string.gallery_details), onDetails, size = 52.dp, background = pill)
            RoundIcon(VisorIcons.Trash, stringResource(R.string.gallery_delete), onDelete, size = 52.dp, background = pill)
        }
    }
}

/** Play or pause, and a thin line to drag through the video, with where it is and how long it runs. */
@Composable
private fun VideoBar(playback: Playback, modifier: Modifier) {
    val colors = Visor.colors
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val fraction = scrubbing ?: if (playback.durationMs > 0) playback.positionMs.toFloat() / playback.durationMs else 0f
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(
            if (playback.playing) VisorIcons.Pause else VisorIcons.Play,
            stringResource(if (playback.playing) R.string.gallery_pause else R.string.gallery_play),
            { if (playback.playing) playback.pause() else playback.play(fromStart = playback.positionMs >= playback.durationMs - 50) },
            size = 48.dp,
        )
        Text(duration((fraction * playback.durationMs).toLong()), style = TABULAR, color = colors.textPrimary, modifier = Modifier.width(44.dp))
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(32.dp)
                .pointerInput(playback) {
                    detectHorizontalDragGestures(
                        onDragStart = { scrubbing = (it.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ ->
                            val f = (change.position.x / size.width).coerceIn(0f, 1f)
                            scrubbing = f
                            playback.seek((f * playback.durationMs).toLong())
                        },
                        onDragEnd = { scrubbing = null },
                        onDragCancel = { scrubbing = null },
                    )
                }
                .pointerInput(playback) { detectTapGestures { playback.seek(((it.x / size.width).coerceIn(0f, 1f) * playback.durationMs).toLong()) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.25f)))
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(2.dp).background(Color.White))
            Box(
                Modifier
                    .offset(x = maxWidth * fraction.coerceIn(0f, 1f) - 6.dp)
                    .size(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White),
            )
        }
        Text(duration(playback.durationMs), style = TABULAR, color = colors.textSecondary, modifier = Modifier.padding(start = 10.dp))
    }
}

/** Tabular figures: a running time does not shift its neighbours. */
private val TABULAR @Composable get() = Visor.type.label.copy(fontFeatureSettings = "tnum")

/** Lays out and turns the clip the way [edit] cut its photo. */
private fun Modifier.cutLike(edit: Edit): Modifier {
    val (l, t, r, b) = edit.crop
    return layout { measurable, constraints ->
        val fullW = (constraints.maxWidth / (r - l)).roundToInt()
        val fullH = (constraints.maxHeight / (b - t)).roundToInt()
        val placeable = measurable.measure(Constraints.fixed(fullW, fullH))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place((-l * fullW).roundToInt(), (-t * fullH).roundToInt()) }
    }.graphicsLayer {
        rotationZ = edit.angle
        val s = coverScale(size.width, size.height, edit.angle)
        scaleX = s
        scaleY = s
    }
}

/** The largest rect of [aspect] on [screen] inside [clear]. */
private fun fitRect(screen: Size, aspect: Float, clear: ClosedFloatingPointRange<Float>): Rect {
    val w = minOf(screen.width, (clear.endInclusive - clear.start) * aspect)
    val h = w / aspect
    val top = ((screen.height - h) / 2f).coerceIn(clear.start, maxOf(clear.start, clear.endInclusive - h))
    return Rect(Offset((screen.width - w) / 2f, top), Size(w, h))
}

/** What the top chrome takes under the status bar, and the actions and the video bar above the navigation bar. */
private val CHROME_TOP = 64.dp
internal val CHROME_BOTTOM = 140.dp

/** A rect of [visible] size centred in the layer, with [radius] corners, in the layer's own units. */
private fun cropShape(visible: Size, radius: Float): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = (size.width - visible.width) / 2f
        val top = (size.height - visible.height) / 2f
        val rect = Rect(left, top, left + visible.width, top + visible.height)
        return if (radius <= 0f) Outline.Rectangle(rect)
        else Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
    }
}

/** Pan, kept so the zoomed picture never leaves an edge of the screen bare. */
private fun clampPan(pan: Offset, zoom: Float, w: Float, h: Float): Offset {
    // The double-tap spring overshoots below 1x on the way out; an empty range would throw.
    val x = (w * (zoom - 1f).coerceAtLeast(0f)) / 2f
    val y = (h * (zoom - 1f).coerceAtLeast(0f)) / 2f
    return Offset(pan.x.coerceIn(-x, x), pan.y.coerceIn(-y, y))
}

private val OPEN = spring<Float>(dampingRatio = 0.86f, stiffness = 340f)
/** Back to the tile with no overshoot: landing past it and coming back reads as a wobble. */
private val CLOSE = spring<Float>(dampingRatio = 1f, stiffness = 380f)
/** The origin key for the stage's picture. */
const val STAGE_KEY = "stage"
private val SETTLE = spring<Float>(dampingRatio = 0.85f, stiffness = 420f)
private const val PANEL = 0.6f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val HOLD_MS = 160L
private const val MAX_ZOOM = 5f
