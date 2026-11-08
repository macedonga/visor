package com.macedonga.visor.dat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.design.VisorTheme
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.SheetAnswers
import com.macedonga.visor.settings.SheetTitle
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.WidePill
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * The toolkit setup's screen: the once-only question when an app first needs the glasses' motion or display, then
 * each step as it runs, the toolkit app's download in percent, and how it ended.
 */
@AndroidEntryPoint
class ToolkitActivity : ComponentActivity() {
    @Inject lateinit var toolkit: Toolkit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VisorTheme {
                val progress by toolkit.progress.collectAsState()
                if (progress is ToolkitProgress.Ready) {
                    LaunchedEffect(Unit) {
                        delay(DONE_SHOWN_MS)
                        close()
                    }
                }
                VisorSheet(onDismiss = ::close) {
                    when (val p = progress) {
                        is ToolkitProgress.Asking -> {
                            SheetTitle(stringResource(R.string.toolkit_title))
                            Line(stringResource(R.string.toolkit_body, p.app))
                            SheetAnswers(stringResource(R.string.toolkit_allow), { toolkit.answer(true) }, stringResource(R.string.toolkit_deny), ::close)
                        }
                        is ToolkitProgress.Running -> {
                            SheetTitle(stringResource(R.string.toolkit_notification))
                            Steps(p.step, p.fraction)
                        }
                        is ToolkitProgress.Ready -> {
                            SheetTitle(stringResource(R.string.toolkit_ready))
                            Steps(null, null)
                        }
                        is ToolkitProgress.Failed -> {
                            SheetTitle(stringResource(R.string.toolkit_failed))
                            Line(p.reason)
                            WidePill(stringResource(R.string.toolkit_close), ::close, Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 8.dp))
                        }
                        ToolkitProgress.Idle -> LaunchedEffect(Unit) { close() }
                    }
                }
            }
        }
    }

    // Going away while asked is a no; going away while the setup runs leaves it running, in its notification.
    private fun close() {
        if (toolkit.progress.value is ToolkitProgress.Asking) toolkit.answer(false)
        toolkit.dismiss()
        finish()
    }

    @Composable
    private fun Line(text: String) {
        Text(text, style = Visor.type.body, color = Visor.colors.textPrimary, modifier = Modifier.padding(horizontal = SIDE).padding(bottom = 12.dp))
    }

    /** Every step: done before [current], running at it (with the download's share), waiting after; all done when null. */
    @Composable
    private fun Steps(current: ToolkitStep?, fraction: Float?) {
        for (step in ToolkitStep.entries) {
            val state = when {
                current == null || step.ordinal < current.ordinal -> stringResource(R.string.toolkit_step_done)
                step == current -> fraction?.let { "${(it * 100).toInt()}%" } ?: stringResource(R.string.toolkit_step_running)
                else -> ""
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(bottom = 10.dp)) {
                Text(stringResource(Toolkit.stepText(step)), style = Visor.type.body, color = if (step == current) Visor.colors.textPrimary else Visor.colors.textSecondary, modifier = Modifier.weight(1f))
                Text(state, style = Visor.type.body, color = Visor.colors.textSecondary)
            }
        }
    }

    private companion object {
        const val DONE_SHOWN_MS = 1_500L
    }
}
