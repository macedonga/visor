@file:OptIn(VdkInternal::class)

package com.macedonga.visor.outside

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.vdk.protocol.Hello
import com.macedonga.visor.vdk.protocol.VdkInternal
import com.macedonga.visor.vdk.protocol.Scope
import com.macedonga.visor.vdk.server.Caller
import com.macedonga.visor.vdk.server.Dispatcher
import com.macedonga.visor.vdk.server.Gate
import com.macedonga.visor.vdk.server.VdkServer
import com.macedonga.visor.vdk.server.ShellServer
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Visor's side of the VDK: apps bind [VdkService] for [server], and what they may do is only what the person granted
 * them. While Developer mode is on, the `vdk` command on a computer also reaches the VDK, through adb and
 * [ShellServer].
 */
@Singleton
class VdkHost @Inject constructor(
    private val developer: DeveloperMode,
    private val grants: VdkGrants,
    routes: VdkRoutes,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val gate = object : Gate {
        override fun admit(uid: Int): Caller? {
            val app = grants.packageOf(uid) ?: return null
            return Caller(uid, app)
        }

        override fun granted(caller: Caller): Set<Scope> = grants.scopesOf(caller.packageName)

        override fun grants(): Map<String, Set<Scope>> = grants.all.value.keys.associateWith(grants::scopesOf)

        override fun setGranted(packageName: String, scopes: Set<Scope>): Boolean = grants.set(packageName, scopes)

        override fun greeted(caller: Caller, hello: Hello) {
            log.add("VDK: ${caller.packageName} connected with VDK ${hello.vdk}, protocol ${hello.protocol}")
        }
    }

    private val dispatcher = Dispatcher(BuildConfig.VERSION_NAME, gate, routes.routes(), log::add)
    val server = VdkServer(dispatcher)
    private val shell = ShellServer(dispatcher)

    /** For the app's start: follows Developer mode and the grants for as long as the process lives. */
    fun start() {
        scope.launch {
            developer.on.collect { on -> if (on) openShell() else shell.stop() }
        }
        scope.launch {
            // Streams of a scope the person took back end now.
            grants.all.collect { dispatcher.recheck() }
        }
    }

    private fun openShell() {
        try {
            shell.start()
        } catch (e: IOException) {
            log.add("VDK: the adb socket is taken by another process: ${e.message}")
        }
    }
}

/** What apps bind with the VDK's BIND action. */
@AndroidEntryPoint
class VdkService : Service() {
    @Inject lateinit var host: VdkHost

    override fun onBind(intent: Intent): IBinder = host.server
}
