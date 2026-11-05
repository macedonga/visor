package com.macedonga.visor.pairs

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.look.GlassesLook
import com.macedonga.visor.core.identity.look.fetchGlassesLook
import com.macedonga.visor.core.identity.look.fetchPairPictures
import com.macedonga.visor.core.identity.token.withToken
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Why the look is not known. */
enum class LookProblem { LOGGED_OUT, NO_RECORD, FAILED }

/** Each pair's look from Meta's records, asked for once and kept; a failure is tried again, no record a day later. */
@Singleton
class Looks @Inject constructor(
    @ApplicationContext context: Context,
    connection: GlassesConnection,
    private val login: MetaLogin,
    private val log: EventLog,
) {
    private val prefs = context.getSharedPreferences("look", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var serial: String? = null
    private val _look = MutableStateFlow<GlassesLook?>(null)
    private val _asking = MutableStateFlow(false)
    private val _problem = MutableStateFlow<LookProblem?>(null)

    /** The look of the glasses Visor is set up for, once known. */
    val look: StateFlow<GlassesLook?> = _look.asStateFlow()
    val asking: StateFlow<Boolean> = _asking.asStateFlow()
    val problem: StateFlow<LookProblem?> = _problem.asStateFlow()

    private val _all = MutableStateFlow(
        prefs.all.mapNotNull { (key, text) -> if (key.startsWith(LOOK) && text is String) decode(text)?.let { key.removePrefix(LOOK) to it } else null }.toMap(),
    )

    /** Every pair's look known so far, by serial: the home shows each pair as it is. */
    val all: StateFlow<Map<String, GlassesLook>> = _all.asStateFlow()

    init {
        scope.launch {
            combine(connection.pair, connection.state.map { it.info?.serial }.distinctUntilChanged(), login.status) { pair, told, _ -> pair ?: told }
                .collect { serial -> if (serial != null) ensure(serial, force = false) }
        }
    }

    /** Ask again now, whatever was answered before. */
    fun retry() {
        val serial = serial ?: return
        scope.launch { ensure(serial, force = true) }
    }

    private val _pictures = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    /** Each pair as Meta pictures it, by serial: only in memory, asked for again at each launch. */
    val pictures: StateFlow<Map<String, ImageBitmap>> = _pictures.asStateFlow()
    private val picturing = Mutex()
    private val askedPictures = mutableSetOf<String>()

    /** Ask Meta once per launch for the pictures of [serials]; a failed query is asked again. */
    fun wantPictures(serials: List<String>) {
        scope.launch {
            picturing.withLock {
                val missing = serials.filter { it !in _pictures.value && it !in askedPictures }
                if (missing.isEmpty() || !login.source.hasCachedLogin) return@withLock
                val where = runCatching { login.source.withToken { fetchPairPictures(it, missing) } }
                    .onFailure { log.add("Pictures: ${it.message}") }.getOrNull() ?: return@withLock
                askedPictures += missing
                for ((serial, picture) in where) {
                    // The app is dark: the picture made for a dark background.
                    val bitmap = runCatching { download(picture.dark ?: picture.light) }.onFailure { log.add("Pictures: $serial: ${it.message}") }.getOrNull()
                    if (bitmap != null) _pictures.value = _pictures.value + (serial to bitmap)
                }
            }
        }
    }

    private fun download(url: String): ImageBitmap {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            val bytes = connection.inputStream.use { it.readBytes() }
            return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "not an image" }.asImageBitmap()
        } finally {
            connection.disconnect()
        }
    }

    /** Debug builds: a demo pair's look and Meta's picture at [picture], in memory only; a null look takes them away. */
    fun demo(serial: String, look: GlassesLook?, picture: String?) {
        if (look == null) {
            _all.value = _all.value - serial
            _pictures.value = _pictures.value - serial
            return
        }
        _all.value = _all.value + (serial to look)
        picture ?: return
        scope.launch {
            runCatching { download(picture) }.onSuccess { _pictures.value = _pictures.value + (serial to it) }
                .onFailure { log.add("Pictures: demo: ${it.message}") }
        }
    }

    /** Ask for another pair's look, if it is not known yet; the current pair's stays as it is. */
    fun want(serial: String) {
        if (serial in _all.value) return
        scope.launch { lock.withLock { fetch(serial, force = false) } }
    }

    private suspend fun ensure(serial: String, force: Boolean) = lock.withLock {
        this.serial = serial
        val kept = _all.value[serial]
        _look.value = kept
        if (kept != null) {
            _problem.value = null
            return@withLock
        }
        _asking.value = true
        val (look, problem) = fetch(serial, force)
        // The pair may have changed while Meta answered.
        if (this.serial == serial) {
            if (look != null) _look.value = look
            _problem.value = problem
        }
        _asking.value = false
    }

    /** [serial]'s look from Meta, or why there is none. */
    private suspend fun fetch(serial: String, force: Boolean): Pair<GlassesLook?, LookProblem?> {
        if (!login.source.hasCachedLogin) return null to LookProblem.LOGGED_OUT
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(NO_RECORD + serial, 0) < RETRY_MS) return null to LookProblem.NO_RECORD
        // Only language and country: Android adds the regional preferences to the default locale's name.
        val locale = Locale.getDefault().let { if (it.country.isEmpty()) "en_US" else "${it.language}_${it.country}" }
        return runCatching { login.source.withToken { fetchGlassesLook(it, serial, locale) } }.fold(
            onSuccess = { look ->
                if (look == null) {
                    log.add("Look: Meta has no record of $serial")
                    prefs.edit().putLong(NO_RECORD + serial, now).apply()
                    null to LookProblem.NO_RECORD
                } else {
                    log.add("Look: ${look.style} ${look.color} ${look.lens}")
                    prefs.edit().putString(LOOK + serial, encode(look)).apply()
                    _all.value = _all.value + (serial to look)
                    look to null
                }
            },
            onFailure = {
                log.add("Look: ${it.message}")
                null to LookProblem.FAILED
            },
        )
    }

    private fun encode(l: GlassesLook) = JSONObject()
        .put("style", l.style).put("styleName", l.styleName).put("color", l.color).put("colorName", l.colorName)
        .put("lens", l.lens).put("lensName", l.lensName).put("size", l.size).toString()

    private fun decode(text: String): GlassesLook? = runCatching {
        val o = JSONObject(text)
        fun s(k: String) = o.optString(k).takeIf { it.isNotEmpty() && o.has(k) && !o.isNull(k) }
        GlassesLook(s("style"), s("styleName"), s("color"), s("colorName"), s("lens"), s("lensName"), s("size"))
    }.getOrNull()

    companion object {
        private const val LOOK = "look_"
        private const val NO_RECORD = "no_record_"
        private const val RETRY_MS = 24 * 60 * 60 * 1000L
    }
}
