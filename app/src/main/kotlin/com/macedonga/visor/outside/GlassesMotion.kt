@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.SVC_STATUS
import com.macedonga.visor.core.connection.SnApp
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.MotionSample
import com.macedonga.visor.kit.protocol.MotionSource
import com.macedonga.visor.kit.protocol.Vector3
import com.macedonga.visor.log.EventLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The glasses' motion from their data collection daemon, svc1027 (miranda), with no toolkit app and nothing on the
 * screen. The svc28 SNIntent starts the daemon; CONFIGURE turns on the accelerometer, gyroscope and magnetometer
 * through Android's sensor API; START_RECORDING runs them; SUBSCRIBE_STREAM sends a SensorDataBatch every 100 ms.
 * Every app shares one stream at the first app's rate. It starts again when the glasses' link comes back, and the last
 * app leaving stops it and deletes what the daemon kept on the glasses. Measured on the Display, 2026-11-08.
 */
@Singleton
class GlassesMotion @Inject constructor(
    private val connection: GlassesConnection,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val samples = MutableSharedFlow<MotionSample>(extraBufferCapacity = 1024)
    private var users = 0
    private var job: Job? = null

    /** The glasses' motion at [rateHz] while collected. */
    fun samples(rateHz: Int): Flow<MotionSample> = flow {
        gate.withLock {
            if (users++ == 0) job = scope.launch { run(rateHz) }
        }
        try {
            samples.collect { emit(it) }
        } finally {
            withContext(NonCancellable) {
                gate.withLock {
                    if (--users == 0) {
                        job?.cancel()
                        job = null
                        stop()
                    }
                }
            }
        }
    }

    // Each time the glasses' link is up: start, configure, record, subscribe, then read batches until it drops.
    private suspend fun run(rateHz: Int) {
        connection.state.map { it.link == LinkState.READY }.distinctUntilChanged().collectLatest { ready ->
            if (!ready) return@collectLatest
            val subscribed = runCatching { open(rateHz) }.onFailure { log.add("Motion: ${it.message}") }.getOrDefault(false)
            if (!subscribed) return@collectLatest
            log.add("Motion: streaming at $rateHz Hz from the glasses' sensors")
            connection.serviceData.collect { event ->
                if (event.serviceId == SERVICE && event.messageType == BATCH) runCatching { batch(event.payload) }
            }
        }
    }

    private suspend fun open(rateHz: Int): Boolean {
        val started = withTimeoutOrNull(ANSWER_MS * 2) {
            connection.serviceData
                .onSubscription { connection.submit { it.sendOnService(SVC_STATUS, SnApp.MT_SNINTENT, SnApp.intent(START_DAEMON)) } }
                .first { it.serviceId == SVC_STATUS && it.messageType == SnApp.MT_SNINTENT + 1 }
        }
        if (started == null) error("the glasses did not start their data collection")
        // The daemon registers svc1027 about half a second after it starts. A daemon still recording from before the
        // link dropped refuses a new configure, so its recording stops first; with none running, that is a no-op.
        delay(DAEMON_MS)
        connection.submit { it.sendOnService(SERVICE, STOP_RECORDING, Wire.fieldString(1, "visor-reset")) }
        val periodUs = 1_000_000 / rateHz
        val configured = (1..CONFIGURE_TRIES).firstNotNullOfOrNull {
            ask(CONFIGURE, Wire.fieldString(1, "visor-configure") + Wire.fieldString(2, config(periodUs, rateHz)), CONFIGURED, ANSWER_MS)
        } ?: error("the glasses' data collection did not answer")
        if (Wire.parse(configured).varint(2) != 1L) error("the glasses refused the motion setup: ${Wire.parse(configured).bytes(3).decodeToString()}")
        val recording = ask(START_RECORDING, Wire.fieldString(1, "visor-record"), RECORDING, ANSWER_MS)
        if (recording == null || Wire.parse(recording).varint(2) != 1L) error("the glasses did not start their sensors")
        val request = Wire.fieldString(1, "visor-subscribe") +
            CHANNELS.fold(ByteArray(0)) { out, id -> out + Wire.fieldBytes(2, Wire.fieldString(1, id) + Wire.fieldVarint(2, rateHz.toLong())) } +
            Wire.fieldVarint(3, BATCH_MS)
        val answer = ask(SUBSCRIBE, request, SUBSCRIBED, ANSWER_MS)?.let(Wire::parse) ?: error("the glasses did not answer the subscribe")
        val channels = answer.repeatedBytes(4).map { it.decodeToString() }
        if (answer.varint(2) != 1L || "accelerometer" !in channels) error("the glasses streamed ${channels.ifEmpty { listOf("nothing") }}: ${answer.bytes(3).decodeToString()}")
        return true
    }

    // Sends [type] and waits for [reply] on svc1027.
    private suspend fun ask(type: Int, payload: ByteArray, reply: Int, waitMs: Long): ByteArray? = withTimeoutOrNull(waitMs) {
        connection.serviceData
            .onSubscription { connection.submit { it.sendOnService(SERVICE, type, payload) } }
            .first { it.serviceId == SERVICE && it.messageType == reply }
            .payload
    }

    private suspend fun stop() {
        connection.submit { it.sendOnService(SERVICE, UNSUBSCRIBE, Wire.fieldString(1, "visor-unsubscribe") + CHANNELS.fold(ByteArray(0)) { out, id -> out + Wire.fieldString(2, id) }) }
        connection.submit { it.sendOnService(SERVICE, STOP_RECORDING, Wire.fieldString(1, "visor-stop")) }
        // The stop finishes the recording file; the cleanup then deletes it, so nothing stays on the glasses.
        delay(STOP_MS)
        connection.submit { it.sendOnService(SERVICE, CLEANUP, Wire.fieldString(1, "visor-cleanup")) }
        delay(STOP_MS)
        connection.submit { it.sendOnService(SVC_STATUS, SnApp.MT_SNINTENT, SnApp.intent(STOP_DAEMON)) }
        log.add("Motion: stopped the glasses' data collection")
    }

    // SensorDataBatch {1 SensorChannelSamples {1 channel_id, 2 packed timestamps_ms, 3 packed doubles, 4 values_per_sample}}.
    // The accelerometer and gyroscope share their timestamps; each sample carries the latest magnetometer reading.
    private fun batch(payload: ByteArray) {
        val channels = Wire.parse(payload).repeatedBytes(1).map(Wire::parse).associate { c ->
            c.bytes(1).decodeToString() to Channel(varints(c.bytes(2)), doubles(c.bytes(3)), c.varint(4).toInt().coerceAtLeast(1))
        }
        channels["magnetometer"]?.let { m -> if (m.times.isNotEmpty()) magnetometer = m.vector(m.times.lastIndex) }
        val accel = channels["accelerometer"] ?: return
        val gyro = channels["gyroscope"]
        for (i in accel.times.indices) {
            samples.tryEmit(
                MotionSample(
                    time = accel.times[i] * 1_000_000,
                    accelerometer = accel.vector(i),
                    gyroscope = gyro?.takeIf { i < it.times.size }?.vector(i),
                    magnetometer = magnetometer,
                    source = MotionSource.GLASSES,
                ),
            )
        }
    }

    @Volatile private var magnetometer: Vector3? = null

    private class Channel(val times: List<Long>, val values: DoubleArray, val per: Int) {
        fun vector(i: Int): Vector3? = if (per < 3 || (i + 1) * per > values.size) null else
            Vector3(values[i * per].toFloat(), values[i * per + 1].toFloat(), values[i * per + 2].toFloat())
    }

    private fun varints(data: ByteArray): List<Long> {
        val out = ArrayList<Long>()
        var at = 0
        while (at < data.size) Wire.decodeVarint(data, at).let { out += it.value; at = it.offset }
        return out
    }

    private fun doubles(data: ByteArray): DoubleArray =
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).let { b -> DoubleArray(data.size / 8) { b.double } }

    private companion object {
        const val SERVICE = 1027
        const val START_RECORDING = 1
        const val RECORDING = 2
        const val STOP_RECORDING = 3
        const val CLEANUP = 5
        const val CONFIGURE = 7
        const val CONFIGURED = 8
        const val SUBSCRIBE = 30
        const val SUBSCRIBED = 31
        const val BATCH = 32
        const val UNSUBSCRIBE = 33
        const val START_DAEMON = "action.miranda_data_collection_start"
        const val STOP_DAEMON = "action.miranda_data_collection_stop"
        val CHANNELS = listOf("accelerometer", "gyroscope", "magnetometer")
        const val BATCH_MS = 100L
        const val ANSWER_MS = 1_500L
        const val CONFIGURE_TRIES = 6
        const val STOP_MS = 1_000L
        const val DAEMON_MS = 1_000L

        // The daemon's ConfigParser: session_info at the root (its session id lets START_RECORDING run), the sensors
        // under config. Motion only, through Android's sensor API, which reads whether or not the glasses are worn.
        fun config(periodUs: Int, rateHz: Int) =
            "{\"session_info\":{\"session_id\":\"visor-motion\"},\"config\":{\"sensors\":{" +
                "\"accelerometer\":{\"enabled\":true,\"period_us\":$periodUs,\"use_sensor_manager\":true}," +
                "\"gyroscope\":{\"enabled\":true,\"period_us\":$periodUs,\"use_sensor_manager\":true}," +
                "\"magnetometer\":{\"enabled\":true,\"sampling_rate_hz\":$rateHz}}}}"
    }
}
