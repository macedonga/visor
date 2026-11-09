package com.macedonga.visor.home

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.identity.Diagnosis
import com.macedonga.visor.core.identity.look.GlassesLook
import com.macedonga.visor.core.media.Capture
import com.macedonga.visor.core.media.ImportProblem
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.gallery.Images
import com.macedonga.visor.gallery.importProblemText
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.CARD_RADIUS
import com.macedonga.visor.settings.NavRow
import com.macedonga.visor.settings.ProgressBar
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.SheetTitle
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.WidePill
import com.macedonga.visor.settings.openAppPage
import com.macedonga.visor.stage.CRITICAL_PERCENT
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.stage.SETTLE_EASE
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.sizeWords
import com.macedonga.visor.ui.temperatureWords
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One clock for the home's entrance, run once per launch after the mark lets go; [firstVisit] false starts it at its end. */
@Composable
fun rememberEntrance(firstVisit: Boolean): State<Float> {
    val stage = LocalStage.current
    val clock = remember { Animatable(if (firstVisit) 0f else ENTRANCE_MS) }
    LaunchedEffect(clock) {
        stage.intro.awaitLetGo()
        clock.animateTo(ENTRANCE_MS, tween(ENTRANCE_MS.toInt(), easing = LinearEasing))
    }
    return clock.asState()
}

/** How far in something that fades from [fromMs] over [durationMs] of the entrance is, eased. */
fun entranceAlpha(entrance: State<Float>, fromMs: Float, durationMs: Float): Float =
    SETTLE_EASE.transform(((entrance.value - fromMs) / durationMs).coerceIn(0f, 1f))

/** Rises and fades in, [order] beats after the stage has lit. */
fun Modifier.appear(entrance: State<Float>, order: Int): Modifier = graphicsLayer {
    val local = ((entrance.value - FIRST_MS - order * STEP_MS) / RISE_MS).coerceIn(0f, 1f)
    val eased = SETTLE_EASE.transform(local)
    alpha = eased
    translationY = (1f - eased) * 22.dp.toPx()
}

/** "Good evening, Marco": the hour decides the words, the phone's own profile the name. */
@Composable
fun Greeting(firstName: String?, modifier: Modifier = Modifier) {
    val hour = Instant.ofEpochMilli(rememberNow()).atZone(ZoneId.systemDefault()).hour
    val words = when (hour) {
        in 5..11 -> if (firstName != null) R.string.greeting_morning_named else R.string.greeting_morning
        in 12..17 -> if (firstName != null) R.string.greeting_afternoon_named else R.string.greeting_afternoon
        else -> if (firstName != null) R.string.greeting_evening_named else R.string.greeting_evening
    }
    Text(
        if (firstName != null) stringResource(words, firstName) else stringResource(words),
        style = Visor.type.title,
        color = Visor.colors.textPrimary,
        modifier = modifier,
    )
}

/** The glasses at a glance in one capsule: batteries, charging, recording, or where they are and, dimmed, what they last read. */
@Composable
fun StatusPill(
    state: GlassesState,
    mood: Mood,
    remembered: LastBattery?,
    modifier: Modifier = Modifier,
    paired: Boolean? = null,
    allowed: Boolean = true,
    onAllow: () -> Unit = {},
    onSetUp: () -> Unit = {},
    onChangeOwner: (address: String?) -> Unit,
    // The Display's Neural Band, when Visor has read it.
    band: Int? = null,
    bandCharging: Boolean = false,
) {
    when {
        !allowed -> return ActionPill(stringResource(R.string.home_allow_bluetooth), onAllow, modifier)
        paired == false -> return ActionPill(stringResource(R.string.home_set_up), onSetUp, modifier)
        // Nearby and refusing the key would otherwise look asleep, with nothing to do about it.
        state.diagnosis == Diagnosis.EVICTED && state.link != LinkState.READY ->
            return RefusedPill({ onChangeOwner((state.target?.link as? GlassesLink.Bluetooth)?.address) }, modifier)
    }
    val colors = Visor.colors
    val shown = shownBattery(mood, state.device?.battery, state.device?.case?.levelPercent(), remembered)
    val here = shown.here
    val glasses = shown.glasses
    val case = shown.case
    val charging = here && state.device?.battery?.charging == true
    val caseCharging = here && state.device?.case?.charging == true
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surface)
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 300f))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mood == Mood.RECORDING) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(colors.signal))
            Spacer(Modifier.size(8.dp))
        }
        if (!here) {
            if (mood == Mood.IN_CASE) {
                Icon(VisorIcons.Bolt, null, Modifier.padding(end = 6.dp).size(13.dp), tint = colors.textSecondary)
            }
            Text(stringResource(moodWords(mood)), style = Visor.type.label, color = colors.textSecondary)
            if (glasses != null) Spacer(Modifier.size(12.dp))
        }
        if (glasses != null) {
            Row(Modifier.graphicsLayer { alpha = if (here) 1f else 0.6f }, verticalAlignment = Alignment.CenterVertically) {
                Level(VisorIcons.Glasses, stringResource(R.string.glasses_battery), glasses, critical = here && glasses <= CRITICAL_PERCENT && !charging, charging = charging)
                if (case != null) {
                    Spacer(Modifier.size(14.dp))
                    Level(VisorIcons.Case, stringResource(R.string.glasses_case_label), case, critical = false, charging = caseCharging)
                }
                if (band != null) {
                    Spacer(Modifier.size(14.dp))
                    Level(VisorIcons.Band, stringResource(R.string.topic_band), band, critical = band <= CRITICAL_PERCENT && !bandCharging, charging = bandCharging)
                }
            }
        }
    }
}

/** A pair not connected, in one capsule: how it is or when it last said, its batteries as last read, and Connect. */
@Composable
fun OtherPairPill(mood: Mood, seen: PairSeen?, peeking: Boolean, onConnect: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = Visor.colors
    val said = seen?.at?.takeIf { it > 0 }
    val fresh = said != null && rememberNow() - said < FRESH_MS
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surface)
            .then(if (onConnect != null) Modifier.clickable(onClickLabel = stringResource(R.string.glasses_connect), onClick = onConnect) else Modifier)
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 300f))
            .padding(start = 16.dp, end = if (onConnect != null) 4.dp else 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val words = when {
            peeking || fresh -> stringResource(moodWords(mood))
            said != null -> stringResource(R.string.pill_seen, shortAgo(said))
            else -> stringResource(R.string.pill_not_connected)
        }
        Text(words, style = Visor.type.label, color = colors.textSecondary, modifier = Modifier.padding(vertical = 5.dp))
        val glasses = seen?.glasses
        if (glasses != null && !peeking) {
            Spacer(Modifier.size(12.dp))
            Row(Modifier.graphicsLayer { alpha = if (fresh) 1f else 0.6f }, verticalAlignment = Alignment.CenterVertically) {
                Level(VisorIcons.Glasses, stringResource(R.string.glasses_battery), glasses, critical = false)
                seen.case?.let {
                    Spacer(Modifier.size(14.dp))
                    Level(VisorIcons.Case, stringResource(R.string.glasses_case_label), it, critical = false)
                }
            }
        }
        if (onConnect != null) {
            Spacer(Modifier.size(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.textPrimary)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Text(stringResource(R.string.glasses_connect), style = Visor.type.label, color = colors.canvas)
            }
        }
    }
}

/** Which page the stage shows: a dot per pair, the shown one drawn out, then a plus for another pair. A tap goes there. */
@Composable
fun PairPager(names: List<String>, index: Int, onGo: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Visor.colors
    val add = stringResource(R.string.glasses_set_up_new)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(names.size + 1) { i ->
            val on by animateFloatAsState(if (i == index) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 400f), label = "dot")
            val tint = lerp(colors.textTertiary, colors.textPrimary, on)
            val label = names.getOrNull(i) ?: add
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClickLabel = label) { onGo(i) }
                    .semantics {
                        contentDescription = label
                        selected = i == index
                    }
                    .sizeIn(minWidth = 24.dp, minHeight = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (i < names.size) {
                    Box(Modifier.size(width = (5 + 9 * on).dp, height = 5.dp).clip(RoundedCornerShape(50)).background(tint))
                } else {
                    // Drawn rather than an icon: at this size the icon's strokes go thin.
                    Canvas(Modifier.size(9.dp)) {
                        val w = 1.6.dp.toPx()
                        val r = CornerRadius(w / 2)
                        drawRoundRect(tint, Offset(0f, (size.height - w) / 2), Size(size.width, w), r)
                        drawRoundRect(tint, Offset((size.width - w) / 2, 0f), Size(w, size.height), r)
                    }
                }
            }
        }
    }
}

/** The shown pair's name, with a pencil that says a tap renames it. */
@Composable
fun PairName(name: String, onRename: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Visor.colors
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = stringResource(R.string.glasses_name), onClick = onRename)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = Visor.type.label, color = colors.textSecondary)
        Icon(VisorIcons.Rename, null, Modifier.padding(start = 6.dp).size(12.dp), tint = colors.textTertiary)
    }
}

/** What to call a pair: the name it gave itself, else its frame and colour as Meta has them. */
@Composable
fun pairName(seen: PairSeen?, look: GlassesLook?): String =
    seen?.name ?: look?.styleName?.let { style -> look.colorName?.let { stringResource(R.string.pair_look_name, style, it) } ?: style }
        ?: stringResource(R.string.glasses_default_name)

/** The time, ticking every half minute, so anything that says how long ago stays true while the app is open. */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** How long ago, in one short unit: "just now", "35 min ago", "2 h ago", "3 d ago". */
@Composable
private fun shortAgo(at: Long): String {
    val minutes = ((rememberNow() - at) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.ago_now)
        minutes < 60 -> stringResource(R.string.ago_minutes, minutes)
        minutes < 24 * 60 -> stringResource(R.string.ago_hours, minutes / 60)
        else -> stringResource(R.string.ago_days, minutes / (24 * 60))
    }
}

/** An icon and a percentage that counts to its value rather than jumping. */
@Composable
private fun Level(icon: ImageVector, label: String, percent: Int, critical: Boolean, charging: Boolean = false) {
    val shown by animateFloatAsState(percent.toFloat(), tween(900, easing = SETTLE_EASE), label = "level")
    val color = if (critical) Visor.colors.signal else Visor.colors.textPrimary
    Icon(icon, label, Modifier.size(17.dp), tint = color)
    Spacer(Modifier.size(6.dp))
    Text("${shown.toInt()}%", style = Visor.type.label, color = color)
    if (charging) Icon(VisorIcons.Bolt, stringResource(R.string.mood_charging), Modifier.padding(start = 2.dp).size(13.dp), tint = Visor.colors.textPrimary)
}

/** The carousel's first page while the glasses hold media this phone has not got: previews, how many, Import, progress. */
@Composable
fun WaitingCard(
    fresh: List<Capture>,
    previews: Map<String, File>,
    importing: Boolean,
    progress: Float,
    onImport: () -> Unit,
    modifier: Modifier,
    problem: ImportProblem? = null,
    overBluetooth: Boolean = false,
) {
    val colors = Visor.colors
    val newest = fresh.sortedByDescending { it.timestampNs }
    val n = newest.size
    fun preview(i: Int) = newest.getOrNull(i)?.preview?.let { previews[it.id] }
    // One fills the card, two share it, three or more make a large one and two small.
    val big by animateFloatAsState(if (n >= 3) 2f else 1f, LAYOUT, label = "big")
    val side by animateFloatAsState(if (n >= 2) 1f else 0.001f, LAYOUT, label = "side")
    val third by animateFloatAsState(if (n >= 3) 1f else 0.001f, LAYOUT, label = "third")
    Column(modifier.clip(RoundedCornerShape(CARD_RADIUS)).background(colors.raised)) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            // Softened by how far each preview is blown up: alike when they share the card, the large one more.
            Thumb(preview(0), 0, Modifier.weight(big).fillMaxHeight(), soften = 5.dp)
            if (side > 0.01f) {
                Column(Modifier.weight(side).fillMaxHeight().padding(start = 2.dp)) {
                    Thumb(preview(1), 0, Modifier.weight(1f).fillMaxWidth(), soften = if (n >= 3) 3.dp else 5.dp)
                    if (third > 0.01f) Thumb(preview(2), (n - 3).coerceAtLeast(0), Modifier.weight(third).fillMaxWidth().padding(top = 2.dp), soften = 3.dp)
                }
            }
        }
        // Why an import fell short, in the signal colour, only for captures it was about and still waiting.
        val failed = problem?.takeIf { p -> !importing && fresh.any { it.handle in p.handles } }
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val photos = fresh.count { !it.isVideo }
                val title = countWords(LocalResources.current, photos, n - photos).joinToString(" · ")
                Text(title, style = Visor.type.heading, color = colors.textPrimary)
                val line = when {
                    importing && overBluetooth && progress < 0.01f -> stringResource(R.string.import_over_bluetooth_wait)
                    importing -> stringResource(R.string.pill_importing, (progress * 100).toInt())
                    failed != null -> importProblemText(failed)
                    else -> null
                }
                if (line != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(line, style = Visor.type.label, color = if (failed != null) colors.signal else colors.textSecondary)
                }
            }
            if (!importing) {
                Spacer(Modifier.size(12.dp))
                ActionPill(stringResource(if (failed != null) R.string.try_again else R.string.home_import), onImport)
            }
        }
        if (importing) {
            val shown by animateFloatAsState(progress, tween(400), label = "import")
            ProgressBar(shown)
        }
    }
}

@Composable
private fun Thumb(file: File?, more: Int, modifier: Modifier, soften: Dp = 0.dp) {
    val initial = remember(file) { file?.let { Images.cached(Images.previewKey(it, THUMB_PX)) } }
    val bitmap by produceState<ImageBitmap?>(initial, file) {
        value = file?.let { Images.preview(it, THUMB_PX) }
    }
    Box(modifier.clipToBounds().background(Visor.colors.control), contentAlignment = Alignment.Center) {
        // The glasses' previews are small; blown up they turn to blocks, so they are softened a little.
        Crossfade(bitmap, animationSpec = tween(350), label = "thumb") { b ->
            b?.let {
                Image(
                    it, null,
                    Modifier.fillMaxSize().then(if (soften > 0.dp) Modifier.blur(soften, BlurredEdgeTreatment.Rectangle) else Modifier),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        if (more > 0) {
            Box(Modifier.fillMaxSize().background(Visor.colors.canvas.copy(alpha = 0.55f)))
            Text("+$more", style = Visor.type.heading, color = Visor.colors.textPrimary)
        }
    }
}

/** A moment already on the phone: a cover, two more, when it was, and how much is in it. */
@Composable
fun MomentCard(moment: List<PhoneShot>, onOpen: () -> Unit, modifier: Modifier) {
    val colors = Visor.colors
    val context = LocalContext.current
    val cover = moment.firstOrNull { !it.video } ?: moment.first()
    val photos = moment.count { !it.video }
    val videos = moment.size - photos
    val now = rememberNow()
    val day = remember(cover.takenAt, now / 60_000) { momentDay(context, cover.takenAt, now) }
    Column(
        modifier
            .clip(RoundedCornerShape(CARD_RADIUS))
            .background(colors.raised)
            .clickable(onClick = onOpen),
    ) {
        // One shot fills the card, two share it in halves, three or more put the cover beside a column of two.
        val others = moment.filter { it != cover }.take(2)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ShotImage(cover, Modifier.weight(if (others.size == 2) 2f else 1f).fillMaxHeight())
            when (others.size) {
                1 -> ShotImage(others[0], Modifier.weight(1f).fillMaxHeight())
                2 -> Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (shot in others) ShotImage(shot, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        Column(Modifier.padding(20.dp)) {
            Text(day, style = Visor.type.heading, color = colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(countWords(LocalResources.current, photos, videos).joinToString(" · "), style = Visor.type.label, color = colors.textSecondary)
        }
    }
}

/** A shot's thumbnail from the gallery, video posters included. */
@Composable
private fun ShotImage(shot: PhoneShot, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(Images.cached(Images.thumbKey(shot.uri, SHOT_PX)), shot.uri) {
        value = Images.thumb(context, shot.uri, SHOT_PX)
    }
    Box(modifier.background(Visor.colors.control)) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** The moments on the phone, newest first, each as wide as the page: swipe sideways for older ones. */
@Composable
fun Memories(
    moments: List<List<PhoneShot>>,
    onOpen: () -> Unit,
    modifier: Modifier,
    waiting: (@Composable (Modifier) -> Unit)? = null,
    waitingCount: Int = 0,
    leaving: Boolean = false,
    onLeft: () -> Unit = {},
) {
    val first = if (waiting != null) 1 else 0
    val pages = moments.size + first
    val pager = rememberPagerState { pages }
    // A new photo on the glasses brings the waiting page into view.
    var seen by remember { mutableIntStateOf(waitingCount) }
    LaunchedEffect(waitingCount) {
        if (waitingCount > seen && first == 1) pager.animateScrollToPage(0)
        seen = waitingCount
    }
    // When the waiting page is done, slide to the newest moment; the page goes once it is out of sight.
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        // The moment the import made can land a beat after the import ends: wait for it, then slide to it.
        withTimeoutOrNull(MOMENT_WAIT_MS) { snapshotFlow { pager.pageCount }.first { it > 1 } }
        if (pager.pageCount > 1 && pager.currentPage != 1) pager.animateScrollToPage(1, animationSpec = tween(650, easing = SETTLE_EASE))
        onLeft()
    }
    Column(modifier) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 10.dp,
            key = { if (it < first) "waiting" else moments[it - first].first().uri.toString() },
            modifier = Modifier.weight(1f),
        ) { page ->
            if (page < first) waiting?.invoke(Modifier.fillMaxSize()) else MomentCard(moments[page - first], onOpen, Modifier.fillMaxSize())
        }
        if (pages > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pages) { i ->
                    val on = i == pager.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (on) Visor.colors.textPrimary else Visor.colors.textTertiary),
                    )
                }
            }
        }
    }
}

/** Four numbers: free space, volume, temperature and time since the last capture, dimmed while not connected. */
@Composable
fun Facts(facts: GlassesFacts, live: Boolean, lastCapture: Long?, modifier: Modifier) {
    val context = LocalContext.current
    val dash = "\u2014"
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(stringResource(R.string.fact_free), facts.freeMb?.let { sizeWords(context, it * 1_048_576L) } ?: dash, dim = !live, Modifier.weight(1f))
            Fact(stringResource(R.string.fact_volume), facts.volume?.let { "$it%" } ?: dash, dim = !live, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(stringResource(R.string.fact_last_capture), lastCapture?.let { shortAgo(it) } ?: dash, dim = !live, Modifier.weight(1f))
            Fact(stringResource(R.string.fact_temperature), facts.temperatureC?.let { temperatureWords(it) } ?: dash, dim = !live, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Fact(label: String, value: String, dim: Boolean, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(CARD_RADIUS))
            .background(Visor.colors.raised)
            .padding(18.dp),
    ) {
        Text(label, style = Visor.type.label, color = Visor.colors.textSecondary)
        Spacer(Modifier.height(10.dp))
        // A large font shrinks the number to fit rather than cutting it short.
        BasicText(
            value,
            // Away, only the number dims: enough to read as old, still 4.5:1 on the card.
            style = Visor.type.readoutSmall.copy(color = Visor.colors.textPrimary.copy(alpha = if (dim) 0.6f else 1f)),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = Visor.type.readoutSmall.fontSize, stepSize = 1.sp),
        )
    }
}

@Composable
fun Places(
    onMedia: () -> Unit,
    onLive: () -> Unit,
    onMetaAi: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier,
    forThisPair: Boolean = true,
    camera: Boolean = true,
) {
    Column(modifier) {
        NavRow(VisorIcons.Gallery, stringResource(R.string.home_place_media), null, onMedia)
        // The Live, Meta AI and settings are the connected pair's: not while the home shows another.
        if (camera) NavRow(VisorIcons.Live, stringResource(R.string.home_place_live), null, onLive, enabled = forThisPair)
        NavRow(VisorIcons.Voice, stringResource(R.string.home_place_meta_ai), null, onMetaAi, enabled = forThisPair)
        NavRow(VisorIcons.Settings, stringResource(R.string.home_place_settings), null, onSettings, enabled = forThisPair)
    }
}

/** The Meta AI app, the other app the glasses take as their owner. */
private const val META_AI_PACKAGE = "com.facebook.stella"

private fun metaAiInstalled(context: Context): Boolean = try {
    context.packageManager.getPackageInfo(META_AI_PACKAGE, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

/** A refused key; with the Meta AI app installed, the likely cause, its sheet offers to remove it, then set up again. */
@Composable
private fun RefusedPill(onChangeOwner: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var metaAi by remember { mutableStateOf(metaAiInstalled(context)) }
    // Checked again on the way back, since uninstalling it happens in the system's own page.
    LifecycleResumeEffect(Unit) {
        metaAi = metaAiInstalled(context)
        onPauseOrDispose { }
    }
    // Closed again whenever Meta AI comes or goes, so a reinstall never opens it by itself.
    var explaining by remember(metaAi) { mutableStateOf(false) }
    if (!metaAi) return RefusedCapsule(stringResource(R.string.key_refused_set_up_again), onChangeOwner, modifier)
    RefusedCapsule(stringResource(R.string.home_key_refused_meta_ai), { explaining = true }, modifier)
    if (explaining) {
        VisorSheet({ explaining = false }) {
            SheetTitle(stringResource(R.string.key_refused_meta_ai_title), stringResource(R.string.key_refused_meta_ai_text))
            Column(Modifier.padding(horizontal = SIDE), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WidePill(stringResource(R.string.key_refused_uninstall_meta_ai), { openAppPage(context, META_AI_PACKAGE) }, Modifier.fillMaxWidth())
                WidePill(stringResource(R.string.key_refused_set_up_again), {
                    explaining = false
                    onChangeOwner()
                }, Modifier.fillMaxWidth(), quiet = true)
            }
        }
    }
}

/** The status capsule for a refused key: the error dot, what is wrong, and a tap to the fix. */
@Composable
private fun RefusedCapsule(text: String, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Visor.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Visor.colors.signal))
        Spacer(Modifier.size(8.dp))
        Text(text, style = Visor.type.label, color = Visor.colors.textPrimary)
    }
}

fun moodWords(mood: Mood): Int = when (mood) {
    Mood.ABSENT -> R.string.mood_absent
    Mood.FAR -> R.string.mood_far
    Mood.SEARCHING -> R.string.mood_searching
    Mood.CONNECTED -> R.string.mood_connected
    Mood.CHARGING -> R.string.mood_charging
    Mood.RECORDING, Mood.GOING_LIVE -> R.string.mood_recording
    Mood.LOW_BATTERY -> R.string.mood_low_battery
    Mood.IMPORTING -> R.string.mood_importing
    Mood.ASLEEP -> R.string.mood_asleep
    Mood.IN_CASE -> R.string.mood_in_case
}

// A reading newer than this, from a quick look, counts as now.
private const val FRESH_MS = 5 * 60_000L
private const val FIRST_MS = 350f
private const val STEP_MS = 80f
private const val RISE_MS = 620f
private const val ENTRANCE_MS = 2200f
private const val THUMB_PX = 320
private const val SHOT_PX = 720
private const val MOMENT_WAIT_MS = 3_000L
private val LAYOUT = spring<Float>(dampingRatio = 0.85f, stiffness = 260f)
