package com.macedonga.visor.gallery

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.CaptureType
import com.macedonga.visor.core.media.ImportProblem
import com.macedonga.visor.home.countWords
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.BAR
import com.macedonga.visor.settings.Card
import com.macedonga.visor.settings.Hairline
import com.macedonga.visor.settings.LocalBack
import com.macedonga.visor.settings.ProgressRing
import com.macedonga.visor.settings.RoundIcon
import com.macedonga.visor.settings.UndoPill
import com.macedonga.visor.stage.SHEET_RADIUS
import com.macedonga.visor.stage.sheetShape
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import com.macedonga.visor.ui.rememberHaptics
import com.macedonga.visor.ui.rememberTrailingFold
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.launch

/** The stage over a day-by-day sheet of what is on the phone and the glasses. */
@Composable
fun GalleryScreen(model: GalleryModel = hiltViewModel()) {
    val context = LocalContext.current
    val colors = Visor.colors
    val haptics = rememberHaptics()
    val gate = rememberImportGate()
    val listed by model.items.collectAsStateWithLifecycle()
    val loaded = listed != null
    val items = listed.orEmpty()
    val previews by model.previews.collectAsStateWithLifecycle()
    val inFlight by model.inFlight.collectAsStateWithLifecycle()
    val problem by model.problem.collectAsStateWithLifecycle()
    val overBluetooth by model.overBluetooth.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val ready by model.ready.collectAsStateWithLifecycle()

    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val anyFavorite = remember(items) { items.any { it.favorite } }
    val shown = remember(items, favoritesOnly) { if (favoritesOnly) items.filter { it.favorite } else items }
    val days = remember(shown) { GalleryModel.days(shown) }
    var stagedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val staged = shown.firstOrNull { it.key == stagedKey } ?: shown.firstOrNull()
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val selecting = choosing || selection.isNotEmpty()
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editKey by rememberSaveable { mutableStateOf<String?>(null) }
    val deleted by model.undoable.collectAsStateWithLifecycle()
    val bounds = remember { HashMap<String, Rect>() }

    fun endSelection() {
        selection = emptySet()
        choosing = false
    }

    BackHandler(enabled = selecting && openKey == null) { endSelection() }

    // Places: asked for once, from the sheet, until given or put off.
    val prefs = remember { context.getSharedPreferences("gallery", Context.MODE_PRIVATE) }
    val (placesOk, allowPlaces) = rememberPlacesAsk()
    var placesPutOff by remember { mutableStateOf(prefs.getBoolean(PLACES_PUT_OFF, false)) }

    val grid = rememberLazyGridState()
    // Each item's place in the grid: the stage, the sheet's head, then each day's header and its tiles.
    val gridIndex = remember(days) {
        val map = HashMap<String, Int>()
        var i = 1
        for ((n, day) in days.withIndex()) {
            i += if (n == 0) 2 else 1
            for (item in day.items) map[item.key] = i++
        }
        map
    }
    val flat = remember(days) { days.flatMap { it.items } }
    val order by rememberUpdatedState(flat)

    fun delete(chosen: Collection<GalleryItem>) {
        val onPhone = chosen.filter { it.phone != null }
        if (onPhone.isEmpty()) return
        haptics.toggle()
        model.delete(onPhone)
    }

    fun importOne(item: GalleryItem) {
        stagedKey = item.key
        if (ready && item.key !in inFlight) {
            haptics.toggle()
            gate { model.import(listOf(item)) }
        }
    }

    fun tap(item: GalleryItem, stageOpen: Boolean) {
        when {
            selecting -> selection = if (item.key in selection) selection - item.key else selection + item.key
            item.onGlassesOnly && ready -> importOne(item)
            stageOpen && staged?.key != item.key -> {
                haptics.tick(0.4f)
                stagedKey = item.key
            }
            else -> openKey = item.key
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.canvas)) {
        val density = LocalDensity.current
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val stageHeight = maxWidth * STAGE_RATIO
        val strip = top + BAR
        val fullPx = with(density) { stageHeight.toPx() }
        val stripPx = with(density) { strip.toPx() }
        val fold by rememberTrailingFold(fullPx - stripPx) {
            if (grid.firstVisibleItemIndex == 0) grid.firstVisibleItemScrollOffset.toFloat() else fullPx
        }
        val sheetTop = remember {
            derivedStateOf {
                grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == STAGE_KEY }?.let { (it.offset.y + it.size.height).toFloat() } ?: Float.NEGATIVE_INFINITY
            }
        }
        val stageOpen by remember { derivedStateOf { fold < 0.5f } }
        // Where the sheet's top edge is: on the stage's own spacer while it shows, else where the fold has the stage.
        val edge = { max(sheetTop.value, fullPx - fold * (fullPx - stripPx)).coerceIn(stripPx, fullPx) }
        val screenLong = with(density) { maxOf(maxWidth, maxHeight).roundToPx() }

        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            state = grid,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    clip = true
                    shape = sheetShape(edge(), SHEET_RADIUS.toPx())
                }
                .drawBehind { drawRect(colors.surface) }
                .dragToSelect(
                    grid,
                    onStart = { key ->
                        haptics.tick(0.6f)
                        selection = selection + key
                    },
                    onExtend = { from, to, base ->
                        val a = order.indexOfFirst { it.key == from }
                        val b = order.indexOfFirst { it.key == to }
                        if (a >= 0 && b >= 0) {
                            val range = order.subList(minOf(a, b), maxOf(a, b) + 1).map { it.key }
                            val next = base + range
                            if (next != selection) haptics.tick(0.3f)
                            selection = next
                        }
                    },
                    selection = { selection },
                ),
            contentPadding = PaddingValues(bottom = bottom + 112.dp),
            horizontalArrangement = Arrangement.spacedBy(GAP),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            item(key = STAGE_KEY, span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.fillMaxWidth().height(stageHeight)) }
            val island: @Composable () -> Unit = {
                Island(
                    // What the problem names stays offered, though the glasses count it delivered.
                    waiting = items.filter { it.onGlassesOnly && (it.capture?.isNew == true || it.key in problem?.handles.orEmpty()) },
                    arriving = inFlight.size,
                    problem = problem,
                    overBluetooth = overBluetooth,
                    started = progress.values.any { (it ?: 0f) >= 0.01f },
                    canImport = ready,
                    askPlaces = !placesOk && !placesPutOff,
                    onPlaces = allowPlaces,
                    onPlacesLater = {
                        placesPutOff = true
                        prefs.edit().putBoolean(PLACES_PUT_OFF, true).apply()
                    },
                    onImport = { haptics.toggle(); gate { model.import(it) } },
                )
            }
            if (days.isEmpty() && loaded) {
                item(key = ISLAND, span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.animateItem().padding(top = 16.dp)) { island() } }
            }
            for ((index, day) in days.withIndex()) {
                item(key = "day:${day.date}", span = { GridItemSpan(maxLineSpan) }, contentType = "day") { DayHeader(day, first = index == 0, Modifier.animateItem()) }
                if (index == 0) item(key = ISLAND, span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.animateItem()) { island() } }
                items(day.items, key = { it.key }, contentType = { "tile" }) { item ->
                    Tile(
                        modifier = Modifier.animateItem(fadeInSpec = tween(260), placementSpec = spring(dampingRatio = 0.85f, stiffness = 380f), fadeOutSpec = tween(200)),
                        item, previews,
                        selecting = selecting,
                        selected = item.key in selection,
                        arriving = item.capture?.handle in inFlight,
                        progress = progress[item.capture?.handle],
                        staged = stageOpen && item.key == staged?.key,
                        onTap = { tap(item, stageOpen) },
                        onBounds = { bounds[item.key] = it },
                        onGone = { bounds.remove(item.key) },
                    )
                }
            }
        }

        // The stage, over the sheet: the picked moment and its light, folding away as the sheet rides up.
        Box(
            Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val h = edge().toInt()
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .clipToBounds()
                .scrollable(grid, Orientation.Vertical, reverseDirection = true)
                .background(colors.canvas),
        ) {
            Stage(
                staged, previews, edge, stageHeight, strip,
                progress = progress[staged?.capture?.handle],
                arriving = staged?.capture?.handle in inFlight,
                canImport = ready,
                favoritesOnly = favoritesOnly,
                loaded = loaded,
                selecting = selecting,
                onOpen = { staged?.let { if (selecting) tap(it, false) else openKey = it.key } },
                onImport = { staged?.let { importOne(it) } },
                onBounds = { bounds[STAGE_KEY] = it },
            )
            TopBar(
                selecting = selecting,
                count = selection.size,
                favoritesOnly = favoritesOnly,
                showFavorites = favoritesOnly || anyFavorite,
                canSelect = shown.isNotEmpty(),
                onClear = { endSelection() },
                onSelect = {
                    haptics.tick(0.4f)
                    choosing = true
                },
                onFavorites = {
                    haptics.toggle()
                    favoritesOnly = !favoritesOnly
                },
            )
        }

        val chosen = shown.filter { it.key in selection }
        // What the bar last showed, kept while it fades out.
        val lastChosen = remember { mutableStateOf(emptyList<GalleryItem>()) }
        SideEffect { if (chosen.isNotEmpty()) lastChosen.value = chosen }
        AnimatedVisibility(
            visible = selection.isNotEmpty() && openKey == null,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            enter = fadeIn(tween(180)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 500f)) { it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 2 },
        ) {
            SelectionBar(
                chosen.ifEmpty { lastChosen.value },
                canImport = ready,
                onImport = {
                    gate { model.import(chosen) }
                    endSelection()
                },
                onShare = { model.shareIntent(chosen)?.let(context::startActivity) },
                onFavorite = { on -> haptics.toggle(); model.favorite(chosen, on) },
                onDelete = { delete(chosen); endSelection() },
            )
        }

        // Frozen while open: a favourite turned off in the favourites view stays where it is.
        val viewerKeys = remember(openKey != null) { shown.map { it.key } }
        val viewerItems = remember(viewerKeys, items) {
            val byKey = items.associateBy { it.key }
            viewerKeys.mapNotNull(byKey::get)
        }
        openKey?.let { key ->
            Viewer(
                items = viewerItems,
                startKey = key,
                previews = previews,
                progress = { progress[it.capture?.handle] },
                arriving = { it.capture?.handle in inFlight },
                canImport = ready,
                origin = { k -> if (k == STAGE_KEY) bounds[STAGE_KEY] else if (k == staged?.key && fold < 0.2f) bounds[STAGE_KEY] else bounds[k] },
                // From the phone's own index: the glasses need not be near for the details.
                originals = { (it.kept?.handle ?: it.capture?.handle)?.let(model.media.index::originals) },
                onPage = { item ->
                    stagedKey = item.key
                    // Bring the tile into view under the viewer, so closing has somewhere to land.
                    if (bounds[item.key] == null && !(item.key == staged?.key && fold < 0.2f)) gridIndex[item.key]?.let { grid.requestScrollToItem(it) }
                },
                onClose = { openKey = null },
                onFavorite = { haptics.toggle(); model.favorite(listOf(it), !it.favorite) },
                onShare = { model.shareIntent(listOf(it))?.let(context::startActivity) },
                onDelete = { delete(listOf(it)) },
                onImport = { importOne(it) },
                onEdit = { editKey = it.key },
            )
        }

        editKey?.let { key ->
            val item = items.firstOrNull { it.key == key }
            val kept = item?.kept
            if (item == null || kept == null) {
                LaunchedEffect(Unit) { editKey = null }
                return@let
            }
            Editor(
                kept = kept,
                darkroom = model.media.darkroom,
                originals = model.media.index.originals(kept.handle),
                shown = Images.cached(Images.pictureKey(item, screenLong)),
                onSaved = {
                    Images.forget(item)
                    model.library.reload()
                    editKey = null
                },
                onCancel = { editKey = null },
            )
        }

        UndoPill(
            if (deleted > 0) pluralStringResource(R.plurals.gallery_deleted, deleted, deleted) else null,
            { haptics.toggle(); model.undoDelete() },
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(
                bottom = when {
                    openKey != null -> CHROME_BOTTOM
                    selection.isNotEmpty() -> 96.dp
                    else -> 20.dp
                },
            ),
        )
    }
}

@Composable
private fun TopBar(
    selecting: Boolean,
    count: Int,
    favoritesOnly: Boolean,
    showFavorites: Boolean,
    canSelect: Boolean,
    onClear: () -> Unit,
    onSelect: () -> Unit,
    onFavorites: () -> Unit,
) {
    val colors = Visor.colors
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(BAR).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(selecting, Modifier.weight(1f), label = "bar") { choosing ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (choosing) {
                    RoundIcon(VisorIcons.Close, stringResource(R.string.close), onClear)
                    Text(
                        if (count == 0) stringResource(R.string.gallery_select) else pluralStringResource(R.plurals.gallery_selected, count, count),
                        style = Visor.type.heading,
                        color = colors.textPrimary,
                        modifier = Modifier.asHeading(),
                    )
                } else {
                    RoundIcon(VisorIcons.ChevronLeft, stringResource(R.string.settings_back), LocalBack.current)
                    Text(stringResource(R.string.gallery_title), style = Visor.type.title, color = colors.textPrimary, modifier = Modifier.asHeading().padding(start = 4.dp))
                }
            }
        }
        if (!selecting && canSelect) RoundIcon(VisorIcons.Check, stringResource(R.string.gallery_select), onSelect)
        if (!selecting && showFavorites) {
            val pop = remember { Animatable(1f) }
            val scope = rememberCoroutineScope()
            val wash by animateColorAsState(if (favoritesOnly) colors.control else Color.Transparent, tween(200), label = "wash")
            Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
                RoundIcon(
                    if (favoritesOnly) VisorIcons.HeartFilled else VisorIcons.Heart,
                    stringResource(R.string.gallery_favorites),
                    {
                        onFavorites()
                        scope.launch {
                            pop.snapTo(0.8f)
                            pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
                        }
                    },
                    background = wash,
                    selected = favoritesOnly,
                )
            }
        }
    }
}

/** The staged capture, lit by its colours, shrinking as the sheet rides up. */
@Composable
private fun Stage(
    item: GalleryItem?,
    previews: Map<String, File>,
    edge: () -> Float,
    height: Dp,
    strip: Dp,
    progress: Float?,
    arriving: Boolean,
    canImport: Boolean,
    favoritesOnly: Boolean,
    loaded: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onImport: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val colors = Visor.colors
    val density = LocalDensity.current
    val picture by rememberPicture(item, previews, STAGE_PX)
    val glow by rememberThumb(item, previews)
    val described = item?.let { spoken(it) }
    // The picture's room at rest: from under the bar to above the caption.
    val region = height - strip - PHOTO_TOP - CAPTION - STAGE_FOOT
    val fullPx = with(density) { height.toPx() }
    val stripPx = with(density) { strip.toPx() }
    val regionPx = with(density) { region.toPx() }
    val restPx = with(density) { (PHOTO_TOP + CAPTION + STAGE_FOOT).toPx() }
    val edgePx = edge()
    val fold = ((fullPx - edgePx) / (fullPx - stripPx)).coerceIn(0f, 1f)
    // What is left for it now, the caption and the air giving way first.
    val scale = ((edgePx - stripPx - restPx * (1f - fold)) / regionPx).coerceIn(0f, 1f)
    // It fades as it shrinks, gone just as it reaches the bar.
    val photoAlpha = ((scale - 0.08f) / 0.92f).coerceIn(0f, 1f)
    val topPx = with(density) { (strip + PHOTO_TOP).toPx() }
    // Laid out at full height whatever the folding box around it allows: the picture's own scale does the shrinking.
    Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(height)) {
        PhotoLight(
            glow,
            Modifier.fillMaxSize().graphicsLayer { alpha = photoAlpha * (0.4f + 0.6f * scale) },
            level = 0.62f,
            center = Offset(0.5f, (topPx + regionPx * scale / 2f) / fullPx),
            radius = 0.62f * (0.35f + 0.65f * scale),
        )
        if (item == null) {
            if (loaded) Text(
                stringResource(if (favoritesOnly) R.string.gallery_empty_favorites else R.string.gallery_empty),
                style = Visor.type.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp).graphicsLayer { alpha = 1f - fold },
            )
            return@Box
        }
        Box(
            Modifier
                .padding(top = strip + PHOTO_TOP)
                .fillMaxWidth()
                .height(region)
                .padding(horizontal = 32.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = photoAlpha
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .aspectRatio(item.aspect, matchHeightConstraintsFirst = true)
                    .onGloballyPositioned { onBounds(it.boundsInRoot()) }
                    .clip(RoundedCornerShape(PHOTO_RADIUS))
                    .background(colors.raised)
                    .semantics { described?.let { contentDescription = it } }
                    .clickable(onClick = if (item.onGlassesOnly && !selecting && canImport) onImport else onOpen),
            ) {
                Crossfade(item.key to picture, label = "stage") { (_, image) ->
                    image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                if (item.onGlassesOnly) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = GLASSES_VEIL)))
                    Box(Modifier.align(Alignment.Center)) {
                        if (arriving) ProgressRing(progress, Modifier.size(44.dp))
                        else if (!selecting) ActionPill(stringResource(R.string.gallery_import), onImport, enabled = canImport)
                    }
                }
            }
        }
        Text(
            caption(item),
            style = Visor.type.label,
            color = colors.textSecondary,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = strip + PHOTO_TOP + region + 12.dp)
                .graphicsLayer {
                    // It rides up with the picture's bottom edge, and is gone well before the picture is.
                    translationY = -regionPx * (1f - scale)
                    alpha = (1f - fold * 2.5f).coerceIn(0f, 1f)
                },
        )
    }
}

/** One rounded island under the first day's title, a row for each thing worth saying, or nothing. */
@Composable
private fun Island(
    waiting: List<GalleryItem>,
    arriving: Int,
    problem: ImportProblem?,
    overBluetooth: Boolean,
    started: Boolean,
    canImport: Boolean,
    askPlaces: Boolean,
    onPlaces: () -> Unit,
    onPlacesLater: () -> Unit,
    onImport: (List<GalleryItem>) -> Unit,
) {
    val colors = Visor.colors
    val rows = buildList<@Composable () -> Unit> {
        when {
            arriving > 0 -> add {
                IslandRow(
                    pluralStringResource(R.plurals.gallery_arriving, arriving, arriving) +
                        (if (overBluetooth) " · " + stringResource(if (started) R.string.import_over_bluetooth else R.string.import_over_bluetooth_wait) else ""),
                    lead = { ProgressRing(null, Modifier.size(18.dp)) },
                )
            }
            problem != null && waiting.any { it.capture?.handle in problem.handles } -> add {
                IslandRow(
                    importProblemText(problem),
                    lead = { Box(Modifier.size(8.dp).clip(CircleShape).background(colors.signal)) },
                ) { ActionPill(stringResource(R.string.try_again), { onImport(waiting) }, enabled = canImport) }
            }
            waiting.isNotEmpty() -> add {
                IslandRow(
                    pluralStringResource(R.plurals.gallery_waiting, waiting.size, waiting.size),
                    lead = { Icon(VisorIcons.Glasses, null, Modifier.size(20.dp), tint = colors.textPrimary) },
                ) { ActionPill(stringResource(R.string.gallery_import_all), { onImport(waiting) }, enabled = canImport) }
            }
        }
        if (askPlaces) add {
            IslandRow(stringResource(R.string.places_title), lead = { Icon(VisorIcons.Pin, null, Modifier.size(20.dp), tint = colors.textPrimary) }) {
                Text(
                    stringResource(R.string.gallery_not_now),
                    style = Visor.type.label,
                    color = colors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onPlacesLater).heightIn(min = 48.dp).wrapContentHeight().padding(horizontal = 10.dp),
                )
                ActionPill(stringResource(R.string.allow), onPlaces)
            }
        }
    }
    Column(Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.9f, stiffness = 400f))) {
        if (rows.isEmpty()) return@Column
        Card(Modifier.padding(bottom = 14.dp)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) Hairline()
                row()
            }
        }
    }
}

@Composable
private fun IslandRow(text: String, lead: @Composable () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { lead() }
        Spacer(Modifier.width(12.dp))
        Text(text, style = Visor.type.body, color = Visor.colors.textPrimary, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
    }
}

@Composable
private fun DayHeader(day: Day, first: Boolean, modifier: Modifier) {
    val colors = Visor.colors
    val context = LocalContext.current
    val photos = day.items.count { it.type != CaptureType.VIDEO }
    val videos = day.items.size - photos
    // Where most of the day was taken.
    val spot = remember(day) {
        day.items.mapNotNull { i -> i.kept?.lat?.let { lat -> i.kept.lon?.let { lon -> lat to lon } } }
            .groupBy { (lat, lon) -> (lat * 100).toLong() to (lon * 100).toLong() }.maxByOrNull { it.value.size }?.value?.first()
    }
    val place by produceState(spot?.let { PlaceNames.cached(it.first, it.second) }, spot) {
        if (spot != null && value == null) value = PlaceNames.of(context, spot.first, spot.second)
    }
    // The first day opens the sheet; the others follow the tiles above them closely.
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = if (first) 26.dp else 14.dp, bottom = 12.dp)) {
        Text(dayTitle(day.date), style = Visor.type.heading, color = colors.textPrimary, modifier = Modifier.asHeading())
        Text(
            (listOfNotNull(place) + countWords(LocalResources.current, photos, videos)).joinToString(" · "),
            style = Visor.type.label,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** One grid square with its type, favourite and glasses marks. */
@Composable
private fun Tile(
    modifier: Modifier,
    item: GalleryItem,
    previews: Map<String, File>,
    selecting: Boolean,
    selected: Boolean,
    arriving: Boolean,
    progress: Float?,
    staged: Boolean,
    onTap: () -> Unit,
    onBounds: (Rect) -> Unit,
    onGone: () -> Unit,
) {
    val colors = Visor.colors
    val thumb by rememberThumb(item, previews)
    val inset by animateFloatAsState(if (selected) 0.86f else 1f, spring(dampingRatio = 0.7f, stiffness = 600f), label = "inset")
    val described = spoken(item)
    DisposableEffect(item.key) { onDispose(onGone) }
    Box(
        modifier
            .aspectRatio(1f)
            .background(colors.raised)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .semantics {
                contentDescription = described
                if (selecting) this.selected = selected
            }
            .clickable(onClick = onTap),
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = inset
                scaleY = inset
                clip = inset < 1f
                shape = RoundedCornerShape(10.dp)
            },
        ) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (item.onGlassesOnly) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = GLASSES_VEIL)))
            if (item.type == CaptureType.VIDEO || item.favorite) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.4f).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))))
            }
            val mark = Modifier.padding(6.dp).size(15.dp)
            when {
                item.onGlassesOnly -> Icon(VisorIcons.Glasses, null, mark.align(Alignment.BottomStart), tint = Color.White)
                item.favorite -> Icon(VisorIcons.HeartFilled, null, mark.align(Alignment.BottomStart), tint = Color.White)
            }
            if (item.type == CaptureType.LIVE) Icon(VisorIcons.LivePhoto, null, mark.align(Alignment.TopEnd), tint = Color.White.copy(alpha = 0.9f))
            if (item.type == CaptureType.VIDEO) {
                val length = item.phone?.durationMs?.takeIf { it > 0 }
                if (length != null) {
                    Text(duration(length), style = Visor.type.label, color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 7.dp, vertical = 5.dp))
                } else {
                    Icon(VisorIcons.Play, null, mark.align(Alignment.BottomEnd), tint = Color.White)
                }
            }
            if (arriving) ProgressRing(progress, Modifier.align(Alignment.Center).size(26.dp))
        }
        if (staged && !selecting) Box(Modifier.fillMaxSize().border(2.dp, Color.White.copy(alpha = 0.9f)))
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color.Black.copy(alpha = 0.25f))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(VisorIcons.Check, null, Modifier.size(15.dp), tint = Color.Black)
            }
        }
    }
}

@Composable
private fun SelectionBar(
    chosen: List<GalleryItem>,
    canImport: Boolean,
    onImport: () -> Unit,
    onShare: () -> Unit,
    onFavorite: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Visor.colors
    val onPhone = chosen.filter { it.phone != null }
    val allFavorite = onPhone.isNotEmpty() && onPhone.all { it.favorite }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(colors.raised).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chosen.any { it.onGlassesOnly }) ActionPill(stringResource(R.string.gallery_import), onImport, Modifier.padding(horizontal = 4.dp), enabled = canImport)
        if (onPhone.isNotEmpty()) {
            RoundIcon(VisorIcons.Share, stringResource(R.string.gallery_share), onShare, size = 48.dp)
            RoundIcon(if (allFavorite) VisorIcons.HeartFilled else VisorIcons.Heart, stringResource(R.string.gallery_favorite), { onFavorite(!allFavorite) }, size = 48.dp, selected = allFavorite)
            RoundIcon(VisorIcons.Trash, stringResource(R.string.gallery_delete), onDelete, size = 48.dp)
        }
    }
}

/** Long-press a tile and drag: everything between it and the finger is chosen, on top of what already was. */
private fun Modifier.dragToSelect(
    grid: LazyGridState,
    onStart: (String) -> Unit,
    onExtend: (from: String, to: String, base: Set<String>) -> Unit,
    selection: () -> Set<String>,
): Modifier = pointerInput(grid) {
    var from: String? = null
    var base = emptySet<String>()
    detectDragGesturesAfterLongPress(
        onDragStart = { at ->
            grid.tileAt(at)?.let { key ->
                from = key
                base = selection()
                onStart(key)
            }
        },
        onDrag = { change, _ ->
            val start = from ?: return@detectDragGesturesAfterLongPress
            grid.tileAt(change.position)?.let { onExtend(start, it, base) }
        },
        onDragEnd = { from = null },
        onDragCancel = { from = null },
    )
}

private fun LazyGridState.tileAt(at: Offset): String? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.contentType == "tile" && IntRect(it.offset, it.size).contains(at.round()) }?.key as? String

private const val ISLAND = "island"
private const val PLACES_PUT_OFF = "places_put_off"
private const val COLUMNS = 4
private val GAP = 2.dp
/** Black over what is still only on the glasses. */
internal const val GLASSES_VEIL = 0.38f
/** The stage is this many widths tall. */
private const val STAGE_RATIO = 1.3f
private const val STAGE_PX = 1600
internal val PHOTO_RADIUS = 14.dp
/** Air between the bar and the stage's picture, its caption, and under them. */
private val PHOTO_TOP = 28.dp
private val CAPTION = 29.dp
private val STAGE_FOOT = 24.dp
