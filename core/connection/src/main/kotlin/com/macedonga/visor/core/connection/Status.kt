package com.macedonga.visor.core.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import java.util.UUID

/** svc28, smartglasses_appmanager: device state pushes, and [SnApp]'s StartApp, StopApp and SNIntent. */
const val SVC_STATUS: Int = 28
const val MT_INIT: Int = 1000

const val SUBSCRIPTION_DEVICE_STATE: Long = 1

/** Every svc28 type the Display knows. Any other non-zero type kills its system process (an NPE in the svc28 parser). */
val DISPLAY_STATUS_TYPES: Set<Int> = (
    listOf(0, 1000, 1001, 2100, 2101, 2300, 2301, 2600, 2601, 2603, 2700, 2800, 2801) +
        (2000..2045) + (2050..2052) + (2200..2207) + (2400..2402) + (2500..2503) + (2900..2903) + (3000..3002)
    ).toSet()

/** Every svc28 type that carries a DeviceState. 2005 is the snapshot; the rest name one field. */
private val DEVICE_STATE_TYPES: Set<Int> = (2005..2037).toSet() + (2039..2044)

private val CHARGE_STATE: Map<Long, String> = mapOf(
    0L to "NO_DATA", 1L to "CHARGING", 2L to "CHARGING_FULL", 3L to "CHARGING_LOW", 4L to "NOT_CHARGING",
)
private val CHARGING_STATES = setOf("CHARGING", "CHARGING_FULL", "CHARGING_LOW")
private val BATTERY_HEALTH: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "NORMAL", 2L to "WARNING", 3L to "CRITICAL", 4L to "SHUTDOWN",
    5L to "EMERGENCY_SHUTDOWN",
)
private val MOUNT_STATE: Map<Long, String> = mapOf(0L to "NONE", 1L to "DON", 2L to "DOFF")
const val CAMERA_PHOTO_STARTED = "CAPTURE_PHOTO_STARTED"
const val CAMERA_VIDEO_STARTED = "CAPTURE_VIDEO_STARTED"
const val HINGE_CLOSED = "CLOSED"
private val CAMERA_STATE: Map<Long, String> = mapOf(
    0L to "NO_STATE", 1L to CAMERA_PHOTO_STARTED, 2L to CAMERA_VIDEO_STARTED,
    3L to "CAPTURE_COMPLETED", 4L to "CAPTURE_FAILED", 5L to "CAPTURE_READY", 6L to "CAMERA_FAILING",
    7L to "CAPTURE_PREVIEW_STARTED", 8L to "CAPTURE_PREVIEW_PHOTO_STARTED",
    9L to "CAPTURE_PREVIEW_VIDEO_STARTED", 10L to "CAPTURE_READY_PREVIEW_ON",
)
private val HINGE: Map<Long, String> = mapOf(0L to "UNKNOWN", 1L to "OPEN", 2L to HINGE_CLOSED)
private val THERMAL_HEALTH: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "NORMAL", 2L to "LIGHT", 3L to "MODERATE", 4L to "SEVERE", 5L to "CRITICAL",
    6L to "SHUTDOWN",
)
private val VOLUME_STREAM: Map<Long, String> = mapOf(0L to "UNKNOWN", 1L to "MEDIA", 2L to "TELEPHONY", 3L to "CURRENT")
private val ASSISTANT_ATTENTION: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "IDLE", 2L to "WAKE", 3L to "LISTENING", 4L to "PROCESSING", 5L to "RESPONDING",
    6L to "LONG_RUNNING_SESSION_ACTIVATED",
)
private val CASE_PLATFORM: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "CABO", 2L to "LYNX", 3L to "BORA", 4L to "RIO", 5L to "BORACAY", 6L to "BATAN",
    7L to "ORCA", 8L to "TIGERFISH", 9L to "BRANZINO", 10L to "BORA_ULC", 11L to "ORCA_ULC", 16L to "SHALE",
    17L to "FUJI", 18L to "RAPTOR", 19L to "TOPAZ", 20L to "BLUEFIN", 21L to "BIGEYE", 253L to "COCOS",
)

private fun name(table: Map<Long, String>, value: Long): String = table[value] ?: "UNKNOWN_$value"

data class Battery(
    val percent: Long,
    val charging: Boolean,
    val chargeState: String,
    val health: String,
    val volts: Double,
    val temperatureC: Double,
)

/** What the glasses know about their charging case. */
data class CaseBattery(
    val known: Boolean,
    val present: Boolean,
    val level: Int,
    val scale: Long,
    val percent: Int?,
    val charging: Boolean,
    val health: String,
    val serial: String?,
    val firmware: String?,
    val platform: String,
    val incompatibleCharger: Boolean,
    val liquidDetected: Boolean,
)

data class Led(
    val ledOk: Boolean,
    val privacyLedOk: Boolean,
    val ledActive: Boolean,
    val privacyLedActive: Boolean,
)

data class Thermal(
    val health: String,
    /** As pushed. The unit has not been pinned down on hardware, so it is not converted. */
    val temperature: Long,
    val throttling: Boolean,
    val throttleReason: Long,
)

data class Storage(val freeMb: Long, val totalMb: Long)

data class Volume(val level: Long, val percent: Long, val stream: String)

/** What Visor reads of a DeviceState message. */
data class DeviceState(
    val battery: Battery? = null,
    val case: CaseBattery? = null,
    val camera: String? = null,
    val hinge: String? = null,
    val led: Led? = null,
    val mount: String? = null,
    val deepSleepPending: Boolean? = null,
    val inDeepSleep: Boolean? = null,
    val thermal: Thermal? = null,
    val streamingActive: Boolean? = null,
    val storage: Storage? = null,
    val volume: Volume? = null,
    val assistantAttention: String? = null,
    // The Display's Neural Band, as the Display reports it: battery (23), how tight it sits (30), which way round (34).
    val bandBattery: Int? = null,
    val bandCharging: Boolean? = null,
    val bandFit: String? = null,
    val bandOrientation: String? = null,
) {
    val worn: Boolean? get() = mount?.let { it == "DON" }
}

fun isDeviceStateType(messageType: Int): Boolean = messageType in DEVICE_STATE_TYPES

/**
 * [base] with what a svc28 DeviceState message, full or partial, carries. The Display sends it wrapped,
 * HNDeviceState {1: stateCommon, 2: deviceLockState}, and Meta's app unwraps it by device type: [display].
 */
fun decodeDeviceStatePush(payload: ByteArray, display: Boolean, base: DeviceState?): DeviceState =
    decodeDeviceStateFull(if (display) Wire.parse(payload).bytes(1) else payload, base ?: DeviceState())

private val BAND_FIT = mapOf(0L to "OFF", 1L to "LOOSE", 2L to "TIGHT", 3L to "UNKNOWN")
private val BAND_ORIENTATION = mapOf(0L to "UNKNOWN", 1L to "INCONCLUSIVE", 2L to "CORRECT", 3L to "INCORRECT")

private fun text(fields: Wire.Fields, number: Int): String =
    String(fields.bytes(number), Charsets.UTF_8)

/** A varint read back as the signed 32-bit field it was written from. */
private fun int32(value: Long): Int = (value and 0xFFFFFFFFL).toInt()

private fun bool(value: Long): Boolean = value != 0L

private fun decodeCase(c: Wire.Fields): CaseBattery {
    val level = int32(c.varint(3))
    // The app's default when the field is absent.
    val scale = c.varint(7).takeIf { it != 0L } ?: 4L
    val known = level.toLong() in 0L..scale
    val firmware = text(c, 5)
    return CaseBattery(
        known = known, present = bool(c.varint(8)), level = level, scale = scale,
        percent = if (known && scale == 100L) level else null,
        charging = bool(c.varint(2)), health = name(BATTERY_HEALTH, c.varint(1)),
        serial = text(c, 4).ifEmpty { null },
        firmware = if (firmware == "" || firmware == "0.0.0") null else firmware,
        platform = name(CASE_PLATFORM, c.varint(9)),
        incompatibleCharger = bool(c.varint(6)), liquidDetected = bool(c.varint(10)),
    )
}

private fun decodeDeviceStateFull(payload: ByteArray, base: DeviceState): DeviceState {
    val top = Wire.parse(payload)
    var state = base

    if (top.has(1)) {
        val b = Wire.parse(top.bytes(1))
        val charge = name(CHARGE_STATE, b.varint(2))
        state = state.copy(battery = Battery(
            percent = b.varint(3), charging = charge in CHARGING_STATES, chargeState = charge,
            health = name(BATTERY_HEALTH, b.varint(1)),
            volts = b.varint(5) / 1000.0, temperatureC = b.varint(4) / 1000.0,
        ))
    }
    if (top.has(2)) {
        state = state.copy(case = decodeCase(Wire.parse(top.bytes(2))))
    }
    if (top.has(3)) state = state.copy(camera = name(CAMERA_STATE, top.varint(3)))
    if (top.has(6)) state = state.copy(hinge = name(HINGE, top.varint(6)))
    if (top.has(8)) {
        val led = Wire.parse(top.bytes(8))
        state = state.copy(led = Led(
            ledOk = bool(led.varint(1)), privacyLedOk = bool(led.varint(2)),
            ledActive = bool(led.varint(3)), privacyLedActive = bool(led.varint(4)),
        ))
    }
    if (top.has(9)) state = state.copy(mount = name(MOUNT_STATE, top.varint(9)))
    if (top.has(10)) {
        val p = Wire.parse(top.bytes(10))
        state = state.copy(deepSleepPending = bool(p.varint(1)), inDeepSleep = bool(p.varint(2)))
    }
    if (top.has(13)) {
        val t = Wire.parse(top.bytes(13))
        state = state.copy(thermal = Thermal(
            health = name(THERMAL_HEALTH, t.varint(1)), temperature = t.varint(2),
            throttling = bool(t.varint(3)), throttleReason = t.varint(4),
        ))
    }
    if (top.has(17)) state = state.copy(streamingActive = bool(top.varint(17)))
    if (top.has(21)) {
        val s = Wire.parse(top.bytes(21))
        state = state.copy(storage = Storage(freeMb = s.varint(1), totalMb = s.varint(2)))
    }
    if (top.has(23)) {
        // EmgBatteryState {1 level, 2 charge: CHARGING 1, NOT_CHARGING 2}.
        val b = Wire.parse(top.bytes(23))
        state = state.copy(bandBattery = b.varint(1).toInt(), bandCharging = b.varint(2) == 1L)
    }
    if (top.has(30)) state = state.copy(bandFit = name(BAND_FIT, Wire.parse(top.bytes(30)).varint(1)))
    if (top.has(34)) state = state.copy(bandOrientation = name(BAND_ORIENTATION, Wire.parse(top.bytes(34)).varint(1)))
    if (top.has(25)) {
        val v = Wire.parse(top.bytes(25))
        state = state.copy(volume = Volume(
            level = v.varint(1), percent = v.varint(3), stream = name(VOLUME_STREAM, v.varint(2)),
        ))
    }
    if (top.has(31)) {
        val a = Wire.parse(top.bytes(31))
        state = state.copy(assistantAttention = name(ASSISTANT_ATTENTION, a.varint(1)))
    }
    return state
}

/** A CompanionDeviceInit (message type 1000) that subscribes to DEVICE_STATE. */
private fun subscribePayload(): ByteArray =
    Wire.fieldBytes(1, Wire.encodeVarint(SUBSCRIPTION_DEVICE_STATE)) + Wire.fieldString(2, UUID.randomUUID().toString())

/**
 * Subscribe on [channel], or on a new one when null or no longer open for svc28, and return it. Every new channel is a
 * subscription of its own and gets every push again; asking again on the same one only brings a fresh snapshot.
 */
suspend fun subscribeOn(connection: DataXConnection, channel: Int?): Int {
    val payload = subscribePayload()
    // Only a channel's first frame carries the service tag.
    if (channel != null && connection.localService(channel) == SVC_STATUS) {
        connection.send(channel, payload, messageType = MT_INIT)
        return channel
    }
    val on = connection.openChannel(SVC_STATUS)
    connection.send(on, payload, messageType = MT_INIT, serviceId = SVC_STATUS)
    return on
}
