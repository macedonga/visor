package com.macedonga.visor.connection

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.protocol.SVC_LEASE
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.SettingsState
import com.macedonga.visor.core.settings.ProtoField
import com.macedonga.visor.core.settings.asBool
import com.macedonga.visor.core.settings.encodeProto
import com.macedonga.visor.core.settings.parseProto
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

/** A web app on the glasses: its partner_app_settings key, title, page and icon. */
data class WebApp(val key: String, val name: String, val url: String, val icon: String = "")

enum class AddResult { ADDED, EXISTS, NOT_HTTPS, FULL, NOT_CONNECTED, UNREADABLE }

/** svc55074, the Display's web apps in partner_app_settings; Visor changes only the entries it adds, removes or renames. */
@Singleton
class WebApps @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: Provider<GlassesConnection>,
    private val settings: Provider<GlassesSettings>,
    private val log: EventLog,
) : Responder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val apps: StateFlow<List<WebApp>> by lazy {
        settings.get().state.map { appsIn(inForce(it)) }.stateIn(scope, SharingStarted.Eagerly, appsIn(inForce(settings.get().state.value)))
    }
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
            else -> log.add("Web apps: type ${message.info.messageType} not served")
        }
    }

    /** Add a page, from the Display's CREATE or the Add sheet, with Meta's [manifest] (base64) for a catalogue app. */
    fun add(name: String, url: String, manifest: String? = null): AddResult {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host.isNullOrEmpty()) return AddResult.NOT_HTTPS
        synchronized(lock) {
            val fields = current() ?: return if (ready()) AddResult.UNREADABLE else AddResult.NOT_CONNECTED
            val list = apps(fields)
            if (list.any { it.url == url }) return AddResult.EXISTS
            if (list.size >= MAX_APPS) return AddResult.FULL
            val keys = fields.mapNotNull(::entryKey).toSet()
            val key = generateSequence(0) { it + 1 }.map { "$PREFIX$it" }.first { it !in keys }
            // DisplayConfig {1 item {1 title}, 2 url, 3 signedWebAppManifest}: the manifest goes in as the catalogue sent it.
            val config = listOfNotNull(
                message(1, listOf(text(1, name.ifEmpty { uri.host.orEmpty() }))),
                text(2, url),
                manifest?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }?.let { ProtoField(3, 2, bytes = it) },
            )
            if (!write(fields + message(DISPLAY_CONFIGS, listOf(text(1, key), message(2, config))))) return AddResult.NOT_CONNECTED
            val s = settings.get()
            if (!asBool(s.value(DEVELOPER_MODE)) && !s.write(DEVELOPER_MODE, "true")) log.add("Web apps: developer mode did not go on")
        }
        log.add("Web apps: added ${uri.host}")
        return AddResult.ADDED
    }

    fun remove(key: String): Boolean = synchronized(lock) {
        val fields = current() ?: return false
        val rest = fields.filterNot { it.number == DISPLAY_CONFIGS && entryKey(it) == key }
        rest.size != fields.size && write(rest)
    }

    private fun rename(key: String, name: String): Boolean = synchronized(lock) {
        val fields = current() ?: return false
        if (name.isEmpty() || fields.none { entryKey(it) == key }) return false
        write(fields.map { f -> if (entryKey(f) == key) renamed(f, name) else f })
    }

    private fun ready() = connection.get().state.value.link == LinkState.READY && settings.get().state.value.readAt != null

    /** The setting as it is now, read into fields; null when it cannot be changed safely (not connected, unreadable). */
    private fun current(): List<ProtoField>? {
        if (!ready()) return null
        val value = inForce(settings.get().state.value)
        if (value.isEmpty()) return emptyList()
        return runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull()?.let(::parseProto)
    }

    /** The value written last if the glasses have not confirmed it yet, else what they hold. */
    private fun inForce(state: SettingsState): String = state.pending[SETTING] ?: state.stored[SETTING].orEmpty()

    private fun write(fields: List<ProtoField>): Boolean {
        val sent = settings.get().write(SETTING, Base64.encodeToString(encodeProto(fields), Base64.NO_WRAP))
        if (!sent) log.add("Web apps: the list did not go to the glasses")
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
            if (bound.await() == null) log.add("Web apps: the browser's service did not report bound; sending OPEN anyway")
        }
        delay(REGISTER_MS)
        val request = Wire.fieldString(1, UUID.randomUUID().toString()) +
            Wire.fieldVarint(2, ACTION_OPEN) +
            Wire.fieldString(3, app.name) +
            Wire.fieldString(4, app.url)
        glasses.submit { mux -> mux.send(mux.openChannel(SVC), request, messageType = MT_MANAGE, serviceId = SVC) }
        log.add("Web apps: OPEN sent for ${app.name}")
        return true
    }

    private fun manage(f: Wire.Fields): ByteArray {
        val id = f.bytes(1)
        fun answer(error: String? = null, apps: List<WebApp> = emptyList()): ByteArray =
            Wire.fieldBytes(1, id) +
                Wire.fieldVarint(2, if (error == null) 1L else 0L) +
                (error?.let { Wire.fieldString(3, it) } ?: ByteArray(0)) +
                apps.fold(ByteArray(0)) { out, app -> out + Wire.fieldBytes(4, info(app)) }
        val key = f.bytes(5).decodeToString()
        val name = f.bytes(3).decodeToString()
        return when (f.varint(2)) {
            ACTION_CREATE -> when (add(name, f.bytes(4).decodeToString())) {
                AddResult.ADDED, AddResult.EXISTS -> answer()
                AddResult.NOT_HTTPS -> answer(context.getString(R.string.webapps_not_https))
                AddResult.FULL -> answer(context.getString(R.string.webapps_full))
                AddResult.NOT_CONNECTED, AddResult.UNREADABLE -> answer(context.getString(R.string.webapps_unreadable))
            }
            ACTION_DELETE -> if (remove(key)) answer() else answer(context.getString(R.string.webapps_err_missing))
            ACTION_LIST -> answer(apps = apps.value)
            ACTION_UPDATE -> if (rename(key, name)) answer() else answer(context.getString(R.string.webapps_err_missing))
            // OPEN goes from the phone to the glasses only.
            ACTION_OPEN -> answer(context.getString(R.string.webapps_err_open))
            // Developer mode goes on with the first web app Visor adds.
            ACTION_DEV_MODE_STATUS -> answer()
            else -> answer(context.getString(R.string.webapps_err_unknown))
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
                Wire.fieldString(1, name) + Wire.fieldString(2, value) +
                    Wire.fieldString(3, host) + Wire.fieldString(4, "/"),
            )
        }
        return Wire.fieldBytes(1, f.bytes(1)) + Wire.fieldString(2, url) + cookies
    }

    /** WebappAppInfo {1 partnerKey, 2 appName, 3 appUrl, 4 iconUrl}. */
    private fun info(app: WebApp): ByteArray =
        Wire.fieldString(1, app.key) + Wire.fieldString(2, app.name) +
            Wire.fieldString(3, app.url) + Wire.fieldString(4, app.icon)

    /** PartnerAppSettings {3 displayConfigs: map entry {1 key, 2 DisplayConfig {1 Item {1 title, 2 icon}, 2 url}}}. */
    private fun appsIn(value: String): List<WebApp> =
        value.takeIf { it.isNotEmpty() }?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }?.let(::parseProto)?.let(::apps).orEmpty()

    private fun apps(fields: List<ProtoField>): List<WebApp> = fields.filter { it.number == DISPLAY_CONFIGS }.mapNotNull { entry ->
        val e = parseProto(entry.bytes) ?: return@mapNotNull null
        val key = e.firstOrNull { it.number == 1 }?.bytes?.decodeToString() ?: return@mapNotNull null
        val config = e.firstOrNull { it.number == 2 }?.bytes?.let(::parseProto) ?: return@mapNotNull null
        val url = config.firstOrNull { it.number == 2 }?.bytes?.decodeToString()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
        val item = config.firstOrNull { it.number == 1 }?.bytes?.let(::parseProto).orEmpty()
        val name = item.firstOrNull { it.number == 1 }?.bytes?.decodeToString().orEmpty()
        WebApp(key, name.ifEmpty { Uri.parse(url).host.orEmpty() }, url, item.firstOrNull { it.number == 2 }?.bytes?.decodeToString().orEmpty())
    }

    /** [entry] with its item's title set to [name]; every other field kept as it was. */
    private fun renamed(entry: ProtoField, name: String): ProtoField {
        val e = parseProto(entry.bytes) ?: return entry
        return message(DISPLAY_CONFIGS, e.map { f ->
            val config = if (f.number == 2) parseProto(f.bytes) else null
            if (config == null) f else message(2, config.map { c ->
                val item = if (c.number == 1) parseProto(c.bytes) else null
                if (item == null) c else message(1, listOf(text(1, name)) + item.filterNot { it.number == 1 })
            })
        })
    }

    private fun entryKey(entry: ProtoField): String? =
        entry.takeIf { it.number == DISPLAY_CONFIGS }?.let { parseProto(it.bytes) }?.firstOrNull { it.number == 1 }?.bytes?.decodeToString()

    private fun text(number: Int, value: String) = ProtoField(number, 2, bytes = value.encodeToByteArray())

    private fun message(number: Int, fields: List<ProtoField>) = ProtoField(number, 2, bytes = encodeProto(fields))

    companion object {
        const val SVC = 55074
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
    }
}
