package com.macedonga.visor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.design.VisorTheme
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.phone.askCompanionOnce
import com.macedonga.visor.phone.observeCompanions
import com.macedonga.visor.update.Updates
import com.macedonga.visor.update.downloadIntent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** The only activity. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var updates: Updates
    @Inject lateinit var connection: GlassesConnection
    @Inject lateinit var log: EventLog

    private val companion = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }

    /** Places to open, from a notification: each is taken once. */
    private val opens = Channel<String>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val open = if (savedInstanceState == null) intent?.getStringExtra(EXTRA_OPEN) else null
        open?.let(::take)
        // The mark opens the app where the system splash leaves it, unless the launch goes straight somewhere else.
        val mark = savedInstanceState == null && (open == null || open == OPEN_ONBOARDING)
        setContent {
            VisorTheme { VisorNav(opens.receiveAsFlow(), mark) }
        }
        // Connected glasses, with Visor in front: the moment to ask for them as companion device.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                connection.state.map { s -> (s.target?.link as? GlassesLink.Bluetooth)?.address?.takeIf { s.link == LinkState.READY } }
                    .distinctUntilChanged().filterNotNull()
                    .collect { address -> askCompanionOnce(this@MainActivity, address) { companion.launch(IntentSenderRequest.Builder(it).build()) } }
            }
        }
    }

    // Also after the system's association dialog, which returns here.
    override fun onResume() {
        super.onResume()
        observeCompanions(this, log)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let(::take)
    }

    // The update notification comes here, not to the browser: a PendingIntent stays explicit.
    private fun take(open: String) {
        if (open == OPEN_UPDATE) updates.newer.value?.let { runCatching { startActivity(downloadIntent(it)) } } else opens.trySend(open)
    }

    companion object {
        const val OPEN_UPDATE = "update"
        const val EXTRA_OPEN = "open"
        const val OPEN_GALLERY = "gallery"
        const val OPEN_LIVE = "live"
        const val OPEN_META_AI = "metaai"
        const val OPEN_ONBOARDING = "onboarding"
        const val OPEN_ACCOUNTS = "accounts"
    }
}
