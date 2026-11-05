package com.macedonga.visor.settings

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.Telephony
import android.service.media.MediaBrowserService
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.settings.Control
import com.macedonga.visor.core.settings.localeValue
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ROLE_SMS = "sms"

/** What a setting the phone fills can take: the phone's locales, or the apps that can fill [Control.App.role]. */
@Composable
internal fun phoneOptions(c: Control): List<Control.Option> {
    val context = LocalContext.current
    val res = LocalResources.current
    val role = (c as? Control.App)?.role
    val found by produceState(emptyList<Control.Option>(), role) {
        value = withContext(Dispatchers.IO) { if (role == null) localeOptions(res) else installedApps(context, res, role) }
    }
    return (c as? Control.App)?.extra.orEmpty() + found
}

/** The locales the phone knows, its own first. */
private fun localeOptions(res: Resources): List<Control.Option> {
    val phone = Locale.getDefault()
    val own = localeValue(phone)
    return listOf(Control.Option(own, res.getString(R.string.adv_this_phone, phone.displayName))) +
        Locale.getAvailableLocales()
            .filter { it.language.isNotEmpty() && it.country.isNotEmpty() && it.variant.isEmpty() && it.script.isEmpty() }
            .distinctBy { localeValue(it) }
            .filter { localeValue(it) != own }
            .sortedBy { it.displayName }
            .map { Control.Option(localeValue(it), it.displayName) }
}

/** Apps on this phone that can fill [role], the default SMS app first. */
private fun installedApps(context: Context, res: Resources, role: String): List<Control.Option> {
    val pm = context.packageManager
    val infos = when (role) {
        ROLE_SMS -> pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")), 0).map { it.activityInfo.applicationInfo }
        else -> (
            pm.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0).map { it.serviceInfo.applicationInfo } +
                pm.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), 0).map { it.activityInfo.applicationInfo }
            )
    }
    val default = if (role == ROLE_SMS) Telephony.Sms.getDefaultSmsPackage(context) else null
    return infos.distinctBy { it.packageName }
        .map { info ->
            val name = pm.getApplicationLabel(info).toString()
            Control.Option(info.packageName, if (info.packageName == default) res.getString(R.string.adv_default_app, name) else name)
        }
        .sortedWith(compareBy({ it.value != default }, { it.label.lowercase() }))
}

@Composable
internal fun PhoneListControl(c: Control.PhoneList, value: String?, enabled: Boolean, write: (String) -> Unit) {
    val saved = remember(value) { value.orEmpty().split(c.separator).map { it.trim() }.filter { it.isNotEmpty() } }
    var adding by remember { mutableStateOf(false) }
    saved.forEachIndexed { i, n ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n, style = Visor.type.body, color = Visor.colors.textPrimary, modifier = Modifier.weight(1f))
            ActionPill(stringResource(R.string.adv_remove), { write(saved.filterIndexed { j, _ -> j != i }.joinToString(c.separator)) }, quiet = true, enabled = enabled)
        }
    }
    ActionPill(stringResource(R.string.adv_add_number), { adding = true }, Modifier.padding(top = 8.dp), quiet = true, enabled = enabled)
    if (adding) {
        TextSheet(
            stringResource(R.string.adv_add_number), "",
            onSave = { write((saved + it.trim()).joinToString(c.separator)) },
            onDismiss = { adding = false },
            keyboard = KeyboardType.Phone,
            filter = { t -> t.filter { ch -> ch.isDigit() || ch in "+ " } },
            valid = { it.isNotBlank() },
        )
    }
}
