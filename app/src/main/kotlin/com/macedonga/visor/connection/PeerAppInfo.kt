package com.macedonga.visor.connection

import android.os.Build
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.MT_PEER_APP_INFO
import com.macedonga.visor.core.connection.MT_PEER_GET_INFO
import com.macedonga.visor.core.connection.SVC_PEER_BUILD_INFO
import com.macedonga.visor.core.connection.encodePeerAppInfo
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.login.MetaLogin
import com.macedonga.visor.settings.hasDisplay
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * svc100, peer_build_info, asked by the Display's apps: each opens a channel, sends GET_INFO (type 1, empty) and waits
 * 5 s for AppInfo (type 2). Unanswered, they take the phone for neither Android nor iOS. Visor's own ask is the canary.
 */
@Singleton
class PeerAppInfo @Inject constructor(
    private val glasses: Provider<GlassesConnection>,
    private val login: Provider<MetaLogin>,
) : Responder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })

    private val body: ByteArray by lazy {
        encodePeerAppInfo(
            BuildConfig.VERSION_NAME,
            Build.VERSION.SDK_INT.toString(),
            Build.MANUFACTURER.orEmpty(),
            runCatching { login.get().deviceId }.getOrDefault(""),
            Build.MODEL.orEmpty(),
        )
    }

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val info = message.info
        // Only the opening frame names the service; a reused channel id can file other services' types under 100.
        if (info.serviceId != SVC_PEER_BUILD_INFO || info.messageType != MT_PEER_GET_INFO) return
        if ((info.channelId and 0x8000) == 0 || info.flags.isClosed) return
        val reply = DataXConnection.mirror(info.channelId)
        scope.launch {
            // The apps ask as the link comes up, before the canary's answer names the model.
            val build = withTimeoutOrNull(MODEL_WAIT_MS) { glasses.get().state.mapNotNull { it.info?.build }.first() }
            if (hasDisplay(build)) connection.send(reply, body, messageType = MT_PEER_APP_INFO)
        }
    }

    private companion object {
        const val MODEL_WAIT_MS = 4_000L
    }
}
