package com.macedonga.visor.connection

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.ConfigState
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.ProtoField
import com.macedonga.visor.core.settings.encodeProto
import com.macedonga.visor.core.settings.parseProto
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

data class WebApp(val key: String, val name: String, val url: String)

enum class AddResult { ADDED, EXISTS, NOT_HTTPS, FULL }

/** Whether the glasses open a page from the phone: the two MobileConfig switches the browser checks. */
enum class OpenGate { UNREAD, UNAVAILABLE, OFF, ON }

val OPEN_GATES = listOf("web_on_hn.web_app", "web_on_hn.web_app_connections")

fun gateOf(cfg: ConfigState): OpenGate = when {
    cfg.params.isEmpty() -> OpenGate.UNREAD
    OPEN_GATES.any { it !in cfg.params } -> OpenGate.UNAVAILABLE
    OPEN_GATES.all { cfg.values[it] == true } -> OpenGate.ON
    else -> OpenGate.OFF
}

/**
 * svc55074, the browser's phone service: the Display's web-app manager (type 15: CREATE 1, DELETE 2, LIST 3, UPDATE 4,
 * OPEN 5, DEV MODE STATUS 6, answered with 16), cookies (11, answered with 9) and a BLE scan (17, answered with 20 and
 * no devices). Visor keeps its own list in a file and writes it to the glasses' partner_app_settings, one
 * dev_mode_web_app_<n> entry each, as the Meta app does. [open] sends OPEN to the glasses.
 */
@Singleton
class WebApps @Inject constructor(
    @ApplicationContext context: Context,
    private val connection: Provider<GlassesConnection>,
    private val settings: Provider<GlassesSettings>,
    private val log: Provider<EventLog>,
) : Responder {
    private val file = File(context.filesDir, "web_apps.json")
    private val _apps = MutableStateFlow(load())
    val apps: StateFlow<List<WebApp>> = _apps.asStateFlow()
    private val lock = Any()

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.payload.isEmpty() && message.info.flags.isClosed) return
        val reply = DataXConnection.mirror(message.info.channelId)
        val f = runCatching { Wire.parse(message.payload) }.getOrNull() ?: return
        when (message.info.messageType) {
            MT_MANAGE -> connection.send(reply, manage(f), messageType = MT_MANAGE_RESPONSE)
            MT_GET_COOKIES -> connection.send(reply, cookies(f), messageType = MT_SET_COOKIES)
            // Visor scans for nothing: the page gets an answer with no devices.
            MT_BLE_SCAN_START -> connection.send(reply, Wire.fieldBytes(1, f.bytes(1)), messageType = MT_BLE_SCAN_RESPONSE)
            else -> log.get().add("Web apps: type ${message.info.messageType} not served")
        }
    }

    /** Add a page, from the Display's CREATE or the Add sheet. The glasses take it with [push]. */
    fun add(name: String, url: String): AddResult {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host.isNullOrEmpty()) return AddResult.NOT_HTTPS
        synchronized(lock) {
            val list = _apps.value
            if (list.any { it.url == url }) return AddResult.EXISTS
            if (list.size >= MAX_APPS) return AddResult.FULL
            val key = generateSequence(0) { it + 1 }.map { "$PREFIX$it" }.first { k -> list.none { it.key == k } }
            save(list + WebApp(key, name.ifEmpty { uri.host.orEmpty() }, url))
        }
        log.get().add("Web apps: added ${uri.host}")
        return AddResult.ADDED
    }

    fun remove(key: String): Boolean = synchronized(lock) {
        val list = _apps.value
        val rest = list.filterNot { it.key == key }
        if (rest.size == list.size) return false
        save(rest)
        true
    }

    private fun rename(key: String, name: String): Boolean = synchronized(lock) {
        if (name.isEmpty() || _apps.value.none { it.key == key }) return false
        save(_apps.value.map { if (it.key == key) it.copy(name = name) else it })
        true
    }

    /**
     * Write the list into the glasses' partner_app_settings, with developer mode on. False when the glasses cannot take
     * it now (not connected, or their settings not read yet).
     */
    fun push(): Boolean {
        val glasses = connection.get()
        val s = settings.get()
        if (glasses.state.value.link != LinkState.READY || s.state.value.readAt == null) return false
        val value = Base64.encodeToString(partnerSettings(s.value(SETTING).orEmpty(), _apps.value), Base64.NO_WRAP)
        val sent = s.write(SETTING, value) && s.write(DEVELOPER_MODE, "true")
        if (!sent) log.get().add("Web apps: the list did not go to the glasses")
        return sent
    }

    /** Open [app] on the glasses: a lease on the browser's service (svc56, feature 19), then OPEN on svc55074. */
    suspend fun open(app: WebApp): Boolean {
        val glasses = connection.get()
        if (glasses.state.value.link != LinkState.READY) return false
        coroutineScope {
            val bound = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(BOUND_MS) {
                    glasses.serviceData.first {
                        it.serviceId == SVC_LEASE && it.messageType == MT_LIFECYCLE &&
                            runCatching { Wire.parse(it.payload).varint(2) }.getOrNull() == STATE_BOUND
                    }
                }
            }
            glasses.submit { it.sendOnService(SVC_LEASE, MT_LEASE, Wire.fieldVarint(1, FEATURE_WEB_APP)) }
            if (bound.await() == null) log.get().add("Web apps: the browser's service did not report bound; sending OPEN anyway")
        }
        delay(REGISTER_MS)
        val request = Wire.fieldBytes(1, UUID.randomUUID().toString().encodeToByteArray()) +
            Wire.fieldVarint(2, ACTION_OPEN) +
            Wire.fieldBytes(3, app.name.encodeToByteArray()) +
            Wire.fieldBytes(4, app.url.encodeToByteArray())
        glasses.submit { mux -> mux.send(mux.openChannel(SVC), request, messageType = MT_MANAGE, serviceId = SVC) }
        log.get().add("Web apps: OPEN sent for ${app.name}")
        return true
    }

    private fun manage(f: Wire.Fields): ByteArray {
        val id = f.bytes(1)
        fun answer(error: String? = null, apps: List<WebApp> = emptyList()): ByteArray =
            Wire.fieldBytes(1, id) +
                Wire.fieldVarint(2, if (error == null) 1L else 0L) +
                (error?.let { Wire.fieldBytes(3, it.encodeToByteArray()) } ?: ByteArray(0)) +
                apps.fold(ByteArray(0)) { out, app -> out + Wire.fieldBytes(4, info(app)) }
        val key = f.bytes(5).decodeToString()
        val name = f.bytes(3).decodeToString()
        return when (f.varint(2)) {
            ACTION_CREATE -> when (add(name, f.bytes(4).decodeToString())) {
                AddResult.ADDED, AddResult.EXISTS -> {
                    push()
                    answer()
                }
                AddResult.NOT_HTTPS -> answer("Only https pages can be web apps")
                AddResult.FULL -> answer("Up to $MAX_APPS web apps")
            }
            ACTION_DELETE -> if (remove(key)) { push(); answer() } else answer("No such web app")
            ACTION_LIST -> answer(apps = _apps.value)
            ACTION_UPDATE -> if (rename(key, name)) { push(); answer() } else answer("No such web app")
            // The glasses open pages through the phone, not the other way round.
            ACTION_OPEN -> answer("Web apps open from the phone")
            // Visor keeps the list, so developer mode is always on for it.
            ACTION_DEV_MODE_STATUS -> answer()
            else -> answer("Unknown action")
        }
    }

    /** Get cookies: the phone's own jar for the page's address, as cookies for its host. */
    private fun cookies(f: Wire.Fields): ByteArray {
        val url = f.bytes(2).decodeToString()
        val host = Uri.parse(url).host.orEmpty()
        val jar = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
        val cookies = jar.split(";").map { it.trim().split("=", limit = 2) }.filter { it.size == 2 }.fold(ByteArray(0)) { out, (name, value) ->
            out + Wire.fieldBytes(
                3,
                Wire.fieldBytes(1, name.encodeToByteArray()) + Wire.fieldBytes(2, value.encodeToByteArray()) +
                    Wire.fieldBytes(3, host.encodeToByteArray()) + Wire.fieldBytes(4, PATH_ROOT),
            )
        }
        return Wire.fieldBytes(1, f.bytes(1)) + Wire.fieldBytes(2, url.encodeToByteArray()) + cookies
    }

    /** WebappAppInfo {1 partnerKey, 2 appName, 3 appUrl, 4 iconUrl}. */
    private fun info(app: WebApp): ByteArray =
        Wire.fieldBytes(1, app.key.encodeToByteArray()) + Wire.fieldBytes(2, app.name.encodeToByteArray()) +
            Wire.fieldBytes(3, app.url.encodeToByteArray()) + Wire.fieldBytes(4, ByteArray(0))

    /**
     * The stored PartnerAppSettings with its dev entries replaced by ours. PartnerAppSettings {3 displayConfigs: map
     * entry {1 key, 2 DisplayConfig {1 Item {1 title}, 2 url}}}. Everything else in the setting is kept as it was.
     */
    private fun partnerSettings(current: String, apps: List<WebApp>): ByteArray {
        val old = runCatching { parseProto(Base64.decode(current, Base64.NO_WRAP)) }.getOrNull().orEmpty()
        val kept = old.filterNot { it.number == DISPLAY_CONFIGS && entryKey(it)?.startsWith(PREFIX) == true }
        val ours = apps.map { app ->
            message(DISPLAY_CONFIGS, listOf(text(1, app.key), message(2, listOf(message(1, listOf(text(1, app.name))), text(2, app.url)))))
        }
        return encodeProto(kept + ours)
    }

    private fun entryKey(entry: ProtoField): String? =
        parseProto(entry.bytes)?.firstOrNull { it.number == 1 }?.bytes?.decodeToString()

    private fun text(number: Int, value: String) = ProtoField(number, 2, bytes = value.encodeToByteArray())

    private fun message(number: Int, fields: List<ProtoField>) = ProtoField(number, 2, bytes = encodeProto(fields))

    private fun save(list: List<WebApp>) {
        _apps.value = list
        runCatching {
            file.writeText(JSONArray(list.map { JSONObject().put("key", it.key).put("name", it.name).put("url", it.url) }).toString())
        }
    }

    private fun load(): List<WebApp> = runCatching {
        val a = JSONArray(file.readText())
        List(a.length()) { i -> a.getJSONObject(i).let { WebApp(it.getString("key"), it.getString("name"), it.getString("url")) } }
    }.getOrDefault(emptyList())

    companion object {
        const val SVC = 55074
        private const val SVC_LEASE = 56
        private const val MT_LEASE = 1
        private const val MT_LIFECYCLE = 3
        private const val STATE_BOUND = 1L
        private const val FEATURE_WEB_APP = 19L
        private const val MT_MANAGE = 15
        private const val MT_MANAGE_RESPONSE = 16
        private const val MT_GET_COOKIES = 11
        private const val MT_SET_COOKIES = 9
        private const val MT_BLE_SCAN_START = 17
        private const val MT_BLE_SCAN_RESPONSE = 20
        private const val ACTION_CREATE = 1L
        private const val ACTION_DELETE = 2L
        private const val ACTION_LIST = 3L
        private const val ACTION_UPDATE = 4L
        private const val ACTION_OPEN = 5L
        private const val ACTION_DEV_MODE_STATUS = 6L
        private const val PREFIX = "dev_mode_web_app_"
        private const val SETTING = "partner_app_settings"
        private const val DEVELOPER_MODE = "developer_mode"
        private const val DISPLAY_CONFIGS = 3
        private const val MAX_APPS = 20
        private const val BOUND_MS = 3_000L
        private const val REGISTER_MS = 1_500L
        private val PATH_ROOT = "/".encodeToByteArray()
    }
}
