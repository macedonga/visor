package com.macedonga.visor.connection

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LatLng(val lat: Double, val lng: Double)

/** The Display's maneuver directions (the phone's D42 enum). */
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

/** One maneuver. [metres] and [seconds] are the leg that leads into it; [along] is where it starts, in metres. */
class Step(val direction: Int, val street: String, val metres: Int, val seconds: Int, val along: Double, val text: String)

/** A snapped point on the line: where it is, the index of its segment, and metres from the start. */
class Snap(val at: LatLng, val index: Int, val along: Double, val off: Double)

/** A route from OSRM. [encoded] is the polyline as OSRM gives it (precision 5), passed on unchanged. */
class Route(val encoded: String, val line: List<LatLng>, val steps: List<Step>, val metres: Double, val seconds: Double) {
    /** Metres from the start to each point of [line], scaled to the route total OSRM gives. */
    private val along = DoubleArray(line.size).also { a ->
        for (i in 1 until line.size) a[i] = a[i - 1] + haversine(line[i - 1], line[i])
        val total = a.lastOrNull() ?: 0.0
        if (total > 0) for (i in a.indices) a[i] *= metres / total
    }

    val secondsPerMetre: Double get() = if (metres > 0) seconds / metres else 0.0

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
class OsrmRouter @Inject constructor() {
    /** Throws when the network fails or the server finds no route. */
    suspend fun route(from: LatLng, to: LatLng, mode: Mode = Mode.WALK): Route = withContext(Dispatchers.IO) {
        val url = URL(
            "https://routing.openstreetmap.de/routed-${mode.path}/route/v1/${mode.profile}/" +
                "${from.lng},${from.lat};${to.lng},${to.lat}?steps=true&overview=full&geometries=polyline",
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", "Visor")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (conn.responseCode != 200) throw IOException("routing server answered ${conn.responseCode}")
            parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): Route {
        val json = JSONObject(body)
        if (json.optString("code") != "Ok") throw IOException(json.optString("code", "no route"))
        val r = json.getJSONArray("routes").getJSONObject(0)
        val steps = r.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        val out = ArrayList<Step>()
        var along = 0.0
        var legMetres = 0.0
        var legSeconds = 0.0
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            val m = s.getJSONObject("maneuver")
            val name = s.optString("name")
            val dir = direction(m.optString("type"), m.optString("modifier"))
            out += Step(dir, name, legMetres.roundToInt(), legSeconds.roundToInt(), along, words(dir, name))
            legMetres = s.getDouble("distance")
            legSeconds = s.getDouble("duration")
            along += legMetres
        }
        val encoded = r.getString("geometry")
        return Route(encoded, decodePolyline(encoded), out, r.getDouble("distance"), r.getDouble("duration"))
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
        val onto = if (street.isBlank()) "" else " onto $street"
        return when (dir) {
            Dir.DEPART -> if (street.isBlank()) "Start" else "Start on $street"
            Dir.ARRIVE -> "You have arrived"
            Dir.ARRIVE_LEFT -> "You have arrived, on the left"
            Dir.ARRIVE_RIGHT -> "You have arrived, on the right"
            Dir.SHARP_LEFT -> "Turn sharp left$onto"
            Dir.LEFT -> "Turn left$onto"
            Dir.SLIGHT_LEFT -> "Bear left$onto"
            Dir.SLIGHT_RIGHT -> "Bear right$onto"
            Dir.RIGHT -> "Turn right$onto"
            Dir.SHARP_RIGHT -> "Turn sharp right$onto"
            Dir.U_TURN -> "Make a U-turn"
            Dir.LEFT_FORK -> "Keep left$onto"
            Dir.RIGHT_FORK -> "Keep right$onto"
            Dir.MIDDLE_FORK -> "Go through the junction$onto"
            else -> if (street.isBlank()) "Continue straight" else "Continue on $street"
        }
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

fun haversine(a: LatLng, b: LatLng): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}

private const val EARTH_RADIUS_M = 6_371_000.0
private const val METRES_PER_DEGREE = 111_320.0
