@file:OptIn(KitInternal::class)

package com.macedonga.visor.dat

import android.content.Context
import com.macedonga.visor.band.BandLink
import com.macedonga.visor.band.Bands
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.productId
import com.macedonga.visor.core.identity.ConstellationManifest
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.rawPublicKey
import com.macedonga.visor.core.protocol.Ecdh
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.kit.KitException
import com.macedonga.visor.kit.protocol.ErrorCode
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.setup.Provisioner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Visor's constellation manifest for a pair, kept as the Meta app's ManifestManager keeps its own: the glasses, each
 * band set up with them and Visor's app key, signed by an authority key Visor made. The Display links only to devices
 * its manifest lists, and the band trusts the Display only through the same manifest.
 */
@Singleton
class Manifests @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val store: ToolkitStore,
    private val identities: IdentityStore,
    private val provisioner: Provisioner,
    private val bands: Bands,
    private val bandLink: BandLink,
    private val sync: ManifestSync,
    private val log: EventLog,
) {
    /** The glasses, and each band Visor set up with them, as the manifest lists them by their receipt keys. */
    fun devices(serial: String, address: String, build: String): List<ConstellationManifest.Device> {
        val key = identities.load(serial)?.devicePublicKey ?: return emptyList()
        val product = productId(build) ?: throw KitException(ErrorCode.UNSUPPORTED, "Visor does not know these glasses' model.", reason = "unknown_model")
        val glasses = ConstellationManifest.Device(key, ConstellationManifest.vendorData(product), address, address)
        val withBands = bands.all.value.filter { it.glasses == serial }.mapNotNull { band ->
            val bandKey = band.publicKey?.let(::rawPublicKey) ?: return@mapNotNull null
            ConstellationManifest.Device(bandKey, ConstellationManifest.vendorData(BAND_PRODUCT), "", band.address)
        }
        return listOf(glasses) + withBands
    }

    /** Reads the glasses' receipt key when Visor has none: a change of owner with the same key, about a second. */
    suspend fun receiptKey(address: String, serial: String) {
        if (identities.load(serial)?.devicePublicKey != null) return
        provisioner.start(address)
        val end = withTimeoutOrNull(PROVISION_MS) { provisioner.status.first { it is Provisioner.Status.Done || it is Provisioner.Status.Failed } }
        if (end !is Provisioner.Status.Done || identities.load(serial)?.devicePublicKey == null) {
            throw KitException(ErrorCode.FAILED, "Visor could not read the glasses' key from Meta.")
        }
        withTimeoutOrNull(RECONNECT_MS) { connection.state.first { it.link == LinkState.READY } }
            ?: throw KitException(ErrorCode.NOT_CONNECTED, "The glasses did not come back after their key was read.")
    }

    /**
     * Signs a new manifest for the pair, one version past the glasses' own, and installs it on each band set up with
     * them, then on the glasses: a band left out of a new manifest refuses the Display (0xc004), while a Display
     * behind the band takes the band's newer one. Returns what Visor keeps for the pair; the toolkit's grants carry over.
     */
    suspend fun install(serial: String, address: String, build: String): ToolkitPair {
        val devices = devices(serial, address, build)
        if (devices.isEmpty()) throw KitException(ErrorCode.FAILED, "Visor has no key for these glasses.")
        val before = store.pair(serial)
        val authority = before?.let { ToolkitStore.pkcs8(it.authority) to it.authorityPublic }
            ?: Ecdh.generateEphemeral().let { it.privateKey to it.publicKey64 }
        val group = before?.groupId ?: ByteArray(16).also(SecureRandom()::nextBytes)
        val installed = sync.version() ?: throw KitException(ErrorCode.FAILED, "The glasses did not say which manifest they hold.")
        val version = installed.version + 1
        val app = ConstellationManifest.App(store.appKey().second, context.packageName)
        val manifest = ConstellationManifest.build(authority.first, authority.second, devices, listOf(app), version, group)
        if (bands.all.value.any { it.glasses == serial }) {
            val held = runCatching { bandLink.installManifest(authority.second, manifest) }.getOrNull()
            if (held == null) throw KitException(ErrorCode.FAILED, "The band did not answer.", reason = BAND_AWAY)
            if (held != version) throw KitException(ErrorCode.FAILED, "The band did not take Visor's manifest (it holds version $held).")
        }
        val now = sync.install(authority.second, manifest)
        if (now?.version != version) throw KitException(ErrorCode.FAILED, "The glasses did not take Visor's manifest (they hold version ${now?.version}).")
        val pair = ToolkitPair(
            authority.first.encoded, authority.second, version, now.salt, group, devices.map { it.publicKey.toHexString() }.toSet(),
            granted = before?.granted ?: false, manifest = manifest,
        )
        store.save(serial, pair)
        log.add("Manifest: $version installed on $serial with ${devices.size} devices")
        return pair
    }

    companion object {
        /** The [KitException] reason when a band set up with the glasses did not answer. */
        const val BAND_AWAY = "band_away"
        private const val BAND_PRODUCT = 1537
        private const val PROVISION_MS = 60_000L
        private const val RECONNECT_MS = 60_000L
    }
}
