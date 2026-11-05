package com.macedonga.visor.gallery

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.macedonga.visor.R
import com.macedonga.visor.core.media.CaptureType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Width over height: the phone copy's own, or the glasses' 3:4 portrait. */
val GalleryItem.aspect: Float
    get() = phone?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: 0.75f

/** "Today", "Yesterday", a weekday within the week, a date within the year, a full date before it. */
@Composable
@ReadOnlyComposable
fun dayTitle(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    val text = when {
        date == today -> stringResource(R.string.gallery_today)
        date == today.minusDays(1) -> stringResource(R.string.gallery_yesterday)
        date.isAfter(today.minusDays(7)) -> DateTimeFormatter.ofPattern("EEEE", locale).format(date)
        date.year == today.year -> DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(date)
        else -> DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMMyyyy"), locale).format(date)
    }
    return text.replaceFirstChar { it.titlecase(locale) }
}

@Composable
@ReadOnlyComposable
fun clockTime(at: Long): String {
    val pattern = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a"
    return DateTimeFormatter.ofPattern(pattern, LocalConfiguration.current.locales[0]).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
}

@Composable
@ReadOnlyComposable
fun kindWord(type: CaptureType): String = when (type) {
    CaptureType.LIVE -> stringResource(R.string.gallery_live)
    CaptureType.VIDEO -> stringResource(R.string.gallery_video)
    else -> stringResource(R.string.gallery_photo)
}

/** "Today, 00:09 · Live photo". */
@Composable
@ReadOnlyComposable
fun caption(item: GalleryItem): String = dayTitle(item.date) + ", " + clockTime(item.takenAt) + " · " + kindWord(item.type)

/** What TalkBack reads for an item: "Live photo, Today, 00:09, Favourite, On the glasses". */
@Composable
@ReadOnlyComposable
fun spoken(item: GalleryItem): String = listOfNotNull(
    kindWord(item.type),
    dayTitle(item.date) + ", " + clockTime(item.takenAt),
    stringResource(R.string.gallery_favorite).takeIf { item.favorite },
    stringResource(R.string.gallery_on_glasses).takeIf { item.onGlassesOnly },
).joinToString(", ")

/** 0:12, 4:05, 1:02:03. */
fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
