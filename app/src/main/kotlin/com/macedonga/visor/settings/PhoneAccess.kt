package com.macedonga.visor.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.media.wifiDirectAllowed
import com.macedonga.visor.core.media.wifiDirectPermissions
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.gallery.allows
import com.macedonga.visor.notify.MirrorListener
import com.macedonga.visor.phone.associateCompanion
import com.macedonga.visor.phone.companionAssociated
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Something Visor asks of the phone, why, and how to grant it. */
enum class Need(val title: Int, val why: Int) {
    NEARBY(R.string.access_nearby, R.string.access_nearby_why),
    LISTENER(R.string.access_listener, R.string.access_listener_why),
    NOTIFY(R.string.access_notify, R.string.access_notify_why),
    MIC(R.string.access_mic, R.string.access_mic_why),
    CONTACTS(R.string.access_contacts, R.string.access_contacts_why),
    SMS(R.string.access_sms, R.string.access_sms_why),
    CALLS(R.string.access_calls, R.string.access_calls_why),
    CALL_CONTROL(R.string.access_call_control, R.string.access_call_control_why),
    LOCATION(R.string.access_location, R.string.access_location_why),

    /** Location while Visor is in the background: Android asks it on its own, once [LOCATION] is given. */
    LOCATION_ALWAYS(R.string.access_location_always, R.string.access_location_always_why),
    WIFI(R.string.access_wifi, R.string.access_wifi_why),

    /** The glasses as Visor's companion device, granted in Android's own dialog. */
    COMPANION(R.string.access_companion, R.string.access_companion_why),
    ;

    fun granted(context: Context): Boolean = when (this) {
        NEARBY -> context.allows(Manifest.permission.BLUETOOTH_CONNECT) && context.allows(Manifest.permission.BLUETOOTH_SCAN)
        LISTENER -> context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        NOTIFY -> context.allows(Manifest.permission.POST_NOTIFICATIONS)
        MIC -> context.allows(Manifest.permission.RECORD_AUDIO)
        CONTACTS -> context.allows(Manifest.permission.READ_CONTACTS)
        SMS -> context.allows(Manifest.permission.READ_SMS) && context.allows(Manifest.permission.SEND_SMS)
        CALLS -> context.allows(Manifest.permission.READ_CALL_LOG)
        CALL_CONTROL -> context.allows(Manifest.permission.CALL_PHONE) && context.allows(Manifest.permission.ANSWER_PHONE_CALLS) &&
            context.allows(Manifest.permission.READ_PHONE_STATE)
        LOCATION -> context.allows(Manifest.permission.ACCESS_FINE_LOCATION)
        LOCATION_ALWAYS -> context.allows(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        WIFI -> wifiDirectAllowed(context)
        COMPANION -> !context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) || companionAssociated(context)
    }

    /** The runtime permissions to ask for next, or empty when only a system page can grant it. */
    fun ask(context: Context): Array<String> = when (this) {
        NEARBY -> arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        LISTENER -> emptyArray()
        NOTIFY -> arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        MIC -> arrayOf(Manifest.permission.RECORD_AUDIO)
        CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
        SMS -> arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
        CALLS -> arrayOf(Manifest.permission.READ_CALL_LOG)
        CALL_CONTROL -> arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE)
        LOCATION -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        // Android grants it only after the foreground one, so that one comes first when it is missing.
        LOCATION_ALWAYS -> if (context.allows(Manifest.permission.ACCESS_FINE_LOCATION)) arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else LOCATION.ask(context)
        // On Android 17 Wi-Fi Direct also wants the local network.
        WIFI -> wifiDirectPermissions
        COMPANION -> emptyArray()
    }
}

fun missingNeeds(context: Context): List<Need> = Need.entries.filter { !it.granted(context) }

@HiltViewModel
class PhoneAccessModel @Inject constructor(private val connection: GlassesConnection) : ViewModel() {
    /** The glasses' Bluetooth address, once they are claimed. */
    fun address(): String? = (connection.state.value.target?.link as? GlassesLink.Bluetooth)?.address
}

/** Everything Visor asks of the phone, in one place: what is given, why each is wanted, one tap for the rest. */
@Composable
fun PhoneAccessScreen(model: PhoneAccessModel = hiltViewModel()) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf<Need?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Refused for good, the system asks no more: its own page is the way left.
        val need = asked
        if (need != null && !need.granted(context) && need.ask(context).isNotEmpty() && it.values.none { ok -> ok }) openAppPage(context)
        tick++
    }
    val companion = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { tick++ }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }

    SettingsPage(stringResource(R.string.topic_phone), stringResource(R.string.access_lede), light = 0.35f) {
        item(key = "needs") {
            Rows(
                *Need.entries.map { need ->
                    @Composable {
                        val granted = remember(tick) { need.granted(context) }
                        RowFrame(stringResource(need.title), onClick = null, sub = stringResource(need.why)) {
                            if (granted) {
                                Text(stringResource(R.string.access_allowed), style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(start = 12.dp))
                            } else {
                                ActionPill(stringResource(R.string.access_allow), {
                                    asked = need
                                    val ask = need.ask(context)
                                    when {
                                        need == Need.COMPANION -> associateCompanion(
                                            context, model.address(), { companion.launch(IntentSenderRequest.Builder(it).build()) }, { tick++ },
                                        )
                                        ask.isEmpty() -> openListenerPage(context)
                                        else -> launcher.launch(ask)
                                    }
                                }, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }.toTypedArray(),
            )
        }
        item(key = "foot") { Footnote(stringResource(R.string.access_foot)) }
    }
}

internal fun openAppPage(context: Context, packageName: String = context.packageName) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal fun openListenerPage(context: Context) {
    val component = ComponentName(context, MirrorListener::class.java).flattenToString()
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
