package com.macedonga.visor.connection

import com.macedonga.visor.core.connection.objects
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.log.responderScope
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** svc55072, place search: Nominatim places around the phone, nearest first; a prewarm gets none at once and fills the cache. */
@Singleton
class PlaceSearch @Inject constructor(
    private val position: PhonePosition,
    private val requests: RequestLog,
    private val log: EventLog,
) : Responder {
    private val scope = responderScope(log, "Place search")
    // Nominatim allows one request a second, and the glasses send their prewarms together.
    private val nominatim = Mutex()
    private var lastAsked = 0L
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) = size > CACHE_SIZE
    }

    private class Cached(val at: Long, val places: Deferred<List<Place>>)

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
        val logId = requests.start("place://$query", RequestSource.MAPS)
        val here = here()
        val found = try {
            places(query, radius, here)
        } catch (e: Exception) {
            val code = if (e is IOException) ERROR_NETWORK else ERROR_INTERNAL
            requests.finish(logId, e.message ?: "search failed", failed = true)
            log.add("Place search: \"$query\" failed: ${e.message ?: e}")
            return error(e.message ?: "Search failed", code)
        }
        requests.finish(logId, "${found.size} places", failed = false)
        log.add("Place search: \"$query\", ${found.size} places" + if (here == null) ", without the phone's position" else "")
        var body = Wire.fieldVarint(1, RESULT_SUCCESS)
        for (p in found) body += Wire.fieldBytes(2, encode(p, here))
        return body
    }

    /** Shared with the same search near the same spot from the last five minutes, finished or still running. */
    private suspend fun places(query: String, radius: Double, here: LatLng?): List<Place> {
        if (query.isEmpty()) return emptyList()
        val key = query.lowercase(Locale.ROOT) + "@" + (here?.let { "%.3f,%.3f".format(Locale.ROOT, it.lat, it.lng) } ?: "")
        val now = System.currentTimeMillis()
        val work = synchronized(cache) {
            cache[key]?.takeIf { now - it.at < CACHE_MS }?.places ?: scope.async { searchNear(query, radius, here) }.also { d ->
                cache[key] = Cached(now, d)
                // A failed search is not kept.
                d.invokeOnCompletion { e -> if (e != null) synchronized(cache) { if (cache[key]?.places === d) cache.remove(key) } }
            }
        }
        return work.await()
    }

    private suspend fun searchNear(query: String, radius: Double, here: LatLng?): List<Place> =
        if (here == null) {
            search(query, null, bounded = false)
        } else {
            // Nominatim ranks by importance, not distance: a wide box gives the city's best known, so the box grows only
            // until the list is full. A name farther than the radius is still found, the nearest first.
            val near = LinkedHashMap<String, Place>()
            for (r in NEAR_M.filter { it < radius } + radius) {
                search(query, box(here, r), bounded = true).forEach { near.putIfAbsent(it.id, it) }
                if (near.size >= MAX_RESULTS) break
            }
            near.values.ifEmpty { search(query, box(here, radius), bounded = false) }
                .sortedBy { haversine(here, it.at) }
                .take(MAX_RESULTS)
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
            val results = JSONArray(httpGet(url.toString(), TIMEOUT_MS))
            results.objects().mapNotNull { place(it) }
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

    /** PlaceSearchResult {1 id, 2 title, 3 Address, 4 GeoCoordinate, 8 category, 13 walking, 14 phone}. */
    private fun encode(p: Place, here: LatLng?): ByteArray {
        val address = listOf(1 to p.street, 2 to p.city, 4 to p.country, 5 to p.postcode)
            .filter { it.second.isNotBlank() }
            .fold(ByteArray(0)) { b, (field, value) -> b + Wire.fieldString(field, value) }
        var b = Wire.fieldString(1, p.id) +
            Wire.fieldString(2, p.name) +
            Wire.fieldBytes(3, address) +
            Wire.fieldBytes(4, Wire.fieldDouble(1, p.at.lat) + Wire.fieldDouble(2, p.at.lng))
        if (p.category.isNotEmpty()) b += Wire.fieldString(8, p.category)
        if (here != null) {
            val metres = haversine(here, p.at)
            b += Wire.fieldBytes(13, Wire.fieldVarint(1, metres.roundToLong()) + Wire.fieldVarint(2, (metres / WALK_MPS).roundToLong()))
        }
        if (p.phone.isNotEmpty()) b += Wire.fieldString(14, p.phone)
        return b
    }

    private fun error(text: String, code: Long): ByteArray =
        Wire.fieldVarint(1, RESULT_ERROR) + Wire.fieldString(3, text) + Wire.fieldVarint(4, code)

    companion object {
        const val SVC = 55072
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
        private const val SPACING_MS = 1_100L
        private const val DEFAULT_RADIUS_M = 1_500.0
        private const val MIN_RADIUS_M = 300.0
        private const val MAX_RADIUS_M = 20_000.0
        private val NEAR_M = listOf(1_000.0, 4_000.0)
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
