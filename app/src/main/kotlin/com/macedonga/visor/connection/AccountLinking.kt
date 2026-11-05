package com.macedonga.visor.connection

import com.macedonga.visor.core.identity.token.withToken
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.token.LinkedAccountType
import com.macedonga.visor.core.identity.token.TokenError
import com.macedonga.visor.core.identity.token.exchangeAccountToken
import com.macedonga.visor.core.identity.token.LinkedAccountRef
import com.macedonga.visor.core.identity.token.fetchLinkedAccounts
import com.macedonga.visor.core.identity.token.fetchLinkedMetadata
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
enum class LinkOutcome { LINKED, LOGGED_OUT, UNREACHABLE, NO_ANSWER, REFUSED, META_REFUSED, NOT_LINKED, NEEDS_INTERNET }

/**
 * svc89, wearables_whatsapp_linking: the Meta account linking the Meta app runs with the Display. Visor opens the
 * channel. 25 asks whether a Meta account is present (answered by 26). 5 is the Meta account sync, with the user's token
 * minted for the glasses' accounts app (answered by 13, after an ack 17 Visor does not wait for). 6 is a linked provider
 * account, Facebook (which Messenger rides on), Instagram or Threads, with its tokens and ids (answered by 14, after an
 * ack 18). WhatsApp's own linking is not here: the phone's WhatsApp takes it only from an app signed with Meta's key.
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
        val f = ask(MT_PRESENT_REQUEST, MT_PRESENT_RESPONSE, Wire.fieldString(1, uuid()), ANSWER_MS) ?: return null
        return f.varint(2) == 1L
    }

    /** Mints the user's token for the accounts app and sends the Meta account to the glasses. */
    suspend fun syncMeta(): LinkOutcome {
        if (!login.source.hasCachedLogin) return LinkOutcome.LOGGED_OUT
        val identity = try {
            withContext(Dispatchers.IO) { login.source.withToken { exchangeAccountToken(it) } }
        } catch (error: TokenError) {
            log.add("Accounts: ${error.message}")
            return if (error.unreachable) LinkOutcome.UNREACHABLE else LinkOutcome.META_REFUSED
        }
        val request = Wire.fieldString(1, uuid()) +
            Wire.fieldString(2, identity.accessToken) +
            Wire.fieldString(3, identity.appId) +
            Wire.fieldString(4, identity.userId) +
            Wire.fieldString(5, identity.analyticsClaim) +
            Wire.fieldString(6, SOURCE) +
            Wire.fieldString(7, identity.accountId) +
            Wire.fieldString(8, identity.profilePictureUrl) +
            Wire.fieldVarint(9, flag(identity.isEmployee)) +
            Wire.fieldVarint(10, flag(identity.isTestUser)) +
            Wire.fieldString(11, identity.name) +
            Wire.fieldString(12, uuid())
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

    /** Sends a provider account to the glasses: with its ids they set it up from the tokens, without them they look it up. */
    suspend fun syncLinked(type: LinkedAccountType): LinkOutcome {
        val name = type.name.lowercase()
        if (!login.source.hasCachedLogin) return LinkOutcome.LOGGED_OUT
        val found = try {
            withContext(Dispatchers.IO) {
                val accountsToken = login.source.withToken { exchangeAccountToken(it) }.accessToken
                val account = fetchLinkedAccounts(accountsToken).firstOrNull { it.type == type } ?: return@withContext null
                val tokens = fetchLinkedTokens(accountsToken, account, login.deviceId) ?: throw TokenError("Meta gave no $name token")
                val metadata = try {
                    fetchLinkedMetadata(type, tokens)
                } catch (error: TokenError) {
                    log.add("Accounts: no $name ids: ${error.message}")
                    null
                }
                Triple(account, tokens, metadata)
            }
        } catch (error: TokenError) {
            log.add("Accounts: ${error.message}")
            return if (error.unreachable) LinkOutcome.UNREACHABLE else LinkOutcome.META_REFUSED
        }
        if (found == null) {
            log.add("Accounts: $name is not linked to this Meta account")
            return LinkOutcome.NOT_LINKED
        }
        val (account, tokens, metadata) = found
        val credentials = metadata?.let {
            Wire.fieldString(1, tokens.accessToken) +
                (tokens.firstPartyToken?.let { t -> Wire.fieldString(2, t) } ?: ByteArray(0)) +
                Wire.fieldString(3, it)
        }
        val request = Wire.fieldString(1, uuid()) +
            Wire.fieldVarint(2, type.wire.toLong()) +
            Wire.fieldString(4, account.obfuscatedId) +
            Wire.fieldVarint(5, flag(true)) +
            (credentials?.let { Wire.fieldBytes(7, it) } ?: ByteArray(0))
        if (credentials == null) log.add("Accounts: sending $name without its ids; the glasses look it up")
        val f = ask(MT_LINKED_SYNC_REQUEST, MT_LINKED_SYNC_RESPONSE, request, LINKED_MS)
        if (f == null) {
            log.add("Accounts: the glasses did not answer the $name account")
            return LinkOutcome.NO_ANSWER
        }
        if (f.varint(2) != 1L) {
            log.add("Accounts: the glasses refused the $name account, code ${f.varint(3)}")
            return if (f.varint(3) == CODE_NO_INTERNET) LinkOutcome.NEEDS_INTERNET else LinkOutcome.REFUSED
        }
        log.add("Accounts: $name account sent to the glasses")
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
                withTimeoutOrNull(waitMs) { fromGlasses.receive() }?.let { runCatching { Wire.parse(it.payload) }.getOrNull() }
            } finally {
                listening.cancel()
                fromGlasses.close()
            }
        }
    }

    private fun uuid() = UUID.randomUUID().toString()

    private fun flag(on: Boolean) = if (on) 1L else 0L

    companion object {
        const val SVC = 89

        // As long as the Meta app waits for an account answer.
        private const val ANSWER_MS = 20_000L

        // Past the glasses' own 32 s deadline for a provider account, so their answer is heard.
        private const val LINKED_MS = 35_000L

        // LinkedAccountSyncResponse's error when the glasses could not reach the internet.
        private const val CODE_NO_INTERNET = 101L

        // The Meta app's source constant for the account sync.
        private const val SOURCE = "306760944872162"

        private const val MT_META_SYNC_REQUEST = 5
        private const val MT_META_SYNC_RESPONSE = 13
        private const val MT_PRESENT_REQUEST = 25
        private const val MT_PRESENT_RESPONSE = 26
        private const val MT_LINKED_SYNC_REQUEST = 6
        private const val MT_LINKED_SYNC_RESPONSE = 14
    }
}
