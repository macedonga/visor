package com.macedonga.visor.gallery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Size
import com.macedonga.visor.MainActivity
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.media.wifiDirectAllowed
import com.macedonga.visor.core.media.wifiIsOn
import com.macedonga.visor.core.session.LinkState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** When the glasses' new photos and videos come over on their own. */
enum class AutoImport { CHARGING, ALWAYS, OFF }

/** The person's choice, kept on the phone. Charging is the default: the glasses are idle and on power then. */
@Singleton
class ImportChoice @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("import", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(runCatching { AutoImport.valueOf(prefs.getString(KEY, null) ?: "") }.getOrDefault(AutoImport.CHARGING))
    val mode: StateFlow<AutoImport> = _mode.asStateFlow()

    fun set(mode: AutoImport) {
        _mode.value = mode
        prefs.edit().putString(KEY, mode.name).apply()
    }

    private companion object {
        const val KEY = "auto"
    }
}

/**
 * Imports what is new on the glasses by itself, as [ImportChoice] says: once they are connected (and charging, by
 * default), Wi-Fi is on and nothing else is coming over. When it is done a quiet notification says how much came.
 * A try that brought nothing waits [RETRY_MS] before the next.
 */
fun importOnItsOwn(context: Context, scope: CoroutineScope, connection: GlassesConnection, media: MediaImport, choice: ImportChoice) {
    scope.launch {
        var lastTry = 0L
        var failed = false
        while (true) {
            delay(CHECK_MS)
            val mode = choice.mode.value
            val state = connection.state.value
            val device = state.device
            val fresh = media.fresh.value
            val wanted = mode != AutoImport.OFF &&
                state.link == LinkState.READY &&
                (mode == AutoImport.ALWAYS || device?.battery?.charging == true) &&
                device?.streamingActive != true &&
                !media.importing.value && media.inFlight.value.isEmpty() &&
                fresh.isNotEmpty() &&
                wifiIsOn(context) && wifiDirectAllowed(context)
            val now = System.currentTimeMillis()
            if (!wanted || now - lastTry < if (failed) RETRY_MS else SPACING_MS) continue
            lastTry = now
            val before = media.saved.value
            media.importAllNew()
            // Done once nothing is moving or waiting to be put together, or after a generous while.
            if (withTimeoutOrNull(START_TIMEOUT_MS) { media.importing.first { it } } != null) {
                withTimeoutOrNull(DONE_TIMEOUT_MS) {
                    media.importing.first { !it }
                    media.inFlight.first { it.isEmpty() }
                }
            }
            val came = media.saved.value - before
            failed = came <= 0
            if (came > 0) notifyImported(context, came)
        }
    }
}

private fun notifyImported(context: Context, count: Int) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL, context.getString(R.string.import_channel), NotificationManager.IMPORTANCE_LOW),
    )
    val open = PendingIntent.getActivity(
        context, 1,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_GALLERY).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_visor)
        .setContentTitle(context.resources.getQuantityString(R.plurals.import_done, count, count))
        .setContentText(context.getString(R.string.import_done_text))
        .setLargeIcon(newest(context))
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    manager.notify(NOTIFICATION_ID, notification)
}

/** The newest photo in Pictures/Visor, small, for the notification. */
private fun newest(context: Context): Bitmap? = runCatching {
    val collection = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
    context.contentResolver.query(
        collection, arrayOf(android.provider.MediaStore.MediaColumns._ID),
        "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/Visor%"),
        "${android.provider.MediaStore.MediaColumns.DATE_TAKEN} DESC",
    )?.use { c ->
        if (!c.moveToFirst()) return@use null
        val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
        context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
    }
}.getOrNull()

private const val CHANNEL = "imports"
private const val NOTIFICATION_ID = 7
private const val CHECK_MS = 15_000L
private const val SPACING_MS = 60_000L
private const val RETRY_MS = 10 * 60_000L
private const val START_TIMEOUT_MS = 10_000L
private const val DONE_TIMEOUT_MS = 15 * 60_000L
