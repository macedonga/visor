package com.macedonga.visor.connection

import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import androidx.annotation.RequiresApi
import com.macedonga.visor.core.identity.IdentityStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Bound by the system when the associated glasses appear, so the connection starts even with Visor in the background.
 * Nothing is done when they disappear: the reconnect ladder handles that.
 */
@AndroidEntryPoint
class GlassesPresence : CompanionDeviceService() {

    @Inject lateinit var store: IdentityStore

    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event != DevicePresenceEvent.EVENT_BLE_APPEARED && event.event != DevicePresenceEvent.EVENT_BT_CONNECTED) return
        val address = getSystemService(CompanionDeviceManager::class.java).myAssociations
            .firstOrNull { it.id == event.associationId }?.deviceMacAddress?.toString()
        appeared(address)
    }

    @RequiresApi(33)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared(associationInfo.deviceMacAddress?.toString())

    // Before Android 13, the association is known by its address.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) = appeared(address)

    /** Reaches the pair Visor is for when it is the one at [address]; another pair stays as it is. */
    private fun appeared(address: String?) {
        if (address == null) return
        val current = runBlocking(Dispatchers.IO) { store.currentSerial()?.let { serial -> store.all().firstOrNull { it.serial == serial } } } ?: return
        if (!current.glassesAddress.equals(address, ignoreCase = true)) return
        GlassesService.reconnect(this)
    }
}
