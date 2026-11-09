package com.macedonga.visor.connection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.log.responderScope
import com.macedonga.visor.outside.KitWebApps
import com.macedonga.visor.ui.allows
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * BLE scans for the Display's web apps (navigator.bluetooth.requestLEScan, the browser's NativeBluetoothLeScanBridge):
 * one svc55074 channel a scan, START (17) {1 request id, 2 accept all, 3 keep repeated, 4 filters}, answered with
 * START_RESPONSE (20) {1 request id}, then an ADVERTISEMENT (18) for each device that matches, until STOP (19) or the
 * channel closes. The Display lets a page scan only when its origin is in MobileConfig
 * web_on_hn.webapp_ble_scan_allowed_origins, which Visor writes for the web apps the person allows.
 */
@Singleton
class WebBleScans @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kit: Provider<KitWebApps>,
    private val log: EventLog,
) {
    private val scope = responderScope(log, "Web app scans")
    private val scans = ConcurrentHashMap<Int, Scan>()

    /** A filter as Web Bluetooth has it; every part given must match. */
    private class Filter(
        val services: List<UUID>,
        val name: String?,
        val namePrefix: String?,
        val manufacturer: Map<Int, Pair<ByteArray, ByteArray>>,
        val serviceData: Map<UUID, Pair<ByteArray, ByteArray>>,
    )

    fun start(connection: DataXConnection, channel: Int, payload: ByteArray) {
        val f = Wire.parse(payload)
        val id = f.bytes(1).decodeToString()
        val reply = DataXConnection.mirror(channel)
        scans.remove(channel)?.stop()
        // A web app speaking to Visor through visor-kit, not a scan.
        kit.get().frameOf(f.repeatedBytes(4))?.let { frame ->
            scope.launch { runCatching { connection.send(reply, Wire.fieldString(1, id), messageType = MT_START_RESPONSE) } }
            kit.get().onScan(connection, channel, id, frame)
            return
        }
        val scan = Scan(connection, reply, id, f.varint(2) != 0L, f.varint(3) != 0L, f.repeatedBytes(4).mapNotNull(::filter))
        scans[channel] = scan
        scope.launch { runCatching { connection.send(reply, Wire.fieldString(1, id), messageType = MT_START_RESPONSE) } }
        scan.start()
    }

    fun stop(channel: Int) {
        scans.remove(channel)?.stop()
        kit.get().onScanEnd(channel)
    }

    private fun filter(bytes: ByteArray): Filter? = runCatching {
        val f = Wire.parse(bytes)
        Filter(
            services = f.repeatedBytes(1).map { uuid(it.decodeToString()) },
            name = f.bytes(2).decodeToString().ifEmpty { null },
            namePrefix = f.bytes(3).decodeToString().ifEmpty { null },
            // map<uint32 company, BluetoothDataFilter {1 prefix, 2 mask}> and map<string service, the same>, as entries {1 key, 2 value}.
            manufacturer = f.repeatedBytes(4).associate { e -> Wire.parse(e).let { it.varint(1).toInt() to data(it.bytes(2)) } },
            serviceData = f.repeatedBytes(5).associate { e -> Wire.parse(e).let { uuid(it.bytes(1).decodeToString()) to data(it.bytes(2)) } },
        )
    }.getOrNull()

    private fun data(bytes: ByteArray): Pair<ByteArray, ByteArray> = Wire.parse(bytes).let { it.bytes(1) to it.bytes(2) }

    // A full UUID, or a 16- or 32-bit one on the Bluetooth base.
    private fun uuid(text: String): UUID {
        val t = text.trim().lowercase()
        if (t.length <= 8 && t.all { it.isDigit() || it in 'a'..'f' }) return UUID.fromString("${t.padStart(8, '0')}-0000-1000-8000-00805f9b34fb")
        return UUID.fromString(t)
    }

    private inner class Scan(
        private val mux: DataXConnection,
        private val reply: Int,
        private val id: String,
        private val acceptAll: Boolean,
        private val keepRepeated: Boolean,
        private val filters: List<Filter>,
    ) : ScanCallback() {
        private val seen = ConcurrentHashMap<String, Long>()
        private val out = Channel<ByteArray>(capacity = OUT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private var sender: Job? = null
        private var linkWatch: Job? = null
        private val scanner get() = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

        @SuppressLint("MissingPermission")
        fun start() {
            sender = scope.launch { for (ad in out) runCatching { mux.send(reply, ad, messageType = MT_ADVERTISEMENT) } }
            linkWatch = scope.launch {
                mux.awaitGone()
                if (scans.values.remove(this@Scan)) stop()
            }
            if (!context.allows(Manifest.permission.BLUETOOTH_SCAN)) return log.add("Web app scans: no Bluetooth scan permission")
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            runCatching { scanner?.startScan(hardwareFilters(), settings, this) ?: error("Bluetooth is off") }
                .onSuccess { log.add("Web app scans: scanning for a web app (${if (acceptAll) "every device" else "${filters.size} filters"})") }
                .onFailure { log.add("Web app scans: could not scan: ${it.message}") }
        }

        // The hardware filters keep a scan going with the phone's screen off, which an empty one does not; a name prefix
        // has no hardware filter, so a filter with one is matched on the phone only.
        private fun hardwareFilters(): List<ScanFilter>? {
            if (acceptAll || filters.isEmpty() || filters.any { it.namePrefix != null }) return null
            return filters.map { f ->
                ScanFilter.Builder().apply {
                    f.services.firstOrNull()?.let { setServiceUuid(ParcelUuid(it)) }
                    f.name?.let { setDeviceName(it) }
                    f.manufacturer.entries.firstOrNull()?.let { (company, d) -> setManufacturerData(company, d.first, mask(d)) }
                    f.serviceData.entries.firstOrNull()?.let { (service, d) -> setServiceData(ParcelUuid(service), d.first, mask(d)) }
                }.build()
            }
        }

        private fun mask(d: Pair<ByteArray, ByteArray>) = d.second.takeIf { it.size == d.first.size } ?: ByteArray(d.first.size) { -1 }

        @SuppressLint("MissingPermission")
        fun stop() {
            linkWatch?.cancel()
            runCatching { scanner?.stopScan(this) }
            out.close()
            sender?.cancel()
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!acceptAll && filters.isNotEmpty() && filters.none { matches(it, result) }) return
            val address = result.device.address
            val now = System.currentTimeMillis()
            val last = seen[address]
            if (last != null && (!keepRepeated || now - last < REPEAT_MS)) return
            seen[address] = now
            out.trySend(advertisement(result, now))
        }

        override fun onScanFailed(errorCode: Int) {
            log.add("Web app scans: the scan failed, code $errorCode")
        }

        @SuppressLint("MissingPermission")
        private fun matches(f: Filter, r: ScanResult): Boolean {
            val record = r.scanRecord ?: return false
            val name = record.deviceName ?: runCatching { r.device.name }.getOrNull()
            if (f.name != null && f.name != name) return false
            if (f.namePrefix != null && name?.startsWith(f.namePrefix) != true) return false
            val uuids = record.serviceUuids.orEmpty().map { it.uuid }
            if (!uuids.containsAll(f.services)) return false
            for ((company, d) in f.manufacturer) {
                if (!prefixed(record.getManufacturerSpecificData(company) ?: return false, d)) return false
            }
            for ((service, d) in f.serviceData) {
                if (!prefixed(record.getServiceData(ParcelUuid(service)) ?: return false, d)) return false
            }
            return true
        }

        private fun prefixed(data: ByteArray, d: Pair<ByteArray, ByteArray>): Boolean {
            val (prefix, mask) = d
            if (data.size < prefix.size) return false
            return prefix.indices.all { i ->
                val m = if (i < mask.size) mask[i].toInt() else 0xFF
                (data[i].toInt() and m) == (prefix[i].toInt() and m)
            }
        }

        @SuppressLint("MissingPermission")
        private fun advertisement(r: ScanResult, now: Long): ByteArray {
            val record = r.scanRecord
            val name = record?.deviceName ?: runCatching { r.device.name }.getOrNull()
            var b = Wire.fieldString(1, id) + Wire.fieldString(2, r.device.address)
            if (name != null) b += Wire.fieldString(3, name)
            b += Wire.fieldVarint(4, r.rssi.toLong()) + Wire.fieldVarint(11, 1)
            if (r.txPower != ScanResult.TX_POWER_NOT_PRESENT) b += Wire.fieldVarint(5, r.txPower.toLong()) + Wire.fieldVarint(6, 1)
            record?.serviceUuids.orEmpty().forEach { b += Wire.fieldString(7, it.uuid.toString()) }
            val makers = record?.manufacturerSpecificData
            if (makers != null) {
                for (i in 0 until makers.size()) {
                    b += Wire.fieldBytes(8, Wire.fieldVarint(1, makers.keyAt(i).toLong()) + Wire.fieldBytes(2, makers.valueAt(i)))
                }
            }
            record?.serviceData.orEmpty().forEach { (uuid, data) ->
                b += Wire.fieldBytes(9, Wire.fieldString(1, uuid.uuid.toString()) + Wire.fieldBytes(2, data))
            }
            return b + Wire.fieldVarint(10, now)
        }
    }

    companion object {
        const val MT_START = 17
        const val MT_ADVERTISEMENT = 18
        const val MT_STOP = 19
        const val MT_START_RESPONSE = 20
        // A device heard again goes to the page at most this often.
        private const val REPEAT_MS = 250L
        private const val OUT_BUFFER = 64
    }
}
