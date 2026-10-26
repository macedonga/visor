package com.macedonga.visor.connection

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * svc9002, panels: the Display's home asks which panels the phone offers (type 1, answered 2) and opens one (type 0,
 * answered with a UICommand on 0). The one panel is the Briefing, the Glanceables (weather, reminders and the rest):
 * Meta's XFBGlanceablesQuery run with the user's login, its preview and each card's Bloks payload gzipped into a
 * BloksPayload, the cards' list beside it. A cache prewarm (3) is answered 4.
 */
@Singleton
class Panels @Inject constructor(
    @ApplicationContext private val context: Context,
    private val login: Provider<MetaLogin>,
    private val requests: RequestLog,
    private val log: Provider<EventLog>,
) : Responder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> log.get().add("Panels: failed: $e") })

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val reply = DataXConnection.mirror(message.info.channelId)
        when (message.info.messageType) {
            MT_GET_ENABLED -> connection.send(reply, Wire.fieldBytes(1, briefingInfo()), messageType = MT_ENABLED)
            MT_OPEN -> {
                val host = runCatching { Host.decode(Wire.parse(message.payload)) }.getOrNull() ?: return
                scope.launch { open(host, reply, connection) }
            }
            MT_PREWARM -> connection.send(reply, Wire.fieldVarint(1, 0), messageType = MT_PREWARMED)
        }
    }

    /** PanelInfo: 1 id, 2 name, 3 ttl s, 4 renderer (Bloks), 5 data provider (DataX), 6 cache policy (enabled). */
    private fun briefingInfo(): ByteArray =
        Wire.fieldVarint(1, BRIEFING.toLong()) + Wire.fieldBytes(2, "Briefing".encodeToByteArray()) + Wire.fieldVarint(3, TTL_S) +
            Wire.fieldVarint(4, RENDERER_BLOKS) + Wire.fieldVarint(5, PROVIDER_DATAX) + Wire.fieldVarint(6, CACHE_ENABLED)

    private suspend fun open(host: Host, reply: Int, mux: DataXConnection) {
        val id = requests.start("bloks://glanceables", SOURCE_META)
        val started = System.nanoTime()
        try {
            val g = fetch(host)
            val hashes = mutableMapOf<String, String>()
            val payloads = g.optJSONArray("l1_payload_list") ?: JSONArray()
            var bloks = ByteArray(0)
            for (i in 0 until payloads.length()) {
                val p = payloads.getJSONObject(i)
                val cardId = p.optString("id")
                val hash = p.optString("content_hash")
                hashes[cardId] = hash
                bloks += Wire.fieldBytes(1, Wire.fieldBytes(1, cardId.encodeToByteArray()) + Wire.fieldBytes(2, p.optString("payload").encodeToByteArray()) + Wire.fieldBytes(3, hash.encodeToByteArray()))
            }
            bloks += Wire.fieldBytes(2, g.optString("preview").encodeToByteArray())
            val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bloks) } }.toByteArray()

            var data = Wire.fieldBytes(1, gz)
            val cards = g.optJSONArray("all_glanceables") ?: JSONArray()
            for (i in 0 until cards.length()) {
                val c = cards.getJSONObject(i)
                val name = c.optString("name")
                data += Wire.fieldBytes(
                    2,
                    Wire.fieldBytes(1, c.optString("id").encodeToByteArray()) + Wire.fieldBytes(2, name.encodeToByteArray()) +
                        Wire.fieldBytes(3, "Shows ${name.lowercase()} information".encodeToByteArray()) + Wire.fieldVarint(4, if (c.optBoolean("enabled")) 1 else 0) +
                        Wire.fieldBytes(6, hashes[c.optString("id")].orEmpty().encodeToByteArray()) + Wire.fieldBytes(7, c.optString("icon_uri").encodeToByteArray()),
                )
            }
            data += Wire.fieldVarint(3, g.optLong("fresh_ttl")) + Wire.fieldVarint(4, g.optLong("ttl"))
            val ms = (System.nanoTime() - started) / 1_000_000f
            val stats = Wire.fieldVarint(1, OS_ANDROID) + Wire.fieldFloat(3, ms)
            val command = Wire.fieldVarint(1, COMMAND_CREATE) + Wire.fieldVarint(2, WIDGET_DATAPAYLOAD) + Wire.fieldBytes(3, stats) + Wire.fieldBytes(10, data)
            mux.send(reply, command, messageType = MT_OPEN)
            requests.finish(id, "from Meta, ${payloads.length()} cards", failed = false, bytes = gz.size.toLong())
        } catch (error: Exception) {
            requests.finish(id, "failed: ${error.message}", failed = true)
            val details = Wire.fieldBytes(1, (error.message ?: "failed").encodeToByteArray())
            runCatching { mux.send(reply, Wire.fieldVarint(1, COMMAND_ERROR) + Wire.fieldBytes(11, details), messageType = MT_OPEN) }
        }
    }

    private fun fetch(host: Host): JSONObject {
        val vars = JSONObject()
            .put("bloks_version_id", host.versioningId)
            .put("device_type", if (host.device == DEVICE_WRIST) "WRIST" else "GREATWHITE")
            .put("design_system_version", host.designSystem)
            .put("timezone", TimeZone.getDefault().id)
            .put("request_id", host.requestId)
            .put("assistant_locale", Locale.getDefault().toString())
            .put("display_locale", Locale.getDefault().toString())
        here()?.let { (lat, lon) -> vars.put("latitude", lat).put("longitude", lon) }
        if (host.persist) {
            vars.put("enabled_glanceables", JSONArray(host.cards.filter { it.enabled }.map { JSONObject().put("id", it.id).put("name", it.name).put("cached_hash", it.hash) }))
        }
        val answer = login.get().graphql("XFBGlanceablesQuery", GLANCEABLES_DOC, vars.toString())
        val json = JSONObject(answer)
        json.optJSONArray("errors")?.let { error(it.optJSONObject(0)?.optString("message") ?: "Meta refused it") }
        val data = json.optJSONObject("data") ?: error(json.optJSONObject("error")?.optString("message") ?: answer.take(300))
        return data.getJSONObject("xfb_glanceables")
    }

    /** The phone's last known place, for the weather; none without the permission. */
    @SuppressLint("MissingPermission")
    private fun here(): Pair<Double, Double>? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        // Two decimals, as the Display itself sends them: a kilometre is plenty for the weather.
        return Math.round(best.latitude * 100) / 100.0 to Math.round(best.longitude * 100) / 100.0
    }

    private class Card(val id: String, val name: String, val enabled: Boolean, val hash: String)

    /** OpenPanel 2 HostInfo: 1 device, 4 Bloks version, 5 design system, 7 panel configs, 8 persist them, 9 request id. */
    private class Host(val device: Long, val versioningId: String, val designSystem: String, val cards: List<Card>, val persist: Boolean, val requestId: String) {
        companion object {
            fun decode(open: Wire.Fields): Host {
                val h = Wire.parse(open.bytes(2))
                val cards = h.repeatedBytes(7).map { Wire.parse(it) }.filter { it.varint(5) == BRIEFING.toLong() }.map {
                    Card(it.bytes(1).decodeToString(), it.bytes(2).decodeToString(), it.varint(4) != 0L, it.bytes(6).decodeToString())
                }
                return Host(h.varint(1), h.bytes(4).decodeToString(), h.bytes(5).decodeToString(), cards, h.varint(8) != 0L, h.bytes(9).decodeToString())
            }
        }
    }

    companion object {
        const val SVC = 9002
        private const val MT_OPEN = 0
        private const val MT_GET_ENABLED = 1
        private const val MT_ENABLED = 2
        private const val MT_PREWARM = 3
        private const val MT_PREWARMED = 4
        private const val BRIEFING = 30
        private const val TTL_S = 3600L
        private const val RENDERER_BLOKS = 2L
        private const val PROVIDER_DATAX = 1L
        private const val CACHE_ENABLED = 1L
        private const val COMMAND_CREATE = 0L
        private const val COMMAND_ERROR = 3L
        private const val WIDGET_DATAPAYLOAD = 5L
        private const val OS_ANDROID = 1L
        private const val DEVICE_WRIST = 2L
        private const val GLANCEABLES_DOC = "168198258811666417495687582669"
        private const val SOURCE_META = -2
    }
}
