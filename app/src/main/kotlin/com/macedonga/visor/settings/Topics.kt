package com.macedonga.visor.settings

import androidx.compose.ui.graphics.vector.ImageVector
import com.macedonga.visor.R
import com.macedonga.visor.core.settings.productId
import com.macedonga.visor.features.Feature
import com.macedonga.visor.ui.VisorIcons

/** The curated settings, by what they are about. [keys] are the glasses' own, in the order a page shows them. */
enum class Topic(val title: Int, val icon: ImageVector, val keys: List<String> = emptyList()) {
    META_AI(
        R.string.topic_meta_ai, VisorIcons.Voice,
        listOf(
            "assistant_enabled", "wakeword_enabled", "wakeword_sound_enabled", "assistant_follow_up_enabled",
            "natural_invocation_enabled", "live_ai_enabled", "detailed_responses_live_ai_enabled", "live_ai_voice_only",
            "blv_scene_reader_enabled",
        ),
    ),
    LANGUAGE(R.string.topic_language, VisorIcons.Globe, listOf("tts_speak_speed")),
    NOTIFICATIONS(
        R.string.topic_notifications, VisorIcons.Bell,
        listOf(
            "sg_do_not_disturb", "attention_deferral_user_enabled", "notification_led_brightness", "smart_reply_enabled",
            "user_setup_ica_toggle_enabled", "enable_calling_with_meta_ai", "mute_announcements_default_duration",
            "enable_phone_to_wearable_contacts_sync", "enable_phone_to_wearable_call_log_sync",
            "enable_calendar_event_alert", "enable_calendar_event_alert_announce_details",
        ),
    ),
    CONTROLS(
        R.string.topic_controls, VisorIcons.Touch,
        listOf(
            "capture_mode", "assignable_action_button_short_press_mapping", "assignable_action_button_long_press_mapping",
            "action_button_assistant_custom_prompt", "tap_and_hold_mapping", "quick_actions", "captouch_enabled",
            "sg_captouch_invert_swipe_direction_horizontal", "sg_captouch_invert_swipe_direction_vertical", "wake_handedness",
            "spotify_tap_enabled",
        ),
    ),
    // The Display's: the band itself, then what the glasses do with it.
    BAND(R.string.topic_band, VisorIcons.Pulse, listOf("sg_emg_display_off", "sg_emg_instant_action", "sg_invert_wrist_roll_direction")),
    // Spatial audio is the feature row: the setting does nothing while Meta's switch is off.
    SOUND(
        R.string.topic_sound, VisorIcons.Speaker,
        listOf("notification_volume", "avc_enabled", "mono_audio_enabled", "sg_headphone_mode_enabled"),
    ),
    // Conversation focus, hearing correction and group conversations, all the glasses' hearing app.
    HEARING(R.string.topic_hearing, VisorIcons.Ear),
    WEAR(
        R.string.topic_wear, VisorIcons.Battery,
        listOf("battery_saver_mode_enabled", "wear_detection_enabled", "auto_play_pause_on_don_doff", "water_eject_enabled", "posture_detection_enabled"),
    ),
    CAMERA(
        R.string.topic_camera, VisorIcons.Camera,
        listOf(
            "resolution_and_framerate", "video_duration_ms", "eis_mode", "capture_aspect_ratio",
            "quick_clips_photo_capture_enabled", "media_auto_leveling_enabled", "media_occlusion_removal_enabled",
            "capture_auto_review", "audio_playback_during_capture_enabled", "music_concurrency_enabled", "motion_blur_reduction_enabled",
            "occlusion_detection_enabled", "audio_noise_reduction_enabled",
        ),
    ),
    PHOTOS(R.string.topic_photos, VisorIcons.Gallery, listOf("location_camera_geotagging_enabled")),
    LIVE(R.string.topic_live, VisorIcons.Live),
    PRIVACY(
        R.string.topic_privacy, VisorIcons.Shield,
        listOf(
            "location_global_enabled", "location_chatbot_enabled", "assistant_store_voice_recordings",
            "assistant_store_voice_transcripts", "cloud_media_processing_enabled", "media_cloud_enabled",
            "device_share_additional_data_enabled", "passive_visual_capture_enabled", "passive_phone_unlock_enabled",
            "verified_session_enabled",
        ),
    ),
    PHONE(R.string.topic_phone, VisorIcons.Phone),
    ;

    companion object {
        /** The main page's cards. */
        val CARDS = listOf(
            listOf(META_AI, LANGUAGE),
            listOf(NOTIFICATIONS, CONTROLS, BAND, SOUND, HEARING, WEAR),
            listOf(CAMERA, PHOTOS, LIVE),
            listOf(PRIVACY, PHONE),
        )

        fun of(key: String): Topic? = entries.firstOrNull { key in it.keys }
    }
}

/** A tile's icon: the setting's own where it has one, else its topic's. */
fun tileIcon(id: String): ImageVector = when (id) {
    "g:sg_do_not_disturb" -> VisorIcons.Moon
    "g:wakeword_enabled" -> VisorIcons.Voice
    "v:notifications" -> VisorIcons.Bell
    "g:battery_saver_mode_enabled" -> VisorIcons.Battery
    "g:wear_detection_enabled" -> VisorIcons.Glasses
    "g:camera_use_enabled" -> VisorIcons.Camera
    "g:sg_headphone_mode_enabled", "g:spatial_media_enabled" -> VisorIcons.Speaker
    else -> when {
        id.startsWith("g:") -> Topic.of(id.removePrefix("g:"))?.icon ?: VisorIcons.Sliders
        id.startsWith("f:") -> Feature.byId(id.removePrefix("f:"))?.icon ?: VisorIcons.Sliders
        else -> VisorIcons.Sliders
    }
}

/** Groups the firmware keeps for glasses with a display; they matter only to a model that has one. */
val DISPLAY_GROUPS = setOf("Display", "Neural band gestures")

/** Whether the connected glasses have a display: the build fingerprint names the device, "Meta/greatwhite/greatwhite:14/...". */
fun hasDisplay(build: String?): Boolean = productId(build) in DISPLAY_PRODUCTS

// Meta Ray-Ban Display.
private val DISPLAY_PRODUCTS = setOf(259, 270, 271)

/** Whether [product] has anything on this topic: the band is the Display's, and hearing is nothing but features. */
fun Topic.shownOn(product: Int?): Boolean = when (this) {
    Topic.BAND -> product in DISPLAY_PRODUCTS
    Topic.HEARING -> Feature.entries.any { it.topic == Topic.HEARING && it.on(product) }
    else -> true
}
