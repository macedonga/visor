package com.macedonga.visor.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.phone.observeCompanions
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * MY_PACKAGE_REPLACED: an update kills the service, and the glasses already in reach raise no presence event for it.
 * Starts the connection again, as [GlassesPresence] does, and asks Android for the presence once more.
 */
@AndroidEntryPoint
class AfterUpdate : BroadcastReceiver() {

    @Inject lateinit var store: IdentityStore

    @Inject lateinit var log: EventLog

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        observeCompanions(context, log)
        val paired = runBlocking(Dispatchers.IO) { store.all().isNotEmpty() }
        if (paired) GlassesService.reconnect(context)
    }
}
