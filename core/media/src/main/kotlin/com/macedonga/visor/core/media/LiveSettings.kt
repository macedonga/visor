package com.macedonga.visor.core.media

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What can be drawn over the picture, each on or off. */
enum class LiveOverlay { READOUTS, HEAT, GRID }

/** How the phone and glasses meet on Wi-Fi for the Live view. */
enum class LiveWifi {
    /** A Wi-Fi Direct group the phone owns; the glasses join it. */
    DIRECT,
    /** A network the glasses host; the phone joins it. */
    GLASSES,
    /** A regular Wi-Fi network the glasses join, streaming to the phone over the local network. */
    NETWORK,
}

/** How the Live view is set up. Quality is automatic unless [manual]; the rest are the Advanced settings. */
data class LiveSettings(
    val manual: Boolean = false,
    val quality: StreamQuality = StreamQuality.FULL_HD,
    val bitrate: Int = 4_000_000,
    val frameRate: Int = 30,
    val overlays: Set<LiveOverlay> = setOf(LiveOverlay.HEAT),
    val replaySeconds: Int = 120,
    /** The glasses' microphones in the phone's speaker while watching. Recordings get them either way. */
    val listen: Boolean = false,
    val rtmpUrl: String = "",
    val rtmpKey: String = "",
    val rtmpCodec: BroadcastCodec = BroadcastCodec.H264,
    /** How the phone and glasses connect over Wi-Fi. */
    val wifi: LiveWifi = LiveWifi.DIRECT,
    /** The Wi-Fi network the glasses join when [wifi] is [LiveWifi.NETWORK]; empty uses the phone's network if saved on the glasses. */
    val wifiSsid: String = "",
    /** Its WPA2 passphrase; empty when [wifiOpen] is true or the glasses already keep it. */
    val wifiPassword: String = "",
    /** True when [wifiSsid] is an open network with no passphrase. */
    val wifiOpen: Boolean = false,
) {
    val glassesHost: Boolean get() = wifi == LiveWifi.GLASSES
}

class LiveSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("live", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<LiveSettings> = _settings.asStateFlow()

    fun update(change: (LiveSettings) -> LiveSettings) {
        val next = change(_settings.value)
        prefs.edit()
            .putBoolean("manual", next.manual)
            .putString("quality", next.quality.name)
            .putInt("bitrate", next.bitrate)
            .putInt("frame_rate", next.frameRate)
            .putStringSet("overlays", next.overlays.map { it.name }.toSet())
            .putInt("replay_seconds", next.replaySeconds)
            .putBoolean("listen", next.listen)
            .putString("rtmp_url", next.rtmpUrl)
            .putString("rtmp_key", next.rtmpKey)
            .putString("rtmp_codec", next.rtmpCodec.name)
            .putString("wifi", next.wifi.name)
            .putBoolean("glasses_host", next.glassesHost)
            .putString("wifi_ssid", next.wifiSsid)
            .putString("wifi_password", next.wifiPassword)
            .putBoolean("wifi_open", next.wifiOpen)
            .apply()
        _settings.value = next
    }

    private fun load(): LiveSettings {
        val d = LiveSettings()
        return LiveSettings(
            manual = prefs.getBoolean("manual", d.manual),
            quality = runCatching { StreamQuality.valueOf(prefs.getString("quality", null).orEmpty()) }.getOrDefault(d.quality),
            bitrate = prefs.getInt("bitrate", d.bitrate),
            frameRate = prefs.getInt("frame_rate", d.frameRate),
            overlays = prefs.getStringSet("overlays", null)?.mapNotNull { runCatching { LiveOverlay.valueOf(it) }.getOrNull() }?.toSet() ?: d.overlays,
            replaySeconds = prefs.getInt("replay_seconds", d.replaySeconds),
            listen = prefs.getBoolean("listen", d.listen),
            rtmpUrl = prefs.getString("rtmp_url", d.rtmpUrl).orEmpty(),
            rtmpKey = prefs.getString("rtmp_key", d.rtmpKey).orEmpty(),
            rtmpCodec = runCatching { BroadcastCodec.valueOf(prefs.getString("rtmp_codec", null).orEmpty()) }.getOrDefault(d.rtmpCodec),
            // Before "wifi", a boolean chose the glasses' own network or the phone's Wi-Fi Direct group.
            wifi = prefs.getString("wifi", null)?.let { runCatching { LiveWifi.valueOf(it) }.getOrNull() }
                ?: if (prefs.getBoolean("glasses_host", false)) LiveWifi.GLASSES else LiveWifi.DIRECT,
            wifiSsid = prefs.getString("wifi_ssid", d.wifiSsid).orEmpty(),
            wifiPassword = prefs.getString("wifi_password", d.wifiPassword).orEmpty(),
            wifiOpen = prefs.getBoolean("wifi_open", d.wifiOpen),
        )
    }
}
