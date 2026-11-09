@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.SVC_STATUS
import com.macedonga.visor.core.connection.SnApp
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.kit.KitException
import com.macedonga.visor.kit.protocol.ErrorCode
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.MotionSample
import com.macedonga.visor.kit.protocol.MotionSource
import com.macedonga.visor.kit.protocol.Quaternion
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
 * app leaving stops it and deletes what the daemon kept on the glasses. Measured on the Display, 2026-11-08, and on
 * Ray-Ban Meta (Gen 2), which sends no magnetometer, 2026-11-09.
 * The same daemon speaks text in the glasses' voice, unasked: [say].
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

    // One daemon serves motion and speech: its start, configure and stop do not interleave.
    private val daemon = Mutex()

    // True while the running daemon was configured with its speech on; a restart or a stop clears it.
    @Volatile
    private var speaking = false
    private var idle: Job? = null

    init {
        scope.launch { connection.state.collect { if (it.link != LinkState.READY) speaking = false } }
    }

    /**
     * The glasses say [text] in their own voice, unasked. With no motion running, the daemon starts for it with speech
     * alone and stops after [SPEECH_IDLE_MS] without another line.
     */
    suspend fun say(text: String) = daemon.withLock {
        if (connection.state.value.link != LinkState.READY) throw KitException(ErrorCode.NOT_CONNECTED, "The glasses are not connected.")
        idle?.cancel()
        if (!speaking) {
            restartDaemon()
            val answer = configure(SPEECH_CONFIG) ?: throw KitException(ErrorCode.TIMEOUT, "The glasses' data collection did not answer.")
            if (Wire.parse(answer).varint(2) != 1L) {
                throw KitException(ErrorCode.FAILED, "The glasses refused the speech setup: ${Wire.parse(answer).bytes(3).decodeToString()}")
            }
            speaking = true
        }
        val reply = ask(PLAY_TTS, Wire.fieldString(1, "visor-say") + Wire.fieldString(2, text.trim()), PLAYED, ANSWER_MS * 4)
            ?: throw KitException(ErrorCode.TIMEOUT, "The glasses did not answer the speech.")
        val played = Wire.parse(reply)
        if (played.varint(2) != 1L) throw KitException(ErrorCode.FAILED, "The glasses did not speak: ${played.bytes(3).decodeToString()}")
        idle = scope.launch {
            delay(SPEECH_IDLE_MS)
            daemon.withLock {
                if (users == 0 && speaking) {
                    speaking = false
                    connection.submit { it.sendOnService(SVC_STATUS, SnApp.MT_SNINTENT, SnApp.intent(STOP_DAEMON)) }
                }
            }
        }
    }

    // The daemon starts when its property changes to 1: one that exited with the property still 1 starts again only
    // after a stop, so the property always goes to 0 first. It registers svc1027 about half a second after it starts.
    private suspend fun restartDaemon() {
        intent(STOP_DAEMON)
        delay(RESTART_MS)
        if (!intent(START_DAEMON)) throw KitException(ErrorCode.TIMEOUT, "The glasses did not start their data collection.")
        delay(DAEMON_MS)
    }

    // CONFIGURE, sent again while svc1027 is not registered yet; the answer, or null.
    private suspend fun configure(json: String): ByteArray? = (1..CONFIGURE_TRIES).firstNotNullOfOrNull {
        ask(CONFIGURE, Wire.fieldString(1, "visor-configure") + Wire.fieldString(2, json), CONFIGURED, ANSWER_MS)
    }

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

    private suspend fun open(rateHz: Int): Boolean = daemon.withLock {
        speaking = false
        restartDaemon()
        // A daemon still recording from before the link dropped refuses a new configure, so its recording stops first;
        // with none running, that is a no-op.
        connection.submit { it.sendOnService(SERVICE, STOP_RECORDING, Wire.fieldString(1, "visor-reset")) }
        val periodUs = 1_000_000 / rateHz
        val configured = configure(config(periodUs, rateHz)) ?: error("the glasses' data collection did not answer")
        if (Wire.parse(configured).varint(2) != 1L) error("the glasses refused the motion setup: ${Wire.parse(configured).bytes(3).decodeToString()}")
        speaking = true
        val recording = ask(START_RECORDING, Wire.fieldString(1, "visor-record"), RECORDING, ANSWER_MS)
        if (recording == null || Wire.parse(recording).varint(2) != 1L) error("the glasses did not start their sensors")
        val request = Wire.fieldString(1, "visor-subscribe") +
            CHANNELS.fold(ByteArray(0)) { out, id -> out + Wire.fieldBytes(2, Wire.fieldString(1, id) + Wire.fieldVarint(2, rateHz.toLong())) } +
            Wire.fieldVarint(3, BATCH_MS)
        val answer = ask(SUBSCRIBE, request, SUBSCRIBED, ANSWER_MS)?.let(Wire::parse) ?: error("the glasses did not answer the subscribe")
        val channels = answer.repeatedBytes(4).map { it.decodeToString() }
        if (answer.varint(2) != 1L || "accelerometer" !in channels) error("the glasses streamed ${channels.ifEmpty { listOf("nothing") }}: ${answer.bytes(3).decodeToString()}")
        true
    }

    // A svc28 SNIntent, true once the glasses answer it.
    private suspend fun intent(action: String): Boolean = withTimeoutOrNull(ANSWER_MS * 2) {
        connection.serviceData
            .onSubscription { connection.submit { it.sendOnService(SVC_STATUS, SnApp.MT_SNINTENT, SnApp.intent(action)) } }
            .first { it.serviceId == SVC_STATUS && it.messageType == SnApp.MT_SNINTENT + 1 }
    } != null

    // Sends [type] and waits for [reply] on svc1027.
    private suspend fun ask(type: Int, payload: ByteArray, reply: Int, waitMs: Long): ByteArray? = withTimeoutOrNull(waitMs) {
        connection.serviceData
            .onSubscription { connection.submit { it.sendOnService(SERVICE, type, payload) } }
            .first { it.serviceId == SERVICE && it.messageType == reply }
            .payload
    }

    private suspend fun stop() = daemon.withLock {
        speaking = false
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
            val a = accel.vector(i)
            val g = gyro?.takeIf { i < it.times.size }?.vector(i)
            samples.tryEmit(
                MotionSample(
                    time = accel.times[i] * 1_000_000,
                    accelerometer = a,
                    gyroscope = g,
                    magnetometer = magnetometer,
                    orientation = if (a != null && g != null) fusion.update(accel.times[i], a, g, magnetometer) else null,
                    source = MotionSource.GLASSES,
                ),
            )
        }
    }

    // The glasses send no orientation on this path: the phone fuses the three sensors into one, as DAT's samples carry.
    private val fusion = Madgwick()

    /**
     * Madgwick's orientation filter (Madgwick, "An efficient orientation filter for inertial and inertial/magnetic
     * sensor arrays", 2010): gyroscope integration corrected toward gravity and, with a magnetometer, toward north.
     */
    private class Madgwick {
        private var w = 1.0
        private var x = 0.0
        private var y = 0.0
        private var z = 0.0
        private var lastMs = 0L
        private var steps = 0

        @Synchronized
        fun update(timeMs: Long, a: Vector3, g: Vector3, m: Vector3?): Quaternion {
            val gap = lastMs == 0L || timeMs <= lastMs || timeMs - lastMs > MAX_STEP_MS
            val dt = if (gap) 0.0 else (timeMs - lastMs) / 1000.0
            lastMs = timeMs
            if (gap) steps = 0
            // A strong pull at first, so a new stream turns to gravity and north in a moment, then the usual one.
            val beta = if (steps++ < WARMUP_STEPS) WARMUP_BETA else BETA
            val gx = g.x.toDouble()
            val gy = g.y.toDouble()
            val gz = g.z.toDouble()
            // The rate of change from the gyroscope alone.
            var dw = 0.5 * (-x * gx - y * gy - z * gz)
            var dx = 0.5 * (w * gx + y * gz - z * gy)
            var dy = 0.5 * (w * gy - x * gz + z * gx)
            var dz = 0.5 * (w * gz + x * gy - y * gx)
            val an = norm(a.x.toDouble(), a.y.toDouble(), a.z.toDouble())
            if (an > 0) {
                val ax = a.x / an
                val ay = a.y / an
                val az = a.z / an
                val step = if (m != null && norm(m.x.toDouble(), m.y.toDouble(), m.z.toDouble()) > 0) marg(ax, ay, az, m) else imu(ax, ay, az)
                dw -= beta * step[0]
                dx -= beta * step[1]
                dy -= beta * step[2]
                dz -= beta * step[3]
            }
            w += dw * dt
            x += dx * dt
            y += dy * dt
            z += dz * dt
            val n = Math.sqrt(w * w + x * x + y * y + z * z)
            w /= n
            x /= n
            y /= n
            z /= n
            return Quaternion(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())
        }

        // The gradient step toward gravity alone.
        private fun imu(ax: Double, ay: Double, az: Double): DoubleArray {
            val f1 = 2 * (x * z - w * y) - ax
            val f2 = 2 * (w * x + y * z) - ay
            val f3 = 1 - 2 * (x * x + y * y) - az
            val s = doubleArrayOf(
                -2 * y * f1 + 2 * x * f2,
                2 * z * f1 + 2 * w * f2 - 4 * x * f3,
                -2 * w * f1 + 2 * z * f2 - 4 * y * f3,
                2 * x * f1 + 2 * y * f2,
            )
            return normalized(s)
        }

        // The gradient step toward gravity and north.
        private fun marg(ax: Double, ay: Double, az: Double, m: Vector3): DoubleArray {
            val mn = norm(m.x.toDouble(), m.y.toDouble(), m.z.toDouble())
            val mx = m.x / mn
            val my = m.y / mn
            val mz = m.z / mn
            // The magnetic field in the earth frame, kept horizontal toward north and vertical.
            val hx = 2 * mx * (0.5 - y * y - z * z) + 2 * my * (x * y - w * z) + 2 * mz * (x * z + w * y)
            val hy = 2 * mx * (x * y + w * z) + 2 * my * (0.5 - x * x - z * z) + 2 * mz * (y * z - w * x)
            val bx = Math.sqrt(hx * hx + hy * hy)
            val bz = 2 * mx * (x * z - w * y) + 2 * my * (y * z + w * x) + 2 * mz * (0.5 - x * x - y * y)
            val f1 = 2 * (x * z - w * y) - ax
            val f2 = 2 * (w * x + y * z) - ay
            val f3 = 2 * (0.5 - x * x - y * y) - az
            val f4 = 2 * bx * (0.5 - y * y - z * z) + 2 * bz * (x * z - w * y) - mx
            val f5 = 2 * bx * (x * y - w * z) + 2 * bz * (w * x + y * z) - my
            val f6 = 2 * bx * (w * y + x * z) + 2 * bz * (0.5 - x * x - y * y) - mz
            val s = doubleArrayOf(
                -2 * y * f1 + 2 * x * f2 - 2 * bz * y * f4 + (-2 * bx * z + 2 * bz * x) * f5 + 2 * bx * y * f6,
                2 * z * f1 + 2 * w * f2 - 4 * x * f3 + 2 * bz * z * f4 + (2 * bx * y + 2 * bz * w) * f5 + (2 * bx * z - 4 * bz * x) * f6,
                -2 * w * f1 + 2 * z * f2 - 4 * y * f3 + (-4 * bx * y - 2 * bz * w) * f4 + (2 * bx * x + 2 * bz * z) * f5 + (2 * bx * w - 4 * bz * y) * f6,
                2 * x * f1 + 2 * y * f2 + (-4 * bx * z + 2 * bz * x) * f4 + (-2 * bx * w + 2 * bz * y) * f5 + 2 * bx * x * f6,
            )
            return normalized(s)
        }

        private fun normalized(s: DoubleArray): DoubleArray {
            val n = Math.sqrt(s.sumOf { it * it })
            return if (n == 0.0) s else DoubleArray(4) { s[it] / n }
        }

        private fun norm(a: Double, b: Double, c: Double) = Math.sqrt(a * a + b * b + c * c)

        private companion object {
            const val BETA = 0.1
            const val WARMUP_BETA = 2.5
            const val WARMUP_STEPS = 60
            // A gap longer than this (a pause, a new stream) is not integrated.
            const val MAX_STEP_MS = 500L
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
        const val PLAY_TTS = 40
        const val PLAYED = 41
        const val SPEECH_IDLE_MS = 60_000L
        const val START_DAEMON = "action.miranda_data_collection_start"
        const val STOP_DAEMON = "action.miranda_data_collection_stop"
        val CHANNELS = listOf("accelerometer", "gyroscope", "magnetometer")
        const val BATCH_MS = 100L
        const val ANSWER_MS = 1_500L
        const val CONFIGURE_TRIES = 6
        const val STOP_MS = 1_000L
        const val DAEMON_MS = 1_000L
        const val RESTART_MS = 700L

        // The daemon's ConfigParser: session_info at the root (its session id lets START_RECORDING run), the sensors
        // under config. Motion only, through Android's sensor API, which reads whether or not the glasses are worn.
        fun config(periodUs: Int, rateHz: Int) =
            "{\"session_info\":{\"session_id\":\"visor-motion\"},\"config\":{\"sensors\":{" +
                "\"accelerometer\":{\"enabled\":true,\"period_us\":$periodUs,\"use_sensor_manager\":true}," +
                "\"gyroscope\":{\"enabled\":true,\"period_us\":$periodUs,\"use_sensor_manager\":true}," +
                "\"magnetometer\":{\"enabled\":true,\"sampling_rate_hz\":$rateHz},\"tts\":{\"enabled\":true}}}}"

        // Speech alone: the daemon's TtsSensorProvider plays text through the glasses' earcon service.
        const val SPEECH_CONFIG = "{\"session_info\":{\"session_id\":\"visor-speech\"},\"config\":{\"sensors\":{\"tts\":{\"enabled\":true}}}}"
    }
}
