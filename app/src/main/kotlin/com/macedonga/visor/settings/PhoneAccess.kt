package com.macedonga.visor.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import com.macedonga.visor.notify.MirrorListener
import com.macedonga.visor.phone.SpotifySearch
import com.macedonga.visor.phone.associateCompanion
import com.macedonga.visor.phone.companionAssociated
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.allows
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
    CALENDAR(R.string.access_calendar, R.string.access_calendar_why),
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
        NOTIFY -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.allows(Manifest.permission.POST_NOTIFICATIONS)
        MIC -> context.allows(Manifest.permission.RECORD_AUDIO)
        CONTACTS -> context.allows(Manifest.permission.READ_CONTACTS)
        SMS -> context.allows(Manifest.permission.READ_SMS) && context.allows(Manifest.permission.SEND_SMS)
        CALLS -> context.allows(Manifest.permission.READ_CALL_LOG)
        CALL_CONTROL -> context.allows(Manifest.permission.CALL_PHONE) && context.allows(Manifest.permission.ANSWER_PHONE_CALLS) &&
            context.allows(Manifest.permission.READ_PHONE_STATE)
        LOCATION -> context.allows(Manifest.permission.ACCESS_FINE_LOCATION)
        CALENDAR -> context.allows(Manifest.permission.READ_CALENDAR)
        LOCATION_ALWAYS -> context.allows(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        WIFI -> wifiDirectAllowed(context)
        COMPANION -> !context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) || companionAssociated(context)
    }

    /** Android withholds it from an app installed from a file until App info allows restricted settings (#88). */
    fun restricted(context: Context): Boolean = when (this) {
        SMS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        LISTENER -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        else -> false
    } && installedFromFile(context)

    /** The runtime permissions to ask for next, or empty when only a system page can grant it. */
    fun ask(context: Context): Array<String> = when (this) {
        NEARBY -> arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        LISTENER -> emptyArray()
        NOTIFY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        MIC -> arrayOf(Manifest.permission.RECORD_AUDIO)
        CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
        SMS -> arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
        CALLS -> arrayOf(Manifest.permission.READ_CALL_LOG)
        CALL_CONTROL -> arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE)
        LOCATION -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        CALENDAR -> arrayOf(Manifest.permission.READ_CALENDAR)
        // Android grants it only after the foreground one, so that one comes first when it is missing.
        LOCATION_ALWAYS -> if (context.allows(Manifest.permission.ACCESS_FINE_LOCATION)) arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else LOCATION.ask(context)
        // On Android 17 Wi-Fi Direct also wants the local network.
        WIFI -> wifiDirectPermissions
        COMPANION -> emptyArray()
    }
}

fun missingNeeds(context: Context): List<Need> = Need.entries.filter { !it.granted(context) }

// A browser or a file manager installed it, not a store or adb.
private fun installedFromFile(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }.getOrNull()
        .let { it == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE || it == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE }

@HiltViewModel
class PhoneAccessModel @Inject constructor(private val connection: GlassesConnection, val spotify: SpotifySearch) : ViewModel() {
    /** The glasses' Bluetooth address, once they are claimed. */
    fun address(): String? = (connection.state.value.target?.link as? GlassesLink.Bluetooth)?.address
}

/** Everything Visor asks of the phone, in one place: what is given, why each is wanted, one tap for the rest. */
@Composable
fun PhoneAccessScreen(model: PhoneAccessModel = hiltViewModel()) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var tick by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf<Need?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Refused for good: only Android's own page can grant it now.
        val need = asked
        if (need != null && !need.granted(context) && need.ask(context).isNotEmpty() && it.values.none { ok -> ok } &&
            it.keys.all { p -> activity?.shouldShowRequestPermissionRationale(p) == false }
        ) openAppPage(context)
        tick++
    }
    val companion = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { tick++ }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }

    SettingsPage(stringResource(R.string.topic_phone), light = 0.35f) {
        item(key = "needs") {
            Rows(
                *Need.entries.map { need ->
                    @Composable {
                        val granted = remember(tick) { need.granted(context) }
                        val restricted = remember(tick) { !granted && need.restricted(context) }
                        val why = stringResource(need.why)
                        val steps = stringResource(R.string.access_restricted)
                        RowFrame(stringResource(need.title), onClick = null, sub = if (granted) null else if (restricted) "$why\n$steps" else why) {
                            if (granted) {
                                Icon(VisorIcons.Check, stringResource(R.string.access_allowed), Modifier.padding(start = 12.dp).size(20.dp), tint = Visor.colors.textSecondary)
                            } else {
                                ActionPill(stringResource(R.string.allow), {
                                    asked = need
                                    val ask = need.ask(context)
                                    when {
                                        need == Need.COMPANION -> associateCompanion(
                                            context, model.address(), { companion.launch(IntentSenderRequest.Builder(it).build()) }, { tick++ },
                                        )
                                        need == Need.LISTENER -> openListenerPage(context)
                                        else -> launcher.launch(ask)
                                    }
                                }, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }.toTypedArray(),
            )
        }
        item(key = "spotify") {
            val id = remember(sheet) { model.spotify.clientId }
            val secret = remember(sheet) { model.spotify.clientSecret }
            Column {
                SectionLabel(stringResource(R.string.spotify_title))
                Rows(
                    { ValueRow(stringResource(R.string.spotify_client_id), id.ifEmpty { null }, onClick = { sheet = "id" }) },
                    { ValueRow(stringResource(R.string.spotify_client_secret), if (secret.isEmpty()) null else "••••••", onClick = { sheet = "secret" }) },
                )
            }
        }
    }

    when (sheet) {
        "id" -> TextSheet(
            stringResource(R.string.spotify_client_id), model.spotify.clientId,
            onSave = { model.spotify.clientId = it }, onDismiss = { sheet = null },
        )
        "secret" -> TextSheet(
            stringResource(R.string.spotify_client_secret), model.spotify.clientSecret,
            onSave = { model.spotify.clientSecret = it }, onDismiss = { sheet = null },
            secret = true, keyboard = KeyboardType.Password,
        )
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
