package com.macedonga.visor.connection

import com.macedonga.visor.core.identity.token.withToken
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.token.LinkedAccountType
import com.macedonga.visor.core.identity.token.TokenError
import com.macedonga.visor.core.identity.token.exchangeAccountToken
import com.macedonga.visor.core.identity.token.LinkedAccountRef
import com.macedonga.visor.core.identity.token.fetchLinkedAccounts
import com.macedonga.visor.core.identity.token.fetchLinkedTokens
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What a link attempt came to, for the Accounts page to say. */
enum class LinkOutcome { LINKED, LOGGED_OUT, UNREACHABLE, NO_ANSWER, REFUSED, NOT_LINKED }

/**
 * svc89, wearables_whatsapp_linking: the Meta account and WhatsApp linking the Meta app runs with the Display. Visor
 * opens the channel. 25 asks whether a Meta account is present (answered by 26). 5 is the Meta account sync, with the
 * user's token minted for the glasses' accounts app (answered by 13, after an ack 17 Visor does not wait for). 6 is a
 * linked provider account, Facebook or Instagram, with its tokens (answered by 14, after an ack 18). 22 is the WhatsApp
 * QR pairing: the glasses show a code for WhatsApp's Linked devices and answer 23 when it is scanned.
 */
@Singleton
class AccountLinking @Inject constructor(
    private val connection: GlassesConnection,
    private val login: MetaLogin,
    private val log: EventLog,
) {
    // One exchange at a time: each answer is taken as the next one of its type.
    private val lock = Mutex()

    /** Whether the glasses hold a Meta account; null when they do not answer. */
    suspend fun metaPresent(): Boolean? {
        val f = ask(MT_PRESENT_REQUEST, MT_PRESENT_RESPONSE, Wire.fieldBytes(1, str(uuid())), ANSWER_MS) ?: return null
        return f.varint(2) == 1L
    }

    /** Mints the user's token for the accounts app and sends the Meta account to the glasses. */
    suspend fun syncMeta(): LinkOutcome {
        if (!login.source.hasCachedLogin) return LinkOutcome.LOGGED_OUT
        val identity = try {
            withContext(Dispatchers.IO) { login.source.withToken { exchangeAccountToken(it) } }
        } catch (error: TokenError) {
            log.add("Accounts: ${error.message}")
            return if (error.unreachable) LinkOutcome.UNREACHABLE else LinkOutcome.REFUSED
        }
        val request = Wire.fieldBytes(1, str(uuid())) +
            Wire.fieldBytes(2, str(identity.accessToken)) +
            Wire.fieldBytes(3, str(identity.appId)) +
            Wire.fieldBytes(4, str(identity.userId)) +
            Wire.fieldBytes(5, str(identity.analyticsClaim)) +
            Wire.fieldBytes(6, str(SOURCE)) +
            Wire.fieldBytes(7, str(identity.accountId)) +
            Wire.fieldBytes(8, str(identity.profilePictureUrl)) +
            Wire.fieldVarint(9, flag(identity.isEmployee)) +
            Wire.fieldVarint(10, flag(identity.isTestUser)) +
            Wire.fieldBytes(11, str(identity.name)) +
            Wire.fieldBytes(12, str(uuid()))
        val f = ask(MT_META_SYNC_REQUEST, MT_META_SYNC_RESPONSE, request, ANSWER_MS)
        if (f == null) {
            log.add("Accounts: the glasses did not answer the Meta account")
            return LinkOutcome.NO_ANSWER
        }
        if (f.varint(2) != 1L) {
            log.add("Accounts: the glasses refused the Meta account, code ${f.varint(3)}")
            return LinkOutcome.REFUSED
        }
        log.add("Accounts: Meta account sent to the glasses")
        return LinkOutcome.LINKED
    }

    /** The provider accounts linked to the user's Meta account, as Meta lists them; null when they cannot be read. */
    suspend fun linkedAccounts(): List<LinkedAccountRef>? {
        if (!login.source.hasCachedLogin) return null
        return try {
            withContext(Dispatchers.IO) { fetchLinkedAccounts(login.source.withToken { exchangeAccountToken(it) }.accessToken) }
        } catch (error: TokenError) {
            log.add("Accounts: ${error.message}")
            null
        }
    }

    /** Sends a provider account of the Meta account, with its tokens, to the glasses. */
    suspend fun syncLinked(type: LinkedAccountType): LinkOutcome {
        if (!login.source.hasCachedLogin) return LinkOutcome.LOGGED_OUT
        val found = try {
            withContext(Dispatchers.IO) {
                val accountsToken = login.source.withToken { exchangeAccountToken(it) }.accessToken
                val account = fetchLinkedAccounts(accountsToken).firstOrNull { it.type == type } ?: return@withContext null
                account to (fetchLinkedTokens(accountsToken, account, login.deviceId) ?: return@withContext null)
            }
        } catch (error: TokenError) {
            log.add("Accounts: ${error.message}")
            return if (error.unreachable) LinkOutcome.UNREACHABLE else LinkOutcome.REFUSED
        }
        if (found == null) {
            log.add("Accounts: ${type.name.lowercase()} is not linked to this Meta account")
            return LinkOutcome.NOT_LINKED
        }
        val (account, tokens) = found
        val credentials = Wire.fieldBytes(1, str(tokens.accessToken)) +
            (tokens.firstPartyToken?.let { Wire.fieldBytes(2, str(it)) } ?: ByteArray(0))
        val request = Wire.fieldBytes(1, str(uuid())) +
            Wire.fieldVarint(2, type.wire.toLong()) +
            Wire.fieldBytes(4, str(account.obfuscatedId)) +
            Wire.fieldVarint(5, flag(true)) +
            Wire.fieldBytes(6, str(uuid())) +
            Wire.fieldBytes(7, credentials)
        val f = ask(MT_LINKED_SYNC_REQUEST, MT_LINKED_SYNC_RESPONSE, request, ANSWER_MS)
        if (f == null) {
            log.add("Accounts: the glasses did not answer the ${type.name.lowercase()} account")
            return LinkOutcome.NO_ANSWER
        }
        if (f.varint(2) != 1L) {
            log.add("Accounts: the glasses refused the ${type.name.lowercase()} account, code ${f.varint(3)}")
            return LinkOutcome.REFUSED
        }
        log.add("Accounts: ${type.name.lowercase()} account sent to the glasses")
        return LinkOutcome.LINKED
    }

    /** Has the glasses show their WhatsApp code; the answer comes once it is scanned from WhatsApp's Linked devices. */
    suspend fun linkWhatsApp(): LinkOutcome {
        val f = ask(MT_QR_PAIRING_REQUEST, MT_QR_PAIRING_RESPONSE, Wire.fieldBytes(1, str(uuid())), SCAN_MS)
        if (f == null) {
            log.add("Accounts: no WhatsApp pairing answer from the glasses")
            return LinkOutcome.NO_ANSWER
        }
        if (f.varint(2) != 1L) {
            log.add("Accounts: the glasses did not pair WhatsApp, code ${f.varint(3)}")
            return LinkOutcome.REFUSED
        }
        log.add("Accounts: WhatsApp paired on the glasses")
        return LinkOutcome.LINKED
    }

    /** Sends one request on svc89 and waits for the answer of [answer] type; null when none comes in [waitMs]. */
    private suspend fun ask(type: Int, answer: Int, payload: ByteArray, waitMs: Long): Wire.Fields? = lock.withLock {
        coroutineScope {
            val fromGlasses = Channel<ServiceData>(Channel.UNLIMITED)
            // Subscribed before the request goes out, so the answer is not missed.
            val listening = launch(start = CoroutineStart.UNDISPATCHED) {
                connection.serviceData.collect { if (it.serviceId == SVC && it.messageType == answer) fromGlasses.send(it) }
            }
            try {
                connection.submit { it.sendOnService(SVC, type, payload) }
                withTimeoutOrNull(waitMs) { runCatching { Wire.parse(fromGlasses.receive().payload) }.getOrNull() }
            } finally {
                listening.cancel()
                fromGlasses.close()
            }
        }
    }

    private fun uuid() = UUID.randomUUID().toString()

    private fun str(s: String) = s.encodeToByteArray()

    private fun flag(on: Boolean) = if (on) 1L else 0L

    companion object {
        const val SVC = 89

        // The phone's default for its account and WhatsApp waits.
        private const val ANSWER_MS = 20_000L

        // Until the code is scanned; the glasses refresh it while it waits.
        private const val SCAN_MS = 180_000L

        // The Meta app's source constant for the account sync.
        private const val SOURCE = "306760944872162"

        private const val MT_META_SYNC_REQUEST = 5
        private const val MT_META_SYNC_RESPONSE = 13
        private const val MT_PRESENT_REQUEST = 25
        private const val MT_PRESENT_RESPONSE = 26
        private const val MT_QR_PAIRING_REQUEST = 22
        private const val MT_QR_PAIRING_RESPONSE = 23
        private const val MT_LINKED_SYNC_REQUEST = 6
        private const val MT_LINKED_SYNC_RESPONSE = 14
    }
}
