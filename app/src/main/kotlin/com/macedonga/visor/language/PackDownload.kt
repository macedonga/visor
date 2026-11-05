package com.macedonga.visor.language

import com.macedonga.visor.R
import com.macedonga.visor.core.identity.language.PackOffer
import com.macedonga.visor.core.media.sizeWords
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The phone lacks the [bytes] of free space a download needs. */
internal class NoSpace(val bytes: Long) : IllegalStateException("the phone needs ${sizeWords(bytes)} free")

/** The zip, resumed where an earlier try stopped, checked against Meta's SHA-256; [spare] bytes stay free beside it. */
internal suspend fun downloadPack(offer: PackOffer, file: File, spare: Long, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
    val part = File(file.path + ".part")
    var have = if (part.exists()) part.length() else 0L
    // A part already whole (the app stopped before the rename) goes straight to the check.
    if (offer.size <= 0 || have < offer.size) {
        val need = offer.size - have + spare
        if (file.absoluteFile.parentFile.usableSpace < need) throw NoSpace(need)
        val c = URL(offer.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            when (c.responseCode) {
                206 -> Unit
                200 -> have = 0
                416 -> {
                    part.delete()
                    throw LanguageError("the download answered 416; try again", R.string.language_err_restart)
                }
                else -> throw LanguageError("the download answered ${c.responseCode}", R.string.language_err_download, c.responseCode)
            }
            FileOutputStream(part, have > 0).use { out ->
                c.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        ensureActive()
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
    }
    onProgress(have)
    if (offer.sha256.isNotEmpty() && sha256(part) != offer.sha256.lowercase()) {
        part.delete()
        throw LanguageError("the download did not match Meta's checksum; try again", R.string.language_err_checksum)
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
    return digest.digest().toHexString()
}
