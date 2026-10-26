package com.macedonga.visor.setup

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import com.macedonga.visor.R
import com.macedonga.visor.core.transport.META_LE_SERVICE
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

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

/**
 * Finds glasses the way the Meta app does: an LE scan for Meta's company id (0x01AB) or their GATT service (0xFD5F),
 * whose manufacturer data says the product and whether pairing is open (lensbreak's link/ble.py). Bonds them over LE;
 * the glasses derive the classic key from it, so RFCOMM and L2CAP both work afterwards.
 */
@Singleton
class NearbyGlasses @Inject constructor(@ApplicationContext private val context: Context) {
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val enabled: Boolean get() = adapter?.isEnabled == true

    /** What is heard nearby, newest state first, refreshed while collected; a pair not heard for a while drops out. */
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
                val name = record.deviceName ?: runCatching { device.name }.getOrNull() ?: productName(advert?.product)
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
     * Bonds [address] if it is not already, and returns the address the bond is kept under: the glasses' identity
     * address, which may differ from the one they advertised with. Watched by polling, not by the bond broadcast: that
     * one names the identity address, so it never matched the advertised one and the setup waited for nothing.
     */
    @SuppressLint("MissingPermission")
    suspend fun bond(address: String): String {
        val adapter = adapter?.takeIf { it.isEnabled } ?: throw BondFailed(BondFailed.Why.BLUETOOTH_OFF)
        val device = adapter.getRemoteDevice(address)
        if (device.bondState == BluetoothDevice.BOND_BONDED) return address
        val before = adapter.bondedDevices.map { it.address }.toSet()
        if (!device.createBond()) throw BondFailed(BondFailed.Why.REFUSED)
        var bonding = false
        val deadline = SystemClock.elapsedRealtime() + BOND_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_MS)
            val state = device.bondState
            val added = adapter.bondedDevices.filter { it.address !in before }
            if (state == BluetoothDevice.BOND_BONDED || added.isNotEmpty()) {
                return added.firstOrNull { it.address == address }?.address
                    ?: added.firstOrNull { it.name == device.name }?.address
                    ?: added.firstOrNull()?.address
                    ?: address
            }
            if (state == BluetoothDevice.BOND_BONDING) bonding = true
            // Back to none after trying: turned down on the phone or by the glasses.
            if (bonding && state == BluetoothDevice.BOND_NONE) throw BondFailed(BondFailed.Why.REFUSED)
        }
        throw BondFailed(BondFailed.Why.NO_ANSWER)
    }

    /**
     * The Neural Band heard nearby, one in pairing mode before the strongest: its address, or null when none answers
     * within [BAND_SCAN_MS]. Bonded by [bond], as the glasses are.
     */
    @SuppressLint("MissingPermission")
    suspend fun bandNearby(): String? {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: throw BondFailed(BondFailed.Why.BLUETOOTH_OFF)
        val heard = HashMap<String, Pair<Boolean, Int>>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advert = result.scanRecord?.getManufacturerSpecificData(META_COMPANY)?.let(::advert) ?: return
                if (advert.product !in PRODUCT_BANDS) return
                synchronized(heard) { heard[result.device.address] = advert.pairing to result.rssi }
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        try {
            delay(BAND_SCAN_MS)
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

    private fun productName(product: Int?) = when (product) {
        257 -> "Ray-Ban Stories"
        259, 270, 271 -> "Meta Ray-Ban Display"
        264 -> "Oakley Meta Vanguard"
        265 -> "Oakley Meta HSTN"
        else -> context.getString(R.string.glasses_default_name)
    }

    private companion object {
        const val META_COMPANY = 0x01AB
        val PRODUCT_BANDS = setOf(1537, 1538)
        const val FORGET_MS = 12_000L
        const val SWEEP_MS = 2_000L
        const val BOND_MS = 90_000L
        const val BAND_SCAN_MS = 15_000L
        const val POLL_MS = 400L
        val GLASSES_NAME = Regex("ray-?ban|meta|oakley|stories", RegexOption.IGNORE_CASE)

        // The Neural Band, "Meta Band 0064": set up from the Display's settings, never as glasses.
        val BAND_NAME = Regex("^meta band", RegexOption.IGNORE_CASE)
    }
}
