@file:OptIn(KitInternal::class)

package com.macedonga.visor.dat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.macedonga.visor.R
import com.macedonga.visor.band.BandLink
import com.macedonga.visor.band.Bands
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.productId
import com.macedonga.visor.core.identity.ConstellationManifest
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.appstore.fetchEntitlements
import com.macedonga.visor.core.identity.rawPublicKey
import com.macedonga.visor.core.media.AirshipReceiver
import com.macedonga.visor.core.media.AppInstall
import com.macedonga.visor.core.protocol.Ecdh
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.PERMISSIONS_NAMESPACE
import com.macedonga.visor.core.settings.permissionObject
import com.macedonga.visor.kit.KitException
import com.macedonga.visor.kit.protocol.ErrorCode
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import com.macedonga.visor.setup.Provisioner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The steps of setting a pair up for Meta's toolkit, in order. */
enum class ToolkitStep { KEY, APP, MANIFEST, PERMISSIONS }

/** Where the setup is, for its screen and its notification. */
sealed interface ToolkitProgress {
    data object Idle : ToolkitProgress

    /** Waiting for the person to allow the setup that [app] needs. */
    data class Asking(val app: String) : ToolkitProgress

    /** At [step]; [fraction] while the toolkit app downloads. */
    data class Running(val step: ToolkitStep, val fraction: Float? = null) : ToolkitProgress
    data object Ready : ToolkitProgress
    data class Failed(val step: ToolkitStep?, val reason: String) : ToolkitProgress
}

/** A pair set up for the toolkit: what an app link to it needs. */
class ToolkitReady(val address: String, val appKey: PrivateKey, val appPublicKey: ByteArray, val version: Long, val salt: ByteArray)

/**
 * Sets the active pair up for Meta's device access toolkit, once, as the Meta app sets one up for a DAT app: the
 * developer toolkit app from Meta's store, a constellation manifest that lists Visor's app key (Visor signs it and
 * becomes its authority, as the Meta app was), and the glasses' grants to Visor (developer mode, motion, display,
 * inputs). The person allows it once; every step shows on [progress].
 */
@Singleton
class Toolkit @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val store: ToolkitStore,
    private val identities: IdentityStore,
    private val provisioner: Provisioner,
    private val bands: Bands,
    private val bandLink: BandLink,
    private val settings: GlassesSettings,
    private val login: MetaLogin,
    private val airship: AirshipReceiver,
    private val log: EventLog,
) {
    private val _progress = MutableStateFlow<ToolkitProgress>(ToolkitProgress.Idle)
    val progress: StateFlow<ToolkitProgress> = _progress.asStateFlow()

    private val setup = Mutex()
    private var answer: CompletableDeferred<Boolean>? = null

    /** The active pair, set up, or a [KitException] saying why not. [app] is the name the request shows. */
    suspend fun ready(app: String): ToolkitReady = setup.withLock {
        val info = connection.state.value.info
        val address = (connection.state.value.target?.link as? GlassesLink.Bluetooth)?.address
        if (connection.state.value.link != LinkState.READY || info == null || address == null) {
            throw KitException(ErrorCode.NOT_CONNECTED, "The glasses are not connected.")
        }
        val (appKey, appPublic) = store.appKey()
        val current = store.pair(info.serial)
        if (current != null && current.granted && current.format == MANIFEST_FORMAT && devices(info.serial, address, info.build).map { it.publicKey.toHexString() }.toSet() == current.devices) {
            return ToolkitReady(address, appKey, appPublic, current.version, current.salt)
        }
        // Asked once; after that the setup only shows how far it has come.
        if (store.allowed) {
            step(ToolkitStep.KEY)
            open()
        } else if (!ask(app)) {
            throw KitException(ErrorCode.REFUSED, "The person did not allow Visor to set the glasses up for apps.", reason = "setup_declined")
        }
        store.allowed = true
        try {
            val pair = run(info.serial, address, info.build, info.wearablesRelease, appPublic, current)
            _progress.value = ToolkitProgress.Ready
            notification(null)
            ToolkitReady(address, appKey, appPublic, pair.version, pair.salt)
        } catch (e: KitException) {
            val step = (_progress.value as? ToolkitProgress.Running)?.step
            _progress.value = ToolkitProgress.Failed(step, e.message.orEmpty())
            notification(null)
            log.add("Toolkit: setup failed at $step: ${e.message}")
            throw e
        }
    }

    /** The person's answer from [ToolkitActivity]. */
    fun answer(allowed: Boolean) {
        answer?.complete(allowed)
    }

    /** Forgets the outcome shown, once its screen is gone. */
    fun dismiss() {
        if (_progress.value is ToolkitProgress.Ready || _progress.value is ToolkitProgress.Failed) _progress.value = ToolkitProgress.Idle
    }

    private suspend fun ask(app: String): Boolean {
        val waiting = CompletableDeferred<Boolean>().also { answer = it }
        _progress.value = ToolkitProgress.Asking(app)
        open()
        val allowed = withTimeoutOrNull(ASK_MS) { waiting.await() } ?: false
        if (!allowed) _progress.value = ToolkitProgress.Idle
        return allowed
    }

    // Visor's companion device association lets it open its screen from the background.
    private fun open() {
        context.startActivity(Intent(context, ToolkitActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun run(serial: String, address: String, build: String, release: String, appPublic: ByteArray, before: ToolkitPair?): ToolkitPair {
        step(ToolkitStep.KEY)
        if (identities.load(serial)?.devicePublicKey == null) receiptKey(address, serial)

        step(ToolkitStep.APP)
        installToolkitApp(serial, release)

        step(ToolkitStep.MANIFEST)
        val devices = devices(serial, address, build)
        val authority = before?.let { ToolkitStore.pkcs8(it.authority) to it.authorityPublic }
            ?: Ecdh.generateEphemeral().let { it.privateKey to it.publicKey64 }
        val group = before?.groupId ?: ByteArray(16).also(SecureRandom()::nextBytes)
        val installed = manifestVersion() ?: throw KitException(ErrorCode.FAILED, "The glasses did not say which manifest they hold.")
        val version = installed.version + 1
        val manifest = ConstellationManifest.build(authority.first, authority.second, devices, listOf(ConstellationManifest.App(appPublic, context.packageName)), version, group)
        connection.submit { it.sendOnService(ConstellationManifest.SERVICE, ConstellationManifest.SET_AUTHORITY_KEY, ConstellationManifest.authorityKey(authority.second)) }
        ConstellationManifest.chunks(manifest).forEach { (type, chunk) -> connection.submit { it.sendOnService(ConstellationManifest.SERVICE, type, chunk) } }
        val now = manifestVersion()
        if (now?.version != version) throw KitException(ErrorCode.FAILED, "The glasses did not take Visor's manifest (they hold version ${now?.version}).")
        val pair = ToolkitPair(authority.first.encoded, authority.second, version, now.salt, group, devices.map { it.publicKey.toHexString() }.toSet(), granted = false)
        store.save(serial, pair)
        log.add("Toolkit: manifest $version installed on $serial with ${devices.size} devices")
        // The band checks the Display's manifest against its own authority key, so it gets Visor's key and the manifest.
        if (bands.all.value.any { it.glasses == serial }) {
            val held = runCatching { bandLink.installManifest(authority.second, manifest) }.getOrNull()
            if (held != version) throw KitException(ErrorCode.FAILED, "The band did not take Visor's manifest (it holds version $held).")
        }

        step(ToolkitStep.PERMISSIONS)
        val grants = GRANTS.map { permissionObject(context.packageName, "android.permission.$it", "ALWAYS") }
        settings.readNamespace(PERMISSIONS_NAMESPACE, grants) ?: throw KitException(ErrorCode.FAILED, "The glasses did not take the toolkit's permissions.")
        return pair.copy(granted = true).also { store.save(serial, it) }
    }

    /** The glasses, and each band Visor set up with them, as the manifest lists them by their receipt keys. */
    private fun devices(serial: String, address: String, build: String): List<ConstellationManifest.Device> {
        val key = identities.load(serial)?.devicePublicKey ?: return emptyList()
        val product = productId(build) ?: throw KitException(ErrorCode.UNSUPPORTED, "Visor does not know these glasses' model.", reason = "unknown_model")
        val glasses = ConstellationManifest.Device(key, ConstellationManifest.vendorData(product), address, address)
        val withBands = bands.all.value.filter { it.glasses == serial }.mapNotNull { band ->
            val bandKey = band.publicKey?.let(::rawPublicKey) ?: return@mapNotNull null
            ConstellationManifest.Device(bandKey, ConstellationManifest.vendorData(BAND_PRODUCT), "", band.address)
        }
        return listOf(glasses) + withBands
    }

    // The receipt key comes with a change of owner: the same owner key again, about a second.
    private suspend fun receiptKey(address: String, serial: String) {
        provisioner.start(address)
        val end = withTimeoutOrNull(PROVISION_MS) { provisioner.status.first { it is Provisioner.Status.Done || it is Provisioner.Status.Failed } }
        if (end !is Provisioner.Status.Done || identities.load(serial)?.devicePublicKey == null) {
            throw KitException(ErrorCode.FAILED, "Visor could not read the glasses' key from Meta.")
        }
        withTimeoutOrNull(RECONNECT_MS) { connection.state.first { it.link == LinkState.READY } }
            ?: throw KitException(ErrorCode.NOT_CONNECTED, "The glasses did not come back after their key was read.")
    }

    private suspend fun installToolkitApp(serial: String, release: String) {
        val install = AppInstall(connection, airship)
        if ("installed=true" in install.query(TOOLKIT_APP)) return
        val offer = withContext(Dispatchers.IO) { fetchEntitlements(login.source.get(), serial, release) }.firstOrNull { it.packageName == TOOLKIT_APP }
            ?: throw KitException(ErrorCode.UNSUPPORTED, "Meta's store has no toolkit app for these glasses on this account.", reason = "no_toolkit_app")
        val apk = File(context.cacheDir, "toolkit/${offer.packageName}-${offer.versionCode}.apk")
        download(offer.downloadUrl, offer.sha256, apk)
        step(ToolkitStep.APP, null)
        val result = install.install(apk, offer.packageName, offer.versionCode) { log.add("Toolkit: $it") }
        apk.delete()
        if ("installed=true" !in install.query(TOOLKIT_APP)) throw KitException(ErrorCode.FAILED, "The glasses did not install the toolkit app: $result")
    }

    private suspend fun download(url: String, sha256: String, file: File) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("Authorization", "OAuth ${login.source.get().value}")
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            if (c.responseCode != 200) throw KitException(ErrorCode.FAILED, "Meta's store answered ${c.responseCode} for the toolkit app.")
            val total = c.contentLengthLong.takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            var have = 0L
            file.outputStream().use { out ->
                c.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        have += n
                        if (total != null) step(ToolkitStep.APP, have.toFloat() / total)
                    }
                }
            }
            if (sha256.isNotEmpty() && digest.digest().toHexString() != sha256.lowercase()) {
                file.delete()
                throw KitException(ErrorCode.FAILED, "The toolkit app's download did not match Meta's checksum.")
            }
        } finally {
            c.disconnect()
        }
    }

    /** svc10's answer to GET_VERSION, or null when the glasses say nothing within five seconds. */
    private suspend fun manifestVersion(): ConstellationManifest.Version? = coroutineScope {
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(5_000) {
                connection.serviceData.first { it.serviceId == ConstellationManifest.SERVICE && it.messageType == ConstellationManifest.VERSION_INFO }
            }
        }
        connection.submit { it.sendOnService(ConstellationManifest.SERVICE, ConstellationManifest.GET_VERSION, ByteArray(0)) }
        reply.await()?.let { ConstellationManifest.version(it.payload) }
    }

    private fun step(step: ToolkitStep, fraction: Float? = null) {
        val next = ToolkitProgress.Running(step, fraction)
        if (_progress.value == next) return
        _progress.value = next
        if (fraction == null) log.add("Toolkit: ${step.name.lowercase()}")
        notification(next)
    }

    // While the setup runs, a notification shows its step; tapping it opens the setup's screen.
    private fun notification(running: ToolkitProgress.Running?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (running == null) return manager.cancel(NOTIFICATION_ID)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.toolkit_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, ToolkitActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val percent = running.fraction?.let { (it * 100).toInt() }
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_visor)
                .setContentTitle(context.getString(R.string.toolkit_notification))
                .setContentText(context.getString(stepText(running.step)))
                .setProgress(100, percent ?: 0, percent == null)
                .setOngoing(true)
                .setContentIntent(open)
                .build(),
        )
    }

    companion object {
        const val TOOLKIT_APP = "com.meta.wearable.dat.developer"
        private const val BAND_PRODUCT = 1537
        private const val CHANNEL = "toolkit"
        private const val NOTIFICATION_ID = 7009
        private const val ASK_MS = 120_000L
        private const val PROVISION_MS = 60_000L
        private const val RECONNECT_MS = 60_000L

        /** The glasses' permissions the toolkit app checks for Visor's package. */
        private val GRANTS = listOf("DEV_MODE", "IMU", "DISPLAY", "INPUTS")

        fun stepText(step: ToolkitStep): Int = when (step) {
            ToolkitStep.KEY -> R.string.toolkit_step_key
            ToolkitStep.APP -> R.string.toolkit_step_app
            ToolkitStep.MANIFEST -> R.string.toolkit_step_manifest
            ToolkitStep.PERMISSIONS -> R.string.toolkit_step_permissions
        }
    }
}
