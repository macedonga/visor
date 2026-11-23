package com.macedonga.visor.core.identity

/** Where bundles live, one per pair, sealed by the platform. */
expect class IdentityStore {
    fun all(): List<IdentityBundle>

    /** The bundle for [serial]. */
    fun load(serial: String): IdentityBundle?

    /** The pair last chosen, else the newest set up. */
    fun chosen(): IdentityBundle?

    fun currentSerial(): String?

    fun choose(serial: String)

    /** The one owner key this account uses, or null before the first setup. */
    fun ownerKeyPem(): ByteArray?

    /** Add or replace by serial. */
    fun save(bundle: IdentityBundle)

    /** Change the bundle for [serial] in place; false when it is gone or unchanged. */
    fun update(serial: String, change: (IdentityBundle) -> IdentityBundle): Boolean

    /** Forget the glasses with [serial]: their bundle goes, the others stay. */
    fun remove(serial: String)
}
