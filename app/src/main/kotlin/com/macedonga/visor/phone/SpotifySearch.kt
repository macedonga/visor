package com.macedonga.visor.phone

import android.content.Context
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A Spotify track: the uri that opens it, and its name. */
data class SpotifyTrack(val uri: String, val name: String)

/** Finds a track through Spotify's Web API with the wearer's own app's Client ID and secret; no user login. */
@Singleton
class SpotifySearch @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("spotify", Context.MODE_PRIVATE)

    @Volatile private var token: String? = null
    @Volatile private var tokenUntil = 0L

    var clientId: String
        get() = prefs.getString(ID, "").orEmpty()
        set(value) {
            prefs.edit().putString(ID, value.trim()).apply()
            token = null
        }

    var clientSecret: String
        get() = prefs.getString(SECRET, "").orEmpty()
        set(value) {
            prefs.edit().putString(SECRET, value.trim()).apply()
            token = null
        }

    val ready: Boolean get() = clientId.isNotEmpty() && clientSecret.isNotEmpty()

    /** The first track for [query], or null; why not goes to [note]. */
    suspend fun trackFor(query: String, note: (String) -> Unit): SpotifyTrack? = withContext(Dispatchers.IO) {
        runCatching {
            val items = request("GET", "$SEARCH?q=${Uri.encode(query)}&type=track&limit=1", "Bearer ${bearer()}")
                .getJSONObject("tracks").getJSONArray("items")
            val track = items.optJSONObject(0)
            if (track == null) {
                note("Spotify has no track for \"$query\"")
                null
            } else {
                SpotifyTrack(track.getString("uri"), track.getString("name"))
            }
        }.onFailure { note("Spotify search failed: ${it.message}") }.getOrNull()
    }

    /** A token for the credentials, kept until it nearly expires. */
    private fun bearer(): String {
        token?.let { if (System.currentTimeMillis() < tokenUntil) return it }
        val basic = Base64.encodeToString("$clientId:$clientSecret".toByteArray(), Base64.NO_WRAP)
        val answer = request("POST", TOKEN, "Basic $basic", "grant_type=client_credentials")
        val fresh = answer.getString("access_token")
        tokenUntil = System.currentTimeMillis() + answer.optLong("expires_in", 3600) * 1000 - EXPIRY_MARGIN_MS
        token = fresh
        return fresh
    }

    private fun request(method: String, url: String, auth: String, form: String? = null): JSONObject {
        val http = URL(url).openConnection() as HttpURLConnection
        try {
            http.requestMethod = method
            http.connectTimeout = TIMEOUT_MS
            http.readTimeout = TIMEOUT_MS
            http.setRequestProperty("Authorization", auth)
            if (form != null) {
                http.doOutput = true
                http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                http.outputStream.use { it.write(form.toByteArray()) }
            }
            val code = http.responseCode
            val text = (if (code in 200..299) http.inputStream else http.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) error("answered $code: ${text.take(200)}")
            return JSONObject(text)
        } finally {
            http.disconnect()
        }
    }

    private companion object {
        const val TOKEN = "https://accounts.spotify.com/api/token"
        const val SEARCH = "https://api.spotify.com/v1/search"
        const val ID = "client_id"
        const val SECRET = "client_secret"
        const val TIMEOUT_MS = 8_000
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}
