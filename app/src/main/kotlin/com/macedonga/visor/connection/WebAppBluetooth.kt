package com.macedonga.visor.connection

import android.content.Context
import android.net.Uri
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Which web apps may scan for Bluetooth devices near the wearer. The Display's browser lets a page scan only when its
 * origin is in MobileConfig web_on_hn.webapp_ble_scan_allowed_origins (a JSON array of origins, matched exactly), so
 * the person's choice goes to the glasses as that value; the scans themselves run on the phone ([WebBleScans]).
 */
@Singleton
class WebAppBluetooth @Inject constructor(
    @ApplicationContext context: Context,
    private val config: GlassesConfig,
    private val log: EventLog,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _allowed = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())

    /** The origins allowed to scan, such as `https://example.com`. */
    val allowed: StateFlow<Set<String>> = _allowed.asStateFlow()

    /**
     * Allows or stops [url]'s origin, and writes the list to the glasses: their configuration is read first when Visor
     * has none, which takes a bug report. Null once the glasses have it, else why not.
     */
    suspend fun allow(url: String, on: Boolean): String? {
        val origin = originOf(url) ?: return "not a web address"
        val next = if (on) _allowed.value + origin else _allowed.value - origin
        if (!config.readOnce(READ_MS) {}) return "the glasses' configuration could not be read"
        val why = config.send(mapOf(PARAM to JSONArray(next.sorted()).toString()))
        if (why == null) {
            prefs.edit().putStringSet(KEY, next).apply()
            _allowed.value = next
            log.add("Web apps: Bluetooth ${if (on) "allowed for" else "taken from"} $origin")
        }
        return why
    }

    fun isAllowed(url: String): Boolean = originOf(url) in _allowed.value

    companion object {
        const val PARAM = "web_on_hn.webapp_ble_scan_allowed_origins"
        private const val PREFS = "web_apps"
        private const val KEY = "bluetooth_origins"
        private const val READ_MS = 5 * 60_000L

        /** scheme://host[:port], lower case, the port only when it is not the scheme's own: as the browser compares them. */
        fun originOf(url: String): String? {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "https" || it == "http" } ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val port = uri.port.takeIf { it >= 0 && it != if (scheme == "https") 443 else 80 }
            return "$scheme://$host" + (port?.let { ":$it" } ?: "")
        }
    }
}
