package com.macedonga.visor.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.MainActivity
import com.macedonga.visor.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A release on GitHub, and where its APK downloads from. */
data class Release(val version: String, val url: String)

/** Asks GitHub for the latest release twice a day while Visor runs, and tells once about each newer one. */
@Singleton
class Updates @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _newer = MutableStateFlow(if (BuildConfig.TAGGED && _enabled.value) saved()?.takeIf { newerThan(it.version, BuildConfig.VERSION_NAME) } else null)
    /** The latest release, when it is newer than this build. */
    val newer: StateFlow<Release?> = _newer.asStateFlow()

    fun start() {
        if (loop != null || !BuildConfig.TAGGED) return
        loop = scope.launch {
            while (true) {
                if (_enabled.value && System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) > EVERY_MS) check()
                delay(TICK_MS)
            }
        }
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) scope.launch { check() } else _newer.value = null
    }

    private fun check() {
        if (!BuildConfig.TAGGED) return
        // Offline or rate limited: the next tick tries again.
        val latest = runCatching { latest() }.getOrNull() ?: return
        prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis())
            .putString(KEY_VERSION, latest.version).putString(KEY_URL, latest.url).apply()
        val newer = latest.takeIf { newerThan(it.version, BuildConfig.VERSION_NAME) }
        _newer.value = newer
        if (newer != null && prefs.getString(KEY_TOLD, null) != newer.version) {
            notifyRelease(newer)
            prefs.edit().putString(KEY_TOLD, newer.version).apply()
        }
    }

    private fun saved(): Release? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        return Release(version, prefs.getString(KEY_URL, null) ?: return null)
    }

    private fun latest(): Release? {
        val http = URL(LATEST).openConnection() as HttpURLConnection
        try {
            http.connectTimeout = 10_000
            http.readTimeout = 10_000
            http.setRequestProperty("Accept", "application/vnd.github+json")
            http.setRequestProperty("User-Agent", "Visor")
            if (http.responseCode != 200) return null
            val json = JSONObject(http.inputStream.use { it.readBytes().decodeToString() })
            val assets = json.optJSONArray("assets")
            val apk = assets?.let { a -> (0 until a.length()).map(a::getJSONObject).firstOrNull { it.optString("name").endsWith(".apk") } }
            return Release(json.getString("tag_name").removePrefix("v"), apk?.optString("browser_download_url") ?: json.getString("html_url"))
        } finally {
            http.disconnect()
        }
    }

    private fun notifyRelease(release: Release) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_LOW))
        val open = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_UPDATE)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val download = PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_visor)
            .setContentTitle(context.getString(R.string.update_ready, release.version))
            .setContentText(context.getString(R.string.update_ready_text))
            .setContentIntent(download)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val LATEST = "https://api.github.com/repos/macedonga/visor/releases/latest"
        const val CHANNEL = "updates"
        const val NOTIFICATION_ID = 8
        const val EVERY_MS = 12 * 60 * 60 * 1000L
        const val TICK_MS = 60 * 60 * 1000L
        const val KEY_ENABLED = "enabled"
        const val KEY_CHECKED = "checked"
        const val KEY_VERSION = "version"
        const val KEY_URL = "url"
        const val KEY_TOLD = "told"
    }
}

/** 1.2.10 against 1.2.9 by number; a release is newer than its own pre-releases (1.2.0-beta.1). */
internal fun newerThan(a: String, b: String): Boolean {
    fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val x = parts(a)
    val y = parts(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return '-' !in a && '-' in b
}
