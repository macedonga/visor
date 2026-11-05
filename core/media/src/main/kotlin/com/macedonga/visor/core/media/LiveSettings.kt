package com.macedonga.visor.core.media

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What can be drawn over the picture, each on or off. */
enum class LiveOverlay { READOUTS, HEAT, GRID }

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
    /** The glasses host the network and the phone joins it, instead of the glasses joining the phone's group. */
    val glassesHost: Boolean = false,
)

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
            .putBoolean("glasses_host", next.glassesHost)
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
            glassesHost = prefs.getBoolean("glasses_host", d.glassesHost),
        )
    }
}
