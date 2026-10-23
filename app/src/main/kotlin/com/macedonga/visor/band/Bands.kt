package com.macedonga.visor.band

import android.content.Context
import android.util.Base64
import android.util.Log
import com.macedonga.visor.core.identity.KeystoreSealer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Neural Band this phone owns: where it is, the key its lease hints name, the Display it goes with, and its own owner
 * key, as the Meta app keeps one per device.
 */
data class BandRecord(val serial: String, val address: String, val publicKey: ByteArray?, val glasses: String?, val key: ByteArray? = null) {
    override fun equals(other: Any?): Boolean =
        other is BandRecord && serial == other.serial && address == other.address && glasses == other.glasses &&
            publicKey.contentEquals(other.publicKey) && key.contentEquals(other.key)

    override fun hashCode(): Int = serial.hashCode()
}

/** The bands set up here, kept apart from the glasses' identity store so the home never takes one for a pair. */
@Singleton
class Bands @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.filesDir, "bands.json")
    private val sealer = KeystoreSealer(alias = "visor.band")
    // Set when bands.json exists but cannot be read; the next write keeps it aside as bands.json.bad first.
    private var unreadable = false
    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<BandRecord>> = _all.asStateFlow()

    fun forGlasses(serial: String?): BandRecord? = serial?.let { s -> _all.value.firstOrNull { it.glasses == s } }

    @Synchronized
    fun save(band: BandRecord) {
        // One band per Display, as the Meta app pairs them.
        _all.value = _all.value.filter { it.serial != band.serial && (band.glasses == null || it.glasses != band.glasses) } + band
        write()
    }

    @Synchronized
    fun forget(serial: String) {
        _all.value = _all.value.filter { it.serial != serial }
        write()
    }

    private fun read(): List<BandRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val list = JSONArray(file.readText())
            (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                BandRecord(
                    serial = o.getString("serial"),
                    address = o.getString("address"),
                    publicKey = o.optString("public_key").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) },
                    glasses = o.optString("glasses").takeIf { it.isNotEmpty() },
                    key = o.optString("key").takeIf { it.isNotEmpty() }?.let { sealer.unseal(Base64.decode(it, Base64.NO_WRAP)) },
                )
            }
        }.getOrElse {
            Log.w("Visor", "bands.json cannot be read", it)
            unreadable = true
            emptyList()
        }
    }

    private fun write() {
        if (unreadable) {
            runCatching { file.copyTo(File(file.parentFile, "${file.name}.bad"), overwrite = true) }.onFailure {
                Log.w("Visor", "bands.json could not be kept aside", it)
                return
            }
            unreadable = false
        }
        val list = JSONArray()
        for (b in _all.value) {
            list.put(
                JSONObject().put("serial", b.serial).put("address", b.address).put("glasses", b.glasses ?: "")
                    .put("public_key", b.publicKey?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "")
                    .put("key", b.key?.let { Base64.encodeToString(sealer.seal(it), Base64.NO_WRAP) } ?: ""),
            )
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(list.toString())
        tmp.renameTo(file)
    }
}
