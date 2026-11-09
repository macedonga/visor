package com.macedonga.visor.setup

import android.content.Context
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.connection.TransportFactory
import com.macedonga.visor.core.identity.BundleState
import com.macedonga.visor.core.identity.IdentityBundle
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.nowIso
import com.macedonga.visor.core.identity.provision.ProvisionStep
import com.macedonga.visor.core.identity.provision.WrongOwnerError
import com.macedonga.visor.core.identity.provision.provision
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.transport.LeKeeper
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Runs one change owner setup, and keeps its progress for whoever is watching. */
@Singleton
class Provisioner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val store: IdentityStore,
    private val transports: TransportFactory,
    private val login: MetaLogin,
    private val log: EventLog,
    private val keeper: LeKeeper,
    private val media: MediaImport,
) {
    sealed interface Status {
        data object Idle : Status
        data class Running(val reached: List<ProvisionStep>) : Status
        data class Done(val target: GlassesTarget) : Status
        data class Failed(val reached: List<ProvisionStep>, val wrongOwner: Boolean = false) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun start(address: String) {
        if (_status.value is Status.Running) return
        val reached = mutableListOf<ProvisionStep>()
        _status.value = Status.Running(emptyList())
        log.add("Setup: starting on $address")
        scope.launch {
            // A failure lets go of the LE hold only once this setup took it from the service.
            var held = false
            try {
                releaseTheLink()
                // Over LE when it comes up: folded in pairing mode the glasses may not answer on RFCOMM.
                keeper.hold(address)
                held = true
                val existing = withContext(Dispatchers.IO) { store.ownerKeyPem() }
                log.add(if (existing != null) "Setup: reusing the owner key already on this phone" else "Setup: making a new owner key")
                val result = provision(
                    transport = transports.create(GlassesLink.Bluetooth(address)),
                    source = login.source,
                    existingKeyPem = existing,
                    onStep = { step ->
                        reached += step
                        _status.value = Status.Running(reached.toList())
                        log.add("Setup: ${step.words()}")
                    },
                    onLink = { log.add("Setup: link $it") },
                )
                withContext(Dispatchers.IO) {
                    store.save(
                        IdentityBundle(
                            privateKeyPem = result.keyPem,
                            glassesAddress = address,
                            serial = result.serial,
                            createdAt = nowIso(),
                            provenance = "change-owner",
                            state = BundleState.UNVERIFIED,
                        ),
                    )
                }
                log.add("Setup: owner changed. Serial ${result.serial}, key reused: ${result.reusedKey}")
                _status.value = Status.Done(GlassesTarget(GlassesLink.Bluetooth(address), result.serial))
            } catch (error: Exception) {
                val reason = error.message ?: error.javaClass.simpleName
                log.add("Setup failed: $reason")
                if (held) keeper.release()
                _status.value = Status.Failed(reached.toList(), wrongOwner = error is WrongOwnerError)
            }
        }
    }

    /** Back to idle once the screen has shown the result. */
    fun reset() {
        if (_status.value !is Status.Running) _status.value = Status.Idle
    }

    /** Stop the service and wait for its socket to close. */
    private suspend fun releaseTheLink() {
        // An import holds Wi-Fi Direct and pauses Bluetooth: it goes first, and leaves both as they were.
        withTimeoutOrNull(RELEASE_TIMEOUT_MILLIS) { media.stop() }
        if (!connection.running.value) return
        log.add("Setup: stopping the current connection first")
        GlassesService.stop(context)
        withTimeoutOrNull(RELEASE_TIMEOUT_MILLIS) { connection.running.first { !it } }
            ?: throw IllegalStateException("the current connection did not let go of the glasses")
    }

    private companion object {
        const val RELEASE_TIMEOUT_MILLIS = 10_000L
    }
}

/** The setup steps in plain words, for the event log. */
fun ProvisionStep.words(): String = when (this) {
    ProvisionStep.TOKEN -> "getting a pairing token from Meta"
    ProvisionStep.CONNECT -> "reaching the glasses over Bluetooth"
    ProvisionStep.SECURE_LINK -> "setting up the encrypted link"
    ProvisionStep.IDENTITY -> "asking the glasses who they are"
    ProvisionStep.SKIP_CHALLENGE -> "asking the glasses to skip the button press"
    ProvisionStep.PAIR_REQUEST -> "asking Meta to start the pairing"
    ProvisionStep.START_CHANGE_OWNER -> "handing the glasses the pending pairing"
    ProvisionStep.PRESS_BUTTON -> "waiting for a press of the glasses' button"
    ProvisionStep.PAIR -> "asking Meta to finish the pairing"
    ProvisionStep.FINISH_CHANGE_OWNER -> "the glasses saving this phone's key"
}
