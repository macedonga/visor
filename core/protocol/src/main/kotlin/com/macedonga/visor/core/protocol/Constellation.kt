package com.macedonga.visor.core.protocol

import java.io.ByteArrayOutputStream

/** svc11004 messages before compression, version 26, little-endian: 1 and 11 JSON, 2 to 10 syncers led by clock and Timestamp, 0 a piece. */
object Constellation {
    const val VERSION = 26
    const val TYPE_PIECE = 0
    const val TYPE_INIT = 1
    const val TYPE_ROUTE_OPTIONS = 2
    const val TYPE_ROUTE_RESULT = 3
    const val TYPE_LOCATION = 4
    const val TYPE_NAVIGATION_OPTIONS = 5
    const val TYPE_ROUTE_PROGRESS = 6
    const val TYPE_NOTIFICATION = 7
    const val TYPE_ACTIVE_ROUTE = 8
    const val TYPE_NAVIGATOR_STATE = 9
    const val TYPE_OPTIONS = 11
    const val SOURCE_PHONE = 9

    // NavigatorState.
    const val STARTED = 0
    const val ON_ROUTE = 1
    const val OFF_ROUTE = 2
    const val REROUTING = 4
    const val ARRIVED = 5
    const val STOPPED = 6

    // TravelMode.
    const val DRIVING = 0
    const val WALKING = 1
    const val BICYCLING = 2

    private const val NOTIFICATION_MANEUVER = 1
    private const val PRIORITY_HIGH = 1
    private const val ERROR_NO_ROUTE = 1

    private val OPTIONS = listOf(
        "receiveLocation", "receiveRouteOptions", "receiveRouteResult", "receiveNavigationOptions", "receiveNavigatorState",
        "receiveActiveRoute", "receiveRouteProgress", "receiveNotification", "receiveDebugOptions",
    )
    private val WANTED = setOf("receiveRouteOptions", "receiveNavigationOptions")

    data class Stamp(val value: Long, val sub: Long)

    class Geo(val lat: Double, val lng: Double)

    /** A fix: degrees, metres, ns since the epoch, and the optionals Android may give. */
    class Fix(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double?,
        val timeNs: Long,
        val course: Double? = null,
        val speed: Double? = null,
        val horizontalAccuracy: Double? = null,
        val verticalAccuracy: Double? = null,
        val courseAccuracy: Double? = null,
        val speedAccuracy: Double? = null,
    )

    /** Type 2. [options] is the RouteOptions record as the Display wrote it, null when it clears its route. */
    class RouteRequest(val stamp: Stamp, val options: ByteArray?, val waypoints: List<Geo>, val travelMode: Int)

    /** Type 5: a start, on the route the Display picked if it names one, or a stop. */
    class NavigationRequest(val stamp: Stamp, val start: Boolean, val routeId: String?)

    /** [startIndex] and [endIndex] are points of the route's line; [metres] and [seconds] the leg after the turn. */
    class Maneuver(
        val type: Int,
        val spoken: String,
        val full: String,
        val short: String,
        val action: String,
        val street: String?,
        val startIndex: Int,
        val endIndex: Int,
        val metres: Double,
        val seconds: Double,
        val travelMode: Int,
    )

    /** One route of one section. [geometry] is a Google polyline at precision 6. */
    class Route(
        val id: String,
        val options: ByteArray,
        val maneuvers: List<Maneuver>,
        val origin: Geo,
        val destination: Geo,
        val metres: Double,
        val seconds: Double,
        val geometry: String,
        val createdMs: Long,
    )

    class Metrics(val fraction: Double, val travelled: Double, val remaining: Double, val secondsLeft: Double)

    class Progress(
        val routeId: String,
        val route: Metrics,
        val maneuverIndex: Int,
        val maneuver: Metrics,
        val snapped: Fix,
        val snappedIndex: Int,
    )

    /** The init: the versions, and the options that ask for the Display's route requests and trips. */
    fun init(): ByteArray =
        ("{\"min_version\":1,\"current_version\":$VERSION,\"channel_options\":{" +
            OPTIONS.joinToString(",") { "\"$it\":${it in WANTED}" } + "}}").encodeToByteArray()

    /** A CLocation: the fix, then no error. Heading, its accuracy and level are never sent. */
    fun location(clockMs: Long, stamp: Stamp, fix: Fix): ByteArray = head(clockMs, stamp).u8(1).fix(fix).u8(0).bytes()

    /** A CRouteResult for the request of [request]: done, with [route], or with [error]. */
    fun routeResult(clockMs: Long, stamp: Stamp, request: Stamp, route: Route?, error: String?): ByteArray {
        val o = head(clockMs, stamp).stamp(request).u8(0)
        if (route == null) o.u8(0) else o.u8(1).count(1).route(route)
        if (error == null) o.u8(0) else o.u8(1).u32(ERROR_NO_ROUTE).str(error)
        return o.bytes()
    }

    /** A CActiveRoute for the trip of [trip]; no route once it ends. */
    fun activeRoute(clockMs: Long, stamp: Stamp, trip: Stamp, route: Route?): ByteArray {
        val o = head(clockMs, stamp).stamp(trip)
        if (route == null) o.u8(0) else o.route(route)
        return o.bytes()
    }

    /** A CNavigatorState, with no trace id. */
    fun navigatorState(clockMs: Long, stamp: Stamp, trip: Stamp, state: Int): ByteArray =
        head(clockMs, stamp).stamp(trip).u8(0).u32(state).bytes()

    fun routeProgress(clockMs: Long, stamp: Stamp, trip: Stamp, p: Progress): ByteArray =
        head(clockMs, stamp).stamp(trip).u8(1).str(p.routeId)
            .metrics(p.route).u32(0).metrics(p.route)
            .u32(p.maneuverIndex).metrics(p.maneuver)
            .fix(p.snapped).u32(p.snappedIndex).u8(0)
            .bytes()

    /** A CNotification of type MANEUVER. */
    fun maneuverNotice(clockMs: Long, stamp: Stamp, trip: Stamp, m: Maneuver, id: String): ByteArray =
        head(clockMs, stamp).stamp(trip).u32(NOTIFICATION_MANEUVER).u8(1).maneuver(m).u8(0).str(id).bytes()

    /** The Timestamp of any syncer message, readable when the rest is not. */
    fun stampOf(body: ByteArray): Stamp = In(body).run { skip(8); stamp() }

    fun routeRequest(body: ByteArray): RouteRequest {
        val i = In(body)
        i.skip(8)
        val stamp = i.stamp()
        if (i.flag()) i.place()
        if (!i.flag()) return RouteRequest(stamp, null, emptyList(), WALKING)
        val start = i.at
        val waypoints = List(i.count()) { i.waypoint() }
        i.u32()
        val mode = i.u32()
        repeat(6) { i.ostr() }
        // roadNames, reassurance and its distance, requestTimestamp, maxDistance, its short circuit, includeManeuvers,
        // maxRoutes, productSurface, routeTime, a string list, transitVehicles.
        i.u32()
        i.u8()
        i.of64()
        i.skip(16)
        i.skip(2)
        i.u32()
        i.u32()
        if (i.flag()) {
            i.u32()
            if (i.flag()) i.skip(8)
        }
        repeat(i.count()) { i.str() }
        i.skip(4 * i.count())
        return RouteRequest(stamp, body.copyOfRange(start, i.at), waypoints, mode)
    }

    fun navigationRequest(body: ByteArray): NavigationRequest {
        val i = In(body)
        i.skip(8)
        val stamp = i.stamp()
        if (!i.flag()) return NavigationRequest(stamp, false, null)
        // NavigationOptions: seven doubles, the snapping buffer, a request id.
        if (i.flag()) {
            i.skip(7 * 8 + 4)
            i.ostr()
        }
        return NavigationRequest(stamp, true, if (i.flag()) i.str() else null)
    }

    private fun head(clockMs: Long, stamp: Stamp) = Out().u64(clockMs).stamp(stamp)

    private fun Out.fix(f: Fix): Out {
        f64(f.latitude).f64(f.longitude).of64(f.altitude).u64(f.timeNs).u32(SOURCE_PHONE)
        for (v in listOf(f.course, null, f.speed, f.horizontalAccuracy, f.verticalAccuracy, f.courseAccuracy, null, f.speedAccuracy, null)) of64(v)
        return this
    }

    private fun Out.metrics(m: Metrics) = f64(m.fraction).f64(m.travelled).f64(m.remaining).f64(m.secondsLeft)

    private fun Out.waypoint(g: Geo) = f64(g.lat).f64(g.lng).of64(null).of64(null).of64(null).u32(0).ostr(null).ostr(null).ostr(null)

    private fun Out.maneuver(m: Maneuver) =
        u32(m.type).str(m.spoken).str(m.full).str(m.short).str(m.action).ostr(m.street).ostr(m.street)
            .u32(m.startIndex).u32(m.endIndex).ou32(null).f64(m.metres).f64(m.seconds).ou32(m.travelMode).u32(PRIORITY_HIGH)
            .ostr(null).u8(0).u8(0)

    // With its presence byte. No roads, intersections, reassurances, times or alerts; coverage UNKNOWN.
    private fun Out.route(r: Route): Out {
        u8(1).str(r.id).raw(r.options)
        count(1).count(r.maneuvers.size)
        r.maneuvers.forEach { maneuver(it) }
        waypoint(r.origin).waypoint(r.destination).f64(r.metres).f64(r.seconds).ostr(null)
        f64(r.metres).f64(r.seconds).str(r.geometry)
        count(0).count(0).count(0)
        u64(r.createdMs).u32(0).str("")
        return u8(0).u8(0).count(0)
    }

    private class Out {
        private val b = ByteArrayOutputStream()
        fun u8(v: Int) = apply { b.write(v) }
        fun u32(v: Int) = apply { for (i in 0 until 4) b.write(v ushr (8 * i)) }
        fun u64(v: Long) = apply { for (i in 0 until 8) b.write((v ushr (8 * i)).toInt()) }
        fun f64(v: Double) = u64(v.toRawBits())
        fun of64(v: Double?) = if (v == null) u8(0) else u8(1).f64(v)
        fun ou32(v: Int?) = if (v == null) u8(0) else u8(1).u32(v)
        fun raw(bytes: ByteArray) = apply { b.write(bytes, 0, bytes.size) }
        fun stamp(s: Stamp) = u64(s.value).u64(s.sub)

        fun count(n: Int) = apply {
            var v = n
            while (v >= 0x80) {
                b.write(v and 0x7f or 0x80)
                v = v ushr 7
            }
            b.write(v)
        }

        fun str(s: String): Out {
            val bytes = s.encodeToByteArray()
            return count(bytes.size).raw(bytes)
        }

        fun ostr(s: String?) = if (s == null) u8(0) else u8(1).str(s)
        fun bytes(): ByteArray = b.toByteArray()
    }

    /** A reader that throws on a short body. */
    private class In(private val b: ByteArray) {
        var at = 0

        fun u8(): Int {
            if (at >= b.size) throw ProtocolFormatException("short constellation message")
            return b[at++].toInt() and 0xff
        }

        fun flag() = (u8() and 1) != 0

        fun u32(): Int {
            var v = 0
            for (i in 0 until 4) v = v or (u8() shl (8 * i))
            return v
        }

        fun u64(): Long {
            var v = 0L
            for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i))
            return v
        }

        fun f64() = Double.fromBits(u64())
        fun stamp() = Stamp(u64(), u64())

        fun skip(n: Int) {
            if (n < 0 || at + n > b.size) throw ProtocolFormatException("short constellation message")
            at += n
        }

        fun count(): Int {
            var v = 0L
            var shift = 0
            while (true) {
                val x = u8()
                v = v or ((x and 0x7f).toLong() shl shift)
                if (x < 0x80) break
                shift += 7
                if (shift > 35) throw ProtocolFormatException("bad constellation count")
            }
            // Every item takes a byte at least.
            if (v > b.size - at) throw ProtocolFormatException("bad constellation count")
            return v.toInt()
        }

        fun str(): String {
            val n = count()
            return String(b, at, n, Charsets.UTF_8).also { at += n }
        }

        fun ostr() {
            if (flag()) str()
        }

        fun of64() {
            if (flag()) skip(8)
        }

        fun ou32() {
            if (flag()) skip(4)
        }

        fun geo() = Geo(f64(), f64()).also { of64() }

        // Geo, level, course, type, place id, name, address.
        fun waypoint() = geo().also {
            of64()
            of64()
            u32()
            repeat(3) { ostr() }
        }

        // PlacesResultItem, after its presence byte: read past, nothing kept.
        fun place() {
            str()
            str()
            ostr()
            geo()
            repeat(count()) { repeat(3) { str() } }
            if (flag()) geo()
            repeat(count()) {
                u32()
                str()
                ou32()
                ou32()
            }
            ou32()
            if (flag()) {
                of64()
                ou32()
                of64()
                ou32()
            }
            repeat(count()) {
                u32()
                str()
                if (flag()) {
                    u32()
                    str()
                }
            }
            ou32()
            ostr()
            ostr()
            if (flag()) {
                str()
                str()
            }
            if (flag()) repeat(6) { ostr() }
        }
    }
}
