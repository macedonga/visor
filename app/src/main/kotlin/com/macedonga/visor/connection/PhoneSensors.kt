package com.macedonga.visor.connection

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.log.responderScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * svc41120, webapp_phone_imu: the phone's own motion sensors for the Display's web apps, which read them through the
 * Generic Sensor API with `sensorId: 'phone'` (the browser's NativePhoneImuBridge). START_SENSORS (1)
 * {1 sensors, packed, 2 frequency Hz, float} and STOP_SENSORS (2) {1 sensors}; the phone answers SENSOR_DATA (10)
 * {1 readings} or SENSOR_ERROR (11) {1 sensor, 2 code, 3 message}. A reading is {1 sensor, 2 time ms, 3 x, 4 y, 5 z}, or
 * for the orientation {6 qw, 7 qx, 8 qy, 9 qz}. The Display asks the wearer for each site before it starts a sensor.
 */
@Singleton
class PhoneSensors @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: EventLog,
) : Responder {
    private val scope = responderScope(log, "Phone sensors")
    private val feeds = ConcurrentHashMap<Int, Feed>()
    private val manager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val thread by lazy { HandlerThread("phone-sensors").apply { start() } }

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val channel = message.info.channelId
        if (message.info.flags.isClosed) {
            feeds.remove(channel)?.stop()
            return
        }
        val f = runCatching { Wire.parse(message.payload) }.getOrNull() ?: return
        val sensors = sensorsOf(f)
        when (message.info.messageType) {
            MT_START -> {
                val feed = feeds.getOrPut(channel) { Feed(connection, channel).also { it.watch() } }
                val hz = f.float(2, DEFAULT_HZ).coerceIn(MIN_HZ, MAX_HZ)
                sensors.forEach { feed.start(it, hz) }
            }
            MT_STOP -> {
                val feed = feeds[channel] ?: return
                sensors.forEach { feed.stop(it) }
                if (feed.idle) feeds.remove(channel, feed).also { feed.stop() }
            }
            else -> log.add("Phone sensors: type ${message.info.messageType} not served")
        }
    }

    override suspend fun closed(channelId: Int, connection: DataXConnection) {
        feeds.remove(channelId)?.stop()
    }

    // The sensors as a packed list, the way the browser writes them, or one varint each.
    private fun sensorsOf(f: Wire.Fields): List<Int> {
        val packed = f.bytes(1)
        if (packed.isNotEmpty()) {
            val out = mutableListOf<Int>()
            var at = 0
            while (at < packed.size) {
                var value = 0L
                var shift = 0
                while (at < packed.size) {
                    val b = packed[at++].toInt() and 0xFF
                    value = value or ((b and 0x7F).toLong() shl shift)
                    if (b < 0x80) break
                    shift += 7
                }
                out += value.toInt()
            }
            return out
        }
        return f.repeatedVarints(1).map { it.toInt() }
    }

    // One channel's sensors; readings go out together every [FLUSH_MS] at most.
    private inner class Feed(private val mux: DataXConnection, private val channel: Int) {
        private val reply = DataXConnection.mirror(channel)
        private val listeners = ConcurrentHashMap<Int, SensorEventListener>()
        private val pending = ArrayList<ByteArray>()
        private var flusher: Job? = null
        private var linkWatch: Job? = null

        val idle: Boolean get() = listeners.isEmpty()

        // Channel ids repeat across links, so a feed ends with the link it came on.
        fun watch() {
            linkWatch = scope.launch {
                mux.awaitGone()
                if (feeds.remove(channel, this@Feed)) stop()
            }
            flusher = scope.launch {
                while (isActive) {
                    delay(FLUSH_MS)
                    val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
                    if (batch.isNotEmpty()) {
                        val body = batch.fold(ByteArray(0)) { out, reading -> out + Wire.fieldBytes(1, reading) }
                        runCatching { mux.send(reply, body, messageType = MT_DATA) }
                    }
                }
            }
        }

        fun start(sensor: Int, hz: Float) {
            stop(sensor)
            val type = ANDROID_TYPES[sensor]
            val hardware = type?.let { manager?.getDefaultSensor(it) }
            if (hardware == null) {
                val why = if (type == null) "unknown sensor $sensor" else "this phone has no ${hardwareName(sensor)}"
                scope.launch {
                    val body = Wire.fieldVarint(1, sensor.toLong()) + Wire.fieldVarint(2, ERROR_SENSOR_UNAVAILABLE) + Wire.fieldString(3, why)
                    runCatching { mux.send(reply, body, messageType = MT_ERROR) }
                }
                log.add("Phone sensors: $why")
                return
            }
            val gapNs = (1_000_000_000L / hz).toLong()
            val listener = object : SensorEventListener {
                private var lastNs = Long.MIN_VALUE
                private val quaternion = FloatArray(4)

                override fun onSensorChanged(event: SensorEvent) {
                    if (event.timestamp - lastNs < gapNs) return
                    lastNs = event.timestamp
                    var reading = Wire.fieldVarint(1, sensor.toLong()) + Wire.fieldVarint(2, System.currentTimeMillis())
                    reading += if (sensor == ORIENTATION) {
                        // [w, x, y, z] from the rotation vector.
                        SensorManager.getQuaternionFromVector(quaternion, event.values)
                        Wire.fieldFloat(6, quaternion[0]) + Wire.fieldFloat(7, quaternion[1]) +
                            Wire.fieldFloat(8, quaternion[2]) + Wire.fieldFloat(9, quaternion[3])
                    } else {
                        Wire.fieldFloat(3, event.values[0]) + Wire.fieldFloat(4, event.values[1]) + Wire.fieldFloat(5, event.values[2])
                    }
                    synchronized(pending) {
                        if (pending.size < MAX_PENDING) pending += reading
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            manager?.registerListener(listener, hardware, (1_000_000 / hz).toInt(), Handler(thread.looper))
            listeners[sensor] = listener
            log.add("Phone sensors: ${hardwareName(sensor)} for a web app at ${hz.toInt()} Hz")
        }

        fun stop(sensor: Int) {
            listeners.remove(sensor)?.let { manager?.unregisterListener(it) }
        }

        fun stop() {
            linkWatch?.cancel()
            flusher?.cancel()
            listeners.keys.toList().forEach(::stop)
        }
    }

    private fun hardwareName(sensor: Int) = NAMES[sensor] ?: "sensor $sensor"

    companion object {
        const val SVC = 41120
        private const val MT_START = 1
        private const val MT_STOP = 2
        private const val MT_DATA = 10
        private const val MT_ERROR = 11
        private const val ERROR_SENSOR_UNAVAILABLE = 2L
        // The browser clamps the rate to 1 to 100 Hz and asks for its default otherwise.
        private const val MIN_HZ = 1f
        private const val MAX_HZ = 100f
        private const val DEFAULT_HZ = 60f
        private const val FLUSH_MS = 30L
        private const val MAX_PENDING = 256
        private const val ORIENTATION = 5

        // The browser's sensor types: accelerometer, gyroscope, magnetometer, linear acceleration, gravity, absolute orientation.
        private val ANDROID_TYPES = mapOf(
            0 to Sensor.TYPE_ACCELEROMETER,
            1 to Sensor.TYPE_GYROSCOPE,
            2 to Sensor.TYPE_MAGNETIC_FIELD,
            3 to Sensor.TYPE_LINEAR_ACCELERATION,
            4 to Sensor.TYPE_GRAVITY,
            ORIENTATION to Sensor.TYPE_ROTATION_VECTOR,
        )
        private val NAMES = mapOf(
            0 to "accelerometer", 1 to "gyroscope", 2 to "magnetometer", 3 to "linear acceleration", 4 to "gravity sensor",
            ORIENTATION to "orientation sensor",
        )
    }
}
