package com.macedonga.visor.core.identity

import java.io.File
import java.io.IOException

/** Where bundles live: a file sealed with the Keystore. */
actual class IdentityStore(directory: File, private val sealer: Sealer) {

    private val directory: File = directory.absoluteFile

    private val path: File get() = File(directory, STORE_FILE)

    @Synchronized
    actual fun all(): List<IdentityBundle> {
        if (!path.exists()) return emptyList()
        return bundlesFromJson(String(sealer.unseal(path.readBytes()), Charsets.UTF_8))
    }

    /** The bundle for [serial]. */
    actual fun load(serial: String): IdentityBundle? = all().firstOrNull { it.serial == serial }

    /** The pair last chosen, else the newest set up. */
    actual fun chosen(): IdentityBundle? {
        val bundles = all()
        val current = currentSerial()
        return bundles.firstOrNull { it.serial == current } ?: bundles.maxByOrNull { it.createdAt }
    }

    /** The serial last chosen, kept beside the store and unsealed: it is no secret, and reading it needs no key. */
    actual fun currentSerial(): String? = currentPath.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    @Synchronized
    actual fun choose(serial: String) {
        if (serial == currentSerial()) return
        directory.mkdirs()
        currentPath.writeText(serial)
    }

    private val currentPath: File get() = File(directory, CURRENT_FILE)

    /** The one owner key this account uses, or null before the first setup. */
    actual fun ownerKeyPem(): ByteArray? = all().firstOrNull()?.privateKeyPem

    /** Add or replace by serial, then reseal the whole file. */
    @Synchronized
    actual fun save(bundle: IdentityBundle) {
        write(all().filter { it.serial != bundle.serial } + bundle)
    }

    /** Change the bundle for [serial] in place; false when it is gone or unchanged. */
    @Synchronized
    actual fun update(serial: String, change: (IdentityBundle) -> IdentityBundle): Boolean {
        val bundles = all()
        val old = bundles.firstOrNull { it.serial == serial } ?: return false
        val new = change(old)
        if (new == old) return false
        write(bundles.map { if (it.serial == serial) new else it })
        return true
    }

    /** Forget the glasses with [serial]: their bundle goes, the others stay. */
    @Synchronized
    actual fun remove(serial: String) {
        write(all().filter { it.serial != serial })
        if (currentSerial() == serial) currentPath.delete()
    }

    private fun write(bundles: List<IdentityBundle>) {
        directory.mkdirs()
        writeAtomically(sealer.seal(bundlesToJson(bundles).toByteArray(Charsets.UTF_8)))
    }

    /** Write to a temporary file, then rename it over the store. */
    private fun writeAtomically(blob: ByteArray) {
        val temporary = File(directory, "$STORE_FILE.new")
        temporary.writeBytes(blob)
        if (!temporary.renameTo(path)) throw IOException("could not replace $path")
    }

    companion object {
        /** The store's folder under the app's files. */
        const val DIRECTORY = "identity"
        private const val STORE_FILE = "identities.sealed"
        private const val CURRENT_FILE = "current"
    }
}
