package com.macedonga.visor.core.protocol

/**
 * Meta's device access toolkit, as a phone app speaks it on an app-scope link: the session on svc7009, then the
 * toolkit app on the glasses (com.meta.wearable.dat.developer in developer mode) on svc6500.
 */
object Dat {
    const val SESSION_SERVICE = 7009
    const val DWA_SERVICE = 6500

    // svc7009 message types.
    const val SESSION_START_REQUEST = 1
    const val SESSION_START_RESPONSE = 2
    const val SESSION_STOP_REQUEST = 3
    const val SESSION_STOP_RESPONSE = 4
    const val SESSION_STOP = 5
    const val SESSION_PAUSE = 6
    const val SESSION_RESUME = 7

    // SessionStartResponse status.
    const val START_OK = 0
    const val START_UNAUTHORIZED = 1
    const val START_DEVICE_UNAVAILABLE = 2
    const val START_SDK_TOO_OLD = 3

    // SessionStartResponse launch status of the toolkit app.
    const val DWA_LAUNCHED = 1
    const val DWA_NOT_REQUESTED = 2
    const val DWA_LAUNCH_FAILED = 4

    private const val PLATFORM_ANDROID = 2

    /**
     * SessionStartRequest {1 app_id, 2 session_id, 3 session_name, 4 is_dev_mode, 5 uses_dam, 7 platform, 8
     * sdk_version}. Developer mode names no toolkit app (6): the glasses run the developer one.
     */
    fun sessionStart(appId: String, sessionId: String, name: String, sdkVersion: String): ByteArray =
        Wire.fieldString(1, appId) + Wire.fieldString(2, sessionId) + Wire.fieldString(3, name) +
            Wire.fieldVarint(4, 1) + Wire.fieldVarint(5, 1) + Wire.fieldVarint(7, PLATFORM_ANDROID.toLong()) +
            Wire.fieldString(8, sdkVersion)

    /** SessionStartResponse {1 status, 2 session_id, 3 dwa_version_name, 4 dwa_version_code, 5 dwa_launch, 6 compatibility}. */
    class SessionStarted(val status: Int, val sessionId: String, val dwaVersion: String, val dwaLaunch: Int, val compatibility: Int)

    fun sessionStarted(payload: ByteArray): SessionStarted {
        val f = Wire.parse(payload)
        return SessionStarted(
            f.varint(1).toInt(), f.bytes(2).decodeToString(), f.bytes(3).decodeToString(), f.varint(5).toInt(), f.varint(6).toInt(),
        )
    }

    /** SessionStopRequest {1 session_id, 2 is_dev_mode}. */
    fun sessionStop(sessionId: String): ByteArray = Wire.fieldString(1, sessionId) + Wire.fieldVarint(2, 1)

    // svc6500 message types.
    const val REQUEST = 1
    const val RESPONSE = 2
    const val EVENT = 3

    // Capabilities.
    const val DISPLAY = 1
    const val STREAM = 2
    const val CAPTURE = 3
    const val SPEECH = 4
    const val IMU = 5
    const val INPUT = 6

    // DwaApiResponse status.
    const val STATUS_OK = 1
    const val STATUS_ERROR = 2
    const val STATUS_UNSUPPORTED = 3

    private const val PROTOCOL_VERSION = 1L

    // A capability payload over this goes in pieces: fragments first, then the request with the last piece.
    private const val FRAGMENT_BYTES = 15_000

    /** DwaRequestBody {1 capability_query}. */
    fun capabilityQuery(): ByteArray = Wire.fieldBytes(1, ByteArray(0))

    /** DwaRequestBody {2 add_capability {1 capability}}. */
    fun addCapability(capability: Int): ByteArray = Wire.fieldBytes(2, Wire.fieldVarint(1, capability.toLong()))

    /** DwaRequestBody {3 remove_capability {1 capability}}. */
    fun removeCapability(capability: Int): ByteArray = Wire.fieldBytes(3, Wire.fieldVarint(1, capability.toLong()))

    /**
     * DwaRequest {1 header {1 protocol_version, 2 message_id, 3 capability, 4 timestamp_ms}, 2 body}, each one
     * a type [REQUEST] message. [payload] is the capability's own request, carried as body field 4, or null for a
     * body made by [capabilityQuery], [addCapability] or [removeCapability].
     */
    fun requests(messageId: String, capability: Int, body: ByteArray?, payload: ByteArray?, nowMs: Long): List<ByteArray> {
        val header = Wire.fieldBytes(
            1,
            Wire.fieldVarint(1, PROTOCOL_VERSION) + Wire.fieldString(2, messageId) + Wire.fieldVarint(3, capability.toLong()) +
                Wire.fieldVarint(4, nowMs),
        )
        if (payload == null) return listOf(header + Wire.fieldBytes(2, body ?: ByteArray(0)))
        val pieces = payload.toList().chunked(FRAGMENT_BYTES).map { it.toByteArray() }.ifEmpty { listOf(ByteArray(0)) }
        val fragments = pieces.dropLast(1).map { Wire.fieldBytes(3, Wire.fieldBytes(1, it)) }
        return fragments + (header + Wire.fieldBytes(2, Wire.fieldBytes(4, pieces.last())))
    }

    /**
     * DwaApiResponse {2 request_message_id, 3 capability, 5 status, 6 error {1 code, 2 message, 3 recoverable},
     * 7 add_capability {1 active}, 11 capability_denied {1 reason}, 12 capability_response_payload}.
     */
    class Response(
        val requestId: String,
        val capability: Int,
        val status: Int,
        val error: String?,
        val active: Boolean?,
        val denied: String?,
        val payload: ByteArray?,
    )

    fun response(payload: ByteArray): Response {
        val f = Wire.parse(payload)
        val error = if (f.has(6)) Wire.parse(f.bytes(6)).let { e -> "${e.varint(1)}: ${e.bytes(2).decodeToString()}" } else null
        return Response(
            requestId = f.bytes(2).decodeToString(),
            capability = f.varint(3).toInt(),
            status = f.varint(5).toInt(),
            error = error,
            active = if (f.has(7)) Wire.parse(f.bytes(7)).varint(1) != 0L else null,
            denied = if (f.has(11)) Wire.parse(f.bytes(11)).bytes(1).decodeToString() else null,
            payload = if (f.has(12)) f.bytes(12) else null,
        )
    }

    /** DwaEvent {2 capability, 5 forced_shutdown {1 reason}, 6 capability_event_payload, 7 heartbeat}. */
    class Event(val capability: Int, val payload: ByteArray?, val shutdown: String?, val heartbeat: Boolean)

    fun event(payload: ByteArray): Event {
        val f = Wire.parse(payload)
        return Event(
            capability = f.varint(2).toInt(),
            payload = if (f.has(6)) f.bytes(6) else null,
            shutdown = if (f.has(5)) Wire.parse(f.bytes(5)).bytes(1).decodeToString() else null,
            heartbeat = f.has(7),
        )
    }

    /** DisplayRequestPayload {3 display_content {4 payload}}: a gzipped Bloks layout for the toolkit app to draw. */
    fun displayContent(bloks: ByteArray): ByteArray = Wire.fieldBytes(3, Wire.fieldBytes(4, bloks))

    /** DisplayRequestPayload {8 display_clear}. */
    fun displayClear(): ByteArray = Wire.fieldBytes(8, ByteArray(0))

    /** The id of a click in a DisplayEventPayload {1 display_event {1 click {1 identifier}}}, or null for anything else. */
    fun displayClick(eventPayload: ByteArray): String? {
        val event = Wire.parse(eventPayload).takeIf { it.has(1) }?.bytes(1) ?: return null
        val click = Wire.parse(event).takeIf { it.has(1) }?.bytes(1) ?: return null
        return Wire.parse(click).bytes(1).decodeToString()
    }

    /** True for a DisplayEventPayload {1 display_event {2 back}}: the wearer went back, and the layout is gone. */
    fun displayBack(eventPayload: ByteArray): Boolean {
        val event = Wire.parse(eventPayload).takeIf { it.has(1) }?.bytes(1) ?: return false
        return Wire.parse(event).has(2)
    }

    /** The IMU's rates, in Hz, in the order of their enum: 5 Hz is 1. */
    val MOTION_RATES = listOf(5, 10, 15, 24, 30, 60)

    /** MotionRequestPayload {1 motion_start {1 sampling_rate}}. */
    fun motionStart(rateHz: Int): ByteArray {
        val rate = MOTION_RATES.indexOf(rateHz) + 1
        require(rate > 0) { "no IMU rate of $rateHz Hz" }
        return Wire.fieldBytes(1, Wire.fieldVarint(1, rate.toLong()))
    }

    /** MotionRequestPayload {2 motion_stop}. */
    fun motionStop(): ByteArray = Wire.fieldBytes(2, ByteArray(0))

    class Vector(val x: Float, val y: Float, val z: Float)

    class Rotation(val x: Float, val y: Float, val z: Float, val w: Float)

    /** MotionSample {1 timestamp_ns, 2 accelerometer, 3 gyroscope, 4 magnetometer, 5 orientation, 6 source (1 glasses, 2 band)}. */
    class Sample(val timeNs: Long, val accelerometer: Vector?, val gyroscope: Vector?, val magnetometer: Vector?, val orientation: Rotation?, val band: Boolean)

    /** The samples of a MotionEventPayload {1 imu_data {1 repeated MotionSample}}. */
    fun motionSamples(eventPayload: ByteArray): List<Sample> {
        val imu = Wire.parse(eventPayload).takeIf { it.has(1) }?.bytes(1) ?: return emptyList()
        return Wire.parse(imu).repeatedBytes(1).map { raw ->
            val s = Wire.parse(raw)
            Sample(
                timeNs = s.varint(1),
                accelerometer = s.vector(2),
                gyroscope = s.vector(3),
                magnetometer = s.vector(4),
                orientation = if (s.has(5)) Wire.parse(s.bytes(5)).let { q -> Rotation(q.float(1), q.float(2), q.float(3), q.float(4)) } else null,
                band = s.varint(6) == 2L,
            )
        }
    }

    private fun Wire.Fields.vector(fn: Int): Vector? =
        if (has(fn)) Wire.parse(bytes(fn)).let { v -> Vector(v.float(1), v.float(2), v.float(3)) } else null
}
