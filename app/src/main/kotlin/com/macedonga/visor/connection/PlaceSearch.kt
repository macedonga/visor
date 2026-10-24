package com.macedonga.visor.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * svc55072, smartglasses_place_search: the glasses search for a place (PlaceSearchRequest, types 0 and 1 {1 query,
 * 2 radius in metres, 3 search type, 4 requestId, 5 prewarm}) and get PlaceSearchResponse (type 2) with places from
 * OpenStreetMap's Nominatim within the radius around the phone, nearest first. Nominatim reads a kind of place
 * ("cafe", "park") as one, and also finds names and addresses. As the Meta app does, a prewarm is answered at once
 * with no places, and its search fills a five-minute cache for the real request. Media requests (3) get an empty
 * PlaceMediaResponse (4).
 */
@Singleton
class PlaceSearch @Inject constructor(
    private val position: PhonePosition,
    private val requests: RequestLog,
    private val log: Provider<EventLog>,
) : Responder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> log.get().add("Place search: failed: $e") })
    // Nominatim allows one request a second, and the glasses send their prewarms together.
    private val nominatim = Mutex()
    private var lastAsked = 0L
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) = size > CACHE_SIZE
    }

    private class Cached(val at: Long, val places: List<Place>)

    private class Place(
        val id: String,
        val name: String,
        val at: LatLng,
        val street: String,
        val city: String,
        val postcode: String,
        val country: String,
        val category: String,
        val phone: String,
    )

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.flags.isClosed) return
        val reply = DataXConnection.mirror(message.info.channelId)
        when (message.info.messageType) {
            MT_SEARCH, MT_SEARCH_REQUEST -> {
                val req = runCatching { Wire.parse(message.payload) }.getOrNull() ?: return
                val query = req.bytes(1).decodeToString().trim()
                val radius = req.float(2).takeIf { it > 0f }?.toDouble() ?: DEFAULT_RADIUS_M
                if (req.varint(5) != 0L) {
                    connection.send(reply, Wire.fieldVarint(1, RESULT_SUCCESS), messageType = MT_SEARCH_RESPONSE)
                    scope.launch { places(query, radius, here()) }
                    return
                }
                scope.launch { connection.send(reply, answer(query, radius), messageType = MT_SEARCH_RESPONSE) }
            }
            MT_MEDIA_REQUEST -> connection.send(reply, ByteArray(0), messageType = MT_MEDIA_RESPONSE)
        }
    }

    private suspend fun here(): LatLng? = position.current() ?: position.fresh()

    private suspend fun answer(query: String, radius: Double): ByteArray {
        val logId = requests.start("place://$query", SOURCE_MAPS)
        val here = here()
        val found = try {
            places(query, radius, here)
        } catch (e: IOException) {
            requests.finish(logId, e.message ?: "search failed", failed = true)
            log.get().add("Place search: \"$query\" failed: ${e.message}")
            return error(e.message ?: "Search failed", ERROR_NETWORK)
        } catch (e: Exception) {
            requests.finish(logId, e.message ?: "search failed", failed = true)
            log.get().add("Place search: \"$query\" failed: $e")
            return error(e.message ?: "Search failed", ERROR_INTERNAL)
        }
        requests.finish(logId, "${found.size} places", failed = false)
        log.get().add("Place search: \"$query\", ${found.size} places" + if (here == null) ", without the phone's position" else "")
        var body = Wire.fieldVarint(1, RESULT_SUCCESS)
        for (p in found) body += Wire.fieldBytes(2, encode(p, here))
        return body
    }

    /** From the cache when the same search near the same spot ran in the last five minutes. */
    private suspend fun places(query: String, radius: Double, here: LatLng?): List<Place> {
        if (query.isEmpty()) return emptyList()
        val key = query.lowercase(Locale.ROOT) + "@" + (here?.let { "%.3f,%.3f".format(Locale.ROOT, it.lat, it.lng) } ?: "")
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[key]?.takeIf { now - it.at < CACHE_MS }?.let { return it.places } }
        val found = if (here == null) {
            search(query, null, bounded = false)
        } else {
            // A name farther than the radius is still found, the nearest first.
            val box = box(here, radius)
            search(query, box, bounded = true).ifEmpty { search(query, box, bounded = false) }.sortedBy { haversine(here, it.at) }
        }
        synchronized(cache) { cache[key] = Cached(now, found) }
        return found
    }

    /** The viewbox Nominatim takes: west, north, east, south. */
    private fun box(here: LatLng, radius: Double): String {
        val r = radius.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
        val dLat = r / METRES_PER_DEGREE
        val dLng = r / (METRES_PER_DEGREE * cos(Math.toRadians(here.lat)).coerceAtLeast(MIN_COS))
        return listOf(here.lng - dLng, here.lat + dLat, here.lng + dLng, here.lat - dLat).joinToString(",") { "%.5f".format(Locale.ROOT, it) }
    }

    private suspend fun search(query: String, viewbox: String?, bounded: Boolean): List<Place> = nominatim.withLock {
        val wait = lastAsked + SPACING_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        val url = StringBuilder(NOMINATIM)
            .append("?format=jsonv2&addressdetails=1&extratags=1&limit=").append(MAX_RESULTS)
            .append("&accept-language=").append(Locale.getDefault().toLanguageTag())
            .append("&q=").append(URLEncoder.encode(query, "UTF-8"))
        if (viewbox != null) url.append("&viewbox=").append(viewbox).append(if (bounded) "&bounded=1" else "")
        try {
            val results = JSONArray(fetch(url.toString()))
            (0 until results.length()).mapNotNull { place(results.getJSONObject(it)) }
        } finally {
            lastAsked = System.currentTimeMillis()
        }
    }

    private fun place(o: JSONObject): Place? {
        val a = o.optJSONObject("address") ?: JSONObject()
        val extra = o.optJSONObject("extratags") ?: JSONObject()
        val street = listOf(a.optString("road"), a.optString("house_number")).filter { it.isNotBlank() }.joinToString(" ")
        val name = o.optString("name").ifBlank { street }.ifBlank { o.optString("display_name").substringBefore(',') }.ifBlank { return null }
        val lat = o.optString("lat").toDoubleOrNull() ?: return null
        val lng = o.optString("lon").toDoubleOrNull() ?: return null
        return Place(
            id = "osm:${o.optString("osm_type")}/${o.optLong("osm_id")}",
            name = name,
            at = LatLng(lat, lng),
            street = street,
            city = CITY_KEYS.firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotBlank() } }.orEmpty(),
            postcode = a.optString("postcode"),
            country = a.optString("country"),
            category = o.optString("type").takeIf { o.optString("category") in KIND_KEYS }?.let(::label).orEmpty(),
            phone = extra.optString("phone").ifBlank { extra.optString("contact:phone") },
        )
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            // Nominatim's policy asks for a user agent that names the app.
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (conn.responseCode != 200) throw IOException("${conn.url.host} answered ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** PlaceSearchResult {1 id, 2 title, 3 Address, 4 GeoCoordinate, 8 category, 13 walking, 14 phone}. */
    private fun encode(p: Place, here: LatLng?): ByteArray {
        val address = listOf(1 to p.street, 2 to p.city, 4 to p.country, 5 to p.postcode)
            .filter { it.second.isNotBlank() }
            .fold(ByteArray(0)) { b, (field, value) -> b + Wire.fieldBytes(field, value.encodeToByteArray()) }
        var b = Wire.fieldBytes(1, p.id.encodeToByteArray()) +
            Wire.fieldBytes(2, p.name.encodeToByteArray()) +
            Wire.fieldBytes(3, address) +
            Wire.fieldBytes(4, Wire.fieldDouble(1, p.at.lat) + Wire.fieldDouble(2, p.at.lng))
        if (p.category.isNotEmpty()) b += Wire.fieldBytes(8, p.category.encodeToByteArray())
        if (here != null) {
            val metres = haversine(here, p.at)
            b += Wire.fieldBytes(13, Wire.fieldVarint(1, metres.roundToLong()) + Wire.fieldVarint(2, (metres / WALK_MPS).roundToLong()))
        }
        if (p.phone.isNotEmpty()) b += Wire.fieldBytes(14, p.phone.encodeToByteArray())
        return b
    }

    private fun error(text: String, code: Long): ByteArray =
        Wire.fieldVarint(1, RESULT_ERROR) + Wire.fieldBytes(3, text.encodeToByteArray()) + Wire.fieldVarint(4, code)

    companion object {
        const val SVC = 55072
        private const val SOURCE_MAPS = -6
        private const val MT_SEARCH = 0
        private const val MT_SEARCH_REQUEST = 1
        private const val MT_SEARCH_RESPONSE = 2
        private const val MT_MEDIA_REQUEST = 3
        private const val MT_MEDIA_RESPONSE = 4
        private const val RESULT_SUCCESS = 0L
        private const val RESULT_ERROR = 1L
        private const val ERROR_INTERNAL = 0L
        private const val ERROR_NETWORK = 1L
        private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
        private const val USER_AGENT = "Visor (https://github.com/macedonga/visor)"
        private const val SPACING_MS = 1_100L
        private const val DEFAULT_RADIUS_M = 1_500.0
        private const val MIN_RADIUS_M = 300.0
        private const val MAX_RADIUS_M = 20_000.0
        private const val METRES_PER_DEGREE = 111_320.0
        private const val MIN_COS = 0.01
        private const val MAX_RESULTS = 10
        private const val CACHE_MS = 5 * 60_000L
        private const val CACHE_SIZE = 10
        private const val TIMEOUT_MS = 12_000
        private const val WALK_MPS = 1.3

        // The kinds of place whose type is worth showing as the place's category.
        private val KIND_KEYS = setOf("amenity", "shop", "tourism", "leisure", "historic")
        private val CITY_KEYS = listOf("city", "town", "village", "suburb")

        private fun label(value: String): String = value.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
