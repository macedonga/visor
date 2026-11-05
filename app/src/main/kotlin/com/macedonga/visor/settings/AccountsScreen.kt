package com.macedonga.visor.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.R
import com.macedonga.visor.connection.AccountLinking
import com.macedonga.visor.connection.LinkOutcome
import com.macedonga.visor.connection.LinkPrompt
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.token.LinkedAccountRef
import com.macedonga.visor.core.identity.token.LinkedAccountType
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AccountRow(val type: LinkedAccountType?, @StringRes val label: Int) {
    META(null, R.string.accounts_meta),
    FACEBOOK(LinkedAccountType.FACEBOOK, R.string.accounts_facebook_messenger),
    INSTAGRAM(LinkedAccountType.INSTAGRAM, R.string.accounts_instagram),
    THREADS(LinkedAccountType.THREADS, R.string.accounts_threads),
}

@HiltViewModel
class AccountsModel @Inject constructor(
    val connection: GlassesConnection,
    private val linking: AccountLinking,
    private val prompt: LinkPrompt,
    private val log: EventLog,
) : ViewModel() {

    /** The account the glasses asked for from one of their apps; its row leads. */
    val asked: StateFlow<LinkedAccountType?> = prompt.asked

    // Null until the glasses answer.
    private val _meta = MutableStateFlow<Boolean?>(null)
    val meta: StateFlow<Boolean?> = _meta.asStateFlow()

    private val _busy = MutableStateFlow<AccountRow?>(null)
    val busy: StateFlow<AccountRow?> = _busy.asStateFlow()

    private val _metaResult = MutableStateFlow<LinkOutcome?>(null)
    val metaResult: StateFlow<LinkOutcome?> = _metaResult.asStateFlow()

    private val _linkedResults = MutableStateFlow<Map<LinkedAccountType, LinkOutcome>>(emptyMap())
    val linkedResults: StateFlow<Map<LinkedAccountType, LinkOutcome>> = _linkedResults.asStateFlow()

    // The provider accounts Meta lists for the user, null until read.
    private val _accounts = MutableStateFlow<Map<LinkedAccountType, LinkedAccountRef>?>(null)
    val accounts: StateFlow<Map<LinkedAccountType, LinkedAccountRef>?> = _accounts.asStateFlow()

    // Rows whose last link failed without an outcome.
    private val _failed = MutableStateFlow<Set<AccountRow>>(emptySet())
    val failed: StateFlow<Set<AccountRow>> = _failed.asStateFlow()

    fun refreshMeta() {
        viewModelScope.launch { linking.metaPresent()?.let { _meta.value = it } }
    }

    fun refreshAccounts() {
        viewModelScope.launch { linking.linkedAccounts()?.let { list -> _accounts.value = list.associateBy { it.type } } }
    }

    fun linkMeta() = task(AccountRow.META) {
        _metaResult.value = linking.syncMeta()
        if (_metaResult.value == LinkOutcome.LINKED) _meta.value = true
    }

    fun linkProvider(row: AccountRow) {
        val type = row.type ?: return
        task(row) {
            val outcome = linking.syncLinked(type)
            _linkedResults.value = _linkedResults.value + (type to outcome)
            if (outcome == LinkOutcome.LINKED && prompt.asked.value == type) prompt.answered()
        }
    }

    private fun task(row: AccountRow, work: suspend () -> Unit) {
        if (_busy.value != null) return
        _busy.value = row
        _failed.value -= row
        viewModelScope.launch {
            try {
                work()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Reported on the row; a failure must not take the app down.
                log.add("Accounts: ${error.javaClass.simpleName}: ${error.message}")
                val type = row.type
                if (type == null) _metaResult.value = null else _linkedResults.value -= type
                _failed.value += row
            } finally {
                _busy.value = null
            }
        }
    }
}

/** The accounts the Display's apps log in with: the Meta account and its linked Facebook (with Messenger), Instagram and Threads. */
@Composable
fun AccountsScreen(model: AccountsModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    val meta by model.meta.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val metaResult by model.metaResult.collectAsStateWithLifecycle()
    val linked by model.linkedResults.collectAsStateWithLifecycle()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val asked by model.asked.collectAsStateWithLifecycle()
    val failed by model.failed.collectAsStateWithLifecycle()
    LaunchedEffect(ready) { if (ready) model.refreshMeta() }
    // Read again on return: an account linked meanwhile gets its Link.
    LifecycleResumeEffect(Unit) {
        model.refreshAccounts()
        onPauseOrDispose { }
    }
    val providers = AccountRow.entries.filter { it.type != null }.sortedByDescending { it.type == asked }

    SettingsPage(stringResource(R.string.accounts_title), light = 0f) {
        item(key = "accounts") {
            Rows(
                {
                    RowFrame(
                        stringResource(AccountRow.META.label),
                        onClick = null,
                        sub = metaResult?.takeIf(::settled)?.let { outcomeWords(it) } ?: when (meta) {
                            true -> stringResource(R.string.accounts_linked)
                            false -> stringResource(R.string.accounts_not_linked)
                            null -> stringResource(R.string.settings_unknown)
                        },
                        problem = failure(metaResult, AccountRow.META in failed),
                        locked = !ready,
                        busy = busy == AccountRow.META,
                    ) {
                        if (meta != true) {
                            ActionPill(
                                stringResource(R.string.accounts_link), { model.linkMeta() },
                                quiet = true, modifier = Modifier.padding(start = 12.dp), enabled = ready && busy == null,
                            )
                        }
                    }
                },
                *providers.map { row ->
                    @Composable { LinkedRow(model, row, ready, busy, linked, accounts, failed = row in failed, lead = row.type == asked) }
                }.toTypedArray(),
            )
        }
    }
}

@Composable
private fun LinkedRow(
    model: AccountsModel,
    row: AccountRow,
    ready: Boolean,
    busy: AccountRow?,
    results: Map<LinkedAccountType, LinkOutcome>,
    accounts: Map<LinkedAccountType, LinkedAccountRef>?,
    failed: Boolean,
    lead: Boolean,
) {
    val account = row.type?.let { accounts?.get(it) }
    val result = row.type?.let { results[it] }
    RowFrame(
        stringResource(row.label),
        onClick = null,
        sub = result?.takeIf(::settled)?.let { outcomeWords(it) } ?: when {
            accounts == null -> null
            account == null -> stringResource(R.string.accounts_not_linked)
            account.forGlasses -> account.name
            else -> stringResource(R.string.accounts_off_for_glasses, account.name)
        },
        problem = failure(result, failed),
        locked = !ready,
        busy = busy == row,
    ) {
        // Meta must list the account, and a row just linked has nothing left to do.
        if ((accounts == null || account != null) && result != LinkOutcome.LINKED) {
            ActionPill(
                stringResource(R.string.accounts_link), { model.linkProvider(row) },
                quiet = !lead, modifier = Modifier.padding(start = 12.dp), enabled = ready && busy == null,
            )
        }
    }
}

/** An outcome that is the row's state rather than an error. */
private fun settled(outcome: LinkOutcome): Boolean = outcome == LinkOutcome.LINKED || outcome == LinkOutcome.NOT_LINKED

/** What went wrong with the row's last link, if anything. */
@Composable
private fun failure(outcome: LinkOutcome?, failed: Boolean): String? = when {
    failed -> stringResource(R.string.accounts_failed)
    outcome != null && !settled(outcome) -> outcomeWords(outcome)
    else -> null
}

@Composable
private fun outcomeWords(outcome: LinkOutcome): String = stringResource(
    when (outcome) {
        LinkOutcome.LINKED -> R.string.accounts_linked
        LinkOutcome.LOGGED_OUT -> R.string.accounts_logged_out
        LinkOutcome.UNREACHABLE -> R.string.accounts_unreachable
        LinkOutcome.NO_ANSWER -> R.string.accounts_no_answer
        LinkOutcome.REFUSED -> R.string.accounts_refused
        LinkOutcome.META_REFUSED -> R.string.accounts_meta_refused
        LinkOutcome.NOT_LINKED -> R.string.accounts_not_linked
        LinkOutcome.NEEDS_INTERNET -> R.string.accounts_needs_internet
    },
)
