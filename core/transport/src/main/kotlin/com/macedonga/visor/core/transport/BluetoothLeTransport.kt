package com.macedonga.visor.core.transport

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The GATT service the glasses expose, and the characteristic that holds their L2CAP PSM. */
val META_LE_SERVICE: UUID = UUID.fromString("0000fd5f-0000-1000-8000-00805f9b34fb")
val META_PSM_CHARACTERISTIC: UUID = UUID.fromString("05acbe9f-6f61-4ca9-80bf-c8bbb52991c0")

/** The PSM lensbreak connects to when GATT does not say. */
const val META_LE_DEFAULT_PSM: Int = 0x80

/**
 * The byte pipe under AirShield over Bluetooth LE: an L2CAP channel to the bonded glasses at [address], on the LE
 * link the [keeper] holds. The glasses keep it up while folded or charging in the case, when RFCOMM goes quiet.
 * Ported from lensbreak's link/l2cap_le.py.
 */
class BluetoothLeTransport(
    private val context: Context,
    private val address: String,
    private val wireLog: WireLog? = null,
    private val keeper: LeKeeper? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // The Neural Band's is 129.
    private val defaultPsm: Int = META_LE_DEFAULT_PSM,
) : Transport {

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var stream: SduStream? = null

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun connect() {
        close()
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw TransportConnectException(ConnectFailure.PERMISSION_REFUSED, "Visor may not use Bluetooth yet")
        }
        val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
            ?: throw TransportConnectException(ConnectFailure.BLUETOOTH_OFF, "Bluetooth is off")
        // The usual PSM first: reading it over GATT on a fresh link cost up to 6 s; read only when that one fails.
        val known = keeper?.psm
        val first = known ?: defaultPsm
        keeper?.note("opening L2CAP psm $first, LE link ${if (keeper.connected.value) "up" else "down"}")
        try {
            open(device, first)
        } catch (refused: TransportConnectException) {
            if (known != null || refused.failure == ConnectFailure.PERMISSION_REFUSED) throw refused
            val read = withTimeoutOrNull(GATT_MS) { readPsm(device) }
            if (read == null || read == first) throw refused
            keeper?.note("opening L2CAP psm $read, as the glasses name it")
            open(device, read)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun open(device: android.bluetooth.BluetoothDevice, psm: Int) {
        withContext(io) {
            val opening = try {
                device.createInsecureL2capChannel(psm)
            } catch (error: SecurityException) {
                throw TransportConnectException(ConnectFailure.PERMISSION_REFUSED, "Visor may not use Bluetooth yet", error)
            }
            // A healthy open takes a fraction of a second; after Visor died with a channel open, Android's own wait is
            // 20 to 30 s for nothing. Closing the socket ends the wait.
            // Whoever flips [settled] first wins: the timer closes the socket only if it gets there before the connect ends.
            val settled = AtomicBoolean(false)
            val timedOut = AtomicBoolean(false)
            val watchdog = java.util.Timer(true).apply {
                schedule(object : java.util.TimerTask() {
                    override fun run() {
                        if (!settled.compareAndSet(false, true)) return
                        timedOut.set(true)
                        runCatching { opening.close() }
                    }
                }, L2CAP_OPEN_MS)
            }
            try {
                opening.connect()
                if (!settled.compareAndSet(false, true)) throw IOException("the watchdog closed the socket")
            } catch (error: IOException) {
                runCatching { opening.close() }
                val why = if (timedOut.get()) "no answer in ${L2CAP_OPEN_MS / 1000} s" else error.message
                throw TransportConnectException(ConnectFailure.NO_ANSWER, "the glasses did not open L2CAP psm $psm: $why", error)
            } finally {
                watchdog.cancel()
            }
            socket = opening
            stream = SduStream(opening.inputStream, opening.outputStream, wireLog)
        }
    }

    override suspend fun send(frame: ByteArray) {
        withContext(io) { open().send(frame) }
    }

    override suspend fun receive(): ByteArray = withContext(io) { open().receive() }

    @android.annotation.SuppressLint("MissingPermission")
    override fun close() {
        stream = null
        socket?.let { runCatching { it.close() } }
        socket = null
        gatt?.let { runCatching { it.close() } }
        gatt = null
    }

    private fun open(): SduStream = stream ?: throw TransportClosedException("not connected")

    /** The PSM from the glasses' GATT characteristic, when the keeper has not read it yet. */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun readPsm(device: BluetoothDevice): Int? {
        val result = CompletableDeferred<Int?>()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else if (newState == BluetoothProfile.STATE_DISCONNECTED) result.complete(null)
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val characteristic = g.getService(META_LE_SERVICE)?.getCharacteristic(META_PSM_CHARACTERISTIC)
                if (characteristic == null || !g.readCharacteristic(characteristic)) result.complete(null)
            }

            override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                result.complete(if (status == BluetoothGatt.GATT_SUCCESS) parsePsm(value) else null)
            }

            @Deprecated("Before Android 13 the value comes on the characteristic.")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                result.complete(if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value?.let(::parsePsm) else null)
            }
        }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        return result.await()
    }

    private companion object {
        const val GATT_MS = 6_000L
        const val L2CAP_OPEN_MS = 8_000L
    }
}

/**
 * The PSM characteristic is 16-bit little-endian words, e.g. `00 00 81 00`; the channel's PSM is the word in the LE
 * dynamic range, else the fixed one (lensbreak notes/72).
 */
internal fun parsePsm(value: ByteArray): Int? {
    val words = (0 until value.size / 2).map { (value[it * 2].toInt() and 0xFF) or ((value[it * 2 + 1].toInt() and 0xFF) shl 8) }
    return words.firstOrNull { it in 0x80..0xFF } ?: words.firstOrNull { it in 0x01..0x7F }
}

/**
 * LE first, as the Meta app: the glasses keep that link folded and in the case, so the session lives through their
 * sleep and everything they say about hinge and charging arrives on it. RFCOMM only while no LE link is held, or
 * when the glasses refuse the channel. The session above never knows which; [onLe] says.
 */
class LeFirstTransport(
    private val le: Transport,
    private val rfcomm: Transport,
    private val keeper: LeKeeper,
    private val onLe: (Boolean) -> Unit = {},
) : Transport {
    @Volatile private var active: Transport? = null

    override suspend fun connect() {
        // The held link comes up a few seconds after the service starts: worth waiting for.
        if (withTimeoutOrNull(LE_WAIT_MS) { keeper.connected.first { it } } != null) {
            try {
                le.connect()
                active = le
                onLe(true)
                keeper.note("session over L2CAP")
                return
            } catch (refused: TransportConnectException) {
                keeper.note("L2CAP refused, trying RFCOMM: ${refused.message}")
            }
        }
        rfcomm.connect()
        active = rfcomm
        onLe(false)
        keeper.note("session over RFCOMM")
    }

    override suspend fun send(frame: ByteArray) = (active ?: throw TransportClosedException("not connected")).send(frame)

    override suspend fun receive(): ByteArray = (active ?: throw TransportClosedException("not connected")).receive()

    override fun close() {
        active = null
        le.close()
        rfcomm.close()
    }

    private companion object {
        const val LE_WAIT_MS = 8_000L
    }
}
