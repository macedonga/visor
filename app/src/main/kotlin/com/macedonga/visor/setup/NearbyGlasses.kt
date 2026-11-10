package com.macedonga.visor.setup

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.productName
import com.macedonga.visor.core.transport.META_LE_SERVICE
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Meta glasses the phone can hear or is bonded with. [rssi] is null for a bonded pair that is not advertising. */
data class NearbyPair(
    val address: String,
    val name: String,
    val rssi: Int?,
    /** Advertising that they take a new phone: pairing mode. */
    val pairing: Boolean,
    val bonded: Boolean,
)

/** Why a bond did not happen. */
class BondFailed(val why: Why) : Exception(why.name) {
    enum class Why { REFUSED, NO_ANSWER, BLUETOOTH_OFF }
}

/** Finds Meta glasses (company id 0x01AB or service 0xFD5F) and bonds them over LE; the glasses derive the classic key from it. */
@Singleton
class NearbyGlasses @Inject constructor(@ApplicationContext private val context: Context, private val log: EventLog) {
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val enabled: Boolean get() = adapter?.isEnabled == true

    /** What is heard nearby, refreshed while collected; a pair not heard for a while drops out. */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<List<NearbyPair>> = callbackFlow {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(BondFailed(BondFailed.Why.BLUETOOTH_OFF))
            return@callbackFlow
        }
        val heard = LinkedHashMap<String, Pair<NearbyPair, Long>>()
        fun publish() {
            val now = SystemClock.elapsedRealtime()
            val list = synchronized(heard) {
                heard.values.removeAll { now - it.second > FORGET_MS }
                heard.values.map { it.first }
            }
            trySend(list)
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val data = record.getManufacturerSpecificData(META_COMPANY)
                if (data == null && record.serviceUuids?.any { it.uuid == META_LE_SERVICE } != true) return
                val advert = data?.let(::advert)
                if (advert?.product in PRODUCT_BANDS) return
                val device = result.device
                val name = record.deviceName ?: runCatching { device.name }.getOrNull() ?: nameOf(advert?.product)
                if (BAND_NAME.containsMatchIn(name)) return
                val pair = NearbyPair(device.address, name, result.rssi, advert?.pairing == true, device.bondState == BluetoothDevice.BOND_BONDED)
                synchronized(heard) { heard[device.address] = pair to SystemClock.elapsedRealtime() }
                publish()
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("scan failed: $errorCode"))
            }
        }
        // No filters: offloaded manufacturer filters with empty data match nothing on some chips; the screen is on anyway.
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        val sweep = launch {
            while (true) {
                delay(SWEEP_MS)
                // Bluetooth turned off stops the scan with no callback.
                if (adapter?.isEnabled != true) {
                    close(BondFailed(BondFailed.Why.BLUETOOTH_OFF))
                    break
                }
                publish()
            }
        }
        awaitClose {
            sweep.cancel()
            runCatching { scanner.stopScan(callback) }
        }
    }

    /** Glasses this phone is already bonded with, by their name: they may not advertise at all. */
    @SuppressLint("MissingPermission")
    fun bonded(): List<NearbyPair> = runCatching {
        adapter?.bondedDevices.orEmpty()
            .filter { d -> d.name?.let { GLASSES_NAME.containsMatchIn(it) && !BAND_NAME.containsMatchIn(it) } == true }
            .map { NearbyPair(it.address, it.name, null, pairing = false, bonded = true) }
    }.getOrDefault(emptyList())

    /**
     * Bonds [address] over LE; returns the identity address the bond is kept under. Polled: the bond broadcast names
     * only that address. With [newLink], it returns once the LE link the bond went over has closed.
     */
    @SuppressLint("MissingPermission", "NewApi")
    suspend fun bond(address: String, newLink: Boolean = false): String {
        val adapter = adapter?.takeIf { it.isEnabled } ?: throw BondFailed(BondFailed.Why.BLUETOOTH_OFF)
        val device = adapter.getRemoteDevice(address)
        if (device.bondState == BluetoothDevice.BOND_BONDED) return address
        val before = adapter.bondedDevices.map { it.address }.toSet()
        // Left to choose, some phones page the pair over BR/EDR first and time out (#92). From Android 17 the bond
        // names LE; before, an LE link held open makes the phone bond on it.
        val modern = Build.VERSION.SDK_INT >= 37
        val link = if (modern) null else leLink(device)
        fun letGo() {
            link?.let { runCatching { it.disconnect() }; it.close() }
        }
        val kept = try {
            awaitBond(adapter, device, before, modern)
        } catch (error: Throwable) {
            letGo()
            throw error
        }
        if (!newLink || kept.equals(address, ignoreCase = true)) {
            letGo()
            return kept
        }
        // The phone keeps the bond's LE link under the random address. While it is up, an L2CAP channel asked for
        // under [kept] waits on a second link that never comes (#92); the next link comes up under [kept].
        val closed = leLinkClosed(setOf(address, kept), ::letGo)
        log.add("Bond: kept under $kept; the LE link on $address ${if (closed) "closed" else "still up after ${LINK_GONE_MS / 1000} s"}")
        return kept
    }

    @SuppressLint("MissingPermission", "NewApi")
    private suspend fun awaitBond(adapter: BluetoothAdapter, device: BluetoothDevice, before: Set<String>, modern: Boolean): String {
        val asked = if (modern) device.createBond(BluetoothDevice.TRANSPORT_LE) else device.createBond()
        // The glasses may start the bond themselves once the link is up.
        if (!asked && device.bondState == BluetoothDevice.BOND_NONE) throw BondFailed(BondFailed.Why.REFUSED)
        var bonding = false
        val deadline = SystemClock.elapsedRealtime() + BOND_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_MS)
            val state = device.bondState
            val added = adapter.bondedDevices.filter { it.address !in before }
            if (state == BluetoothDevice.BOND_BONDED || added.isNotEmpty()) {
                return added.firstOrNull { it.address == device.address }?.address
                    ?: added.firstOrNull { it.name == device.name }?.address
                    ?: added.firstOrNull()?.address
                    ?: device.address
            }
            if (state == BluetoothDevice.BOND_BONDING) bonding = true
            // Back to none after trying: turned down on the phone or by the glasses.
            if (bonding && state == BluetoothDevice.BOND_NONE) throw BondFailed(BondFailed.Why.REFUSED)
        }
        throw BondFailed(BondFailed.Why.NO_ANSWER)
    }

    /** Runs [letGo], then waits up to [LINK_GONE_MS] for the LE link to any of [addresses] to close; true once it has. */
    private suspend fun leLinkClosed(addresses: Set<String>, letGo: () -> Unit): Boolean {
        val closed = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                // A classic link to the same address closing says nothing about the LE one.
                if (intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE) != BluetoothDevice.TRANSPORT_LE) return
                if (addresses.any { it.equals(device.address, ignoreCase = true) }) closed.complete(Unit)
            }
        }
        // Sent by the Bluetooth app, not the system: a receiver that is not exported never hears it.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED), ContextCompat.RECEIVER_EXPORTED)
        return try {
            letGo()
            withTimeoutOrNull(LINK_GONE_MS) { closed.await() } != null
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    /** An LE link to [device] for a bond to go over; null when it is not up within [LINK_MS]. */
    @SuppressLint("MissingPermission")
    private suspend fun leLink(device: BluetoothDevice): BluetoothGatt? {
        val up = CompletableDeferred<Boolean>()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) up.complete(true)
                if (newState == BluetoothProfile.STATE_DISCONNECTED) up.complete(false)
            }
        }
        val gatt = runCatching { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull() ?: return null
        if (withTimeoutOrNull(LINK_MS) { up.await() } == true) return gatt
        gatt.close()
        return null
    }

    /**
     * Bonds [address] over BR/EDR only, a call public from Android 17, polling as [bond] does until [until]
     * (elapsedRealtime). Returns the device type the bond ends with: 1 classic, 3 dual.
     */
    @RequiresApi(37)
    @SuppressLint("MissingPermission")
    suspend fun bondClassic(address: String, until: Long): Int {
        val adapter = adapter?.takeIf { it.isEnabled } ?: throw BondFailed(BondFailed.Why.BLUETOOTH_OFF)
        val device = adapter.getRemoteDevice(address)
        if (device.bondState == BluetoothDevice.BOND_BONDED) return device.type
        if (!device.createBond(BluetoothDevice.TRANSPORT_BREDR)) throw BondFailed(BondFailed.Why.REFUSED)
        var bonding = false
        while (SystemClock.elapsedRealtime() < until) {
            delay(POLL_MS)
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> return device.type
                BluetoothDevice.BOND_BONDING -> bonding = true
                else -> if (bonding) throw BondFailed(BondFailed.Why.REFUSED)
            }
        }
        throw BondFailed(BondFailed.Why.NO_ANSWER)
    }

    /** Stops a bond still under way with [address]. */
    @SuppressLint("MissingPermission", "NewApi") // public from 37, a hidden but callable method before; runCatching takes a refusal
    fun cancelBond(address: String): Boolean = runCatching { adapter?.getRemoteDevice(address)?.cancelBondProcess() == true }.getOrDefault(false)

    /** Every bonded device as address and type (1 classic, 2 LE, 3 dual). */
    @SuppressLint("MissingPermission")
    fun bondedTypes(): List<Pair<String, Int>> = runCatching { adapter?.bondedDevices.orEmpty().map { it.address to it.type } }.getOrDefault(emptyList())

    /** How the phone holds [address], for the log: bond state, type and, from Android 17, the bond on each transport. */
    @SuppressLint("MissingPermission", "NewApi")
    fun describe(address: String): String {
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return "$address unknown"
        val base = runCatching { "$address bond ${device.bondState} type ${device.type}" }.getOrElse { "$address unreadable: ${it.message}" }
        if (Build.VERSION.SDK_INT < 37) return base
        val transports = runCatching {
            " le ${device.getBondStatus(BluetoothDevice.TRANSPORT_LE)} bredr ${device.getBondStatus(BluetoothDevice.TRANSPORT_BREDR)}"
        }.getOrElse { " transports unreadable: ${it.message}" }
        return base + transports
    }

    /**
     * The Neural Band heard nearby, one in pairing mode before the strongest: its address, or null when none answers
     * within [BAND_SCAN_MS]. Bonded by [bond], as the glasses are.
     */
    @SuppressLint("MissingPermission")
    suspend fun bandNearby(): String? = bandNearby(first = false)

    /**
     * The band heard nearby, one in pairing mode first. With [first], the first band heard, at once: a band the Display
     * has just let go of is the only one advertising. [millis] caps the scan.
     */
    suspend fun bandNearby(first: Boolean, millis: Long = BAND_SCAN_MS): String? {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: throw BondFailed(BondFailed.Why.BLUETOOTH_OFF)
        val heard = HashMap<String, Pair<Boolean, Int>>()
        val any = CompletableDeferred<Unit>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advert = result.scanRecord?.getManufacturerSpecificData(META_COMPANY)?.let(::advert) ?: return
                if (advert.product !in PRODUCT_BANDS) return
                synchronized(heard) { heard[result.device.address] = advert.pairing to result.rssi }
                any.complete(Unit)
            }
        }
        // Filtered on the band's service: Android pauses unfiltered scans while the screen is off, and the band takes a
        // connection only at the address it advertises, not at the one it was bonded under.
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(META_LE_SERVICE)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        try {
            if (first) withTimeoutOrNull(millis) { any.await() } else delay(millis)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        return synchronized(heard) { heard.maxByOrNull { (_, v) -> (if (v.first) 1_000 else 0) + v.second }?.key }
    }

    private class Advert(val product: Int, val pairing: Boolean)

    /** Manufacturer data after the company id: legacy [product:2 LE][flags], or V2 [version][product:2 LE][reserved][flags]. */
    private fun advert(d: ByteArray): Advert? {
        if (d.size < 3) return null
        fun u(i: Int) = d[i].toInt() and 0xff
        return if (u(0) >= 0x80) Advert(u(1) or (u(2) shl 8), d.size >= 5 && u(4) and 1 != 0)
        else Advert(u(0) or (u(1) shl 8), u(2) and 1 != 0)
    }

    private fun nameOf(product: Int?) = productName(product) ?: context.getString(R.string.glasses_default_name)

    private companion object {
        const val META_COMPANY = 0x01AB
        val PRODUCT_BANDS = setOf(1537, 1538)
        const val FORGET_MS = 12_000L
        const val SWEEP_MS = 2_000L
        const val BOND_MS = 90_000L
        const val LINK_MS = 8_000L
        const val LINK_GONE_MS = 10_000L
        const val BAND_SCAN_MS = 15_000L
        const val POLL_MS = 400L
        val GLASSES_NAME = Regex("ray-?ban|meta|oakley|stories", RegexOption.IGNORE_CASE)
    }
}

// The Neural Band, "Meta Band 0064": never listed as glasses.
internal val BAND_NAME = Regex("^meta band", RegexOption.IGNORE_CASE)
