package com.macedonga.visor.core.media

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.SnApp
import com.macedonga.visor.core.protocol.SVC_LEASE
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.ServiceData
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Installs an APK through the glasses' app manager: a svc56 lease, an APP_MANAGER start on svc66, the APK on airship "appmanager". */
class AppInstall(private val connection: GlassesConnection, private val airship: AirshipReceiver) {

    /** Install [apk] as [packageName]; [note] hears every step. Returns how it ended. */
    suspend fun install(apk: File, packageName: String, versionCode: Long, note: (String) -> Unit): String =
        withAppManager(apk.length() / 1_048_576 + 50, note) { send, next ->
        val uuid = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Wire.fieldString(1, UUID.randomUUID().toString())
        val request = Wire.fieldBytes(1, key) + Wire.fieldString(2, packageName) + Wire.fieldString(3, versionCode.toString()) + Wire.fieldBytes(4, uuid) +
            Wire.fieldVarintNonZero(5, apk.length()) + Wire.fieldString(8, packageName)
        send(MT_INSTALL, request)
        note("App install: asked to install $packageName (${sizeWords(apk.length())})")
        val answer = next(ANSWER_MS) ?: return@withAppManager "no answer to the install request"
        note("App install: ${describe(answer)}")
        val f = Wire.parse(answer.payload)
        // Field 2 is the file action; 0 (absent on the wire) is SEND.
        if (answer.messageType != MT_ANSWER || f.varint(2) != 0L || f.varint(4) != 0L) return@withAppManager describe(answer)

        val sent = CompletableDeferred<String?>()
        connection.submit { mux -> sent.complete(airship.send(mux, NAMESPACE, "$packageName.apk", apk.readBytes(), uuid = uuid)) }
        val why = withTimeoutOrNull(TRANSFER_MS) { sent.await() }
        if (!sent.isCompleted || why != null) return@withAppManager "transfer failed: ${why ?: "no link or no answer"}"
        note("App install: file sent")

        do {
            val m = next(INSTALL_MS) ?: return@withAppManager "no word from the glasses after the transfer"
            note("App install: ${describe(m)}")
            if (m.messageType == MT_ERROR) return@withAppManager describe(m)
        } while (m.messageType != MT_PROGRESS || Wire.parse(m.payload).varint(3) != STAGE_COMPLETE)
        "installed"
    }

    /** What the app manager knows about [packageName]: installed, version, sideloaded. */
    suspend fun query(packageName: String, note: (String) -> Unit = {}): String = withAppManager(50, note) { send, next ->
        val key = Wire.fieldString(1, UUID.randomUUID().toString())
        send(MT_QUERY, Wire.fieldBytes(1, key) + Wire.fieldString(2, packageName))
        val m = next(ANSWER_MS) ?: return@withAppManager "no answer"
        if (m.messageType != MT_QUERY_ANSWER) return@withAppManager describe(m)
        val f = Wire.parse(m.payload)
        "installed=${f.varint(5) != 0L} versionCode=${f.varint(3)} versionName=${f.bytes(4).decodeToString()} sideloaded=${f.varint(8) != 0L} " +
            "system=${f.varint(7) != 0L} entitled=${f.varint(6) != 0L}"
    }

    /**
     * [work] with the app manager's service up, on a channel of its own: the lease taken and its BOUND awaited, a start
     * of APP_MANAGER answered (the service registers a moment after the bind, so a channel the glasses did not know is
     * dropped and another one opened), the lease given back after.
     */
    private suspend fun withAppManager(
        megabytes: Long,
        note: (String) -> Unit,
        work: suspend (send: suspend (Int, ByteArray) -> Unit, next: suspend (Long) -> ServiceData?) -> String,
    ): String = coroutineScope {
        val inbox = mapOf(SVC_LEASE to Channel<ServiceData>(Channel.UNLIMITED), SVC to Channel(Channel.UNLIMITED))
        val listening = CompletableDeferred<Unit>()
        val listener = launch {
            connection.serviceData.onSubscription { listening.complete(Unit) }.collect { inbox[it.serviceId]?.send(it) }
        }
        listening.await()
        var channel: Int? = null
        val send: suspend (Int, ByteArray) -> Unit = { type, payload ->
            connection.submit { mux ->
                val open = channel
                if (open != null) {
                    mux.send(open, payload, messageType = type)
                } else {
                    val fresh = mux.openChannel(SVC)
                    channel = fresh
                    mux.send(fresh, payload, messageType = type, serviceId = SVC)
                }
            }
        }
        val next: suspend (Long) -> ServiceData? = { ms -> withTimeoutOrNull(ms) { inbox.getValue(SVC).receive() } }
        val lease = Wire.fieldVarintNonZero(1, FEATURE_APP_MANAGER)
        try {
            connection.submit { it.sendOnService(SVC_LEASE, MT_LEASE, lease) }
            val bound = withTimeoutOrNull(ANSWER_MS) {
                var m: ServiceData
                do {
                    m = inbox.getValue(SVC_LEASE).receive()
                    val state = Wire.parse(m.payload).varint(2)
                    note("App install: lease answer type ${m.messageType}, state $state")
                } while (m.messageType != MT_LEASE_ERROR && !(m.messageType == MT_LIFECYCLE && state == STATE_BOUND))
                m.messageType != MT_LEASE_ERROR
            }
            if (bound != true) return@coroutineScope "the app manager was not bound (${if (bound == null) "no answer" else "lease refused"})"

            val start = SnApp.app(APP_MANAGER, policy = Wire.fieldBytes(1, Wire.fieldVarintNonZero(1, megabytes)))
            var started: ServiceData? = null
            for (attempt in 1..START_TRIES) {
                delay(REGISTER_MS)
                channel = null
                send(SnApp.MT_START_APP, start)
                started = next(START_MS)
                if (started != null) break
                note("App install: no answer to the start yet (try $attempt)")
            }
            val answer = started ?: return@coroutineScope "the app manager's service never answered"
            note("App install: ${describe(answer)}")
            work(send, next)
        } finally {
            connection.submit { it.sendOnService(SVC_LEASE, MT_RELEASE, lease) }
            listener.cancel()
        }
    }

    private fun describe(m: ServiceData): String {
        val f = Wire.parse(m.payload)
        return when (m.messageType) {
            SnApp.MT_START_APP_RESPONSE -> "app manager start: ${SnApp.words(f.varint(1))}, status ${f.varint(2)}"
            MT_ANSWER -> "answer: file action ${f.varint(2)}, error ${f.varint(4)} ${f.bytes(5).decodeToString()}".trim()
            MT_PROGRESS -> "stage ${STAGES.getOrElse(f.varint(3).toInt()) { f.varint(3).toString() }}"
            MT_ERROR -> "${SnApp.words(f.varint(4))} at stage ${f.varint(3)}: ${f.bytes(5).decodeToString()}"
            else -> "message ${m.messageType}, ${m.payload.size} bytes"
        }
    }

    private companion object {
        const val MT_LEASE = 1
        const val MT_RELEASE = 2
        const val MT_LIFECYCLE = 3
        const val MT_LEASE_ERROR = 4
        const val STATE_BOUND = 1L
        const val REGISTER_MS = 1_500L
        const val START_MS = 4_000L
        const val START_TRIES = 4
        const val FEATURE_APP_MANAGER = 18L
        const val SVC = 66
        const val APP_MANAGER = 14L
        const val MT_INSTALL = 2500
        const val MT_ANSWER = 2501
        const val MT_PROGRESS = 2502
        const val MT_ERROR = 2503
        const val MT_QUERY = 2800
        const val MT_QUERY_ANSWER = 2801
        const val NAMESPACE = "appmanager"
        const val STAGE_COMPLETE = 3L
        const val ANSWER_MS = 15_000L
        const val TRANSFER_MS = 10 * 60_000L
        const val INSTALL_MS = 120_000L
        val STAGES = listOf("unknown", "transfer", "installing", "complete", "stopped")
    }
}
