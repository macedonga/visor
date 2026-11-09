package com.macedonga.visor.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.SettingsState
import com.macedonga.visor.core.settings.same
import com.macedonga.visor.home.LastSeen
import com.macedonga.visor.stage.LightPool
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.SETTLE_EASE
import com.macedonga.visor.stage.SHEET_RADIUS
import com.macedonga.visor.stage.StageHolder
import com.macedonga.visor.stage.StageLight
import com.macedonga.visor.stage.lookOf
import com.macedonga.visor.stage.moodAway
import com.macedonga.visor.stage.nod
import com.macedonga.visor.stage.shake
import com.macedonga.visor.ui.Haptics
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import com.macedonga.visor.ui.lineLimit
import com.macedonga.visor.ui.switchRow
import com.macedonga.visor.ui.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Back, for whichever page is on top. The nav graph provides it. */
val LocalBack = staticCompositionLocalOf<() -> Unit> { {} }

@HiltViewModel
class PageModel @Inject constructor(
    val connection: GlassesConnection,
    val settings: GlassesSettings,
    val pins: Pins,
    val toggles: VisorToggles,
    val lastSeen: LastSeen,
) : ViewModel()

/** What every settings row reads: the settings, the pair's model, readiness, the page's notice and the details sheet. */
class PageHost(
    val settings: GlassesSettings,
    val pins: Pins,
    val toggles: VisorToggles,
    val haptics: Haptics,
    val state: SettingsState,
    val product: Int?,
    val ready: Boolean,
    val notice: PageNotice,
    val engineering: Boolean = false,
    val openDetails: (String) -> Unit,
)

val LocalHost = staticCompositionLocalOf<PageHost> { error("no settings page above this row") }

/** The same host for the plain rows, which also work outside a settings page without it. */
internal val LocalRowHost = staticCompositionLocalOf<PageHost?> { null }

// Rows straight on a sheet line up with its title and answers at SIDE; on a card they keep the card's own inset.
private val LocalOnSheet = staticCompositionLocalOf { false }

/** One line at the foot of the page, for a moment. */
class PageNotice {
    var text by mutableStateOf("")
        private set
    var shown by mutableStateOf(false)
        private set
    var at by mutableLongStateOf(0L)
        private set

    fun show(words: String) {
        text = words
        shown = true
        at = System.nanoTime()
    }

    fun clear() {
        shown = false
    }
}

/** The frame under every settings screen: rows' host, light, write nods, notice and details; [seeThrough] leaves the canvas out. */
@Composable
fun SettingsHost(model: PageModel = hiltViewModel(), seeThrough: Boolean = false, engineering: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val colors = Visor.colors
    val stage = LocalStage.current
    val haptics = rememberHaptics()
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val state by model.settings.state.collectAsStateWithLifecycle()
    val product = rememberProduct(model.settings, model.lastSeen)
    val ready = glasses.link == LinkState.READY
    ReadSettingsWhenReady(model.settings, ready)
    StageLight(lookOf(moodAway(glasses, live = false, last = stage.mood), colors))
    Nods(model.settings, stage, haptics)

    val notice = remember { PageNotice() }
    var details by remember { mutableStateOf<String?>(null) }
    val host = remember(state, product, ready, engineering) {
        PageHost(model.settings, model.pins, model.toggles, haptics, state, product, ready, notice, engineering) { key ->
            haptics.tick(0.5f)
            details = key
        }
    }
    CompositionLocalProvider(LocalHost provides host, LocalRowHost provides host) {
        Box(if (seeThrough) Modifier.fillMaxSize() else Modifier.fillMaxSize().background(colors.canvas)) {
            content()
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            NoticePill(notice, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 20.dp))
        }
        details?.let { key -> SettingDetails(key, onDismiss = { details = null }) }
    }
}

/** A settings page: back and title in a bar that darkens on scroll, then the rows. */
@Composable
fun SettingsPage(
    title: String,
    light: Float = 0.55f,
    list: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    sheet: @Composable () -> Unit = {},
    engineering: Boolean = false,
    content: LazyListScope.() -> Unit,
) = SettingsHost(engineering = engineering) {
    val stage = LocalStage.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val fadePx = with(density) { 160.dp.toPx() }
        val scrolled by remember { derivedStateOf { if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f) } }

        if (light > 0f) {
            LightPool(
                stage.light, Offset(0.5f, 0.1f), Size(0.6f, 0.3f), Size(0.001f, 0.001f),
                Modifier.fillMaxWidth().height(maxWidth * 0.95f).graphicsLayer { alpha = light * (1f - scrolled * 0.85f) },
            )
        }

        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = top + BAR, bottom = bottom + 48.dp),
        ) {
            item(key = "page-head") { Spacer(Modifier.height(12.dp)) }
            content()
        }

        BarShade { scrolled }
        TopBar(title, actions = actions)
    }
    sheet()
}

/** The shade under a page's top bar once the list leaves the top: solid behind the title, fading only below the bar. */
@Composable
fun BarShade(scrolled: () -> Float) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val shown by animateFloatAsState(if (scrolled() > 0.05f) 1f else 0f, tween(180), label = "bar")
    val solid = (top + BAR) / (top + BAR + BAR_FADE)
    val canvas = Visor.colors.canvas
    Box(
        Modifier
            .fillMaxWidth()
            .height(top + BAR + BAR_FADE)
            .graphicsLayer { alpha = shown }
            .background(Brush.verticalGradient(0f to canvas, solid to canvas, 1f to Color.Transparent)),
    )
}

/** Back and the page's name beside it, on one line: a long name comes down a few sizes rather than breaking. */
@Composable
fun TopBar(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val back = LocalBack.current
    Row(
        modifier.fillMaxWidth().statusBarsPadding().height(BAR).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(VisorIcons.ChevronLeft, stringResource(R.string.settings_back), back)
        BasicText(
            title,
            style = Visor.type.title.copy(color = Visor.colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = Visor.type.title.fontSize, stepSize = 1.sp),
            modifier = Modifier.weight(1f).asHeading().padding(start = 4.dp, end = 8.dp),
        )
        actions()
    }
}

/** The pair nods when a write is kept and shakes its head when one is refused. */
@Composable
private fun Nods(settings: GlassesSettings, stage: StageHolder, haptics: Haptics) {
    LaunchedEffect(settings) {
        var before = settings.state.value
        settings.state.collect { now ->
            val settled = before.pending.keys - now.pending.keys - before.quiet
            val refused = now.kept - before.kept - now.quiet
            if (refused.isNotEmpty()) {
                stage.light.flicker()
                haptics.error()
                launch { stage.pose.shake() }
            } else if (settled.any { key -> key !in now.kept && same(settings.catalogue[key]?.kind, now.stored[key] ?: settings.catalogue[key]?.default, before.pending.getValue(key)) }) {
                stage.light.pulse(0.55f)
                haptics.tick(0.35f)
                launch { stage.pose.nod() }
            }
            before = now
        }
    }
}

@Composable
private fun NoticePill(notice: PageNotice, modifier: Modifier) {
    LaunchedEffect(notice.at) {
        if (notice.shown) {
            delay(2_400)
            notice.clear()
        }
    }
    AnimatedVisibility(
        notice.shown,
        modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 2 },
        exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 2 },
    ) {
        Text(
            notice.text,
            style = Visor.type.label,
            color = Visor.colors.textPrimary,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Visor.colors.control).padding(horizontal = 18.dp, vertical = 11.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

internal val BAR = 56.dp
private val BAR_FADE = 28.dp
internal val SIDE = 24.dp
val CARD_RADIUS = 18.dp

/** A run of rows on a raised card. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalOnSheet provides false) {
        Column(
            modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(CARD_RADIUS)).background(Visor.colors.raised),
            content = content,
        )
    }
}

/** Between two rows on a card, from edge to edge. */
@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Visor.colors.hairline))
}

/** Rows on one card with hairlines between them. */
@Composable
fun Rows(vararg rows: (@Composable () -> Unit)?) {
    val shown = rows.filterNotNull()
    if (shown.isEmpty()) return
    Card {
        shown.forEachIndexed { i, row ->
            if (i > 0) Hairline()
            row()
        }
    }
}

/** One row per item on one card, each keyed by its item so its state stays with it. */
@Composable
fun <T> Rows(items: List<T>, row: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    Card {
        items.forEachIndexed { i, item ->
            key(item) {
                if (i > 0) Hairline()
                row(item)
            }
        }
    }
}

/** [Rows] as lazy items: one per row, the card's corners on the first and last. */
fun <T> LazyListScope.cardItems(items: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    itemsIndexed(items, key = { _, item -> key(item) }) { i, item ->
        val top = if (i == 0) CARD_RADIUS else 0.dp
        val bottom = if (i == items.lastIndex) CARD_RADIUS else 0.dp
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
                .background(Visor.colors.raised),
        ) {
            if (i > 0) Hairline()
            row(item)
        }
    }
}

/** A small grey heading over a card. */
@Composable
fun SectionLabel(text: String) {
    val inset = if (LocalOnSheet.current) SIDE else 32.dp
    Text(text, style = Visor.type.label, color = Visor.colors.textSecondary, modifier = Modifier.asHeading().padding(start = inset, end = inset, top = 28.dp, bottom = 8.dp))
}

/** A note under a card. */
@Composable
fun Footnote(text: String, color: Color = Visor.colors.textSecondary) {
    val inset = if (LocalOnSheet.current) SIDE else 32.dp
    Text(text, style = Visor.type.label, color = color, modifier = Modifier.padding(start = inset, end = inset, top = 8.dp))
}

/** Changed from its default. */
@Composable
fun ChangedDot() {
    Box(Modifier.padding(start = 8.dp).size(6.dp).clip(CircleShape).background(Visor.colors.textSecondary))
}

/** The mono line engineering rows carry: the key the firmware knows, and what the glasses store. */
@Composable
fun KeyLine(text: String) {
    Text(text, style = MONO, color = Visor.colors.textSecondary, maxLines = lineLimit(2), overflow = TextOverflow.Ellipsis)
}

val MONO = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)

/** A row: title, value beside or under it, lines under it and [trailing]; a locked row dims and says why on tap. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowFrame(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    locked: Boolean = false,
    changed: Boolean = false,
    value: String? = null,
    busy: Boolean = false,
    overline: String? = null,
    sub: String? = null,
    keyLine: String? = null,
    problem: String? = null,
    dense: Boolean = false,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    dim: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val host = LocalRowHost.current
    val haptics = host?.haptics ?: rememberHaptics()
    val blocked = stringResource(R.string.settings_locked)
    val tap: (() -> Unit)? = when {
        locked -> ({ haptics.tick(0.3f); host?.notice?.show(blocked) })
        else -> onClick
    }
    val base = if (tap != null || onLongClick != null) {
        Modifier.combinedClickable(onClick = tap ?: onLongClick ?: {}, onLongClick = onLongClick).then(modifier)
    } else modifier
    Column(
        base
            .fillMaxWidth()
            .heightIn(min = if (dense) 48.dp else 56.dp)
            .padding(horizontal = if (LocalOnSheet.current) SIDE else 16.dp, vertical = if (dense) 10.dp else 13.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.alpha(if (locked || dim) 0.4f else 1f), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(14.dp))
            }
            RowText(overline, title, changed, value, sub, keyLine, Modifier.weight(1f))
            if (busy) Spinner(Modifier.padding(start = 12.dp))
            trailing()
        }
        if (below != null) {
            Column(Modifier.alpha(if (locked) 0.4f else 1f).padding(top = 12.dp), content = below)
        }
        if (problem != null) Text(problem, style = Visor.type.label, color = Visor.colors.signal, modifier = Modifier.padding(top = 6.dp))
    }
}

/** A row's words: the value beside the title when both fit, under it when not. */
@Composable
private fun RowText(overline: String?, title: String, changed: Boolean, value: String?, sub: String?, keyLine: String?, modifier: Modifier) {
    val gap = 16.dp
    val line = 2.dp
    Layout(
        contents = listOf(
            {
                Column(verticalArrangement = Arrangement.spacedBy(line)) {
                    if (overline != null) Text(overline, style = Visor.type.label, color = Visor.colors.textSecondary, maxLines = lineLimit(1), overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = Visor.type.body, color = Visor.colors.textPrimary, modifier = Modifier.weight(1f, fill = false))
                        if (changed) ChangedDot()
                    }
                }
            },
            { if (value != null) Text(value, style = Visor.type.body, color = Visor.colors.textSecondary) },
            {
                if (sub != null || keyLine != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(line)) {
                        if (sub != null) Text(sub, style = Visor.type.label, color = Visor.colors.textSecondary)
                        if (keyLine != null) KeyLine(keyLine)
                    }
                }
            },
        ),
        modifier = modifier,
    ) { (heads, values, tails), constraints ->
        val width = constraints.maxWidth
        val space = line.roundToPx()
        val h = heads.first()
        val v = values.firstOrNull()
        val t = tails.firstOrNull()
        val inline = v != null && h.maxIntrinsicWidth(Constraints.Infinity) + gap.roundToPx() + v.maxIntrinsicWidth(Constraints.Infinity) <= width
        if (v != null && inline) {
            val vp = v.measure(Constraints(maxWidth = width))
            val textWidth = (width - gap.roundToPx() - vp.width).coerceAtLeast(0)
            val hp = h.measure(Constraints(maxWidth = textWidth))
            val tp = t?.measure(Constraints(maxWidth = textWidth))
            val textHeight = hp.height + (tp?.let { space + it.height } ?: 0)
            val height = maxOf(textHeight, vp.height)
            layout(width, height) {
                val top = (height - textHeight) / 2
                hp.place(0, top)
                tp?.place(0, top + hp.height + space)
                vp.place(width - vp.width, (height - vp.height) / 2)
            }
        } else {
            val hp = h.measure(Constraints(maxWidth = width))
            val vp = v?.measure(Constraints(maxWidth = width))
            val tp = t?.measure(Constraints(maxWidth = width))
            val height = hp.height + listOfNotNull(vp, tp).sumOf { space + it.height }
            layout(width, height) {
                var y = 0
                hp.place(0, y)
                y += hp.height
                vp?.let {
                    y += space
                    it.place(0, y)
                    y += it.height
                }
                tp?.place(0, y + space)
            }
        }
    }
}

/** The mark of a choice among several: a white disc with a check when chosen, an empty ring when not. */
@Composable
fun ChoiceMark(chosen: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (chosen) Color.White else Color.Transparent)
            .border(1.5.dp, if (chosen) Color.White else Visor.colors.textTertiary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) Icon(VisorIcons.Check, null, Modifier.size(16.dp), tint = Color.Black)
    }
}

/** Waiting on the glasses: a short arc turning. */
@Composable
fun Spinner(modifier: Modifier = Modifier) {
    ProgressRing(null, modifier.size(18.dp))
}

@Composable
fun RoundIcon(
    icon: ImageVector,
    description: String?,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    background: Color = Color.Transparent,
    tint: Color = Visor.colors.textPrimary,
    /** For a toggle: whether it is on. */
    selected: Boolean? = null,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)
            .clickable(interactionSource = null, indication = ripple(color = tint), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(22.dp), tint = tint)
    }
}

/** How far something has come, or a turning arc before it starts. */
@Composable
fun ProgressRing(progress: Float?, modifier: Modifier = Modifier, color: Color = Color.White) {
    val spin = rememberInfiniteTransition(label = "spin")
    val turn by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "turn")
    val shown by animateFloatAsState(progress ?: 0f, tween(300), label = "progress")
    val spinning = progress == null || progress <= 0f
    Canvas(modifier.then(if (progress != null && progress > 0f) Modifier.progressSemantics(progress) else Modifier.progressSemantics())) {
        val stroke = size.minDimension * 0.09f
        drawCircle(color.copy(alpha = 0.25f), radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
        drawArc(
            color, if (spinning) turn else -90f, if (spinning) 80f else 360f * shown, false,
            topLeft = Offset(stroke, stroke), size = Size(size.width - 2 * stroke, size.height - 2 * stroke), style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** How far something has come: a thin track filling white. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val shown = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(Visor.colors.control).progressSemantics(shown)) {
        Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(Color.White))
    }
}

/** A switch that only shows: its row takes the tap, so the whole row is the target. */
@Composable
fun VisorSwitch(on: Boolean) {
    val colors = Visor.colors
    Switch(
        checked = on,
        onCheckedChange = null,
        // Its row carries the state for TalkBack (switchRow); the switch itself is only drawn.
        modifier = Modifier.padding(start = 12.dp).clearAndSetSemantics {},
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.Black, checkedTrackColor = Color.White, checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = colors.textSecondary, uncheckedTrackColor = colors.control, uncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
fun ToggleRow(
    title: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
    onLongClick: (() -> Unit)? = null,
    locked: Boolean = false,
    changed: Boolean = false,
    sub: String? = null,
    overline: String? = null,
    keyLine: String? = null,
    problem: String? = null,
    dense: Boolean = false,
    busy: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalRowHost.current?.haptics ?: rememberHaptics()
    RowFrame(
        title, onClick = { haptics.toggle(); onChange(!on) }, onLongClick = onLongClick, locked = locked, changed = changed,
        busy = busy, overline = overline, sub = sub, keyLine = keyLine, problem = problem, dense = dense, modifier = Modifier.switchRow(on),
        leading = leading,
    ) { VisorSwitch(on) }
}

/** A value against the right edge (or under the name when it is long), and a chevron when a tap opens something. */
@Composable
fun ValueRow(
    title: String,
    value: String?,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    locked: Boolean = false,
    changed: Boolean = false,
    sub: String? = null,
    overline: String? = null,
    keyLine: String? = null,
    problem: String? = null,
    dense: Boolean = false,
    busy: Boolean = false,
    dim: Boolean = false,
) {
    RowFrame(
        title, onClick = onClick, onLongClick = onLongClick, locked = locked, changed = changed, value = value, busy = busy,
        overline = overline, sub = sub, keyLine = keyLine, problem = problem, dense = dense, dim = dim,
    ) {
        if (onClick != null) Icon(VisorIcons.Chevron, null, Modifier.padding(start = 8.dp).size(16.dp), tint = Visor.colors.textTertiary)
    }
}

/** A few short options as pills under the title, the chosen one white. */
@Composable
fun <T> PillsRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onPick: (T) -> Unit,
    onLongClick: (() -> Unit)? = null,
    locked: Boolean = false,
    changed: Boolean = false,
    sub: String? = null,
    overline: String? = null,
    keyLine: String? = null,
    problem: String? = null,
    dense: Boolean = false,
    busy: Boolean = false,
) {
    val host = LocalRowHost.current
    val haptics = host?.haptics ?: rememberHaptics()
    val blocked = stringResource(R.string.settings_locked)
    RowFrame(
        title, onClick = null, onLongClick = onLongClick, locked = locked, changed = changed, busy = busy,
        overline = overline, sub = sub, keyLine = keyLine, problem = problem, dense = dense,
        below = {
            Pills(options, selected, on = Visor.colors.control) { picked ->
                if (locked) {
                    haptics.tick(0.3f)
                    host?.notice?.show(blocked)
                } else if (picked != selected) {
                    haptics.toggle()
                    onPick(picked)
                }
            }
        },
    )
}

@Composable
fun <T> Pills(options: List<Pair<T, String>>, selected: T?, on: Color = Visor.colors.raised, onPick: (T) -> Unit) {
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            val chosen = value == selected
            val back by animateColorAsState(if (chosen) Color.White else on, tween(180), label = "pill")
            val ink = if (chosen) Color.Black else Visor.colors.textPrimary
            Text(
                label,
                style = Visor.type.label,
                color = ink,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(back)
                    .selectable(selected = chosen, interactionSource = null, indication = ripple(color = ink), role = Role.RadioButton) { onPick(value) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** A value along a track: the number on the right, the slider under it; written when the finger lifts. */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    words: (Float) -> String,
    // False when the value was not taken, which puts the thumb back.
    onDone: (Float) -> Boolean,
    onLongClick: (() -> Unit)? = null,
    locked: Boolean = false,
    changed: Boolean = false,
    overline: String? = null,
    keyLine: String? = null,
    problem: String? = null,
    dense: Boolean = false,
    busy: Boolean = false,
) {
    var dragged by remember(value) { mutableFloatStateOf(value) }
    RowFrame(
        title, onClick = null, onLongClick = onLongClick, locked = locked, changed = changed, busy = busy,
        overline = overline, keyLine = keyLine, problem = problem, dense = dense,
        below = {
            Slider(
                value = dragged.coerceIn(range.start, range.endInclusive),
                onValueChange = { dragged = it },
                onValueChangeFinished = { if (!onDone(dragged)) dragged = value },
                valueRange = range,
                steps = steps,
                enabled = !locked,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Visor.colors.control,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                    disabledThumbColor = Visor.colors.textSecondary,
                    disabledActiveTrackColor = Visor.colors.textSecondary,
                    disabledInactiveTrackColor = Visor.colors.control,
                ),
            )
        },
    ) {
        Text(words(dragged), style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(start = 16.dp))
    }
}

/** A row that leads to a page, or with no [chevron] does something there and then; [onCard] when it sits inside [Rows]. */
@Composable
fun NavRow(icon: ImageVector?, title: String, summary: String?, onClick: () -> Unit, dim: Boolean = false, onCard: Boolean = false, enabled: Boolean = true, chevron: Boolean = true) {
    val colors = Visor.colors
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = if (onCard) 16.dp else SIDE, vertical = if (onCard) 12.dp else 11.dp).alpha(if (dim || !enabled) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (onCard) colors.control else colors.raised), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = colors.textPrimary)
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Visor.type.body, color = colors.textPrimary)
            if (!summary.isNullOrEmpty()) Text(summary, style = Visor.type.label, color = colors.textSecondary, maxLines = lineLimit(1), overflow = TextOverflow.Ellipsis)
        }
        if (chevron) Icon(VisorIcons.Chevron, null, Modifier.padding(start = 8.dp).size(16.dp), tint = colors.textTertiary)
    }
}

/** A white pill: the one kind of button that asks for something. Quiet ones are grey. */
@Composable
fun ActionPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, quiet: Boolean = false, enabled: Boolean = true) {
    val ink = if (quiet) Visor.colors.textPrimary else Color.Black
    Text(
        text,
        style = Visor.type.label,
        color = ink,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(50))
            .background(if (quiet) Visor.colors.control else Color.White)
            .clickable(interactionSource = null, indication = ripple(color = ink), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** ActionPill's tall form, for a step's or a sheet's answer; the caller gives it its width. */
@Composable
fun WidePill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, quiet: Boolean = false, enabled: Boolean = true, busy: Boolean = false) {
    val touch = remember { MutableInteractionSource() }
    val pressed by touch.collectIsPressedAsState()
    val give by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "press")
    val shown by animateFloatAsState(if (enabled || busy) 1f else 0.4f, tween(260), label = "enabled")
    val ink = if (quiet) Visor.colors.textPrimary else Visor.colors.canvas
    Box(
        modifier
            .heightIn(min = 52.dp)
            .graphicsLayer {
                scaleX = give
                scaleY = give
                alpha = shown
            }
            .clip(RoundedCornerShape(50))
            .background(if (quiet) Visor.colors.control else Visor.colors.textPrimary)
            .clickable(interactionSource = touch, indication = null, enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            ProgressRing(null, Modifier.size(20.dp), color = ink)
        } else {
            AnimatedContent(
                targetState = text,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(320, easing = SETTLE_EASE)) { it / 2 })
                        .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(220)) { -it / 2 })
                },
                label = "label",
            ) { words ->
                Text(words, style = Visor.type.body, color = ink, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A step's words, as the setup's pages have them, and what moves it on. */
@Composable
fun StoryWords(title: String, body: String?, action: (@Composable () -> Unit)? = null) {
    Text(title, style = Visor.type.title, color = Visor.colors.textPrimary, modifier = Modifier.asHeading())
    if (body != null) Text(body, style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(top = 10.dp))
    if (action != null) Box(Modifier.padding(top = 28.dp)) { action() }
}

/** One answer of several, a raised pill. */
@Composable
fun ChoicePill(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(Visor.colors.raised)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = Visor.type.body, color = Visor.colors.textPrimary)
    }
}

/** What was just deleted, and Undo; null hides it, keeping the last words while it fades. */
@Composable
fun UndoPill(text: String?, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Visor.colors
    var last by remember { mutableStateOf("") }
    if (text != null) last = text
    AnimatedVisibility(
        text != null,
        modifier,
        enter = fadeIn(tween(180)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 500f)) { it / 2 },
        exit = fadeOut(tween(160)),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(colors.raised).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(last, style = Visor.type.label, color = colors.textSecondary)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.undo),
                style = Visor.type.label,
                color = colors.textPrimary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onUndo).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** The bottom sheet every settings page uses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisorSheet(onDismiss: () -> Unit, full: Boolean = false, content: @Composable ColumnScope.() -> Unit) =
    VisorSheet(onDismiss, rememberModalBottomSheetState(skipPartiallyExpanded = full), content)

/** The same sheet with its [state] in the caller's hands, to slide it away before it goes. */
@ExperimentalMaterial3Api
@Composable
fun VisorSheet(onDismiss: () -> Unit, state: SheetState, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Visor.colors.surface,
        shape = RoundedCornerShape(topStart = SHEET_RADIUS, topEnd = SHEET_RADIUS),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(50)).background(Visor.colors.control)) },
    ) {
        CompositionLocalProvider(LocalOnSheet provides true) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 20.dp), content = content)
        }
    }
}

@Composable
fun SheetTitle(title: String, sub: String? = null) {
    Column(Modifier.padding(horizontal = SIDE).padding(top = 6.dp, bottom = 16.dp)) {
        Text(title, style = Visor.type.heading, color = Visor.colors.textPrimary, modifier = Modifier.asHeading())
        if (sub != null) Text(sub, style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
    }
}

/** A question, its answer and Cancel; the answer closes the sheet, then acts. */
@Composable
fun ConfirmSheet(title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, dismiss: String = stringResource(R.string.cancel)) {
    VisorSheet(onDismiss) {
        SheetTitle(title, text)
        SheetAnswers(confirm, {
            onDismiss()
            onConfirm()
        }, dismiss, onDismiss)
    }
}

/** A sheet's two answers across its foot, in reach of a thumb: the way back on the left, the answer on the right. */
@Composable
fun SheetAnswers(answer: String, onAnswer: () -> Unit, back: String, onBack: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WidePill(back, onBack, Modifier.weight(1f), quiet = true)
        WidePill(answer, onAnswer, Modifier.weight(1f), enabled = enabled)
    }
}

/** A list of options, the chosen one checked; picking one closes the sheet. A filter field once the list is long. */
@Composable
fun <T> ChoiceSheet(
    title: String,
    options: List<Triple<T, String, String?>>,
    selected: T?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    sub: String? = null,
) {
    var query by remember { mutableStateOf("") }
    VisorSheet(onDismiss, full = options.size > 8) {
        SheetTitle(title, sub)
        if (options.size > 12) {
            SearchField(query, { query = it }, stringResource(R.string.settings_filter), Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
        }
        val needle = query.trim().lowercase()
        val shown = if (needle.isEmpty()) options else options.filter { needle in it.second.lowercase() || needle in (it.third ?: "").lowercase() }
        if (shown.isEmpty()) Text(stringResource(R.string.nothing_found), style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(horizontal = SIDE, vertical = 14.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(shown.size) { i ->
                val (value, label, detail) = shown[i]
                Row(
                    Modifier.fillMaxWidth().semantics { this.selected = value == selected }.clickable {
                        onPick(value)
                        onDismiss()
                    }.padding(horizontal = SIDE, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = Visor.type.body, color = Visor.colors.textPrimary)
                        if (detail != null) Text(detail, style = Visor.type.label, color = Visor.colors.textSecondary)
                    }
                    if (value == selected) Icon(VisorIcons.Check, null, Modifier.padding(start = 12.dp).size(22.dp), tint = Color.White)
                }
            }
        }
    }
}

/** A sheet's text field: a rounded well with the hint inside while it is empty; [focus] takes the keyboard at once. */
@Composable
fun SheetField(
    value: String,
    onValue: (String) -> Unit,
    hint: String = "",
    singleLine: Boolean = true,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    focus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Visor.colors.raised)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) Text(hint, style = Visor.type.body, color = Visor.colors.textTertiary)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = singleLine,
            textStyle = Visor.type.body.copy(color = Visor.colors.textPrimary),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth().heightIn(min = if (singleLine) 0.dp else 140.dp).focusRequester(focusRequester),
        )
    }
}

/** Text to write: one field, and Save; [extra] sits beside Save, such as Paste. */
@Composable
fun TextSheet(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    sub: String? = null,
    hint: String = "",
    singleLine: Boolean = true,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    valid: (String) -> Boolean = { true },
    filter: (String) -> String = { it },
    extra: (@Composable RowScope.(set: (String) -> Unit) -> Unit)? = null,
) {
    var draft by remember { mutableStateOf(initial) }
    VisorSheet(onDismiss, full = !singleLine) {
        SheetTitle(title, sub)
        SheetField(draft, { draft = filter(it) }, hint = hint, singleLine = singleLine, secret = secret, keyboard = keyboard, focus = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            extra?.invoke(this) { draft = it }
            Spacer(Modifier.weight(1f))
            ActionPill(stringResource(R.string.settings_save), {
                onSave(draft)
                onDismiss()
            }, enabled = valid(draft) && draft != initial)
        }
    }
}

/** The search field: a pill with a glass in it. */
@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Visor.colors.raised).heightIn(min = 46.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(VisorIcons.Search, null, Modifier.size(18.dp), tint = Visor.colors.textSecondary)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, style = Visor.type.body, color = Visor.colors.textSecondary)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = Visor.type.body.copy(color = Visor.colors.textPrimary),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onQuery("") }, contentAlignment = Alignment.Center) {
                Icon(VisorIcons.Close, stringResource(R.string.settings_clear), Modifier.size(18.dp), tint = Visor.colors.textSecondary)
            }
        }
    }
}

/** An outlined tile with a hint, where the next pin would go. */
@Composable
fun HintTile(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(CARD_RADIUS)).border(1.dp, Visor.colors.control, RoundedCornerShape(CARD_RADIUS)).padding(14.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(text, style = Visor.type.label, color = Visor.colors.textSecondary)
    }
}

/** A secret as its last four characters; null when there is none. */
internal fun masked(secret: String): String? = secret.takeIf { it.isNotBlank() }?.let { "••••" + it.takeLast(4) }

internal fun isWebAddress(text: String): Boolean = text.trim().let { it.startsWith("https://") || it.startsWith("http://") }

/** A row for [Rows], where a lambda alone would read as a block: `if (x) row { ... } else null`. */
fun row(content: @Composable () -> Unit): @Composable () -> Unit = content

/** [read] again each time the screen resumes. */
@Composable
fun <T> rememberOnResume(read: () -> T): MutableState<T> {
    val state = remember { mutableStateOf(read()) }
    LifecycleResumeEffect(Unit) {
        state.value = read()
        onPauseOrDispose { }
    }
    return state
}
