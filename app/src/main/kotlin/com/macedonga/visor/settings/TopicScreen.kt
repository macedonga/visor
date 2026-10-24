package com.macedonga.visor.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.NotifyAppsKey
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.settings.offeredOn
import com.macedonga.visor.features.featureSection
import com.macedonga.visor.gallery.AutoImport
import com.macedonga.visor.gallery.allows
import com.macedonga.visor.gallery.detail
import com.macedonga.visor.gallery.placesAllowed
import com.macedonga.visor.gallery.title

/** How a topic's page breaks its settings up: a heading (or none) and the keys under it. */
private fun sectionsOf(topic: Topic): List<Pair<Int?, List<String>>> = when (topic) {
    Topic.NOTIFICATIONS -> listOf(
        R.string.section_on_glasses to listOf("sg_do_not_disturb", "attention_deferral_user_enabled", "notification_led_brightness", "smart_reply_enabled"),
        R.string.section_calls to listOf(
            "user_setup_ica_toggle_enabled", "enable_calling_with_meta_ai", "mute_announcements_default_duration",
            "enable_phone_to_wearable_contacts_sync", "enable_phone_to_wearable_call_log_sync",
        ),
        R.string.section_calendar to listOf("enable_calendar_event_alert", "enable_calendar_event_alert_announce_details"),
    )
    Topic.CONTROLS -> listOf(
        R.string.section_capture_button to listOf(
            "capture_mode", "assignable_action_button_short_press_mapping", "assignable_action_button_long_press_mapping",
            "action_button_assistant_custom_prompt",
        ),
        R.string.section_touchpad to listOf(
            "captouch_enabled", "tap_and_hold_mapping", "quick_actions", "sg_captouch_invert_swipe_direction_horizontal",
            "sg_captouch_invert_swipe_direction_vertical", "wake_handedness", "spotify_tap_enabled",
        ),
    )
    Topic.CAMERA -> listOf(
        R.string.section_video to listOf(
            "resolution_and_framerate", "eis_mode", "video_duration_ms", "capture_aspect_ratio", "motion_blur_reduction_enabled",
        ),
        R.string.section_picture to listOf(
            "quick_clips_photo_capture_enabled", "media_auto_leveling_enabled", "media_occlusion_removal_enabled", "occlusion_detection_enabled", "capture_auto_review",
        ),
        R.string.section_audio to listOf("audio_playback_during_capture_enabled", "music_concurrency_enabled", "audio_noise_reduction_enabled"),
    )
    Topic.PRIVACY -> listOf(
        R.string.section_location to listOf("location_global_enabled", "location_chatbot_enabled"),
        R.string.section_camera to listOf("passive_visual_capture_enabled"),
        R.string.section_data to listOf(
            "assistant_store_voice_recordings", "assistant_store_voice_transcripts", "cloud_media_processing_enabled",
            "media_cloud_enabled", "device_share_additional_data_enabled",
        ),
        R.string.section_phone to listOf("passive_phone_unlock_enabled", "verified_session_enabled"),
    )
    Topic.PHOTOS -> listOf(R.string.section_on_glasses to topic.keys)
    else -> listOf(null to topic.keys)
}

private fun ledeOf(topic: Topic): Int? = when (topic) {
    Topic.NOTIFICATIONS -> R.string.topic_notifications_lede
    Topic.CONTROLS -> R.string.topic_controls_lede
    Topic.WEAR -> R.string.topic_wear_lede
    Topic.PRIVACY -> R.string.topic_privacy_lede
    Topic.PHOTOS -> R.string.topic_photos_lede
    else -> null
}

/** One topic: Visor's own part of it first where it has one, then the glasses' settings by section. */
@Composable
fun TopicScreen(name: String, open: (NavKey) -> Unit, model: SettingsViewModel = hiltViewModel()) {
    val topic = Topic.valueOf(name)
    val known = model.settings.specs.keys
    val product = model.settings.state.collectAsStateWithLifecycle().value.product
    SettingsPage(stringResource(topic.title), ledeOf(topic)?.let { stringResource(it) }) {
        when (topic) {
            Topic.NOTIFICATIONS -> item(key = "visor") {
                val on by model.notifyChoice.enabled.collectAsStateWithLifecycle()
                val muted by model.apps.muted.collectAsStateWithLifecycle()
                Rows(
                    { VisorToggleRow(model.toggles.byId("notifications")!!, sub = stringResource(R.string.settings_notifications_sub)) },
                    if (on) row {
                        ValueRow(
                            stringResource(R.string.notify_apps_title),
                            if (muted.isEmpty()) stringResource(R.string.sum_notify_all) else stringResource(R.string.sum_notify_muted, muted.size),
                            onClick = { open(NotifyAppsKey) },
                        )
                    } else null,
                )
            }
            Topic.PHOTOS -> item(key = "visor") { PhotosPart(model) }
            Topic.BAND -> item(key = "visor") { BandPart(model) }
            else -> Unit
        }
        // Hearing is nothing but features, so they need no heading of their own there.
        featureSection(topic, open, heading = topic != Topic.HEARING)
        for ((i, section) in sectionsOf(topic).withIndex()) {
            // Rows the Meta app keeps for other glasses are left to Advanced.
            val keys = section.second.filter { it in known && specOf(model.settings, it).offeredOn(product) }
            if (keys.isEmpty()) continue
            item(key = "section-$i") {
                Column {
                    val heading = section.first
                    if (heading != null) SectionLabel(stringResource(heading)) else if (topic == Topic.NOTIFICATIONS || topic == Topic.PHOTOS) Box(Modifier.padding(top = 20.dp))
                    Rows(*keys.map { key -> @Composable { GlassesRow(key) } }.toTypedArray())
                }
            }
        }
        item(key = "foot") {
            val host = LocalHost.current
            if (!host.ready) Footnote(host.state.readAt?.let { stringResource(R.string.settings_as_read, timeWords(it)) } ?: stringResource(R.string.settings_connect_to_change))
        }
    }
}

/** How photos come over (three cards, the chosen one raised) and whether the phone notes where they were taken. */
@Composable
private fun PhotosPart(model: SettingsViewModel) {
    val context = LocalContext.current
    val mode by model.importChoice.mode.collectAsStateWithLifecycle()
    var places by remember { mutableStateOf(placesAllowed(context)) }
    LifecycleResumeEffect(Unit) {
        places = placesAllowed(context)
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { places = placesAllowed(context) }
    Column {
        SectionLabel(stringResource(R.string.import_title), Modifier.padding(top = 0.dp))
        OptionCards(AutoImport.entries.map { Triple(it, stringResource(it.title), stringResource(it.detail)) }, mode, model.importChoice::set)
        Footnote(stringResource(R.string.import_note))
        SectionLabel(stringResource(R.string.section_places))
        Rows({
            RowFrame(stringResource(R.string.places_title), onClick = null, sub = stringResource(R.string.places_sub)) {
                if (places) {
                    Text(stringResource(R.string.access_allowed), style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(start = 12.dp))
                } else {
                    ActionPill(stringResource(R.string.access_allow), {
                        ask.launch(
                            if (context.allows(Manifest.permission.ACCESS_FINE_LOCATION)) arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    }, modifier = Modifier.padding(start = 12.dp))
                }
            }
        })
    }
}

/** A choice where each option needs a line of its own: one row each on a card, the chosen one checked. */
@Composable
fun <T> OptionCards(options: List<Triple<T, String, String>>, selected: T, onPick: (T) -> Unit) {
    val host = LocalHost.current
    Card {
        for ((i, option) in options.withIndex()) {
            val (value, title, detail) = option
            val on = value == selected
            if (i > 0) Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics { this.selected = on }
                    .clickable {
                        if (!on) {
                            host.haptics.toggle()
                            onPick(value)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = Visor.type.body, color = Visor.colors.textPrimary)
                    Text(detail, style = Visor.type.label, color = Visor.colors.textSecondary)
                }
                ChoiceMark(on, Modifier.padding(start = 12.dp))
            }
        }
    }
}
