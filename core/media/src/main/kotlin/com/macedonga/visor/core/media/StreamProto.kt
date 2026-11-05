package com.macedonga.visor.core.media

import com.macedonga.visor.core.protocol.Wire

object StreamType {
    const val CODEC_CONFIGURATION_UPDATE_REQUEST = 1
    const val CODEC_CONFIGURATION_UPDATE_RESPONSE = 2
    const val CONFIGURE_AUDIO_STREAM_REQUEST = 3
    const val CONFIGURE_AUDIO_STREAM_RESPONSE = 4
    const val CONFIGURE_VIDEO_STREAM_REQUEST = 5
    const val CONFIGURE_VIDEO_STREAM_RESPONSE = 6
    const val CONTROL_STREAM_REQUEST = 7
    const val CONTROL_STREAM_RESPONSE = 8
    const val AUDIO_CAST_BUFFER = 9
    const val VIDEO_CAST_BUFFER = 10
    const val AUDIO_CAST_BUFFER_END = 11
    const val VIDEO_CAST_BUFFER_END = 12
    const val CONTROL_STREAM_STOPPED = 13
    const val STREAMING_LIFECYCLE_NOTIFICATION = 19
    const val SYSTEM_HEALTH_EVENT = 21
    const val SUPPORTED_CONFIGURATION_UPDATE_REQUEST = 22
    const val USER_EVENT_NOTIFICATION = 23
    const val STREAM_TELEMETRY_EVENT = 25
    const val SESSION_SETTINGS = 27
    const val UPDATE_CLIENT_APP_SETTINGS = 28
    const val PHOTO_CAST_BUFFER = 34
    const val PHOTO_CAST_BUFFER_END = 35
    const val METADATA_CAST_BUFFER = 36
    const val CONFIGURE_MEDIA_STREAM_FEATURES = 40
}

object StreamAction {
    const val START = 1
    const val STOP = 2
}

/** ErrorCode, as the glasses answer a configure or control request. */
fun streamErrorWords(code: Long): String = when (code) {
    0L -> "success"
    1L -> "internal error"
    2L -> "invalid parameter"
    3L -> "unsupported parameter"
    4L -> "glasses not worn"
    5L -> "busy"
    else -> "error $code"
}

data class VideoConfig(
    val mime: String = "video/hevc",
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Int,
    val iFrameInterval: Int,
    val eisMode: Int = 1,
    val captureFrameRate: Int = frameRate,
)

/** SessionSettings: 1 connectivity, 2 abr, 3 timers, 4 application type, 5 overrides. */
fun sessionSettings(app: Int, doffTimerSec: Int, inactivityTimerSec: Int, bypassDonCheck: Boolean, allowOcr: Boolean = false): ByteArray =
    Wire.fieldBytes(3, Wire.fieldVarint(1, doffTimerSec.toLong()) + Wire.fieldVarint(2, inactivityTimerSec.toLong())) +
        Wire.fieldVarint(4, app.toLong()) +
        // OverrideSettings: 1 bypassDonCheck, 2 mediaBatchingIntervalMs, 3 allowFrameCapture, 4 allowOcrStreaming.
        Wire.fieldBytes(
            5,
            Wire.fieldVarint(1, if (bypassDonCheck) 1 else 0) + Wire.fieldVarint(2, 0) + Wire.fieldVarint(3, 1) +
                Wire.fieldVarint(4, if (allowOcr) 1 else 0),
        )

/** AudioConfig: the only codec is AAC, named by its mime; profile 0 is AAC-LC. */
data class AudioConfig(val sampleRate: Int = 48_000, val bitrate: Int = 96_000, val channels: Int = 2)

/** ConfigureAudioStreamRequest: 1 nonce, 2 AudioConfig {1 mime, 2 rate, 3 bitrate, 4 channels, 5 profile}, 3 enabled. */
fun configureAudio(nonce: Int, config: AudioConfig, enabled: Boolean = true): ByteArray {
    val c = Wire.fieldString(1, "audio/mp4a-latm") +
        Wire.fieldVarint(2, config.sampleRate.toLong()) +
        Wire.fieldVarint(3, config.bitrate.toLong()) +
        Wire.fieldVarint(4, config.channels.toLong()) +
        Wire.fieldVarint(5, 0)
    return Wire.fieldVarint(1, nonce.toLong()) + Wire.fieldBytes(2, c) + Wire.fieldVarint(3, if (enabled) 1 else 0)
}

/** UserEventNotification: 1 userEvent, 2 userEventAction. Action 4 asks for a photo while streaming. */
fun requestPhotoCapture(): ByteArray = Wire.fieldVarint(2, 4)

/** CodecConfigurationUpdateRequest field 2. */
object CodecType {
    const val AUDIO = 1L
    const val VIDEO = 2L
}

/** ConfigureVideoStreamRequest: 1 nonce, 2 VideoConfig, 3 enabled. */
fun configureVideo(nonce: Int, config: VideoConfig): ByteArray {
    val c = Wire.fieldString(1, config.mime) +
        Wire.fieldVarint(2, config.width.toLong()) +
        Wire.fieldVarint(3, config.height.toLong()) +
        Wire.fieldVarint(4, config.bitrate.toLong()) +
        Wire.fieldVarint(5, config.frameRate.toLong()) +
        Wire.fieldVarint(6, config.iFrameInterval.toLong()) +
        Wire.fieldVarint(7, config.eisMode.toLong()) +
        Wire.fieldVarint(8, config.captureFrameRate.toLong())
    return Wire.fieldVarint(1, nonce.toLong()) + Wire.fieldBytes(2, c) + Wire.fieldVarint(3, 1)
}

/** ControlStreamRequest: 1 nonce, 2 action, 3 session id. */
fun controlStream(nonce: Int, action: Int): ByteArray =
    Wire.fieldVarint(1, nonce.toLong()) + Wire.fieldVarint(2, action.toLong())

/** A configure or control response: 1 nonce, 2 Error {1 code, 2 reason}. No error field means success. */
data class StreamAnswer(val nonce: Long, val code: Long, val reason: String)

fun parseStreamAnswer(payload: ByteArray): StreamAnswer {
    val f = Wire.parse(payload)
    val error = Wire.parse(f.bytes(2))
    return StreamAnswer(f.varint(1), error.varint(1), String(error.bytes(2), Charsets.UTF_8))
}

/** ControlStreamStopped: 1 nonce, 2 reason. */
fun parseStreamStopped(payload: ByteArray): Long = Wire.parse(payload).varint(2)

/** ControlStreamStopped's reason, as the firmware names it. */
fun stopReasonWords(reason: Long): String = STOP_REASONS.getOrNull(reason.toInt()) ?: "reason $reason"

private val STOP_REASONS = listOf(
    "unknown", "user initiated", "system preempt", "system shutdown", "battery low", "thermal threshold", "inactivity",
    "camera failure", "doff", "temples closed", "audio failure", "mobile stream error", "peak power interrupt",
    "user pause", "tamper detected", "hardware toggle", "shutdown for battery", "frame initiated", "client preempt",
    "version not supported", "client initiated", "companion requested stop", "weak signal", "service destroyed",
)

const val STOP_BATTERY_LOW = 4L
const val STOP_THERMAL = 5L
const val STOP_TEMPLES_CLOSED = 9L

/**
 * SystemHealthEvent: 1 status (1 Critical, 2 PreCritical, 3 Warning, 4 Normal, 5 Ideal), 2 type (1 Connectivity,
 * 2 Battery, 3 Thermal, 4 PeakPower), 3 level as sint64.
 */
data class HealthEvent(val status: Long, val type: Long, val level: Long)

fun parseHealthEvent(payload: ByteArray): HealthEvent {
    val f = Wire.parse(payload)
    val raw = f.varint(3)
    return HealthEvent(f.varint(1), f.varint(2), (raw ushr 1) xor -(raw and 1))
}

/** A Thermal health event's status in svc28's words, so one reading serves both. */
fun healthThermalWords(status: Long): String? = when (status) {
    1L -> "CRITICAL"
    2L -> "SEVERE"
    3L -> "MODERATE"
    4L, 5L -> "NORMAL"
    else -> null
}
