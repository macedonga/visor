package com.macedonga.visor.core.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import java.util.UUID

/** svc28 (smartglasses_appmanager) device state: the full snapshot and every push after it. */
const val SVC_STATUS: Int = 28
const val MT_INIT: Int = 1000
const val MT_INIT_RESPONSE: Int = 1001
const val MT_DEVICE_STATE: Int = 2005

const val SUBSCRIPTION_DEVICE_STATE: Long = 1

/** Every svc28 type that carries a DeviceState. 2005 is the snapshot; the rest name one field. */
val DEVICE_STATE_TYPES: Map<Int, String> = mapOf(
    2005 to "device_state", 2006 to "device_state_update", 2007 to "battery",
    2008 to "case_battery", 2009 to "camera", 2010 to "capture_button", 2011 to "captouch",
    2012 to "hinge", 2013 to "hardware", 2014 to "led", 2015 to "mount", 2016 to "power",
    2017 to "storage", 2018 to "tampering", 2019 to "thermal", 2020 to "verified_session",
    2021 to "peak_power", 2022 to "audio_active", 2023 to "streaming_active",
    2024 to "hot_car", 2025 to "bluetooth", 2026 to "app_interrupt",
    2027 to "storage_details", 2028 to "device_lock", 2029 to "registered_apps",
    2030 to "emg_battery", 2031 to "emg_band_tightness", 2032 to "volume", 2033 to "apps",
    2034 to "call", 2035 to "action_button", 2036 to "emg_band_tightness_state",
    2037 to "assistant_attention", 2039 to "peak_power_throttling",
    2040 to "emg_band_orientation", 2041 to "water_detect", 2042 to "camera_sensing",
    2043 to "odc", 2044 to "verified_session_info",
)

val CHARGE_STATE: Map<Long, String> = mapOf(
    0L to "NO_DATA", 1L to "CHARGING", 2L to "CHARGING_FULL", 3L to "CHARGING_LOW", 4L to "NOT_CHARGING",
)
private val CHARGING_STATES = setOf("CHARGING", "CHARGING_FULL", "CHARGING_LOW")
val BATTERY_HEALTH: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "NORMAL", 2L to "WARNING", 3L to "CRITICAL", 4L to "SHUTDOWN",
    5L to "EMERGENCY_SHUTDOWN",
)
val MOUNT_STATE: Map<Long, String> = mapOf(0L to "NONE", 1L to "DON", 2L to "DOFF")
val CAMERA_STATE: Map<Long, String> = mapOf(
    0L to "NO_STATE", 1L to "CAPTURE_PHOTO_STARTED", 2L to "CAPTURE_VIDEO_STARTED",
    3L to "CAPTURE_COMPLETED", 4L to "CAPTURE_FAILED", 5L to "CAPTURE_READY", 6L to "CAMERA_FAILING",
    7L to "CAPTURE_PREVIEW_STARTED", 8L to "CAPTURE_PREVIEW_PHOTO_STARTED",
    9L to "CAPTURE_PREVIEW_VIDEO_STARTED", 10L to "CAPTURE_READY_PREVIEW_ON",
)
val CAPTURE_BUTTON: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "SHORT_PRESS_DOWN", 2L to "LONG_PRESS", 3L to "VERY_LONG_PRESS",
    4L to "VERY_VERY_LONG_PRESS", 5L to "SHORT_PRESS_UP", 6L to "EXTREMELY_LONG_PRESS",
    7L to "VERY_VERY_LONG_PRESS_UP", 8L to "DOUBLE_PRESS",
)
val CAPTOUCH: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "SINGLE_TAP", 2L to "DOUBLE_TAP", 3L to "TAP_HOLD", 4L to "SWIPE_BACKWARD",
    5L to "SWIPE_FORWARD", 6L to "SLIDE_BACKWARD", 7L to "SLIDE_FORWARD", 8L to "TRIPLE_TAP",
    9L to "PRESENSE_TOUCHED", 10L to "PRESENSE_CLEAR", 11L to "PALM_TAP", 12L to "PALM_HOLD",
)
val ACTION_BUTTON: Map<Long, String> =
    mapOf(0L to "UNKNOWN", 1L to "SHORT_PRESS_DOWN", 2L to "SHORT_PRESS_UP", 3L to "LONG_PRESS_UP")
val HINGE: Map<Long, String> = mapOf(0L to "UNKNOWN", 1L to "OPEN", 2L to "CLOSED")
val STORAGE_LEVEL: Map<Long, String> = mapOf(0L to "UNDEFINED", 1L to "STABLE", 2L to "LOW", 3L to "ZERO")
val LOCK_STATE: Map<Long, String> = mapOf(0L to "NO_LOCK", 1L to "LOCKED", 2L to "UNLOCKED")
val THERMAL_HEALTH: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "NORMAL", 2L to "LIGHT", 3L to "MODERATE", 4L to "SEVERE", 5L to "CRITICAL",
    6L to "SHUTDOWN",
)
val VOLUME_STREAM: Map<Long, String> = mapOf(0L to "UNKNOWN", 1L to "MEDIA", 2L to "TELEPHONY", 3L to "CURRENT")
val ASSISTANT_ATTENTION: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "IDLE", 2L to "WAKE", 3L to "LISTENING", 4L to "PROCESSING", 5L to "RESPONDING",
    6L to "LONG_RUNNING_SESSION_ACTIVATED",
)
val PEAK_POWER: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "NORMAL", 2L to "THROTTLE_LEVEL_1", 3L to "THROTTLE_LEVEL_2",
    4L to "THROTTLE_LEVEL_3", 5L to "SHUTDOWN",
)
val ANCS_STATE: Map<Long, String> =
    mapOf(0L to "UNKNOWN", 1L to "READY_TO_CONNECT", 2L to "CONNECTING", 3L to "CONNECTED", 4L to "DISCONNECTED")
val BTC_STATE: Map<Long, String> = mapOf(0L to "UNKNOWN", 1L to "CONNECTED", 2L to "DISCONNECTED")
val CALL_STATE: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "TERMINATED", 2L to "DIALING", 3L to "ACTIVE", 4L to "INCOMING", 5L to "WAITING",
    6L to "ALERTING", 7L to "HELD", 8L to "HELD_BY_RESPONSE_AND_HOLD",
)
val CALLING_STATE: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "IDLE", 2L to "ACTIVE", 3L to "RINGING", 4L to "VIDEO_ACTIVE", 5L to "PTT_ACTIVE",
    6L to "DIALING", 7L to "ACTIVE_3P",
)
val CALL_PLATFORM: Map<Long, String> =
    mapOf(0L to "UNKNOWN", 1L to "NATIVE", 2L to "MESSENGER", 3L to "INSTAGRAM", 4L to "WHATSAPP")
val APP_STATE: Map<Long, String> = mapOf(0L to "UNKNOWN", 1L to "ACTIVE", 2L to "INACTIVE", 3L to "PAUSED")
val WATER_DETECT: Map<Long, String> = mapOf(0L to "UNKNOWN", 1L to "DETECTED", 2L to "NOT_DETECTED")
val CAMERA_SENSING: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "OFF", 2L to "SENSING", 3L to "PAUSED", 4L to "UNMOUNTED", 5L to "BUSY", 6L to "ACS_ERROR",
)
val ODC_STATE: Map<Long, String> =
    mapOf(0L to "UNKNOWN", 1L to "STARTING", 2L to "STARTED", 3L to "STOPPING", 4L to "STOPPED", 5L to "FAILED")
val CASE_PLATFORM: Map<Long, String> = mapOf(
    0L to "UNKNOWN", 1L to "CABO", 2L to "LYNX", 3L to "BORA", 4L to "RIO", 5L to "BORACAY", 6L to "BATAN",
    7L to "ORCA", 8L to "TIGERFISH", 9L to "BRANZINO", 10L to "BORA_ULC", 11L to "ORCA_ULC", 16L to "SHALE",
    17L to "FUJI", 18L to "RAPTOR", 19L to "TOPAZ", 20L to "BLUEFIN", 21L to "BIGEYE", 253L to "COCOS",
)

private fun name(table: Map<Long, String>, value: Long): String = table[value] ?: "UNKNOWN_$value"

/** The flat reading the command line prints: enough to say how the glasses are. */
data class DeviceStatus(
    val batteryPercent: Long,
    val charging: Boolean,
    val volts: Double,
    val temperatureC: Double,
    val worn: Boolean,
    /** MB of storage still free (not used), out of [storageTotalMb]. */
    val storageFreeMb: Long,
    val storageTotalMb: Long,
)

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

data class Hardware(
    val dspOk: Boolean,
    val imuOk: Boolean,
    val mcuOk: Boolean,
    val microphoneOk: Boolean,
    val speakerOk: Boolean,
    val captouchOk: Boolean,
)

data class Led(
    val ledOk: Boolean,
    val privacyLedOk: Boolean,
    val ledActive: Boolean,
    val privacyLedActive: Boolean,
)

data class Tampering(val tampered: Boolean, val lux: Float, val indoor: Boolean)

data class Thermal(
    val health: String,
    /** As pushed. The unit has not been pinned down on hardware, so it is not converted. */
    val temperature: Long,
    val throttling: Boolean,
    val throttleReason: Long,
)

data class Bluetooth(val ancs: String, val map: String, val pbap: String, val btc: String)

data class Storage(val freeMb: Long, val totalMb: Long)

data class Volume(val level: Long, val percent: Long, val stream: String)

data class Call(val state: String, val callingState: String, val platform: String, val startTimeMs: Long)

/** Everything a DeviceState message can carry. */
data class DeviceState(
    val battery: Battery? = null,
    val case: CaseBattery? = null,
    val camera: String? = null,
    val captureButton: String? = null,
    val captouch: String? = null,
    val hinge: String? = null,
    val hardware: Hardware? = null,
    val led: Led? = null,
    val mount: String? = null,
    val deepSleepPending: Boolean? = null,
    val inDeepSleep: Boolean? = null,
    val storageLevel: String? = null,
    val tampering: Tampering? = null,
    val thermal: Thermal? = null,
    val sessionVerified: Boolean? = null,
    val peakPower: List<Pair<Long, String>>? = null,
    val audioActive: Boolean? = null,
    val streamingActive: Boolean? = null,
    val hotCar: Boolean? = null,
    val bluetooth: Bluetooth? = null,
    val interruptedApp: Long? = null,
    val storage: Storage? = null,
    val registeredApps: List<Long>? = null,
    val volume: Volume? = null,
    val lockState: String? = null,
    val apps: List<Pair<Long, String>>? = null,
    val call: Call? = null,
    val actionButton: String? = null,
    val assistantAttention: String? = null,
    val waterDetect: String? = null,
    val cameraSensing: String? = null,
    val odc: String? = null,
    val overhead: Boolean? = null,
    val interactionKey: String? = null,
    // The Display's Neural Band, as the Display reports it: battery (23), how tight it sits (30), which way round (34).
    val bandBattery: Int? = null,
    val bandCharging: Boolean? = null,
    val bandFit: String? = null,
    val bandOrientation: String? = null,
) {
    val worn: Boolean? get() = mount?.let { it == "DON" }
}

/** One svc28 push, decoded. [kind] names what the push updates ([DEVICE_STATE_TYPES]). */
data class DeviceStateUpdate(val messageType: Int, val kind: String, val state: DeviceState) {

    /** The flat reading, when this push carries enough to make one (a snapshot does). */
    val status: DeviceStatus?
        get() {
            val battery = state.battery ?: return null
            val storage = state.storage ?: return null
            val mount = state.mount ?: return null
            return DeviceStatus(
                batteryPercent = battery.percent, charging = battery.charging,
                volts = battery.volts, temperatureC = battery.temperatureC,
                worn = mount == "DON",
                storageFreeMb = storage.freeMb, storageTotalMb = storage.totalMb,
            )
        }
}

/** The base with every field the update carries replaced. */
fun mergeDeviceState(base: DeviceState, update: DeviceState): DeviceState = DeviceState(
    battery = update.battery ?: base.battery,
    case = update.case ?: base.case,
    camera = update.camera ?: base.camera,
    captureButton = update.captureButton ?: base.captureButton,
    captouch = update.captouch ?: base.captouch,
    hinge = update.hinge ?: base.hinge,
    hardware = update.hardware ?: base.hardware,
    led = update.led ?: base.led,
    mount = update.mount ?: base.mount,
    deepSleepPending = update.deepSleepPending ?: base.deepSleepPending,
    inDeepSleep = update.inDeepSleep ?: base.inDeepSleep,
    storageLevel = update.storageLevel ?: base.storageLevel,
    tampering = update.tampering ?: base.tampering,
    thermal = update.thermal ?: base.thermal,
    sessionVerified = update.sessionVerified ?: base.sessionVerified,
    peakPower = update.peakPower ?: base.peakPower,
    audioActive = update.audioActive ?: base.audioActive,
    streamingActive = update.streamingActive ?: base.streamingActive,
    hotCar = update.hotCar ?: base.hotCar,
    bluetooth = update.bluetooth ?: base.bluetooth,
    interruptedApp = update.interruptedApp ?: base.interruptedApp,
    storage = update.storage ?: base.storage,
    registeredApps = update.registeredApps ?: base.registeredApps,
    volume = update.volume ?: base.volume,
    lockState = update.lockState ?: base.lockState,
    apps = update.apps ?: base.apps,
    call = update.call ?: base.call,
    actionButton = update.actionButton ?: base.actionButton,
    assistantAttention = update.assistantAttention ?: base.assistantAttention,
    waterDetect = update.waterDetect ?: base.waterDetect,
    cameraSensing = update.cameraSensing ?: base.cameraSensing,
    odc = update.odc ?: base.odc,
    overhead = update.overhead ?: base.overhead,
    interactionKey = update.interactionKey ?: base.interactionKey,
    bandBattery = update.bandBattery ?: base.bandBattery,
    bandCharging = update.bandCharging ?: base.bandCharging,
    bandFit = update.bandFit ?: base.bandFit,
    bandOrientation = update.bandOrientation ?: base.bandOrientation,
)

fun isDeviceStateType(messageType: Int): Boolean = messageType in DEVICE_STATE_TYPES

/** Decode a svc28/2005 DeviceState push into the flat [DeviceStatus]. */
fun decodeDeviceState(payload: ByteArray): DeviceStatus {
    val top = Wire.parse(payload)
    val battery = Wire.parse(top.bytes(1))
    val storage = Wire.parse(top.bytes(21))

    val charge = CHARGE_STATE[battery.varint(2)] ?: ""
    val wornState = MOUNT_STATE[top.varint(9)] ?: ""

    return DeviceStatus(
        batteryPercent = battery.varint(3),
        charging = charge in CHARGING_STATES,
        volts = battery.varint(5) / 1000.0,
        temperatureC = battery.varint(4) / 1000.0,
        worn = wornState == "DON",
        storageFreeMb = storage.varint(1),
        storageTotalMb = storage.varint(2),
    )
}

/**
 * Decode any svc28 DeviceState message, full or partial, keeping only what it carries. The Display sends it wrapped,
 * HNDeviceState {1: stateCommon, 2: deviceLockState}, and Meta's app unwraps it by device type: [display].
 */
fun decodeDeviceStateUpdate(messageType: Int, payload: ByteArray, display: Boolean = false): DeviceStateUpdate =
    DeviceStateUpdate(messageType, DEVICE_STATE_TYPES.getValue(messageType), decodeDeviceStateFull(if (display) Wire.parse(payload).bytes(1) else payload))

/** Whether a build fingerprint, "Meta/greatwhite/greatwhite:14/...", is a Meta Ray-Ban Display's. */
fun isDisplayBuild(build: String?): Boolean = build?.split('/')?.getOrNull(2)?.substringBefore(':')?.lowercase() in DISPLAY_DEVICES

private val DISPLAY_DEVICES = setOf("greatwhite")

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

fun decodeDeviceStateFull(payload: ByteArray): DeviceState {
    val top = Wire.parse(payload)
    var state = DeviceState()

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
    if (top.has(4)) state = state.copy(captureButton = name(CAPTURE_BUTTON, top.varint(4)))
    if (top.has(5)) state = state.copy(captouch = name(CAPTOUCH, top.varint(5)))
    if (top.has(6)) state = state.copy(hinge = name(HINGE, top.varint(6)))
    if (top.has(7)) {
        val h = Wire.parse(top.bytes(7))
        state = state.copy(hardware = Hardware(
            dspOk = bool(h.varint(1)), imuOk = bool(h.varint(2)), mcuOk = bool(h.varint(3)),
            microphoneOk = bool(h.varint(4)), speakerOk = bool(h.varint(5)),
            captouchOk = bool(h.varint(6)),
        ))
    }
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
    if (top.has(11)) state = state.copy(storageLevel = name(STORAGE_LEVEL, top.varint(11)))
    if (top.has(12)) {
        val t = Wire.parse(top.bytes(12))
        state = state.copy(tampering = Tampering(
            tampered = bool(t.varint(1)), lux = t.float(2), indoor = bool(t.varint(3)),
        ))
    }
    if (top.has(13)) {
        val t = Wire.parse(top.bytes(13))
        state = state.copy(thermal = Thermal(
            health = name(THERMAL_HEALTH, t.varint(1)), temperature = t.varint(2),
            throttling = bool(t.varint(3)), throttleReason = t.varint(4),
        ))
    }
    if (top.has(14)) state = state.copy(sessionVerified = bool(top.varint(14)))
    if (top.has(15)) {
        val entries = top.repeatedBytes(15).map { blob ->
            val e = Wire.parse(blob)
            e.varint(1) to name(PEAK_POWER, e.varint(2))
        }
        state = state.copy(peakPower = entries)
    }
    if (top.has(16)) state = state.copy(audioActive = bool(top.varint(16)))
    if (top.has(17)) state = state.copy(streamingActive = bool(top.varint(17)))
    if (top.has(18)) state = state.copy(hotCar = bool(top.varint(18)))
    if (top.has(19)) {
        val b = Wire.parse(top.bytes(19))
        state = state.copy(bluetooth = Bluetooth(
            ancs = name(ANCS_STATE, b.varint(1)), map = name(ANCS_STATE, b.varint(2)),
            pbap = name(ANCS_STATE, b.varint(3)), btc = name(BTC_STATE, b.varint(4)),
        ))
    }
    if (top.has(20)) {
        val i = Wire.parse(top.bytes(20))
        state = state.copy(interruptedApp = i.varint(1))
    }
    if (top.has(21)) {
        val s = Wire.parse(top.bytes(21))
        state = state.copy(storage = Storage(freeMb = s.varint(1), totalMb = s.varint(2)))
    }
    if (top.has(22)) {
        state = state.copy(registeredApps = top.repeatedBytes(22).map { Wire.parse(it).varint(1) })
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
    if (top.has(26)) state = state.copy(lockState = name(LOCK_STATE, top.varint(26)))
    if (top.has(27)) {
        val entries = top.repeatedBytes(27).map { blob ->
            val e = Wire.parse(blob)
            e.varint(1) to name(APP_STATE, e.varint(2))
        }
        state = state.copy(apps = entries)
    }
    if (top.has(28)) {
        val c = Wire.parse(top.bytes(28))
        state = state.copy(call = Call(
            state = name(CALL_STATE, c.varint(1)), callingState = name(CALLING_STATE, c.varint(2)),
            platform = name(CALL_PLATFORM, c.varint(3)), startTimeMs = c.varint(4),
        ))
    }
    if (top.has(29)) state = state.copy(actionButton = name(ACTION_BUTTON, top.varint(29)))
    if (top.has(31)) {
        val a = Wire.parse(top.bytes(31))
        state = state.copy(assistantAttention = name(ASSISTANT_ATTENTION, a.varint(1)))
    }
    if (top.has(35)) state = state.copy(waterDetect = name(WATER_DETECT, top.varint(35)))
    if (top.has(36)) state = state.copy(cameraSensing = name(CAMERA_SENSING, top.varint(36)))
    if (top.has(37)) state = state.copy(odc = name(ODC_STATE, top.varint(37)))
    if (top.has(39)) state = state.copy(overhead = bool(top.varint(39)))
    if (top.has(10000)) state = state.copy(interactionKey = text(top, 10000))
    return state
}

/** Build a CompanionDeviceInit (message type 1000) that subscribes to DEVICE_STATE. */
fun buildSubscribe(interactionKey: String? = null): Pair<ByteArray, String> {
    val key = interactionKey ?: UUID.randomUUID().toString()
    val subscription = Wire.fieldBytes(1, Wire.encodeVarint(SUBSCRIPTION_DEVICE_STATE))
    return (subscription + Wire.fieldBytes(2, key.toByteArray(Charsets.UTF_8))) to key
}

/** Ask svc28 for device state pushes. */
suspend fun subscribeToDeviceStatus(connection: DataXConnection) {
    subscribeOn(connection, null)
}

/**
 * Subscribe on [channel], or on a new one when null, and return it. Every new channel is a subscription of its
 * own and gets every push again; asking again on the same one only brings a fresh snapshot.
 */
suspend fun subscribeOn(connection: DataXConnection, channel: Int?): Int {
    val (payload, _) = buildSubscribe()
    val on = channel ?: connection.openChannel(SVC_STATUS)
    connection.send(on, payload, messageType = MT_INIT, serviceId = SVC_STATUS)
    return on
}
