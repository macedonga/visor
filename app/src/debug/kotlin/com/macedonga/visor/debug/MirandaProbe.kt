package com.macedonga.visor.debug

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.SVC_STATUS
import com.macedonga.visor.core.connection.SnApp
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.log.EventLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug only: the glasses' motion from their data collection daemon, svc1027 (research dat1-firmware.md 2.4), with no
 * toolkit app. Starts the daemon with the svc28 SNIntent, subscribes to the motion channels the daemon may name, and
 * logs what it accepts and what comes back. Motion channels only.
 */
internal object MirandaProbe {
    private const val SERVICE = 1027
    private const val START_RECORDING = 1
    private const val STOP_RECORDING = 3
    private const val CLEANUP = 5
    private const val CONFIGURE = 7
    private const val CONFIGURED = 8
    private const val SUBSCRIBE = 30
    private const val SUBSCRIBED = 31
    private const val BATCH = 32
    private const val UNSUBSCRIBE = 33
    private const val START = "action.miranda_data_collection_start"
    private const val STOP = "action.miranda_data_collection_stop"

    // The daemon's own names for its motion sources; its answer lists the ones it streams.
    val CANDIDATES = listOf(
        "imu_accelerometer", "imu_gyroscope", "accelerometer", "gyroscope", "accel", "gyro", "acc", "gyr", "imu",
        "imu_accel", "imu_gyro", "imu_default", "magnetometer", "mag", "heading",
    )

    // ConfigParser::parseConfigFromJson and parseSensorsConfig in the daemon: motion sensors only.
    // [extra] is more sensor entries, as JSON members, for trying the daemon's other sources.
    private fun config(periodUs: Int, rateHz: Int, extra: String) =
        "{\"session_info\":{\"session_id\":\"visor-motion\"},\"config\":{\"sensors\":{" +
            "\"accelerometer\":{\"enabled\":true,\"period_us\":$periodUs,\"use_sensor_manager\":true}," +
            "\"gyroscope\":{\"enabled\":true,\"period_us\":$periodUs,\"use_sensor_manager\":true}," +
            "\"magnetometer\":{\"enabled\":true,\"sampling_rate_hz\":$rateHz}," +
            "\"heading\":{\"enabled\":true,\"sampling_rate_hz\":$rateHz}" + (if (extra.isBlank()) "" else ",$extra") + "}}}"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val batches = AtomicInteger()

    fun start(connection: GlassesConnection, rateHz: Int, channels: List<String>, log: EventLog, extra: String = ""): String {
        stop(connection, log, quiet = true)
        batches.set(0)
        job = scope.launch {
            launch {
                connection.serviceData.collect { event ->
                    if (event.serviceId != SERVICE && !(event.serviceId == SVC_STATUS && event.messageType == SnApp.MT_SNINTENT + 1)) return@collect
                    heard(event.serviceId, event.messageType, event.payload, log)
                }
            }
            connection.submit { it.sendOnService(SVC_STATUS, SnApp.MT_SNINTENT, SnApp.intent(START)) }
            log.add("Miranda: asked the glasses to start data collection")
            delay(2_500)
            val json = config(1_000_000 / rateHz.coerceAtLeast(1), rateHz, extra)
            connection.submit { it.sendOnService(SERVICE, CONFIGURE, Wire.fieldString(1, "visor-config") + Wire.fieldString(2, json)) }
            log.add("Miranda: configured $json")
            delay(1_500)
            // Recording first: the subscribe wires the running sensors to the stream.
            connection.submit { it.sendOnService(SERVICE, START_RECORDING, Wire.fieldString(1, "visor-recording")) }
            log.add("Miranda: started recording")
            delay(1_500)
            val request = Wire.fieldString(1, "visor-motion") +
                channels.fold(ByteArray(0)) { out, id -> out + Wire.fieldBytes(2, Wire.fieldString(1, id) + Wire.fieldVarint(2, rateHz.toLong())) } +
                Wire.fieldVarint(3, 100)
            connection.submit { it.sendOnService(SERVICE, SUBSCRIBE, request) }
            log.add("Miranda: subscribed to ${channels.joinToString()} at $rateHz Hz")
        }
        return "starting; LOG for what the glasses answer"
    }

    fun stop(connection: GlassesConnection, log: EventLog, quiet: Boolean = false): String {
        if (job == null) return "not running"
        job?.cancel()
        job = null
        val request = Wire.fieldString(1, "visor-motion-stop") + CANDIDATES.fold(ByteArray(0)) { out, id -> out + Wire.fieldString(2, id) }
        // Unsubscribe, stop recording, delete what the daemon kept on the glasses, then stop the daemon.
        scope.launch {
            connection.submit { it.sendOnService(SERVICE, UNSUBSCRIBE, request) }
            connection.submit { it.sendOnService(SERVICE, STOP_RECORDING, Wire.fieldString(1, "visor-recording-stop")) }
            delay(1_500)
            connection.submit { it.sendOnService(SERVICE, CLEANUP, Wire.fieldString(1, "visor-cleanup")) }
            delay(1_000)
            connection.submit { it.sendOnService(SVC_STATUS, SnApp.MT_SNINTENT, SnApp.intent(STOP)) }
        }
        if (!quiet) log.add("Miranda: unsubscribed and stopped data collection after ${batches.get()} batches")
        return "stopped after ${batches.get()} batches"
    }

    private fun heard(service: Int, type: Int, payload: ByteArray, log: EventLog) {
        if (service == SVC_STATUS) return log.add("Miranda: svc28 answered the intent: ${payload.toHexString()}")
        when (type) {
            CONFIGURED -> {
                val f = Wire.parse(payload)
                log.add("Miranda: configure answered success=${f.varint(2)} \"${f.bytes(3).decodeToString()}\" ${payload.take(80).toByteArray().toHexString()}")
            }
            SUBSCRIBED -> {
                val f = Wire.parse(payload)
                val accepted = f.repeatedBytes(4).map { it.decodeToString() }
                log.add("Miranda: subscribe answered success=${f.varint(2)} \"${f.bytes(3).decodeToString()}\" channels $accepted")
            }
            BATCH -> {
                val n = batches.incrementAndGet()
                if (n <= 3 || n % 50 == 0) {
                    val parts = Wire.parse(payload).repeatedBytes(1).map { describe(Wire.parse(it)) }
                    log.add("Miranda: batch $n: ${parts.joinToString("; ")}")
                }
            }
            else -> log.add("Miranda: svc1027 type $type ${payload.take(120).toByteArray().toHexString()}")
        }
    }

    // One channel's samples: its id, how many, and the first sample read as floats and as doubles.
    private fun describe(c: Wire.Fields): String {
        val id = c.bytes(1).decodeToString()
        val per = c.varint(4).toInt().coerceAtLeast(1)
        val values = c.bytes(3)
        val stamps = c.bytes(2)
        val floats = ByteBuffer.wrap(values).order(ByteOrder.LITTLE_ENDIAN).let { b -> List(minOf(per, values.size / 4)) { b.float } }
        val doubles = ByteBuffer.wrap(values).order(ByteOrder.LITTLE_ENDIAN).let { b -> List(minOf(per, values.size / 8)) { b.double } }
        return "$id x$per, ${values.size} value bytes, ${stamps.size} stamp bytes, first as floats $floats, as doubles $doubles"
    }
}
