package com.macedonga.visor.outside

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.macedonga.visor.MainActivity
import com.macedonga.visor.R
import com.macedonga.visor.core.design.VisorColors
import com.macedonga.visor.home.PhoneShot
import com.macedonga.visor.home.countWords
import com.macedonga.visor.home.momentDay
import com.macedonga.visor.home.moodWords
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.stage.lookOf
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The glasses on the home screen: the light of their mood, how they are, the batteries, and the latest moment when wide. */
@AndroidEntryPoint
class GlassesWidget : AppWidgetProvider() {

    @Inject
    lateinit var outside: Outside

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = push(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = push(context)

    private fun push(context: Context) {
        val pending = goAsync()
        outside.scope.launch {
            try {
                outside.refresh()
                showWidgets(context, outside.glimpse.value)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Draw [glimpse] into every widget on the home screen: small and wide both, the launcher picks by size. */
suspend fun showWidgets(context: Context, glimpse: Glimpse) {
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(ComponentName(context, GlassesWidget::class.java))
    if (ids.isEmpty()) return
    val cover = coverOf(context, glimpse.moment)
    for (id in ids) {
        val views = RemoteViews(mapOf(SizeF(110f, 110f) to views(context, glimpse, null), SizeF(WIDE_DP, 110f) to views(context, glimpse, cover)))
        runCatching { manager.updateAppWidget(id, views) }.onFailure { Log.w("Visor", "widget update failed", it) }
    }
}

/** The small widget when [cover] is null, the wide one with the moment beside it otherwise. */
private fun views(context: Context, g: Glimpse, cover: Cover?): RemoteViews {
    val wide = cover != null
    val v = RemoteViews(context.packageName, if (wide) R.layout.widget_glasses_wide else R.layout.widget_glasses)
    v.setImageViewBitmap(R.id.glow, glow(g.mood, wide))
    v.setViewVisibility(R.id.dot, if (g.mood == Mood.RECORDING) View.VISIBLE else View.GONE)
    v.setTextViewText(R.id.mood, context.getString(moodWords(g.mood)))
    val battery = g.glasses
    if (battery != null) {
        v.setViewVisibility(R.id.battery_row, View.VISIBLE)
        v.setTextViewText(R.id.battery, "$battery%")
        v.setContentDescription(R.id.battery, if (g.charging) context.getString(R.string.glasses_battery_charging, battery) else "$battery%")
        // A number from before, asleep or docked, is shown but dimmed, as on the home.
        v.setTextColor(R.id.battery, context.getColor(if (g.here) R.color.text_primary else R.color.text_dim))
        v.setViewVisibility(R.id.bolt, if (g.charging) View.VISIBLE else View.GONE)
        v.setInt(R.id.bolt, "setImageAlpha", if (g.here) 255 else 115)
        v.setTextViewTextSize(R.id.mood, TypedValue.COMPLEX_UNIT_SP, 13f)
        v.setTextColor(R.id.mood, context.getColor(R.color.text_secondary))
    } else {
        // No number to show: the words take its place.
        v.setViewVisibility(R.id.battery_row, View.GONE)
        v.setTextViewTextSize(R.id.mood, TypedValue.COMPLEX_UNIT_SP, 19f)
        v.setTextColor(R.id.mood, context.getColor(R.color.text_primary))
    }
    val case = g.case
    v.setViewVisibility(R.id.detail, if (case != null && battery != null) View.VISIBLE else View.GONE)
    if (case != null) v.setTextViewText(R.id.detail, context.getString(R.string.widget_case, case))
    v.setOnClickPendingIntent(R.id.status, open(context, null))
    cover?.let { moment(context, v, g.moment, it) }
    return v
}

private fun moment(context: Context, v: RemoteViews, moment: List<PhoneShot>, cover: Cover) {
    val shot = cover.shot
    if (shot == null || cover.bitmap == null) {
        v.setViewVisibility(R.id.scrim, View.GONE)
        v.setTextViewText(R.id.moment_day, context.getString(R.string.widget_no_moment))
        v.setTextColor(R.id.moment_day, context.getColor(R.color.text_secondary))
        v.setViewVisibility(R.id.moment_counts, View.GONE)
        v.setOnClickPendingIntent(R.id.moment, open(context, null))
        return
    }
    v.setImageViewBitmap(R.id.photo, cover.bitmap)
    v.setViewVisibility(R.id.scrim, View.VISIBLE)
    v.setTextViewText(R.id.moment_day, momentDay(context, shot.takenAt))
    v.setTextColor(R.id.moment_day, context.getColor(R.color.text_primary))
    val photos = moment.count { !it.video }
    v.setTextViewText(R.id.moment_counts, countWords(context.resources, photos, moment.size - photos).joinToString(" · "))
    v.setViewVisibility(R.id.moment_counts, View.VISIBLE)
    v.setOnClickPendingIntent(R.id.moment, open(context, MainActivity.OPEN_GALLERY))
}

private fun open(context: Context, where: String?): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    where?.let { intent.putExtra(MainActivity.EXTRA_OPEN, it) }
    return PendingIntent.getActivity(context, where.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** The moment's cover, decoded once for as long as it stays the newest. */
private class Cover(val shot: PhoneShot?, val bitmap: Bitmap?)

@Volatile
private var lastCover = Cover(null, null)

private suspend fun coverOf(context: Context, moment: List<PhoneShot>): Cover {
    val shot = moment.firstOrNull { !it.video } ?: moment.firstOrNull()
    if (shot == lastCover.shot) return lastCover
    val bitmap = shot?.let {
        withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(it.uri, Size(COVER_PX, COVER_PX), null) }.getOrNull() }
    }
    return Cover(shot, bitmap).also { lastCover = it }
}

private val glows = ConcurrentHashMap<Pair<Mood, Boolean>, Bitmap>()

/** [mood]'s stage light as a 96 px pool, left of centre when [wide]; the launcher scales it up. */
private fun glow(mood: Mood, wide: Boolean): Bitmap = glows.getOrPut(mood to wide) {
    val look = lookOf(mood, VisorColors())
    val h = 96
    val w = if (wide) h * 2 else h
    val strength = 0.12f + 0.36f * sqrt(look.level)
    val colors = intArrayOf(
        look.core.copy(alpha = strength).toArgb(),
        look.rim.copy(alpha = strength * 0.38f).toArgb(),
        look.rim.copy(alpha = 0f).toArgb(),
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(if (wide) w * 0.25f else w * 0.5f, -h * 0.1f, h * 1.1f, colors, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint) }
}

private const val WIDE_DP = 250f
private const val COVER_PX = 480
