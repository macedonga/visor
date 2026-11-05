package com.macedonga.visor.core.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds a Bluetooth LE connection to the glasses, as the Meta app does. Pairing bonds them over both radios, so
 * the bonded device is reached over LE by its classic address with no scan (they do not advertise openly once
 * bonded); GATT auto-connect keeps the link up, and back up, whenever they are in reach. Asleep they drop RFCOMM
 * but keep LE, and the session opens its L2CAP channel on it.
 */
class LeKeeper(private val context: Context) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    @Volatile private var device: BluetoothDevice? = null

    @Volatile var psm: Int? = null
        private set

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var highPriority = false

    fun note(text: String) {
        _notes.tryEmit("LE: $text")
    }

    /** Hold the bonded glasses at [address]; does nothing while they are already held. */
    @SuppressLint("MissingPermission")
    fun hold(address: String) {
        if (gatt != null && device?.address == address) return
        release()
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return note("no Bluetooth permission")
        }
        val found = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address) ?: return note("no Bluetooth")
        device = found
        note("holding $address (bond ${found.bondState}, type ${found.type})")
        gatt = found.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * A short connection interval while the glasses are awake, so previews and transfers move quickly over LE; a
     * relaxed one while they sleep, to spare both batteries.
     */
    fun fast(on: Boolean) {
        highPriority = on
        if (_connected.value) gatt?.let { prioritise(it) }
    }

    @SuppressLint("MissingPermission")
    private fun prioritise(g: BluetoothGatt) {
        val asked = g.requestConnectionPriority(if (highPriority) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        note("connection priority ${if (highPriority) "high" else "balanced"}${if (asked) "" else " refused"}")
    }

    @SuppressLint("MissingPermission")
    fun release() {
        gatt?.let { runCatching { it.close() } }
        gatt = null
        device = null
        psm = null
        _connected.value = false
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val up = newState == BluetoothProfile.STATE_CONNECTED
            _connected.value = up
            note("connection ${if (up) "up" else "down"} (status $status)")
            if (!up) return
            // The faster radio: twice the throughput of 1M where both ends have it.
            g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
            prioritise(g)
            if (psm == null) g.discoverServices()
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            note("phy tx $txPhy rx $rxPhy (status $status)")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g.getService(META_LE_SERVICE)?.getCharacteristic(META_PSM_CHARACTERISTIC)
            if (characteristic == null) note("no PSM characteristic (status $status)") else g.readCharacteristic(characteristic)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            psm = parsePsm(value)
            note("psm $psm (status $status)")
        }

        @Deprecated("Before Android 13 the value comes on the characteristic.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            psm = characteristic.value?.let(::parsePsm)
            note("psm $psm (status $status)")
        }
    }
}
