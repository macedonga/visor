package com.macedonga.visor.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.R
import com.macedonga.visor.connection.AccountLinking
import com.macedonga.visor.connection.LinkOutcome
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.token.LinkedAccountRef
import com.macedonga.visor.core.identity.token.LinkedAccountType
import com.macedonga.visor.core.session.LinkState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AccountRow { META, WHATSAPP, FACEBOOK, INSTAGRAM }

@HiltViewModel
class AccountsModel @Inject constructor(
    val connection: GlassesConnection,
    private val linking: AccountLinking,
    private val log: com.macedonga.visor.log.EventLog,
) : ViewModel() {

    // Null until the glasses answer.
    private val _meta = MutableStateFlow<Boolean?>(null)
    val meta: StateFlow<Boolean?> = _meta.asStateFlow()

    private val _busy = MutableStateFlow<AccountRow?>(null)
    val busy: StateFlow<AccountRow?> = _busy.asStateFlow()

    private val _metaResult = MutableStateFlow<LinkOutcome?>(null)
    val metaResult: StateFlow<LinkOutcome?> = _metaResult.asStateFlow()

    private val _whatsappResult = MutableStateFlow<LinkOutcome?>(null)
    val whatsappResult: StateFlow<LinkOutcome?> = _whatsappResult.asStateFlow()

    private val _linkedResults = MutableStateFlow<Map<LinkedAccountType, LinkOutcome>>(emptyMap())
    val linkedResults: StateFlow<Map<LinkedAccountType, LinkOutcome>> = _linkedResults.asStateFlow()

    // The provider accounts Meta lists for the user, null until read.
    private val _accounts = MutableStateFlow<Map<LinkedAccountType, LinkedAccountRef>?>(null)
    val accounts: StateFlow<Map<LinkedAccountType, LinkedAccountRef>?> = _accounts.asStateFlow()

    fun refresh() {
        viewModelScope.launch { linking.metaPresent()?.let { _meta.value = it } }
        viewModelScope.launch { linking.linkedAccounts()?.let { list -> _accounts.value = list.associateBy { it.type } } }
    }

    fun linkMeta() = task(AccountRow.META) {
        _metaResult.value = linking.syncMeta()
        if (_metaResult.value == LinkOutcome.LINKED) _meta.value = true
    }

    fun linkWhatsApp() = task(AccountRow.WHATSAPP) {
        _whatsappResult.value = linking.linkWhatsApp()
    }

    fun linkProvider(row: AccountRow, type: LinkedAccountType) = task(row) {
        _linkedResults.value = _linkedResults.value + (type to linking.syncLinked(type))
    }

    private fun task(row: AccountRow, work: suspend () -> Unit) {
        if (_busy.value != null) return
        _busy.value = row
        viewModelScope.launch {
            try {
                work()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                // A failure the linking did not foresee stays on this page; it must not take the app down.
                log.add("Accounts: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                _busy.value = null
            }
        }
    }
}

/** The accounts the Display's apps log in with: the Meta account, and WhatsApp's link. */
@Composable
fun AccountsScreen(model: AccountsModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    val meta by model.meta.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val metaResult by model.metaResult.collectAsStateWithLifecycle()
    val whatsappResult by model.whatsappResult.collectAsStateWithLifecycle()
    val linked by model.linkedResults.collectAsStateWithLifecycle()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    LaunchedEffect(ready) { if (ready) model.refresh() }

    SettingsPage(stringResource(R.string.accounts_title), light = 0f) {
        item(key = "accounts") {
            Rows(
                {
                    RowFrame(
                        stringResource(R.string.accounts_meta),
                        onClick = null,
                        sub = metaResult?.let { outcomeWords(it) } ?: when (meta) {
                            true -> stringResource(R.string.accounts_linked)
                            false -> stringResource(R.string.accounts_not_linked)
                            null -> stringResource(R.string.settings_unknown)
                        },
                        locked = !ready,
                        busy = busy == AccountRow.META,
                    ) {
                        if (meta == false) {
                            ActionPill(
                                stringResource(R.string.accounts_link), { model.linkMeta() },
                                quiet = true, modifier = Modifier.padding(start = 12.dp), enabled = ready && busy == null,
                            )
                        }
                    }
                },
                {
                    RowFrame(
                        stringResource(R.string.accounts_whatsapp),
                        onClick = null,
                        sub = when {
                            busy == AccountRow.WHATSAPP -> stringResource(R.string.accounts_scan)
                            else -> whatsappResult?.let { outcomeWords(it) }
                        },
                        locked = !ready,
                        busy = busy == AccountRow.WHATSAPP,
                    ) {
                        ActionPill(
                            stringResource(R.string.accounts_link), { model.linkWhatsApp() },
                            quiet = true, modifier = Modifier.padding(start = 12.dp), enabled = ready && busy == null,
                        )
                    }
                },
                { LinkedRow(model, AccountRow.FACEBOOK, LinkedAccountType.FACEBOOK, R.string.accounts_facebook, ready, busy, linked, accounts) },
                { LinkedRow(model, AccountRow.INSTAGRAM, LinkedAccountType.INSTAGRAM, R.string.accounts_instagram, ready, busy, linked, accounts) },
            )
        }
    }
}

@Composable
private fun LinkedRow(
    model: AccountsModel,
    row: AccountRow,
    type: LinkedAccountType,
    label: Int,
    ready: Boolean,
    busy: AccountRow?,
    results: Map<LinkedAccountType, LinkOutcome>,
    accounts: Map<LinkedAccountType, LinkedAccountRef>?,
) {
    val account = accounts?.get(type)
    RowFrame(
        stringResource(label),
        onClick = null,
        sub = results[type]?.let { outcomeWords(it) } ?: when {
            accounts == null -> null
            account == null -> stringResource(R.string.accounts_not_linked)
            account.forGlasses -> stringResource(R.string.accounts_linked_as, account.name)
            else -> stringResource(R.string.accounts_off_for_glasses, account.name)
        },
        locked = !ready,
        busy = busy == row,
    ) {
        ActionPill(
            stringResource(R.string.accounts_link), { model.linkProvider(row, type) },
            quiet = true, modifier = Modifier.padding(start = 12.dp), enabled = ready && busy == null,
        )
    }
}

@Composable
private fun outcomeWords(outcome: LinkOutcome): String = stringResource(
    when (outcome) {
        LinkOutcome.LINKED -> R.string.accounts_done
        LinkOutcome.LOGGED_OUT -> R.string.accounts_logged_out
        LinkOutcome.UNREACHABLE -> R.string.accounts_unreachable
        LinkOutcome.NO_ANSWER -> R.string.accounts_no_answer
        LinkOutcome.REFUSED -> R.string.accounts_refused
        LinkOutcome.NOT_LINKED -> R.string.accounts_not_linked
    },
)
