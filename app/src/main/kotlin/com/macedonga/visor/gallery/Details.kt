package com.macedonga.visor.gallery

import android.content.Intent
import android.media.ExifInterface
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.CaptureType
import com.macedonga.visor.stage.SHEET_RADIUS
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import com.macedonga.visor.ui.sizeWords
import com.macedonga.visor.ui.temperatureWords
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The camera's own words about a photo, from the phone copy's Exif. */
private class CameraWords(val exposure: String?, val iso: String?, val aperture: String?, val focal: String?, val model: String?)

/** What is known about a capture: time, place, file, camera and sensors. */
@Composable
fun Details(item: GalleryItem, originals: File?, modifier: Modifier) {
    val colors = Visor.colors
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val reading by produceState<SensorReading?>(null, item.key, originals) {
        value = originals?.let { dir -> withContext(Dispatchers.IO) { runCatching { SensorReading.read(dir, item.kept?.shutterNs ?: 0) }.getOrNull() } }
    }
    val seconds = stringResource(R.string.unit_s)
    val mm = stringResource(R.string.unit_mm)
    val camera by produceState<CameraWords?>(null, item.phone?.uri) {
        val uri = item.phone?.uri?.takeIf { item.phone.video.not() } ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { stream ->
                    val exif = ExifInterface(stream)
                    CameraWords(
                        exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { exposureWords((it * 1e9).toLong(), seconds) },
                        iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { "ISO $it" },
                        aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "ƒ/%.1f".format(it) },
                        focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "%.1f %s".format(it, mm) },
                        model = listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ").ifBlank { null },
                    )
                }
            }.getOrNull()
        }
    }

    Column(
        modifier
            .clip(RoundedCornerShape(topStart = SHEET_RADIUS, topEnd = SHEET_RADIUS))
            .background(colors.surface)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val at = Instant.ofEpochMilli(item.takenAt).atZone(ZoneId.systemDefault())
            Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(at).replaceFirstChar { it.titlecase(locale) }, style = Visor.type.heading, color = colors.textPrimary, modifier = Modifier.asHeading())
            Text(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm:ss" else "h:mm:ss a", locale).format(at) + " · " + kindWord(item.type), style = Visor.type.label, color = colors.textSecondary)
            val lat = item.kept?.lat
            val lon = item.kept?.lon
            if (lat != null && lon != null) {
                Row(
                    Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).clickable {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))) }
                    }.heightIn(min = 48.dp).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val name by produceState(PlaceNames.cached(lat, lon), lat, lon) { if (value == null) value = PlaceNames.of(context, lat, lon) }
                    Icon(VisorIcons.Pin, null, Modifier.size(16.dp), tint = colors.textSecondary)
                    Spacer(Modifier.width(6.dp))
                    Text(listOfNotNull(name, String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)).joinToString(" · "), style = Visor.type.label, color = colors.textPrimary)
                }
            }
        }

        val phone = item.phone
        Section(stringResource(R.string.details_file)) {
            if (phone != null) {
                if (phone.width > 0 && phone.height > 0) {
                    val mp = phone.width.toLong() * phone.height / 1e6
                    Fact(stringResource(R.string.details_resolution), "${phone.width} × ${phone.height}" + if (phone.video) "" else " · %.1f %s".format(mp, stringResource(R.string.unit_mp)))
                }
                if (phone.video && phone.durationMs > 0) Fact(stringResource(R.string.details_length), duration(phone.durationMs))
                Fact(stringResource(R.string.details_size), sizeWords(context, phone.size))
                Fact(stringResource(R.string.details_name), phone.name)
            } else {
                item.capture?.main?.let { Fact(stringResource(R.string.details_size), sizeWords(context, it.size)) }
                Fact(stringResource(R.string.details_where), stringResource(R.string.gallery_on_glasses))
            }
        }

        val exposures = reading?.exposuresNs.orEmpty()
        val seconds = stringResource(R.string.unit_s)
        val exposure = camera?.exposure ?: when {
            exposures.isEmpty() -> null
            exposures.min() == exposures.max() -> exposureWords(exposures[0], seconds)
            else -> exposureWords(exposures.max(), seconds) + " – " + exposureWords(exposures.min(), seconds)
        }
        val optics = listOfNotNull(camera?.iso, camera?.aperture, camera?.focal)
        if (camera?.model != null || exposure != null || optics.isNotEmpty()) {
            Section(stringResource(R.string.details_camera)) {
                camera?.model?.let { Fact(stringResource(R.string.details_model), it) }
                exposure?.let { Fact(stringResource(R.string.details_exposure), it) }
                if (optics.isNotEmpty()) Fact(stringResource(R.string.details_optics), optics.joinToString(" · "))
            }
        }

        reading?.let { r ->
            Section(stringResource(R.string.details_tilt)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Level(r.rollDeg, r.pitchDeg, Modifier.size(76.dp))
                    Spacer(Modifier.width(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(rollWords(r.rollDeg), style = Visor.type.body, color = colors.textPrimary)
                        Text(pitchWords(r.pitchDeg), style = Visor.type.label, color = colors.textSecondary)
                    }
                }
            }
            Section(stringResource(R.string.details_motion), trailing = stringResource(R.string.details_peak, r.peakDps.roundToInt())) {
                Motion(r, item.type, Modifier.fillMaxWidth().height(96.dp))
            }
            Section(stringResource(R.string.details_raw)) {
                Fact(stringResource(R.string.details_accel), "x %.2f · y %.2f · z %.2f %s".format(r.accel[0], r.accel[1], r.accel[2], stringResource(R.string.unit_mps2)))
                Fact(stringResource(R.string.details_rotation), "x %.1f · y %.1f · z %.1f %s".format(r.gyroDps[0], r.gyroDps[1], r.gyroDps[2], stringResource(R.string.unit_dps)))
                Fact(stringResource(R.string.details_temperature), temperatureWords(r.dieC.toDouble(), decimals = 1))
                Fact(stringResource(R.string.details_samples), stringResource(R.string.details_samples_value, r.samples, r.rateHz.roundToInt(), "%.2f".format(r.spanS)))
            }
        }
    }
}

@Composable
private fun Section(title: String, trailing: String? = null, content: @Composable () -> Unit) {
    val colors = Visor.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = Visor.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
            trailing?.let { Text(it, style = Visor.type.label, color = colors.textSecondary) }
        }
        content()
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val colors = Visor.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.weight(0.42f))
        Text(value, style = Visor.type.body, color = colors.textPrimary, modifier = Modifier.weight(0.58f))
    }
}

@Composable
private fun rollWords(roll: Float): String = when {
    abs(roll) < 1f -> stringResource(R.string.details_level_flat)
    roll > 0 -> stringResource(R.string.details_tilt_right, "%.1f".format(abs(roll)))
    else -> stringResource(R.string.details_tilt_left, "%.1f".format(abs(roll)))
}

@Composable
private fun pitchWords(pitch: Float): String = when {
    abs(pitch) < 2f -> stringResource(R.string.details_pitch_ahead)
    pitch > 0 -> stringResource(R.string.details_pitch_down, abs(pitch).roundToInt())
    else -> stringResource(R.string.details_pitch_up, abs(pitch).roundToInt())
}

/** The horizon at the shutter, turned by roll and raised by pitch. */
@Composable
private fun Level(roll: Float, pitch: Float, modifier: Modifier) {
    val colors = Visor.colors
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(colors.control, r - 1.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        for (side in listOf(-1f, 1f)) {
            drawLine(colors.textTertiary, Offset(c.x + side * (r - 9.dp.toPx()), c.y), Offset(c.x + side * (r - 3.dp.toPx()), c.y), 1.5.dp.toPx(), StrokeCap.Round)
        }
        // The horizon as the camera saw it: turned against the head's roll, raised when looking down.
        val shift = (pitch / 45f).coerceIn(-1f, 1f) * r * 0.55f
        rotate(-roll, c) {
            drawLine(Color.White, Offset(c.x - r * 0.62f, c.y - shift), Offset(c.x + r * 0.62f, c.y - shift), 2.dp.toPx(), StrokeCap.Round)
        }
        drawCircle(Color.White, 2.dp.toPx(), c)
    }
}

/** Head speed across the sensor log; a finger reads the value under it. */
@Composable
private fun Motion(reading: SensorReading, type: CaptureType, modifier: Modifier) {
    val colors = Visor.colors
    var touch by remember { mutableStateOf<Float?>(null) }
    val top = ((reading.motion.maxOrNull() ?: 0f) * 1.15f).coerceAtLeast(30f)
    fun speedAt(t: Float) = reading.motion[(t * (reading.motion.size - 1)).roundToInt().coerceIn(0, reading.motion.size - 1)]
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(reading) {
                    detectDragGestures(
                        onDragStart = { touch = (it.x / size.width).coerceIn(0f, 1f) },
                        onDrag = { change, _ -> touch = (change.position.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { touch = null },
                        onDragCancel = { touch = null },
                    )
                }
                .pointerInput(reading) { detectTapGestures(onPress = { touch = (it.x / size.width).coerceIn(0f, 1f); tryAwaitRelease(); touch = null }) },
        ) {
            val w = size.width
            val h = size.height - 18.dp.toPx()
            val n = reading.motion.size
            fun x(i: Int) = i * w / (n - 1)
            fun y(v: Float) = h - (v / top) * h
            reading.clip?.let { span ->
                if (type == CaptureType.LIVE) drawRect(Color.White.copy(alpha = 0.05f), Offset(span.start * w, 0f), Size((span.endInclusive - span.start) * w, h))
            }
            drawLine(colors.control, Offset(0f, h), Offset(w, h), 1.dp.toPx())
            val line = Path()
            val area = Path().apply { moveTo(0f, h) }
            reading.motion.forEachIndexed { i, v ->
                if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v))
                area.lineTo(x(i), y(v))
            }
            area.lineTo(w, h)
            area.close()
            drawPath(area, Color.White.copy(alpha = 0.07f))
            drawPath(line, Color.White.copy(alpha = 0.92f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            reading.shutterAt?.let { s ->
                drawLine(Color.White.copy(alpha = 0.55f), Offset(s * w, 0f), Offset(s * w, h), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                drawCircle(Color.White, 3.dp.toPx(), Offset(s * w, h))
            }
            touch?.let { t ->
                drawLine(Color.White.copy(alpha = 0.8f), Offset(t * w, 0f), Offset(t * w, h), 1.dp.toPx())
                val v = speedAt(t)
                drawCircle(colors.canvas, 5.dp.toPx(), Offset(t * w, y(v)))
                drawCircle(Color.White, 3.5.dp.toPx(), Offset(t * w, y(v)))
            }
        }
        val seconds = stringResource(R.string.unit_s)
        val dps = stringResource(R.string.unit_dps)
        val shown = touch?.let { t ->
            "%+.2f %s · %d %s".format(t * reading.spanS - (reading.shutterAt ?: 0f) * reading.spanS, seconds, speedAt(t).roundToInt(), dps)
        }
        BoxWithConstraints(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            val shutter = reading.shutterAt
            when {
                shown != null -> Text(shown, style = Visor.type.label, color = colors.textPrimary)
                shutter != null -> {
                    // Under its mark, kept inside the chart's edges.
                    val label = stringResource(R.string.details_shutter)
                    Text(
                        label,
                        style = Visor.type.label,
                        color = colors.textSecondary,
                        modifier = Modifier.offset(x = (maxWidth * shutter - 24.dp).coerceIn(0.dp, maxWidth - 48.dp)).width(48.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (shown == null) Text("%.1f %s".format(reading.spanS, seconds), style = Visor.type.label, color = colors.textTertiary, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}
