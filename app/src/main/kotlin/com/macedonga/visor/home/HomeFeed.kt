package com.macedonga.visor.home

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.macedonga.visor.R
import com.macedonga.visor.core.identity.language.GlassesLook
import com.macedonga.visor.core.connection.CaseBattery
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.identity.Diagnosis
import com.macedonga.visor.core.media.Capture
import com.macedonga.visor.core.media.Transfer
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.SheetTitle
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.openAppPage
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.ui.VisorIcons
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One clock for the home's entrance, so a row scrolled away and back does not play it again. It runs once
 * per launch, after the mark has let go: [firstVisit] false, it starts at its end.
 */
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
    RISE.transform(((entrance.value - fromMs) / durationMs).coerceIn(0f, 1f))

/** Rises and fades in, [order] beats after the stage has lit. */
fun Modifier.appear(entrance: State<Float>, order: Int): Modifier = graphicsLayer {
    val local = ((entrance.value - FIRST_MS - order * STEP_MS) / RISE_MS).coerceIn(0f, 1f)
    val eased = RISE.transform(local)
    alpha = eased
    translationY = (1f - eased) * 22.dp.toPx()
}

/** "Good evening, Marco": the hour decides the words, the phone's own profile the name. */
@Composable
fun Greeting(firstName: String?, modifier: Modifier) {
    val hour = remember { java.time.LocalTime.now().hour }
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

/**
 * The glasses at a glance, in one capsule: their battery and the case's, a bolt while charging, the signal
 * dot while recording. Away, the words for where they are and, dimmed, what they last read.
 */
@Composable
fun StatusPill(
    state: GlassesState,
    mood: Mood,
    remembered: LastBattery?,
    paired: Boolean?,
    allowed: Boolean,
    importing: Boolean,
    importProgress: Float,
    waiting: Int,
    onImport: () -> Unit,
    onAllow: () -> Unit,
    onSetUp: () -> Unit,
    onChangeOwner: (address: String?) -> Unit,
    modifier: Modifier,
    // The Display's Neural Band, when Visor has read it.
    band: Int? = null,
    bandCharging: Boolean = false,
) {
    when {
        !allowed -> return Pill(stringResource(R.string.home_allow_bluetooth), onAllow, modifier)
        paired == false -> return Pill(stringResource(R.string.home_set_up), onSetUp, modifier)
        // Nearby and refusing the key would otherwise look asleep, with nothing to do about it.
        state.diagnosis == Diagnosis.EVICTED && state.link != LinkState.READY ->
            return RefusedPill({ onChangeOwner((state.target?.link as? com.macedonga.visor.core.connection.GlassesLink.Bluetooth)?.address) }, modifier)
    }
    val colors = Visor.colors
    val away = mood == Mood.FAR || mood == Mood.ABSENT || mood == Mood.SEARCHING || mood == Mood.ASLEEP || mood == Mood.IN_CASE
    val here = state.device?.battery != null && !away
    // Out of reach or still looking, an old percentage would only mislead; asleep or docked, the last one is still
    // close to true, as the Meta app shows it.
    val resting = mood == Mood.ASLEEP || mood == Mood.IN_CASE
    val glasses = when {
        here -> state.device?.battery?.percent?.toInt()
        resting || !away -> remembered?.glasses
        else -> null
    }
    val case = when {
        here -> state.device?.case?.levelPercent() ?: remembered?.case
        resting || !away -> remembered?.case
        else -> null
    }
    val charging = here && state.device?.battery?.charging == true
    val caseCharging = here && state.device?.case?.charging == true
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surface)
            // New media on the glasses widen the capsule rather than pop a card in somewhere.
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 300f))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mood == Mood.RECORDING) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(colors.signal))
            Spacer(Modifier.size(8.dp))
        }
        if (!here && !importing) {
            if (mood == Mood.IN_CASE) {
                Icon(VisorIcons.Bolt, null, Modifier.padding(end = 6.dp).size(13.dp), tint = colors.textSecondary)
            }
            Text(stringResource(moodWords(mood)), style = Visor.type.label, color = colors.textSecondary)
            if (glasses != null) Spacer(Modifier.size(12.dp))
        }
        if (glasses != null) {
            Row(Modifier.graphicsLayer { alpha = if (here) 1f else 0.6f }, verticalAlignment = Alignment.CenterVertically) {
                Level(VisorIcons.Glasses, stringResource(R.string.glasses_battery), glasses, critical = here && glasses <= 10 && !charging)
                if (charging) Icon(VisorIcons.Bolt, stringResource(R.string.mood_charging), Modifier.padding(start = 2.dp).size(13.dp), tint = colors.textPrimary)
                if (case != null) {
                    Spacer(Modifier.size(14.dp))
                    Level(VisorIcons.Case, stringResource(R.string.glasses_case_label), case, critical = false)
                    if (caseCharging) Icon(VisorIcons.Bolt, stringResource(R.string.mood_charging), Modifier.padding(start = 2.dp).size(13.dp), tint = colors.textPrimary)
                }
                if (band != null) {
                    Spacer(Modifier.size(14.dp))
                    Level(VisorIcons.Band, stringResource(R.string.topic_band), band, critical = band <= 10 && !bandCharging)
                    if (bandCharging) Icon(VisorIcons.Bolt, stringResource(R.string.mood_charging), Modifier.padding(start = 2.dp).size(13.dp), tint = colors.textPrimary)
                }
            }
        }
        // Over Wi-Fi Direct the battery is not reported: the last reading stays, and the import takes the place of
        // the new-captures count.
        if (importing) {
            if (glasses != null) {
                Spacer(Modifier.size(12.dp))
                Box(Modifier.size(width = 1.dp, height = 14.dp).background(colors.control))
            }
            Text(
                stringResource(R.string.pill_importing, (importProgress * 100).toInt()),
                style = Visor.type.label,
                color = colors.textPrimary,
                modifier = Modifier.padding(start = if (glasses != null) 12.dp else 0.dp),
            )
        } else if (waiting > 0) {
            Spacer(Modifier.size(12.dp))
            Box(Modifier.size(width = 1.dp, height = 14.dp).background(colors.control))
            Text(
                pluralStringResource(R.plurals.pill_new, waiting, waiting),
                style = Visor.type.label,
                color = colors.textPrimary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onImport).padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

/**
 * A pair that is not connected, in one capsule: how it is, or how long ago it said so, its batteries as last read,
 * dimmed once old, and the button that connects to it set into the capsule's end. No button while the Live or an
 * import has the pair that is connected.
 */
@Composable
fun OtherPairPill(mood: Mood, seen: PairSeen?, peeking: Boolean, onConnect: (() -> Unit)?, modifier: Modifier) {
    val colors = Visor.colors
    val said = seen?.at?.takeIf { it > 0 }
    val fresh = said != null && rememberNow() - said < FRESH_MS
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surface)
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
                    .clickable(onClick = onConnect)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Text(stringResource(R.string.glasses_connect), style = Visor.type.label, color = colors.canvas)
            }
        }
    }
}

/** Which of the pairs the stage shows: one dot each, the shown one drawn out. */
@Composable
fun PairDots(count: Int, index: Int, modifier: Modifier = Modifier) {
    val colors = Visor.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val on by animateFloatAsState(if (i == index) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 400f), label = "dot")
            Box(
                Modifier
                    .size(width = (5 + 9 * on).dp, height = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(lerp(colors.control, colors.textPrimary, on)),
            )
        }
    }
}

/** What to call a pair: the name it gave itself, else its frame and colour as Meta has them. */
@Composable
fun pairName(seen: PairSeen?, look: GlassesLook?): String =
    seen?.name ?: look?.styleName?.let { style -> look.colorName?.let { stringResource(R.string.pair_look_name, style, it) } ?: style }
        ?: stringResource(R.string.glasses_default_name)

/**
 * The case's charge as a percentage. The glasses report a level on a scale that is not always 100, and they
 * know it while the case is away too, so neither a missing percent nor an absent case hides it.
 */
fun CaseBattery.levelPercent(): Int? = when {
    !known || scale <= 0 -> null
    else -> percent ?: (level * 100L / scale).toInt()
}

/** The time, ticking every half minute, so anything that says how long ago stays true while the app is open. */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
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
private fun Level(icon: ImageVector, label: String, percent: Int, critical: Boolean) {
    val shown by animateFloatAsState(percent.toFloat(), tween(900, easing = RISE), label = "level")
    val color = if (critical) Visor.colors.signal else Visor.colors.textPrimary
    Icon(icon, label, Modifier.size(17.dp), tint = color)
    Spacer(Modifier.size(6.dp))
    Text("${shown.toInt()}%", style = Visor.type.label, color = color)
}

/**
 * The carousel's first page while the glasses hold media this phone has not got: their own small previews,
 * how many, and the button to bring them over. Importing, it shows how far along it is.
 */
@Composable
fun WaitingCard(
    fresh: List<Capture>,
    previews: Map<String, File>,
    importing: Boolean,
    progress: Float,
    onImport: () -> Unit,
    modifier: Modifier,
    problem: com.macedonga.visor.core.media.ImportProblem? = null,
    overBluetooth: Boolean = false,
) {
    val colors = Visor.colors
    val newest = fresh.sortedByDescending { it.timestampNs }
    val n = newest.size
    fun preview(i: Int) = newest.getOrNull(i)?.preview?.let { previews[it.id] }
    // One fills the card, two share it, three or more make a large one and two small; each new photo eases the
    // others into place instead of snapping them.
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
                Text(pluralStringResource(R.plurals.home_new_captures, n, n), style = Visor.type.heading, color = colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        importing && overBluetooth && progress < 0.01f -> stringResource(R.string.import_over_bluetooth)
                        importing -> stringResource(R.string.pill_importing, (progress * 100).toInt())
                        failed != null -> com.macedonga.visor.gallery.importProblemText(failed)
                        else -> stringResource(R.string.home_new_captures_body)
                    },
                    style = Visor.type.label,
                    color = if (failed != null) colors.signal else colors.textSecondary,
                )
            }
            if (!importing) {
                Spacer(Modifier.size(12.dp))
                Pill(stringResource(if (failed != null) R.string.import_retry else R.string.home_import), onImport)
            }
        }
        if (importing) {
            val shown by animateFloatAsState(progress, tween(400), label = "import")
            Box(Modifier.fillMaxWidth().height(2.dp).background(colors.control)) {
                Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(colors.textPrimary))
            }
        }
    }
}

@Composable
private fun Thumb(file: File?, more: Int, modifier: Modifier, soften: Dp = 0.dp) {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = file?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(it)) { decoder, info, _ ->
                        val scale = THUMB_PX.toFloat() / maxOf(info.size.width, info.size.height)
                        if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                    }.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(modifier.clipToBounds().background(Visor.colors.control), contentAlignment = Alignment.Center) {
        // The glasses' previews are small; blown up they turn to blocks, so they are softened a little.
        Crossfade(bitmap, animationSpec = tween(350), label = "thumb") { b ->
            b?.let {
                Image(
                    it, null,
                    Modifier.matchParentSizeCompat().then(if (soften > 0.dp) Modifier.blur(soften, BlurredEdgeTreatment.Rectangle) else Modifier),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        if (more > 0) {
            Box(Modifier.matchParentSizeCompat().background(Visor.colors.canvas.copy(alpha = 0.55f)))
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
    val day = remember(cover.takenAt) {
        DateUtils.getRelativeTimeSpanString(cover.takenAt, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString() +
            ", " + DateUtils.formatDateTime(context, cover.takenAt, DateUtils.FORMAT_SHOW_TIME)
    }
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
            Text(day.replaceFirstChar { it.titlecase() }, style = Visor.type.heading, color = colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            val counts = listOfNotNull(
                photos.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.home_photos, it, it) },
                videos.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.home_videos, it, it) },
            ).joinToString(" · ")
            Text(counts, style = Visor.type.label, color = colors.textSecondary)
        }
    }
}

/** A shot's thumbnail from the gallery, video posters included. */
@Composable
private fun ShotImage(shot: PhoneShot, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, shot.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(shot.uri, android.util.Size(SHOT_PX, SHOT_PX), null).asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier.background(Visor.colors.control)) {
        bitmap?.let { Image(it, null, Modifier.matchParentSizeCompat(), contentScale = ContentScale.Crop) }
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
    // When the waiting page is done, slide to the newest moment first; the page goes once it is out of sight,
    // and the pager keeps that moment in place by its key.
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        // The moment the import made can land a beat after the import ends: wait for it, then slide to it.
        withTimeoutOrNull(MOMENT_WAIT_MS) { snapshotFlow { pager.pageCount }.first { it > 1 } }
        if (pager.pageCount > 1 && pager.currentPage != 1) pager.animateScrollToPage(1, animationSpec = tween(650, easing = RISE))
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

/** What the glasses report besides their battery, live or as last read. */
data class GlassesFacts(val freeMb: Long?, val volume: Int?, val temperatureC: Double?)

/**
 * Four numbers: the glasses' free space, volume and temperature, and how long ago they last took a photo or a
 * video; all four dim together while the glasses are not connected.
 */
@Composable
fun Facts(facts: GlassesFacts, live: Boolean, lastCapture: Long?, modifier: Modifier) {
    val dash = "\u2014"
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(stringResource(R.string.fact_free), facts.freeMb?.let { "%.1f GB".format(it / 1024.0) } ?: dash, dim = !live, Modifier.weight(1f))
            Fact(stringResource(R.string.fact_volume), facts.volume?.let { "$it%" } ?: dash, dim = !live, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(stringResource(R.string.fact_last_capture), lastCapture?.let { shortAgo(it) } ?: dash, dim = !live, Modifier.weight(1f))
            Fact(stringResource(R.string.fact_temperature), facts.temperatureC?.let { "%.0f\u00B0C".format(it) } ?: dash, dim = !live, Modifier.weight(1f))
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
fun Places(onMedia: () -> Unit, onLive: () -> Unit, onMetaAi: () -> Unit, onSettings: () -> Unit, modifier: Modifier, forThisPair: Boolean = true) {
    Column(modifier) {
        Place(VisorIcons.Gallery, stringResource(R.string.home_place_media), stringResource(R.string.home_place_media_sub), onMedia)
        // The Live, Meta AI and settings are the connected pair's: not while the home shows another.
        Place(VisorIcons.Live, stringResource(R.string.home_place_live), stringResource(R.string.home_place_live_sub), onLive, forThisPair)
        Place(VisorIcons.Voice, stringResource(R.string.home_place_meta_ai), stringResource(R.string.home_place_meta_ai_sub), onMetaAi, forThisPair)
        Place(VisorIcons.Settings, stringResource(R.string.home_place_settings), stringResource(R.string.home_place_settings_sub), onSettings, forThisPair)
    }
}

@Composable
private fun Place(icon: ImageVector, title: String, detail: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = Visor.colors
    val shown by animateFloatAsState(if (enabled) 1f else 0.35f, tween(220), label = "place")
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = shown }.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(colors.raised), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = colors.textPrimary)
        }
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Visor.type.heading, color = colors.textPrimary)
            Text(detail, style = Visor.type.label, color = colors.textSecondary)
        }
        Icon(VisorIcons.Chevron, null, Modifier.size(18.dp), tint = colors.textTertiary)
    }
}

/** The one button shape: a white pill with black text. */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Visor.colors.textPrimary)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
    ) {
        Text(text, style = Visor.type.body, color = Visor.colors.canvas, textAlign = TextAlign.Center)
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

/**
 * A refused key. With the Meta AI app on the phone that is the likely why: it took the glasses back, and it would take
 * them again after setup unless it goes first. So the pill names it, and its sheet offers both steps in order.
 */
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
    if (!metaAi) return RefusedCapsule(stringResource(R.string.home_key_refused), onChangeOwner, modifier)
    RefusedCapsule(stringResource(R.string.home_key_refused_meta_ai), { explaining = true }, modifier)
    if (explaining) {
        VisorSheet({ explaining = false }) {
            SheetTitle(stringResource(R.string.key_refused_meta_ai_title), stringResource(R.string.key_refused_meta_ai_text))
            Row(Modifier.padding(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionPill(stringResource(R.string.key_refused_uninstall_meta_ai), { openAppPage(context, META_AI_PACKAGE) })
                ActionPill(stringResource(R.string.key_refused_set_up_again), {
                    explaining = false
                    onChangeOwner()
                }, quiet = true)
            }
        }
    }
}

/** The status pill's capsule, saying what is wrong with the signal dot an error takes, and opening what fixes it. */
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

private fun Modifier.matchParentSizeCompat(): Modifier = fillMaxWidth().fillMaxHeight()

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

private val RISE = CubicBezierEasing(0.2f, 0f, 0f, 1f)
// A reading newer than this, from a quick look, counts as now.
private const val FRESH_MS = 5 * 60_000L
private const val FIRST_MS = 350f
private const val STEP_MS = 80f
private const val RISE_MS = 620f
private const val ENTRANCE_MS = 2200f
private const val THUMB_PX = 320
private const val SHOT_PX = 720
private val CARD_RADIUS = 18.dp
private const val MOMENT_WAIT_MS = 3_000L
private val LAYOUT = spring<Float>(dampingRatio = 0.85f, stiffness = 260f)
