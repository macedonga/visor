package com.macedonga.visor.gallery

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.Darkroom
import com.macedonga.visor.core.media.Edit
import com.macedonga.visor.core.media.Kept
import com.macedonga.visor.core.media.coverScale
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.BAR
import com.macedonga.visor.settings.ConfirmSheet
import com.macedonga.visor.settings.Pills
import com.macedonga.visor.settings.ProgressRing
import com.macedonga.visor.ui.rememberHaptics
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The cut's shape, width over height; [ORIGINAL] keeps the photo's own. */
private enum class Aspect(val ratio: Float?) {
    ORIGINAL(null),
    SQUARE(1f),
    FOUR_FIVE(0.8f),
    NINE_SIXTEEN(9f / 16f);

    @Composable
    fun label(): String = when (this) {
        ORIGINAL -> stringResource(R.string.editor_original)
        SQUARE -> stringResource(R.string.editor_square)
        FOUR_FIVE -> "4:5"
        NINE_SIXTEEN -> "9:16"
    }
}

/** Picks a live photo's frame, straightens and crops a photo. */
@Composable
fun Editor(
    kept: Kept,
    darkroom: Darkroom,
    originals: File?,
    /** What the viewer already shows, used until the source is decoded when the photo was never edited. */
    shown: ImageBitmap?,
    onSaved: (Kept) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = Visor.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var frameUs by remember { mutableStateOf(kept.edit.frameUs) }
    var angle by remember { mutableFloatStateOf(kept.edit.angle) }
    var aspect by remember { mutableStateOf(Aspect.ORIGINAL) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var centre by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var touching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }

    val times by produceState(emptyList<Long>(), kept.handle) { value = darkroom.frameTimes(kept) }
    val strip by produceState(emptyList<Bitmap>(), kept.handle) { value = darkroom.strip(kept) }
    val roll by produceState<Float?>(null, originals) {
        value = originals?.let { withContext(Dispatchers.IO) { runCatching { SensorReading.read(it, kept.shutterNs)?.rollDeg }.getOrNull() } }
    }
    // The picture being cut: a strip frame at once while scrubbing, then the real one.
    val picked by produceState(shown?.takeIf { kept.edit.isOriginal && frameUs == null }, frameUs) {
        val bitmap = darkroom.source(kept, frameUs) ?: run {
            // Nothing to cut: the editor closes.
            if (value == null) {
                haptics.error()
                onCancel()
            }
            return@produceState
        }
        value = withContext(Dispatchers.Default) { previewOf(bitmap) }
    }
    var scrubbing by remember { mutableStateOf<Int?>(null) }
    val picture = scrubbing?.let { strip.getOrNull(it)?.asImageBitmap() } ?: picked

    val w = picture?.width?.toFloat() ?: 3f
    val h = picture?.height?.toFloat() ?: 4f
    val ratio = aspect.ratio ?: (w / h)

    // The existing cut, read back into a shape, a zoom and a centre once the picture's size is known.
    LaunchedEffect(picture != null) {
        if (picture == null || restored) return@LaunchedEffect
        restored = true
        val (l, t, r, b) = kept.edit.crop
        val cw = r - l
        val ch = b - t
        val shape = cw * w / (ch * h)
        aspect = Aspect.entries.firstOrNull { it.ratio != null && abs(it.ratio - shape) < 0.01f } ?: Aspect.ORIGINAL
        zoom = 1f / max(cw, ch).coerceAtLeast(0.01f)
        centre = Offset((l + r) / 2f, (t + b) / 2f)
    }

    fun cropOf(): List<Float> {
        val (cw, ch) = if (ratio <= w / h) (ratio * h / w / zoom).coerceAtMost(1f) to (1f / zoom) else (1f / zoom) to (w / (ratio * h) / zoom).coerceAtMost(1f)
        val cx = centre.x.coerceIn(cw / 2f, 1f - cw / 2f)
        val cy = centre.y.coerceIn(ch / 2f, 1f - ch / 2f)
        return listOf(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
    }
    val crop = cropOf()
    val edit = Edit(frameUs, (angle * 10).roundToInt() / 10f, crop.map { (it * 10000).roundToInt() / 10000f })
    val changed = edit != kept.edit
    LaunchedEffect(edit) { failed = false }
    var discarding by remember { mutableStateOf(false) }
    BackHandler { if (changed) discarding = true else onCancel() }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(BAR).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.cancel),
                style = Visor.type.label,
                color = colors.textPrimary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { if (changed) discarding = true else onCancel() }
                    .heightIn(min = 48.dp).wrapContentHeight().padding(horizontal = 14.dp),
            )
            Spacer(Modifier.weight(1f))
            if (!edit.isOriginal) {
                Text(
                    stringResource(R.string.editor_reset),
                    style = Visor.type.label,
                    color = colors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                        haptics.toggle()
                        frameUs = null
                        angle = 0f
                        aspect = Aspect.ORIGINAL
                        zoom = 1f
                        centre = Offset(0.5f, 0.5f)
                    }.heightIn(min = 48.dp).wrapContentHeight().padding(horizontal = 14.dp),
                )
            }
            if (saving) {
                ProgressRing(null, Modifier.padding(horizontal = 18.dp).size(26.dp))
            } else {
                ActionPill(stringResource(if (failed) R.string.try_again else R.string.done), {
                    if (!changed) return@ActionPill onCancel()
                    saving = true
                    haptics.done()
                    scope.launch {
                        try {
                            onSaved(darkroom.apply(kept, edit))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            saving = false
                            failed = true
                            haptics.error()
                        }
                    }
                })
            }
        }

        // The picture under its cut: drag and pinch move it beneath the frame; outside the frame it is dimmed.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(ratio, w, h) {
                    detectTransformGestures { _, pan, gesture, _ ->
                        val frame = frameRect(Size(size.width.toFloat(), size.height.toFloat()), ratio, 24.dp.toPx())
                        val (l, _, r, _) = cropOf()
                        val k = frame.width / ((r - l) * w)
                        zoom = (zoom * gesture).coerceIn(1f, MAX_CROP_ZOOM)
                        centre = Offset(centre.x - pan.x / (k * w), centre.y - pan.y / (k * h))
                        val c = cropOf()
                        centre = Offset((c[0] + c[2]) / 2f, (c[1] + c[3]) / 2f)
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        touching = true
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                        } while (event.changes.any { it.pressed })
                        touching = false
                    }
                },
        ) {
            val grid by animateFloatAsState(if (touching) 1f else 0f, tween(220), label = "grid")
            if (picture == null) ProgressRing(null, Modifier.align(Alignment.Center).size(26.dp))
            picture?.let { image ->
                Canvas(Modifier.fillMaxSize()) {
                    val frame = frameRect(size, ratio, 24.dp.toPx())
                    val (l, t, r, _) = crop
                    val k = frame.width / ((r - l) * w)
                    val cover = coverScale(w, h, angle)
                    val sx = w / image.width
                    withTransform({
                        translate(frame.left, frame.top)
                        scale(k, k, Offset.Zero)
                        translate(-l * w, -t * h)
                        rotate(angle, Offset(w / 2f, h / 2f))
                        scale(cover, cover, Offset(w / 2f, h / 2f))
                        scale(sx, sx, Offset.Zero)
                    }) {
                        drawImage(image, dstSize = IntSize(image.width, image.height), filterQuality = FilterQuality.Medium)
                    }
                    val outside = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(Offset.Zero, size))
                        addRect(frame)
                    }
                    drawPath(outside, Color.Black.copy(alpha = 0.72f))
                    drawRect(Color.White.copy(alpha = 0.9f), frame.topLeft, frame.size, style = Stroke(1.dp.toPx()))
                    if (grid > 0f) {
                        for (i in 1..2) {
                            val x = frame.left + frame.width * i / 3f
                            val y = frame.top + frame.height * i / 3f
                            drawLine(Color.White.copy(alpha = 0.35f * grid), Offset(x, frame.top), Offset(x, frame.bottom), 1.dp.toPx())
                            drawLine(Color.White.copy(alpha = 0.35f * grid), Offset(frame.left, y), Offset(frame.right, y), 1.dp.toPx())
                        }
                    }
                }
            }
        }

        if (kept.clipBytes > 0) {
            FrameStrip(
                strip,
                picked = frameUs?.let { us -> times.indexOfFirst { it >= us }.takeIf { it >= 0 } },
                still = times.indexOfFirst { it >= kept.keyFrameUs }.takeIf { it >= 0 },
                onScrub = { i ->
                    if (i != scrubbing) haptics.tick(0.25f)
                    scrubbing = i
                },
                onPick = { i ->
                    scrubbing = null
                    val still = times.indexOfFirst { it >= kept.keyFrameUs }
                    frameUs = if (i == still) null else times.getOrNull(i)
                },
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        AngleDial(
            angle = angle,
            sensor = roll,
            onAngle = { next ->
                if (floor(next) != floor(angle)) haptics.tick(if (next.roundToInt() == 0) 0.7f else 0.25f)
                angle = next
            },
            onLevel = {
                roll?.let {
                    haptics.done()
                    angle = it.coerceIn(-MAX_ANGLE, MAX_ANGLE)
                }
            },
            modifier = Modifier.padding(top = 14.dp),
        )

        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Pills(Aspect.entries.map { it to it.label() }, aspect) { a ->
                haptics.tick(0.4f)
                aspect = a
                zoom = 1f
            }
        }
    }

    if (discarding) {
        ConfirmSheet(stringResource(R.string.editor_discard_title), null, stringResource(R.string.editor_discard), onCancel, { discarding = false }, dismiss = stringResource(R.string.editor_keep))
    }
}

/** The clip's frames side by side: the one picked framed in white, the photo's own instant marked with a dot. */
@Composable
private fun FrameStrip(frames: List<Bitmap>, picked: Int?, still: Int?, onScrub: (Int) -> Unit, onPick: (Int) -> Unit, modifier: Modifier) {
    val images = remember(frames) { frames.map { it.asImageBitmap() } }
    val shown by animateFloatAsState(if (images.isEmpty()) 0f else 1f, tween(260), label = "strip")
    var at by remember { mutableStateOf<Int?>(null) }
    val current = at ?: picked ?: still ?: 0
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(60.dp)
            .then(
                if (images.isEmpty()) Modifier
                else Modifier.semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), 0f..(images.size - 1).toFloat(), steps = (images.size - 2).coerceAtLeast(0))
                    setProgress { onPick(it.roundToInt().coerceIn(0, images.size - 1)); true }
                },
            )
            .pointerInput(images.size) {
                if (images.isEmpty()) return@pointerInput
                fun index(x: Float) = ((x / size.width) * images.size).toInt().coerceIn(0, images.size - 1)
                detectHorizontalDragGestures(
                    onDragStart = { at = index(it.x); onScrub(at!!) },
                    onHorizontalDrag = { change, _ -> at = index(change.position.x); onScrub(at!!) },
                    onDragEnd = { at?.let(onPick); at = null },
                    onDragCancel = { at = null },
                )
            }
            .pointerInput(images.size) { if (images.isNotEmpty()) detectTapGestures { onPick(((it.x / size.width) * images.size).toInt().coerceIn(0, images.size - 1)) } },
    ) {
        // Its room is kept from the start, turning while the frames are decoded; they fade in.
        if (images.isEmpty()) {
            ProgressRing(null, Modifier.align(Alignment.Center).padding(top = 8.dp).size(22.dp))
            return@BoxWithConstraints
        }
        val cell = maxWidth / images.size
        Row(Modifier.fillMaxSize().padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).graphicsLayer { alpha = shown }) {
            for (image in images) Image(image, null, Modifier.width(cell).fillMaxHeight(), contentScale = ContentScale.Crop)
        }
        still?.let {
            Box(Modifier.padding(start = cell * it + cell / 2 - 2.dp).size(4.dp).clip(RoundedCornerShape(50)).background(Color.White))
        }
        Box(
            Modifier
                .padding(start = (cell * current - 3.dp).coerceAtLeast(0.dp), top = 5.dp)
                .width(cell + 6.dp)
                .height(58.dp)
                .border(2.dp, Color.White, RoundedCornerShape(6.dp)),
        )
    }
}

/** A degree ruler: drag to turn; Level goes to the glasses' tilt. */
@Composable
private fun AngleDial(angle: Float, sensor: Float?, onAngle: (Float) -> Unit, onLevel: () -> Unit, modifier: Modifier) {
    val colors = Visor.colors
    val density = LocalDensity.current
    val step = with(density) { 12.dp.toPx() }
    val latest by rememberUpdatedState(angle)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "%.1f°".format(angle),
                style = Visor.type.label,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (sensor != null && abs(sensor - angle) > 0.05f) ActionPill(stringResource(R.string.editor_level), onLevel)
        }
        Canvas(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(40.dp)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(angle, -MAX_ANGLE..MAX_ANGLE)
                    setProgress { onAngle(it.coerceIn(-MAX_ANGLE, MAX_ANGLE)); true }
                }
                .pointerInput(Unit) {
                    var value = latest
                    detectHorizontalDragGestures(onDragStart = { value = latest }) { change, dx ->
                        change.consume()
                        value = (value - dx / step).coerceIn(-MAX_ANGLE, MAX_ANGLE)
                        onAngle(value)
                    }
                },
        ) {
            val cx = size.width / 2f
            val bottom = size.height
            for (d in -MAX_ANGLE.toInt()..MAX_ANGLE.toInt()) {
                val x = cx + (d - angle) * step
                if (x < 0 || x > size.width) continue
                val fade = (1f - abs(x - cx) / cx).coerceIn(0.15f, 1f)
                val major = d % 5 == 0
                drawLine(Color.White.copy(alpha = (if (major) 0.85f else 0.4f) * fade), Offset(x, bottom - if (major) 16.dp.toPx() else 9.dp.toPx()), Offset(x, bottom), 1.5.dp.toPx(), StrokeCap.Round)
            }
            sensor?.let { s ->
                val x = cx + (s - angle) * step
                if (x in 0f..size.width) drawCircle(Color.White, 3.dp.toPx(), Offset(x, bottom - 26.dp.toPx()))
            }
            drawLine(Color.White, Offset(cx, 0f), Offset(cx, bottom), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** The largest rect of [ratio] inside [size] less [margin] all round, centred. */
private fun frameRect(size: Size, ratio: Float, margin: Float): Rect {
    val aw = size.width - 2 * margin
    val ah = size.height - 2 * margin
    val fw = minOf(aw, ah * ratio)
    val fh = fw / ratio
    return Rect(Offset((size.width - fw) / 2f, (size.height - fh) / 2f), Size(fw, fh))
}

/** A copy small enough to redraw every frame of a gesture: 1600 pixels on the long side. */
private fun previewOf(bitmap: Bitmap): ImageBitmap {
    val long = max(bitmap.width, bitmap.height)
    if (long <= PREVIEW_PX) return bitmap.asImageBitmap()
    val s = PREVIEW_PX.toFloat() / long
    val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).roundToInt(), (bitmap.height * s).roundToInt(), true)
    bitmap.recycle()
    return small.asImageBitmap()
}

private const val PREVIEW_PX = 1600
private const val MAX_ANGLE = 15f
private const val MAX_CROP_ZOOM = 4f
