package com.macedonga.visor.core.protocol

/**
 * The Neural Band's RPC on svc52822, as Meta AI 294.1 speaks it (com.oculus.wearableinputservice). A request goes out
 * as DataX type 0x0314, whose low byte is the band's REQUEST; what the band sends carries its own type in the low byte.
 */
object Band {
    const val REQUEST_TYPE = 0x0314
    const val RESPONSE = 21
    const val STREAM_UPDATE = 22

    /** The answer's code when the band did what it was asked. */
    const val OK = 0

    /** The PSM the band's GATT names; the glasses' is 128. */
    const val PSM = 129

    class Battery(val percent: Int, val charging: Boolean)

    /** RpcResponse {1: seq, 2: code, 3: BatteryInfoResp, 4: DeviceInfoResp, 6: ConfigResp, 18: HapticsResp, ...}, as far as Visor reads it. */
    class Response(
        val seq: Int,
        val code: Int,
        val battery: Battery?,
        val leftHanded: Boolean?,
        val protocol: Int? = null,
        val haptics: Int? = null,
    )

    /**
     * A gesture, `EmgImu$GestureEvent`, sent as stream type 13 or a granular type from 60 to 200: {1 seq, 3 finger, 4 action,
     * 5 derived action}. Fingers: THUMB 1, INDEX 2, MIDDLE 3. Actions: PRESS 1, RELEASE 2, TAP 3, DOUBLETAP 4, CLICK 5, UP 6,
     * DOWN 7, LEFT 8, RIGHT 9. Derived: SINGLE_TAP 1, DOUBLE_TAP 2, BUTTON_HOLD 3, BUTTON_RELEASE 4, the four directions 5-8.
     */
    class Gesture(val type: Int, val seq: Int, val finger: Int, val action: Int, val derived: Int)

    /** RpcRequest {1: seq, 2: BatteryInfoReq {}}. */
    fun batteryRequest(seq: Int): ByteArray = request(seq, 2, ByteArray(0))

    /** RpcRequest {1: seq, 3: DeviceInfoReq {}}: the answer names the band's protocol version (field 15). */
    fun deviceInfoRequest(seq: Int): ByteArray = request(seq, 3, ByteArray(0))

    /**
     * RpcRequest {1: seq, 4: StreamControlReq}: the gestures on or off, as the Meta app asks. From protocol 2 a map by stream
     * type (field 36) with priority 90 (35) and the use case "GenericC50" (37); before it the one bool, enableGestures (3).
     */
    fun gestureStreams(seq: Int, on: Boolean, protocol: Int?): ByteArray {
        val map = protocol == null || protocol >= 2
        val entries = if (map) GESTURE_STREAMS.fold(ByteArray(0)) { out, type ->
            out + Wire.fieldBytes(36, Wire.fieldVarint(1, type.toLong()) + Wire.fieldVarint(2, if (on) 1 else 0))
        } + Wire.fieldVarint(35, PRIORITY_GENERIC_C50) + Wire.fieldBytes(37, "GenericC50".encodeToByteArray()) else ByteArray(0)
        val bool = if (!map || protocol == null) Wire.fieldVarint(3, if (on) 1 else 0) else ByteArray(0)
        return request(seq, 4, bool + entries)
    }

    /** RpcRequest {1: seq, 11: GestureAckReq {1 the gesture's seq, 2 source MWA_ANDROID, 3 topology DIRECT}}, one per gesture. */
    fun gestureAck(seq: Int, gesture: Int): ByteArray =
        request(seq, 11, Wire.fieldVarint(1, gesture.toLong()) + Wire.fieldVarint(2, SOURCE_MWA_ANDROID) + Wire.fieldVarint(3, TOPOLOGY_DIRECT))

    /** RpcRequest {1: seq, 17: HapticsReq {1: VibrateReq {1 duration, 2 frequency, 3 amplitude}}}; the units are not known. */
    fun vibrate(seq: Int, duration: Long, frequency: Float, amplitude: Int): ByteArray =
        request(seq, 17, Wire.fieldBytes(1, Wire.fieldVarint(1, duration) + Wire.fieldFloat(2, frequency) + Wire.fieldVarint(3, amplitude.toLong())))

    /** RpcRequest {1: seq, 17: HapticsReq {3: StopReq {}}}. */
    fun stopHaptics(seq: Int): ByteArray = request(seq, 17, Wire.fieldBytes(3, ByteArray(0)))

    fun isGesture(type: Int): Boolean = type == 13 || type in 60..200

    fun gesture(type: Int, payload: ByteArray): Gesture {
        val f = Wire.parse(payload)
        return Gesture(type, f.varint(1).toInt(), f.varint(3).toInt(), f.varint(4).toInt(), f.varint(5).toInt())
    }

    /** RpcRequest {1: seq, 5: ConfigReq}: with [leftHanded] it sets the wrist (field 10), empty it reads the config. */
    fun configRequest(seq: Int, leftHanded: Boolean? = null): ByteArray =
        request(seq, 5, leftHanded?.let { Wire.fieldVarint(10, if (it) 1 else 0) } ?: ByteArray(0))

    fun response(payload: ByteArray): Response {
        val f = Wire.parse(payload)
        val battery = if (f.has(3)) battery(Wire.parse(f.bytes(3)).bytes(1)) else null
        val left = if (f.has(6)) Wire.parse(f.bytes(6)).let { c -> if (c.has(10)) c.varint(10) != 0L else null } else null
        val protocol = if (f.has(4)) Wire.parse(f.bytes(4)).let { d -> if (d.has(15)) d.varint(15).toInt() else null } else null
        // HapticsResp {1: code}, in field 18 of the response.
        val haptics = if (f.has(18)) Wire.parse(f.bytes(18)).let { h -> if (h.has(1)) h.varint(1).toInt() else null } else null
        return Response(f.varint(1).toInt(), f.varint(2).toInt(), battery, left, protocol, haptics)
    }

    /** RpcStreamUpdate field 2, BatteryUpdate {1: BatteryData}. */
    fun streamBattery(payload: ByteArray): Battery? {
        val f = Wire.parse(payload)
        return if (f.has(2)) battery(Wire.parse(f.bytes(2)).bytes(1)) else null
    }

    // BatteryData {1: level, 2: charging, 3: temperature (float), 4: mV, 5: mA, 6: RTC seconds}.
    private fun battery(data: ByteArray): Battery? {
        val d = Wire.parse(data)
        return if (d.has(1)) Battery(d.varint(1).toInt(), d.varint(2) != 0L) else null
    }

    private fun request(seq: Int, field: Int, body: ByteArray) = Wire.fieldVarint(1, seq.toLong()) + Wire.fieldBytes(field, body)

    // Every gesture stream the Meta app turns on: 60-70, 100-109, 150-156 and 200.
    private val GESTURE_STREAMS = (60..70) + (100..109) + (150..156) + 200
    private const val PRIORITY_GENERIC_C50 = 90L
    private const val SOURCE_MWA_ANDROID = 2L
    private const val TOPOLOGY_DIRECT = 0L
}

/** svc150's NotifyLease {1: peerKeyHint, 2: leaseType}: the Display taking its band. Type 1 on the channel it opens. */
class NotifyLease(val keyHint: ByteArray, val type: Int) {
    /** A relay lease leaves the band's own phone link up; the others ask the phone to step back. */
    val relay: Boolean get() = type == RELAY

    companion object {
        const val MESSAGE_TYPE = 1
        const val RELAY = 1

        // The phone's answers: Meta's DeviceLeaseService sends Error 0 ("OK") once the lease is held.
        const val OK = 0x0000
        const val UNKNOWN_TYPE = 0xD001
        const val BAD_REQUEST = 0xD010

        fun decode(payload: ByteArray): NotifyLease {
            val f = Wire.parse(payload)
            return NotifyLease(f.bytes(1), f.varint(2).toInt())
        }
    }
}
