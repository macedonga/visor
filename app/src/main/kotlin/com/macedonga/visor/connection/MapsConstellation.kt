package com.macedonga.visor.connection

import android.content.Context
import android.location.Location
import com.github.luben.zstd.EndDirective
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx
import com.macedonga.visor.R
import com.macedonga.visor.core.protocol.Constellation
import com.macedonga.visor.core.protocol.Constellation.Stamp
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.log.responderScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** svc11004, the Display's Maps: location, routes and trips, on one channel; one flushed, never-ended zstd stream per direction. */
@Singleton
class MapsConstellation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val position: PhonePosition,
    private val router: OsrmRouter,
    private val log: EventLog,
) : Responder {
    private val scope = responderScope(log, "Maps", Dispatchers.Default)

    @Volatile
    private var served: Session? = null

    // The Display takes a Timestamp only above the last one it took, and keeps that one across channels.
    private var stampValue = Long.MIN_VALUE
    private var stampSub = 0L

    // Routes sent lately, so a trip starts on the one the Display names, on this channel or an earlier one.
    private val routes = object : LinkedHashMap<String, Hosted>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Hosted>?) = size > KEPT_ROUTES
    }

    @Volatile
    private var lastRoute: Hosted? = null

    /** True while the Display's Maps follows a trip served here. */
    val guiding: Boolean get() = served?.guiding == true

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.flags.isClosed) return
        val id = message.info.channelId
        val session = served?.takeIf { it.mux === connection && it.id == id } ?: run {
            // Not waited on: the old one may be stuck sending on a link that is gone.
            served?.let { old -> scope.launch { old.lock.withLock { old.end() } } }
            Session(connection, id).also { served = it }
        }
        session.lock.withLock { session.receive(message) }
    }

    override suspend fun closed(channelId: Int, connection: DataXConnection) {
        val session = served?.takeIf { it.mux === connection && it.id == channelId } ?: return
        scope.launch { session.lock.withLock { session.end() } }
    }

    @Synchronized
    private fun stamp(now: Long): Stamp {
        if (now > stampValue) {
            stampValue = now
            stampSub = 0
        } else {
            stampSub++
        }
        return Stamp(stampValue, stampSub)
    }

    private fun remember(h: Hosted) {
        synchronized(routes) { routes[h.record.id] = h }
        lastRoute = h
    }

    private fun recall(id: String?): Hosted? = id?.let { synchronized(routes) { routes[it] } } ?: lastRoute

    /** An OSRM route from [from] to [to] and the record the Display reads, with the request's own [options]. */
    private suspend fun plan(from: LatLng, to: LatLng, travelMode: Int, options: ByteArray): Hosted {
        val mode = when (travelMode) {
            Constellation.DRIVING -> Mode.CAR
            Constellation.BICYCLING -> Mode.BIKE
            else -> Mode.WALK
        }
        val route = router.route(from, to, mode)
        val steps = route.steps
        check(steps.isNotEmpty() && route.line.size >= 2) { "empty route" }
        val maneuvers = steps.mapIndexed { i, s ->
            val next = steps.getOrNull(i + 1)
            Constellation.Maneuver(
                type = MANEUVER_TYPES.getOrElse(s.direction) { 0 },
                spoken = s.text,
                full = s.text,
                short = s.text,
                action = s.action,
                street = s.street.ifBlank { null },
                startIndex = s.index,
                endIndex = next?.index ?: s.index,
                metres = next?.let { it.along - s.along } ?: 0.0,
                seconds = next?.seconds?.toDouble() ?: 0.0,
                travelMode = travelMode,
            )
        }
        val record = Constellation.Route(
            id = UUID.randomUUID().toString(),
            options = options,
            maneuvers = maneuvers,
            origin = route.line.first().geo(),
            destination = route.line.last().geo(),
            metres = route.metres,
            seconds = route.seconds,
            geometry = encodePolyline(route.line, 1e6),
            createdMs = System.currentTimeMillis(),
        )
        return Hosted(route, record, to, mode, travelMode)
    }

    /** A route as the Display has it: OSRM's [route] and its [record], where it ends and how it is travelled. */
    private class Hosted(val route: Route, val record: Constellation.Route, val to: LatLng, val mode: Mode, val travelMode: Int)

    /** A trip: its type 5 Timestamp, which all its messages carry, and the route it follows. */
    private class Trip(val stamp: Stamp, @Volatile var hosted: Hosted) {
        var notified = -1
        var offFixes = 0
        var rerouting = false

        @Volatile
        var arrived = false
    }

    /** One channel: its two zstd streams, the Display's pieces of a message, its location sharing and its trip. */
    private inner class Session(val mux: DataXConnection, val id: Int) {
        val lock = Mutex()
        private val packer = ZstdCompressCtx().setLevel(LEVEL)
        private val unpacker = ZstdDecompressCtx()
        private var pieces = ByteArray(0)
        private var initSent = false
        private var sharing = false
        private var ended = false

        @Volatile
        private var trip: Trip? = null
        private var following = false
        private val follower: (Location) -> Unit = { fix -> scope.launch { lock.withLock { if (sharing) sendLocation(fix) } } }
        private val guide: (Location) -> Unit = { fix -> scope.launch { lock.withLock { progress(fix) } } }

        val guiding: Boolean get() = trip?.arrived == false

        // A link that drops sends no close. Started once assigned, as end() cancels it.
        private val linkWatch: Job = scope.launch(start = CoroutineStart.LAZY) { mux.awaitGone(); lock.withLock { end() } }

        init {
            linkWatch.start()
        }

        /** With [lock] held. Every message goes through the decompressor in order, read or not: later ones refer back. */
        suspend fun receive(message: DataXMessage) {
            if (ended) return
            pieces += message.payload
            val type = message.info.messageType
            if (type == Constellation.TYPE_PIECE) return
            val whole = pieces
            pieces = ByteArray(0)
            val plain = inflate(whole)
            when (type) {
                Constellation.TYPE_INIT -> {
                    if (!initSent) {
                        initSent = true
                        send(Constellation.TYPE_INIT, Constellation.init())
                    }
                    share(wantsLocation(plain.decodeToString()))
                }
                // The init carries the current options, so none before it are lost.
                Constellation.TYPE_OPTIONS -> if (initSent) share(wantsLocation(plain.decodeToString()))
                Constellation.TYPE_ROUTE_OPTIONS -> if (initSent) requestRoute(plain)
                Constellation.TYPE_NAVIGATION_OPTIONS -> if (initSent) navigate(plain)
            }
        }

        /** With [lock] held. */
        fun end() {
            if (ended) return
            ended = true
            linkWatch.cancel()
            if (sharing) stopSharing()
            endTrip()
            packer.close()
            unpacker.close()
            if (served === this) served = null
        }

        private suspend fun share(on: Boolean?) {
            if (on == null || on == sharing || ended) return
            if (!on) return stopSharing()
            sharing = true
            log.add("Maps: the Display asked for the location")
            val known = position.fix()
            position.follow(follower)
            known?.let { sendLocation(it) }
        }

        private fun stopSharing() {
            sharing = false
            position.unfollow(follower)
            log.add("Maps: stopped sending the location")
        }

        // The route goes from the first waypoint to the last; the routing runs off the lock.
        private suspend fun requestRoute(plain: ByteArray) {
            val req = runCatching { Constellation.routeRequest(plain) }.getOrNull()
            if (req == null) {
                log.add("Maps: a route request Visor could not read")
                runCatching { Constellation.stampOf(plain) }.getOrNull()?.let { routeResult(it, null) }
                return
            }
            val options = req.options ?: return
            if (req.waypoints.size < 2) return routeResult(req.stamp, null)
            val from = req.waypoints.first().latLng()
            val to = req.waypoints.last().latLng()
            scope.launch {
                val planned = runCatching { plan(from, to, req.travelMode, options) }
                planned.exceptionOrNull()?.let { log.add("Maps: no route: ${it.message}") }
                lock.withLock { routeResult(req.stamp, planned.getOrNull()) }
            }
        }

        private suspend fun routeResult(request: Stamp, h: Hosted?) {
            if (h != null) {
                remember(h)
                log.add("Maps: route, ${h.route.steps.size} maneuvers, ${h.route.km} km")
            }
            send(Constellation.TYPE_ROUTE_RESULT) { clock, stamp ->
                Constellation.routeResult(clock, stamp, request, h?.record, if (h == null) context.getString(R.string.nav_err_no_route) else null)
            }
        }

        private suspend fun navigate(plain: ByteArray) {
            val req = runCatching { Constellation.navigationRequest(plain) }.getOrNull()
            if (req == null) {
                log.add("Maps: a trip message Visor could not read")
                return
            }
            if (!req.start) return stopTrip(req.stamp)
            val h = recall(req.routeId)
            if (h == null) {
                log.add("Maps: a trip asked with no route")
                return stopTrip(req.stamp)
            }
            endTrip()
            val t = Trip(req.stamp, h)
            trip = t
            send(Constellation.TYPE_ACTIVE_ROUTE) { clock, stamp -> Constellation.activeRoute(clock, stamp, t.stamp, h.record) }
            state(t, Constellation.STARTED)
            state(t, Constellation.ON_ROUTE)
            following = true
            position.follow(guide)
            log.add("Maps: trip started, ${h.route.steps.size} maneuvers")
            position.fix()?.takeIf { System.currentTimeMillis() - it.time < FRESH_FIX_MS }?.let { progress(it) }
        }

        private suspend fun stopTrip(stamp: Stamp) {
            if (trip != null) log.add("Maps: trip ended")
            endTrip()
            send(Constellation.TYPE_NAVIGATOR_STATE) { clock, s -> Constellation.navigatorState(clock, s, stamp, Constellation.STOPPED) }
            send(Constellation.TYPE_ACTIVE_ROUTE) { clock, s -> Constellation.activeRoute(clock, s, stamp, null) }
        }

        private fun endTrip() {
            trip = null
            stopFollowing()
        }

        private fun stopFollowing() {
            if (!following) return
            following = false
            position.unfollow(guide)
        }

        /** With [lock] held. One fix of the trip: progress, a notice for the maneuver ahead, off-route and arrival. */
        private suspend fun progress(fix: Location) {
            val t = trip ?: return
            if (t.arrived || t.rerouting || ended) return
            val route = t.hosted.route
            val here = LatLng(fix.latitude, fix.longitude)
            val snap = route.snap(here)
            if (snap.offRoute) {
                t.offFixes++
                if (t.offFixes == 1) state(t, Constellation.OFF_ROUTE)
                if (t.offFixes >= REROUTE_FIXES) reroute(t, here)
                return
            }
            if (t.offFixes > 0) {
                t.offFixes = 0
                state(t, Constellation.ON_ROUTE)
            }
            val steps = route.steps
            val arrived = route.arrived(snap.along)
            val next = if (arrived) steps.lastIndex else route.nextStep(snap.along)
            val step = steps[next]
            val legStart = steps.getOrNull(next - 1)?.along ?: 0.0
            val perMetre = route.secondsPerMetre
            val p = Constellation.Progress(
                routeId = t.hosted.record.id,
                route = metrics(snap.along, route.metres, perMetre),
                maneuverIndex = next,
                maneuver = metrics(snap.along - legStart, step.along - legStart, perMetre),
                snapped = Constellation.Fix(snap.at.lat, snap.at.lng, null, fix.time * 1_000_000L),
                snappedIndex = snap.index,
            )
            send(Constellation.TYPE_ROUTE_PROGRESS) { clock, stamp -> Constellation.routeProgress(clock, stamp, t.stamp, p) }
            if (next != t.notified && step.along - snap.along <= approach(t.hosted.mode)) {
                t.notified = next
                val m = t.hosted.record.maneuvers[next]
                send(Constellation.TYPE_NOTIFICATION) { clock, stamp ->
                    Constellation.maneuverNotice(clock, stamp, t.stamp, m, UUID.randomUUID().toString())
                }
            }
            if (arrived) {
                t.arrived = true
                state(t, Constellation.ARRIVED)
                stopFollowing()
                log.add("Maps: arrived")
            }
        }

        // The new route is planned off the lock; fixes wait for it.
        private suspend fun reroute(t: Trip, here: LatLng) {
            t.rerouting = true
            state(t, Constellation.REROUTING)
            val old = t.hosted
            scope.launch {
                val fresh = runCatching { plan(here, old.to, old.travelMode, old.record.options) }
                lock.withLock {
                    if (trip !== t || ended) return@withLock
                    t.rerouting = false
                    val h = fresh.getOrNull()
                    if (h == null) {
                        log.add("Maps: reroute failed: ${fresh.exceptionOrNull()?.message}")
                        // Still off route: a fix back on it sends ON_ROUTE, more fixes off it reroute again.
                        t.offFixes = 1
                        state(t, Constellation.OFF_ROUTE)
                        return@withLock
                    }
                    t.offFixes = 0
                    remember(h)
                    t.hosted = h
                    // The new route's start is where the wearer already is.
                    t.notified = 0
                    send(Constellation.TYPE_ACTIVE_ROUTE) { clock, stamp -> Constellation.activeRoute(clock, stamp, t.stamp, h.record) }
                    state(t, Constellation.ON_ROUTE)
                    log.add("Maps: rerouted, ${h.route.steps.size} maneuvers")
                }
            }
        }

        private suspend fun state(t: Trip, state: Int) {
            send(Constellation.TYPE_NAVIGATOR_STATE) { clock, stamp -> Constellation.navigatorState(clock, stamp, t.stamp, state) }
        }

        private suspend fun sendLocation(fix: Location) {
            val f = Constellation.Fix(
                latitude = fix.latitude,
                longitude = fix.longitude,
                altitude = if (fix.hasAltitude()) fix.altitude else null,
                timeNs = fix.time * 1_000_000L,
                course = if (fix.hasBearing()) fix.bearing.toDouble() else null,
                speed = if (fix.hasSpeed()) fix.speed.toDouble() else null,
                horizontalAccuracy = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null,
                verticalAccuracy = if (fix.hasVerticalAccuracy()) fix.verticalAccuracyMeters.toDouble() else null,
                courseAccuracy = if (fix.hasBearingAccuracy()) fix.bearingAccuracyDegrees.toDouble() else null,
                speedAccuracy = if (fix.hasSpeedAccuracy()) fix.speedAccuracyMetersPerSecond.toDouble() else null,
            )
            send(Constellation.TYPE_LOCATION) { clock, stamp -> Constellation.location(clock, stamp, f) }
        }

        // Each message gets the clock and a new Timestamp.
        private suspend fun send(type: Int, body: (Long, Stamp) -> ByteArray) {
            val now = System.currentTimeMillis()
            send(type, body(now, stamp(now)))
        }

        private suspend fun send(type: Int, payload: ByteArray) {
            if (ended) return
            try {
                val body = deflate(payload)
                var at = 0
                // A body over 4096 bytes goes in pieces; only the last carries the type.
                while (body.size - at > PIECE) {
                    mux.send(DataXConnection.mirror(id), body.copyOfRange(at, at + PIECE), messageType = Constellation.TYPE_PIECE)
                    at += PIECE
                }
                mux.send(DataXConnection.mirror(id), body.copyOfRange(at, body.size), messageType = type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The link is gone, and with it this stream.
                end()
            }
        }

        private fun deflate(payload: ByteArray): ByteArray {
            val src = ByteBuffer.wrap(payload)
            var dst = ByteBuffer.allocate(payload.size + SLACK)
            while (!packer.compressByteBufferStream(dst, src, EndDirective.FLUSH)) dst = grown(dst)
            return dst.array().copyOf(dst.position())
        }

        // Done once all input is used and the output still has room, as the Display reads it.
        private fun inflate(body: ByteArray): ByteArray {
            val src = ByteBuffer.wrap(body)
            var dst = ByteBuffer.allocate(body.size * 4 + SLACK)
            while (true) {
                unpacker.decompressByteBufferStream(dst, src)
                if (!src.hasRemaining() && dst.hasRemaining()) return dst.array().copyOf(dst.position())
                if (!dst.hasRemaining()) dst = grown(dst)
            }
        }
    }

    // A missing option is off, as the Display reads it; an unreadable message changes nothing.
    private fun wantsLocation(json: String): Boolean? = runCatching {
        JSONObject(json).optJSONObject("channel_options")?.optBoolean("receiveLocation") ?: false
    }.getOrNull()

    private fun grown(b: ByteBuffer): ByteBuffer = ByteBuffer.allocate(b.capacity() * 2).put(b.array(), 0, b.position())

    // [travelled] of [length] metres, with the time left at the route's pace.
    private fun metrics(travelled: Double, length: Double, perMetre: Double): Constellation.Metrics {
        val done = travelled.coerceIn(0.0, max(length, 0.0))
        val left = max(0.0, length - done)
        return Constellation.Metrics(if (length > 0) done / length else 1.0, done, left, left * perMetre)
    }

    // How far ahead a maneuver is told (not taken from the Display).
    private fun approach(mode: Mode): Double = when (mode) {
        Mode.CAR -> 300.0
        Mode.BIKE -> 100.0
        Mode.WALK -> 40.0
    }

    companion object {
        const val SVC = 11004
        private const val LEVEL = 3
        private const val SLACK = 64
        private const val PIECE = 4096
        private const val KEPT_ROUTES = 8
        private const val REROUTE_FIXES = 5
        private const val FRESH_FIX_MS = 10_000L

        // The Display's ManeuverType for each of Visor's Dir values.
        private val MANEUVER_TYPES = intArrayOf(20, 19, 3, 1, 2, 10, 5, 4, 6, 15, 10, 16, 7, 26, 27)
    }
}

private fun LatLng.geo() = Constellation.Geo(lat, lng)

private fun Constellation.Geo.latLng() = LatLng(lat, lng)
