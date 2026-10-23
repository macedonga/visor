package com.macedonga.visor.connection

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * svc55072, smartglasses_place_search: the glasses search for a place (PlaceSearchRequest, types 0 and 1 {1 query,
 * 2 radius in metres, 4 requestId}) and get PlaceSearchResponse (type 2) with the places nearest the phone, from the
 * phone's own geocoder. Media requests (3) get an empty PlaceMediaResponse (4).
 */
@Singleton
class PlaceSearch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val position: PhonePosition,
    private val requests: RequestLog,
    private val log: Provider<EventLog>,
) : Responder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> log.get().add("Place search: failed: $e") })

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.flags.isClosed) return
        val reply = DataXConnection.mirror(message.info.channelId)
        when (message.info.messageType) {
            MT_SEARCH, MT_SEARCH_REQUEST -> {
                val req = runCatching { Wire.parse(message.payload) }.getOrNull() ?: return
                scope.launch { connection.send(reply, search(req), messageType = MT_SEARCH_RESPONSE) }
            }
            MT_MEDIA_REQUEST -> connection.send(reply, ByteArray(0), messageType = MT_MEDIA_RESPONSE)
        }
    }

    private suspend fun search(req: Wire.Fields): ByteArray = withContext(Dispatchers.IO) {
        val query = req.bytes(1).decodeToString()
        val radius = req.float(2).takeIf { it > 0f }?.toDouble() ?: DEFAULT_RADIUS_M
        val logId = requests.start("place://$query", SOURCE_MAPS)
        val here = position.current()
        if (!Geocoder.isPresent()) {
            requests.finish(logId, "no geocoder on the phone", failed = true)
            return@withContext ERROR_NO_GEOCODER
        }
        val found = try {
            lookup(query, here, radius)
        } catch (e: IOException) {
            requests.finish(logId, e.message ?: "search failed", failed = true)
            return@withContext ERROR_NETWORK
        } catch (e: IllegalArgumentException) {
            requests.finish(logId, e.message ?: "search failed", failed = true)
            return@withContext ERROR_NETWORK
        }
        requests.finish(logId, "${found.size} places", failed = false)
        log.get().add("Place search: \"$query\", ${found.size} places")
        var body = Wire.fieldVarint(1, RESULT_SUCCESS)
        for (address in found) body += Wire.fieldBytes(2, place(address, here))
        body
    }

    /** Bounded to [radius] around the phone when it has a fix. */
    @Suppress("DEPRECATION")
    private fun lookup(query: String, here: LatLng?, radius: Double): List<Address> {
        val geocoder = Geocoder(context)
        if (here == null) return geocoder.getFromLocationName(query, MAX_RESULTS).orEmpty()
        val dLat = radius / METRES_PER_DEGREE
        val dLng = radius / (METRES_PER_DEGREE * cos(Math.toRadians(here.lat)).coerceAtLeast(MIN_COS))
        return geocoder.getFromLocationName(query, MAX_RESULTS, here.lat - dLat, here.lng - dLng, here.lat + dLat, here.lng + dLng)
            .orEmpty()
            .sortedBy { haversine(here, LatLng(it.latitude, it.longitude)) }
    }

    /** PlaceSearchResult {1 id, 2 title, 3 Address {1 street}, 4 GeoCoordinate {1 lat, 2 lng}, 13 walking {1 metres}}. */
    private fun place(a: Address, here: LatLng?): ByteArray {
        val street = if (a.maxAddressLineIndex >= 0) a.getAddressLine(0).orEmpty() else ""
        val title = a.featureName ?: a.thoroughfare ?: street
        var b = Wire.fieldBytes(1, "${a.latitude},${a.longitude}".encodeToByteArray()) +
            Wire.fieldBytes(2, title.encodeToByteArray()) +
            Wire.fieldBytes(3, Wire.fieldBytes(1, street.encodeToByteArray())) +
            Wire.fieldBytes(4, Wire.fieldDouble(1, a.latitude) + Wire.fieldDouble(2, a.longitude))
        if (here != null) {
            b += Wire.fieldBytes(13, Wire.fieldVarint(1, haversine(here, LatLng(a.latitude, a.longitude)).roundToLong()))
        }
        return b
    }

    companion object {
        const val SVC = 55072
        private const val SOURCE_MAPS = -6
        private const val MT_SEARCH = 0
        private const val MT_SEARCH_REQUEST = 1
        private const val MT_SEARCH_RESPONSE = 2
        private const val MT_MEDIA_REQUEST = 3
        private const val MT_MEDIA_RESPONSE = 4
        private const val RESULT_SUCCESS = 0L
        private val ERROR_NO_GEOCODER = Wire.fieldVarint(1, 1) + Wire.fieldBytes(3, "No geocoder on the phone".encodeToByteArray())
        private val ERROR_NETWORK = Wire.fieldVarint(1, 1) + Wire.fieldBytes(3, "Search failed".encodeToByteArray()) + Wire.fieldVarint(4, 1)
        private const val DEFAULT_RADIUS_M = 5_000.0
        private const val METRES_PER_DEGREE = 111_320.0
        private const val MIN_COS = 0.01
        private const val MAX_RESULTS = 10
    }
}
