package com.macedonga.visor.connection

import android.content.Context
import com.macedonga.visor.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LatLng(val lat: Double, val lng: Double)

/** The Display's maneuver directions. */
object Dir {
    const val DEPART = 0
    const val ARRIVE = 1
    const val SHARP_LEFT = 2
    const val LEFT = 3
    const val SLIGHT_LEFT = 4
    const val CONTINUE = 5
    const val SLIGHT_RIGHT = 6
    const val RIGHT = 7
    const val SHARP_RIGHT = 8
    const val LEFT_FORK = 9
    const val MIDDLE_FORK = 10
    const val RIGHT_FORK = 11
    const val U_TURN = 12
    const val ARRIVE_LEFT = 13
    const val ARRIVE_RIGHT = 14
}

/**
 * One maneuver. [metres] and [seconds] are the leg that leads into it; [along] is where it starts, in metres, and
 * [index] the point of the line it starts at. [action] is [text] without the street.
 */
class Step(
    val direction: Int,
    val street: String,
    val metres: Int,
    val seconds: Int,
    val along: Double,
    val text: String,
    val action: String,
    val index: Int,
)

/** A snapped point on the line: where it is, the index of its segment, and metres from the start. */
class Snap(val at: LatLng, val index: Int, val along: Double, val off: Double) {
    val offRoute: Boolean get() = off > OFF_ROUTE_M
}

/** A route from OSRM. [encoded] is the polyline as OSRM gives it (precision 5), passed on unchanged. */
class Route(val encoded: String, val line: List<LatLng>, val steps: List<Step>, val metres: Double, val seconds: Double) {
    /** Metres from the start to each point of [line], scaled to the route total OSRM gives. */
    private val along = DoubleArray(line.size).also { a ->
        for (i in 1 until line.size) a[i] = a[i - 1] + haversine(line[i - 1], line[i])
        val total = a.lastOrNull() ?: 0.0
        if (total > 0) for (i in a.indices) a[i] *= metres / total
    }

    val secondsPerMetre: Double get() = if (metres > 0) seconds / metres else 0.0

    val km: Double get() = (metres / 100).roundToLong() / 10.0

    fun arrived(at: Double): Boolean = metres - at < ARRIVE_M

    /** The maneuver ahead of [at] metres along, else the last. */
    fun nextStep(at: Double): Int = steps.indexOfFirst { at < it.along + PASSED_M }.takeIf { it >= 0 } ?: steps.lastIndex

    /** The point of the line nearest [p], on a flat map around [p], which is exact enough at route scale. */
    fun snap(p: LatLng): Snap {
        val kx = cos(Math.toRadians(p.lat)) * METRES_PER_DEGREE
        var best = Snap(line[0], 0, 0.0, Double.MAX_VALUE)
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val ax = (a.lng - p.lng) * kx
            val ay = (a.lat - p.lat) * METRES_PER_DEGREE
            val dx = (b.lng - p.lng) * kx - ax
            val dy = (b.lat - p.lat) * METRES_PER_DEGREE - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val off = hypot(ax + t * dx, ay + t * dy)
            if (off < best.off) {
                best = Snap(
                    LatLng(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng)),
                    i,
                    along[i] + t * (along[i + 1] - along[i]),
                    off,
                )
            }
        }
        return best
    }
}

enum class Mode(val path: String, val profile: String) {
    WALK("foot", "foot"),
    BIKE("bike", "bike"),
    CAR("car", "driving"),
}

/** Routes from the OpenStreetMap community's public OSRM server. */
@Singleton
class OsrmRouter @Inject constructor(@ApplicationContext private val context: Context) {
    /** Throws when the network fails or the server finds no route. */
    suspend fun route(from: LatLng, to: LatLng, mode: Mode = Mode.WALK): Route = parse(
        httpGet(
            "https://routing.openstreetmap.de/routed-${mode.path}/route/v1/${mode.profile}/" +
                "${from.lng},${from.lat};${to.lng},${to.lat}?steps=true&overview=full&geometries=polyline",
            TIMEOUT_MS,
        ),
    )

    private fun parse(body: String): Route {
        val json = JSONObject(body)
        if (json.optString("code") != "Ok") throw IOException(json.optString("code", "no route"))
        val r = json.getJSONArray("routes").getJSONObject(0)
        val steps = r.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        val encoded = r.getString("geometry")
        val line = decodePolyline(encoded)
        val out = ArrayList<Step>()
        var along = 0.0
        var legMetres = 0.0
        var legSeconds = 0.0
        var index = 0
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            val m = s.getJSONObject("maneuver")
            val name = s.optString("name")
            val dir = direction(m.optString("type"), m.optString("modifier"))
            val at = m.optJSONArray("location")
            if (at != null) index = nearest(line, LatLng(at.getDouble(1), at.getDouble(0)), index, legMetres)
            out += Step(dir, name, legMetres.roundToInt(), legSeconds.roundToInt(), along, words(dir, name), words(dir, ""), index)
            legMetres = s.getDouble("distance")
            legSeconds = s.getDouble("duration")
            along += legMetres
        }
        return Route(encoded, line, out, r.getDouble("distance"), r.getDouble("duration"))
    }

    // The point of [line] nearest [p], from [from] over about [metres] of line, so a route that comes back is not matched early.
    private fun nearest(line: List<LatLng>, p: LatLng, from: Int, metres: Double): Int {
        var best = from
        var bestOff = Double.MAX_VALUE
        var walked = 0.0
        for (j in from until line.size) {
            if (j > from) walked += haversine(line[j - 1], line[j])
            if (walked > metres * 1.1 + 50) break
            val off = haversine(line[j], p)
            if (off < bestOff) {
                bestOff = off
                best = j
            }
        }
        return best
    }

    private fun direction(type: String, modifier: String): Int {
        val side = when (modifier) {
            "sharp left" -> Dir.SHARP_LEFT
            "left" -> Dir.LEFT
            "slight left" -> Dir.SLIGHT_LEFT
            "slight right" -> Dir.SLIGHT_RIGHT
            "right" -> Dir.RIGHT
            "sharp right" -> Dir.SHARP_RIGHT
            "uturn" -> Dir.U_TURN
            else -> Dir.CONTINUE
        }
        val leftish = modifier.contains("left")
        val rightish = modifier.contains("right")
        return when (type) {
            "depart" -> Dir.DEPART
            "arrive" -> when {
                leftish -> Dir.ARRIVE_LEFT
                rightish -> Dir.ARRIVE_RIGHT
                else -> Dir.ARRIVE
            }
            "fork", "end of road" -> when {
                leftish -> Dir.LEFT_FORK
                rightish -> Dir.RIGHT_FORK
                else -> if (type == "fork") Dir.MIDDLE_FORK else side
            }
            "merge", "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> Dir.MIDDLE_FORK
            "on ramp", "off ramp" -> when {
                leftish -> Dir.LEFT_FORK
                rightish -> Dir.RIGHT_FORK
                else -> Dir.CONTINUE
            }
            else -> side
        }
    }

    private fun words(dir: Int, street: String): String {
        val (bare, named) = when (dir) {
            Dir.DEPART -> R.string.nav_start to R.string.nav_start_on
            Dir.ARRIVE -> return context.getString(R.string.nav_arrived)
            Dir.ARRIVE_LEFT -> return context.getString(R.string.nav_arrived_left)
            Dir.ARRIVE_RIGHT -> return context.getString(R.string.nav_arrived_right)
            Dir.SHARP_LEFT -> R.string.nav_sharp_left to R.string.nav_sharp_left_onto
            Dir.LEFT -> R.string.nav_left to R.string.nav_left_onto
            Dir.SLIGHT_LEFT -> R.string.nav_slight_left to R.string.nav_slight_left_onto
            Dir.SLIGHT_RIGHT -> R.string.nav_slight_right to R.string.nav_slight_right_onto
            Dir.RIGHT -> R.string.nav_right to R.string.nav_right_onto
            Dir.SHARP_RIGHT -> R.string.nav_sharp_right to R.string.nav_sharp_right_onto
            Dir.U_TURN -> return context.getString(R.string.nav_u_turn)
            Dir.LEFT_FORK -> R.string.nav_keep_left to R.string.nav_keep_left_onto
            Dir.RIGHT_FORK -> R.string.nav_keep_right to R.string.nav_keep_right_onto
            Dir.MIDDLE_FORK -> R.string.nav_junction to R.string.nav_junction_onto
            else -> R.string.nav_continue to R.string.nav_continue_on
        }
        return if (street.isBlank()) context.getString(bare) else context.getString(named, street)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}

/** Google's encoded polyline, precision 5. */
fun decodePolyline(s: String): List<LatLng> {
    val out = ArrayList<LatLng>()
    var i = 0
    var lat = 0
    var lng = 0
    fun value(): Int {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = s[i++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        return if ((result and 1) != 0) (result shr 1).inv() else result shr 1
    }
    while (i < s.length) {
        lat += value()
        lng += value()
        out += LatLng(lat / 1e5, lng / 1e5)
    }
    return out
}

/** Google's encoded polyline at [precision], 1e5 or 1e6. */
fun encodePolyline(points: List<LatLng>, precision: Double): String {
    val out = StringBuilder()
    var lastLat = 0L
    var lastLng = 0L
    fun put(delta: Long) {
        var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v ushr 5
        }
        out.append((v.toInt() + 63).toChar())
    }
    for (p in points) {
        val lat = Math.round(p.lat * precision)
        val lng = Math.round(p.lng * precision)
        put(lat - lastLat)
        put(lng - lastLng)
        lastLat = lat
        lastLng = lng
    }
    return out.toString()
}

fun haversine(a: LatLng, b: LatLng): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}

// Nominatim's policy asks for a user agent that names the app.
private const val USER_AGENT = "Visor (https://github.com/macedonga/visor)"

/** The body of a 200 answer to a GET of [url]; throws on any other. */
internal suspend fun httpGet(url: String, timeoutMs: Int): String = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        if (conn.responseCode != 200) throw IOException("${conn.url.host} answered ${conn.responseCode}")
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

private const val OFF_ROUTE_M = 50.0
private const val PASSED_M = 15.0
private const val ARRIVE_M = 25.0
private const val EARTH_RADIUS_M = 6_371_000.0
internal const val METRES_PER_DEGREE = 111_320.0
