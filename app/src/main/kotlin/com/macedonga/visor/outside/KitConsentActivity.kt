package com.macedonga.visor.outside

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.design.VisorTheme
import com.macedonga.visor.kit.protocol.KitProtocol
import com.macedonga.visor.kit.protocol.Scope
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.SheetAnswers
import com.macedonga.visor.settings.SheetBody
import com.macedonga.visor.settings.SheetTitle
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.WidePill
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The VDK's consent screen, over the app that asked: the scopes it wants and does not hold yet, Allow, Only this time
 * (until the app's last connection to Visor closes, as DAT's Allow once) or Don't allow.
 * The result is every scope the app holds afterwards, also when the person just goes back.
 */
@AndroidEntryPoint
class KitConsentActivity : ComponentActivity() {
    @Inject lateinit var grants: KitGrants

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only an app that started this for a result is named; Visor must see it to tie the grant to its certificate.
        val app = callingPackage?.takeIf { grants.certificateOf(it) != null }
        if (app == null) {
            setResult(RESULT_CANCELED)
            return finish()
        }
        answer(app)
        // A scope this Visor does not know cannot be granted: the result shows the app does not hold it.
        val asked = intent.getStringArrayExtra(KitProtocol.EXTRA_SCOPES).orEmpty().map(::Scope).filter { it in KNOWN }
        val missing = asked.toSet() - grants.scopesOf(app)
        if (missing.isEmpty()) return finish()

        enableEdgeToEdge()
        val label = grants.labelOf(app)
        setContent {
            VisorTheme {
                VisorSheet(onDismiss = ::finish) {
                    SheetTitle(stringResource(R.string.kit_consent_title, label))
                    SheetBody {
                        for (scope in KNOWN.filter { it in missing }) {
                            Text(
                                stringResource(SCOPE_TEXT.getValue(scope)),
                                style = Visor.type.body,
                                color = Visor.colors.textPrimary,
                                modifier = Modifier.padding(horizontal = SIDE).padding(bottom = 12.dp),
                            )
                        }
                    }
                    WidePill(
                        stringResource(R.string.kit_consent_once),
                        {
                            grants.allowOnce(app, missing)
                            answer(app)
                            finish()
                        },
                        Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 8.dp),
                        quiet = true,
                    )
                    SheetAnswers(
                        stringResource(R.string.kit_consent_allow),
                        {
                            grants.set(app, grants.scopesOf(app) + missing)
                            answer(app)
                            finish()
                        },
                        stringResource(R.string.kit_consent_deny),
                        ::finish,
                    )
                }
            }
        }
    }

    private fun answer(app: String) {
        val held = grants.scopesOf(app).map { it.id }.toTypedArray()
        setResult(RESULT_OK, Intent().putExtra(KitProtocol.EXTRA_SCOPES, held))
    }

    companion object {
        /** The scopes this Visor grants, in the order the screen lists them. */
        val KNOWN = listOf(Scope.STATE, Scope.PAIRS, Scope.INPUTS, Scope.MOTION, Scope.MICROPHONE, Scope.CAMERA, Scope.BAND, Scope.DISPLAY, Scope.VOICE, Scope.ASSISTANT, Scope.NOTIFY, Scope.SETTINGS, Scope.CONVERSATIONS, Scope.RAW)

        val SCOPE_NAME = mapOf(
            Scope.STATE to R.string.kit_scope_name_state,
            Scope.PAIRS to R.string.kit_scope_name_pairs,
            Scope.INPUTS to R.string.kit_scope_name_inputs,
            Scope.MOTION to R.string.kit_scope_name_motion,
            Scope.MICROPHONE to R.string.kit_scope_name_microphone,
            Scope.CAMERA to R.string.kit_scope_name_camera,
            Scope.BAND to R.string.kit_scope_name_band,
            Scope.DISPLAY to R.string.kit_scope_name_display,
            Scope.VOICE to R.string.kit_scope_name_voice,
            Scope.ASSISTANT to R.string.kit_scope_name_assistant,
            Scope.NOTIFY to R.string.kit_scope_name_notify,
            Scope.SETTINGS to R.string.kit_scope_name_settings,
            Scope.CONVERSATIONS to R.string.kit_scope_name_conversations,
            Scope.RAW to R.string.kit_scope_name_raw,
        )

        val SCOPE_TEXT = mapOf(
            Scope.STATE to R.string.kit_scope_state,
            Scope.PAIRS to R.string.kit_scope_pairs,
            Scope.INPUTS to R.string.kit_scope_inputs,
            Scope.MOTION to R.string.kit_scope_motion,
            Scope.MICROPHONE to R.string.kit_scope_microphone,
            Scope.CAMERA to R.string.kit_scope_camera,
            Scope.BAND to R.string.kit_scope_band,
            Scope.DISPLAY to R.string.kit_scope_display,
            Scope.VOICE to R.string.kit_scope_voice,
            Scope.ASSISTANT to R.string.kit_scope_assistant,
            Scope.NOTIFY to R.string.kit_scope_notify,
            Scope.SETTINGS to R.string.kit_scope_settings,
            Scope.CONVERSATIONS to R.string.kit_scope_conversations,
            Scope.RAW to R.string.kit_scope_raw,
        )
    }
}
