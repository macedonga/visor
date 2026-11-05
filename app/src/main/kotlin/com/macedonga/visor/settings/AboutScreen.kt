package com.macedonga.visor.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.R
import com.macedonga.visor.update.NEW_DISCUSSION_URL
import com.macedonga.visor.update.NEW_ISSUE_URL
import com.macedonga.visor.update.REPO_URL
import com.macedonga.visor.update.openLink

/** What this is: Visor's version, the glasses' firmware, and the work it stands on. */
@Composable
fun AboutScreen(model: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val newer by model.updates.newer.collectAsStateWithLifecycle()
    val looking by model.updates.enabled.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.about_title), light = 0.35f) {
        item(key = "versions") {
            Rows(
                { ValueRow("Visor", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", onClick = null, sub = if (BuildConfig.DEBUG) stringResource(R.string.about_debug) else null) },
                newer?.let { release ->
                    {
                        RowFrame(stringResource(R.string.update_ready, release.version), onClick = null) {
                            ActionPill(
                                stringResource(R.string.language_download), { openLink(context, release.url) },
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                },
                { ValueRow(stringResource(R.string.glasses_firmware), glasses.info?.build?.let(::firmwareWords) ?: stringResource(R.string.settings_unknown), onClick = null) },
            )
        }
        if (BuildConfig.TAGGED) {
            item(key = "updates") {
                Column(Modifier.padding(top = 14.dp)) {
                    Rows({ ToggleRow(stringResource(R.string.update_look), looking, model.updates::setEnabled) })
                }
            }
        }
        item(key = "github") {
            Column {
                SectionLabel("GitHub")
                Rows(
                    { ValueRow(stringResource(R.string.about_report), null, onClick = { openLink(context, NEW_ISSUE_URL) }) },
                    { ValueRow(stringResource(R.string.about_discuss), null, onClick = { openLink(context, NEW_DISCUSSION_URL) }) },
                    { ValueRow(stringResource(R.string.about_star), null, onClick = { openLink(context, REPO_URL) }) },
                )
            }
        }
        item(key = "credits") {
            Column {
                SectionLabel(stringResource(R.string.about_credits))
                Rows(
                    *CREDITS.map { (name, licence) -> @Composable { ValueRow(name, licence, onClick = null) } }.toTypedArray(),
                )
            }
        }
    }
}

private val CREDITS = listOf(
    "Inter" to "SIL OFL 1.1",
    "Jetpack Compose, AndroidX" to "Apache 2.0",
    "Filament" to "Apache 2.0",
    "Kotlin, kotlinx" to "Apache 2.0",
    "Dagger Hilt" to "Apache 2.0",
    "FlatBuffers" to "Apache 2.0",
    "OkHttp, Okio" to "Apache 2.0",
    "Guava" to "Apache 2.0",
    "zstd-jni" to "BSD 2-Clause",
    "Bouncy Castle" to "MIT",
)
