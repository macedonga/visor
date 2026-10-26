package com.macedonga.visor.core.identity

import java.io.File

/** Where bundles live. */
class IdentityStore(directory: File, private val sealer: Sealer) {

    private val directory: File = directory.absoluteFile

    private val path: File get() = File(directory, STORE_FILE)

    fun all(): List<IdentityBundle> {
        if (!path.exists()) return emptyList()
        return bundlesFromJson(String(sealer.unseal(path.readBytes()), Charsets.UTF_8))
    }

    /** One bundle, by serial, or the only one there is. */
    fun load(serial: String? = null): IdentityBundle? {
        val bundles = all()
        if (serial == null) return bundles.singleOrNull()
        return bundles.firstOrNull { it.serial == serial }
    }

    /** The pair last chosen, else the only one, else the newest set up. */
    fun chosen(): IdentityBundle? {
        val bundles = all()
        return bundles.firstOrNull { it.serial == currentSerial() } ?: bundles.singleOrNull() ?: bundles.maxByOrNull { it.createdAt }
    }

    /** The serial last chosen, kept beside the store and unsealed: it is no secret, and reading it needs no key. */
    fun currentSerial(): String? = currentPath.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    fun choose(serial: String) {
        if (serial == currentSerial()) return
        directory.mkdirs()
        currentPath.writeText(serial)
    }

    private val currentPath: File get() = File(directory, CURRENT_FILE)

    /** The one owner key this account uses, or null before the first import. */
    fun ownerKeyPem(): ByteArray? = all().firstOrNull()?.privateKeyPem

    /** True when the stored bundles do not all share one owner key. */
    fun mixedKeys(): Boolean =
        all().map { it.privateKeyPem.toList() }.toSet().size > 1

    /** Add or replace by serial, then reseal the whole file. */
    fun save(bundle: IdentityBundle) {
        val bundles = all().filter { it.serial != bundle.serial } + bundle
        val payload = bundlesToJson(bundles).toByteArray(Charsets.UTF_8)
        directory.mkdirs()
        writeAtomically(sealer.seal(payload))
    }

    /** Forget the glasses with [serial]: their bundle goes, the others stay. */
    fun remove(serial: String) {
        val bundles = all().filter { it.serial != serial }
        directory.mkdirs()
        writeAtomically(sealer.seal(bundlesToJson(bundles).toByteArray(Charsets.UTF_8)))
        if (currentSerial() == serial) currentPath.delete()
    }

    /** DIVERGENCE: `store.py` calls `Path.write_bytes`, which truncates first. */
    private fun writeAtomically(blob: ByteArray) {
        val temporary = File(directory, "$STORE_FILE.new")
        temporary.writeBytes(blob)
        if (!temporary.renameTo(path)) {
            // Rename onto an existing file fails on some filesystems rather than replacing.
            path.writeBytes(blob)
            temporary.delete()
        }
    }

    companion object {
        const val STORE_FILE = "identities.sealed"
        const val CURRENT_FILE = "current"
    }

    // DIVERGENCE: `migrate_legacy` does not port.
}
