package com.macedonga.visor.language

import com.macedonga.visor.core.identity.language.PackOffer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The zip, resumed where an earlier try stopped, checked against Meta's SHA-256. */
internal suspend fun downloadPack(offer: PackOffer, file: File, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
    val part = File(file.path + ".part")
    var have = if (part.exists()) part.length() else 0L
    val c = URL(offer.url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        when (c.responseCode) {
            206 -> Unit
            200 -> have = 0
            else -> error("the download answered ${c.responseCode}")
        }
        FileOutputStream(part, have > 0).use { out ->
            c.inputStream.use { input ->
                val buffer = ByteArray(256 * 1024)
                var lastReport = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    have += n
                    if (have - lastReport > 1_000_000) {
                        lastReport = have
                        onProgress(have)
                    }
                }
            }
        }
    } finally {
        c.disconnect()
    }
    onProgress(have)
    if (offer.sha256.isNotEmpty() && sha256(part) != offer.sha256.lowercase()) {
        part.delete()
        error("the download did not match Meta's checksum; try again")
    }
    check(part.renameTo(file)) { "could not keep the download" }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
