package com.macedonga.visor.phone

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
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

/** Android lets Visor open another app while one of its activities is in front, or as the glasses' companion. */
fun mayOpenApps(context: Context): Boolean = ActivityManager.RunningAppProcessInfo()
    .also(ActivityManager::getMyMemoryState).importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
    companionAssociated(context)

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
fun associateCompanion(context: Context, address: String?, ask: (IntentSender) -> Unit, failed: () -> Unit) {
    val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return failed()
    val request = AssociationRequest.Builder()
        .apply { if (address != null) addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build()) }
        .setSingleDevice(address != null)
        .build()
    val callback = object : CompanionDeviceManager.Callback() {
        // Before Android 13.
        @Deprecated("Deprecated in Java")
        override fun onDeviceFound(intentSender: IntentSender) = ask(intentSender)

        override fun onAssociationPending(intentSender: IntentSender) = ask(intentSender)

        override fun onFailure(error: CharSequence?) = failed()
    }
    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) cdm.associate(request, context.mainExecutor, callback)
    else cdm.associate(request, callback, null)
}

/**
 * Asks Android to tell [com.macedonga.visor.connection.GlassesPresence] when each association's glasses appear, once per
 * association. Android keeps observing across restarts, and on some phones each repeat leaves one more Bluetooth scanner
 * running, until no app on the phone can scan at all.
 */
fun observeCompanions(context: Context, log: EventLog) {
    val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return
    val prefs = context.getSharedPreferences("companion", Context.MODE_PRIVATE)
    val before = prefs.getStringSet(OBSERVED, emptySet()).orEmpty()
    val observed = mutableSetOf<String>()
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            for (association in cdm.myAssociations) {
                val key = "id:${association.id}"
                if (key !in before) cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build())
                observed += key
            }
        } else {
            @Suppress("DEPRECATION")
            for (address in cdm.associations) {
                val key = "mac:${address.uppercase()}"
                if (key !in before) cdm.startObservingDevicePresence(address)
                observed += key
            }
        }
    }.onFailure { log.add("Companion presence not observed: ${it.message}") }
    // Associations that are gone drop out, so one made again is observed again.
    prefs.edit().putStringSet(OBSERVED, observed).apply()
}

/** Visor's association with the device at [address], by its id, or null. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun companionId(context: Context, address: String): Int? = runCatching {
    context.getSystemService(CompanionDeviceManager::class.java)?.myAssociations
        ?.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }?.id
}.getOrNull()

/** Has Android remove the phone's bond with the device of association [id]; the device's own removeBond is a system API. */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@SuppressLint("MissingPermission")
fun removeCompanionBond(context: Context, id: Int, failed: (Throwable) -> Unit = {}): Boolean = runCatching {
    context.getSystemService(CompanionDeviceManager::class.java)?.removeBond(id) == true
}.onFailure(failed).getOrDefault(false)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun disassociateCompanion(context: Context, id: Int): Boolean = runCatching {
    val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
    cdm.disassociate(id)
    true
}.getOrDefault(false)

private const val ASKED = "asked"
private const val OBSERVED = "observed"
