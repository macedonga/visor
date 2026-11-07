@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.kit.protocol.Hello
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.Scope
import com.macedonga.visor.kit.server.Caller
import com.macedonga.visor.kit.server.Dispatcher
import com.macedonga.visor.kit.server.Gate
import com.macedonga.visor.kit.server.KitServer
import com.macedonga.visor.kit.server.McpAdapter
import com.macedonga.visor.kit.server.ShellServer
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Visor's side of the VDK: apps bind [KitService] for [server], and what they may do is only what the person granted
 * them. While Developer mode is on, the `visor` command on a computer also reaches the VDK, through adb and
 * [ShellServer].
 */
@Singleton
class KitHost @Inject constructor(
    private val developer: DeveloperMode,
    private val grants: KitGrants,
    routes: KitRoutes,
    private val clients: NetworkClients,
    private val network: KitNetwork,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val gate = object : Gate {
        override fun admit(uid: Int): Caller? {
            val app = grants.packageOf(uid) ?: return null
            return Caller(uid, app)
        }

        override fun granted(caller: Caller): Set<Scope> =
            if (caller.network) clients.scopesOf(caller.packageName) else grants.scopesOf(caller.packageName)

        override fun grants(): Map<String, Set<Scope>> = grants.all.value.keys.associateWith(grants::scopesOf)

        override fun setGranted(packageName: String, scopes: Set<Scope>): Boolean = grants.set(packageName, scopes)

        override fun greeted(caller: Caller, hello: Hello) {
            log.add("VDK: ${caller.packageName} connected with VDK ${hello.kit}, protocol ${hello.protocol}")
        }
    }

    private val dispatcher = Dispatcher(BuildConfig.VERSION_NAME, gate, routes.routes(), log::add)
    val server = KitServer(dispatcher)
    private val shell = ShellServer(dispatcher)
    private val mcp = McpAdapter(dispatcher, "Visor", BuildConfig.VERSION_NAME)

    /** For the app's start: follows Developer mode and the grants for as long as the process lives. */
    fun start() {
        scope.launch {
            developer.on.collect { on -> if (on) openShell() else shell.stop() }
        }
        scope.launch {
            // Streams of a scope the person took back end now.
            combine(grants.all, clients.all) { _, _ -> }.collect { dispatcher.recheck() }
        }
        scope.launch(Dispatchers.IO) {
            combine(clients.on, clients.lan) { on, lan -> on to lan }.collect { (on, lan) ->
                if (on) openNetwork(lan) else network.stop()
            }
        }
    }

    private fun openNetwork(lan: Boolean) {
        try {
            network.start(dispatcher, mcp, lan)
        } catch (e: Exception) {
            // Ktor reports a taken port in its own exception types.
            log.add("Kit: the network door did not open: $e")
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
class KitService : Service() {
    @Inject lateinit var host: KitHost

    override fun onBind(intent: Intent): IBinder = host.server
}
