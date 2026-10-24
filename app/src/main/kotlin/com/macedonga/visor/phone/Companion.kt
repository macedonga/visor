package com.macedonga.visor.phone

import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import com.macedonga.visor.log.EventLog

/**
 * The glasses as Visor's companion device, as the Meta app holds them: Android lets a companion app open other apps
 * from the background, so directions and music open at once instead of waiting in a notification.
 */
fun companionAssociated(context: Context): Boolean = runCatching {
    val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) cdm.myAssociations.isNotEmpty() else cdm.associations.isNotEmpty()
}.getOrDefault(false)

/** Asks once per pair, the first time Visor is in front with it connected; Phone access asks again at any time. */
fun askCompanionOnce(context: Context, address: String, ask: (IntentSender) -> Unit) {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) || companionAssociated(context)) return
    val prefs = context.getSharedPreferences("companion", Context.MODE_PRIVATE)
    val asked = prefs.getStringSet(ASKED, emptySet()).orEmpty()
    if (address.uppercase() in asked) return
    prefs.edit().putStringSet(ASKED, asked + address.uppercase()).apply()
    associateCompanion(context, address, ask) {}
}

/**
 * Asks Android to make the glasses at [address] Visor's companion device, or whichever device the person picks when the
 * address is not known. Android's own dialog comes through [ask].
 */
fun associateCompanion(context: Context, address: String?, ask: (IntentSender) -> Unit, failed: (String) -> Unit) {
    val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return failed("this phone has no companion devices")
    val request = AssociationRequest.Builder()
        .apply { if (address != null) addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build()) }
        .setSingleDevice(address != null)
        .build()
    val callback = object : CompanionDeviceManager.Callback() {
        // Before Android 13.
        @Deprecated("Deprecated in Java")
        override fun onDeviceFound(intentSender: IntentSender) = ask(intentSender)

        override fun onAssociationPending(intentSender: IntentSender) = ask(intentSender)

        override fun onFailure(error: CharSequence?) = failed(error?.toString().orEmpty())
    }
    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) cdm.associate(request, context.mainExecutor, callback)
    else cdm.associate(request, callback, null)
}

/**
 * Asks Android to tell [com.macedonga.visor.connection.GlassesPresence] when each association's glasses appear.
 * Called at every start of the app; repeating it is harmless.
 */
fun observeCompanions(context: Context, log: EventLog) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            cdm.myAssociations.forEach { cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(it.id).build()) }
        } else {
            @Suppress("DEPRECATION")
            cdm.associations.forEach { cdm.startObservingDevicePresence(it) }
        }
    }.onFailure { log.add("Companion presence not observed: ${it.message}") }
}

private const val ASKED = "asked"
