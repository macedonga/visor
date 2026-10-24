package com.macedonga.visor.features

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.macedonga.visor.R
import com.macedonga.visor.core.media.McParam
import com.macedonga.visor.settings.Topic
import com.macedonga.visor.ui.VisorIcons

/** A switch a feature needs, and the value that turns the feature on. */
sealed interface Gate {
    /**
     * A MobileConfig param: it goes out in a table, and the glasses read most of them when they start. [live]: one
     * the glasses keep per session but apply at once all the same, as seen on the glasses, so no restart is asked.
     * [device]: only on glasses whose build names that device. [floor]: a minimum build, on at any number but -1, so
     * Meta's own floor stays; it is only sent where there is none, and turning the feature off leaves it.
     */
    data class Config(
        val name: String,
        val on: Any,
        val off: Any? = null,
        val live: Boolean = false,
        val device: String? = null,
        val floor: Boolean = false,
    ) : Gate {
        fun needsRestart(params: Map<String, McParam>) = !live && params[name]?.sessionless == false

        /** Whether [value] has this switch on. */
        fun holds(value: Any?): Boolean = when {
            value == null -> false
            floor -> value.toString().trim().toLongOrNull()?.let { it != -1L } == true
            else -> matches(value, on)
        }
    }

    /** A glasses setting: written and read back at once. */
    data class Setting(val key: String, val on: String, val off: String? = null) : Gate
}

// Meta Ray-Ban Display, whose image has no hearingservice.
private val DISPLAY = setOf(259, 270, 271)
private val NO_HEARING_SERVICE = DISPLAY

// The hearing service starts focus only above the minimum build MobileConfig gives this model (libhearing's
// getDeviceTypeMinSoCVersion); -1 or none, as on glasses Meta never configured, is INVALID_DEVICE.
private val FOCUS_FLOORS =
    listOf(
        "hammerhead" to "min_rbm_soc_version", "mako" to "min_paloma_soc_version",
        "greathammerhead" to "min_rbm2_soc_version", "pylades" to "min_paloma_lifestyle_soc_version",
    ).map { (device, param) -> Gate.Config("hearing_on_sn.$param", "0", live = true, device = device, floor = true) } +
        listOf("zebra", "silvertip", "lager").map {
            Gate.Config("hearing_on_sn.cf_min_${it}_soc_version", 0L, live = true, device = it, floor = true)
        }

/**
 * A feature Meta keeps off on these glasses (for the country, or for testers), what it is, how to use it, the
 * switches that turn it on and the topic it shows on. Each was traced in the firmware past its gates to the point
 * where it works. Live AI is not one: its one switch is a glasses setting the Meta AI page already shows.
 */
enum class Feature(
    val id: String,
    @StringRes val title: Int,
    @StringRes val about: Int,
    @StringRes val use: Int,
    val icon: ImageVector,
    val gates: List<Gate>,
    /** The Settings topic whose page shows it. */
    val topic: Topic,
    /** Its own page, for a feature with more to set than on and off. */
    val page: Boolean = false,
    /** Products whose image ships none of its code. */
    val absentOn: Set<Int> = emptySet(),
) {
    // getHasCFAccess reads cf_enabled unless conversation_focus_use_eap_settings is on; without Meta's value it is
    // false, NO_ACCESS, and the glasses ask for early access.
    CONVERSATION_FOCUS(
        "conversation_focus", R.string.feature_cf, R.string.feature_cf_about, R.string.feature_cf_use, VisorIcons.Ear,
        listOf(Gate.Setting("hearing_boost_nux_completed", "true"), Gate.Config("hearing_on_sn.cf_enabled", true, live = true)) + FOCUS_FLOORS,
        Topic.HEARING,
        page = true,
        absentOn = NO_HEARING_SERVICE,
    ),
    // setupForHCModes refuses "HC is not supported on this device" (INVALID_DEVICE) while the first is off. The second
    // is the version pass Meta's server sets beside it ("update your device"). Both apply at once on the glasses.
    HEARING_CORRECTION(
        "hearing_correction", R.string.feature_hc, R.string.feature_hc_about, R.string.feature_hc_use, VisorIcons.HearingAid,
        listOf(
            Gate.Config("hearing_on_sn.hc_device_supported", true, live = true),
            Gate.Config("hearing_on_sn.hc_device_version_passed", true, live = true),
        ),
        Topic.HEARING,
        page = true,
        absentOn = NO_HEARING_SERVICE,
    ),
    // The group app's MainActivity finishes unless the first two are on; enable_aurachat starts aurachat through
    // sysprop_map. Without Meta's signed key material the group key is AurachatKeyManager's all-zero DEBUG_KEY.
    GROUP_CONVERSATIONS(
        "group_conversations", R.string.feature_gc, R.string.feature_gc_about, R.string.feature_gc_use, VisorIcons.People,
        listOf(
            Gate.Config("wearables_group_conversations.enabled", true),
            Gate.Config("wearables_group_conversations.min_device_version_passed", true),
            Gate.Config("wearable_connectivity_device_features.enable_aurachat", true),
        ),
        Topic.HEARING,
        // The Display ships neither aurachat nor the group app.
        absentOn = DISPLAY,
    ),
    // The glasses' TAR gate (isSgAlarmMCEnabled); Visor's timer service keeps the alarms Meta's app refuses.
    ALARMS(
        "alarms", R.string.feature_alarms, R.string.feature_alarms_about, R.string.feature_alarms_use, VisorIcons.Timer,
        listOf(Gate.Config("assistant_on_sn.sg_alarms_enabled", true)),
        Topic.META_AI,
    ),
    // SgAudioServerController applies spatial_media_enabled only while xn_spatial_media is on, else logs a no-op.
    SPATIAL_AUDIO(
        "spatial_audio", R.string.feature_spatial, R.string.feature_spatial_about, R.string.feature_spatial_use, VisorIcons.Speaker,
        listOf(Gate.Config("audio_on_xn.xn_spatial_media", true, live = true), Gate.Setting("spatial_media_enabled", "1", off = "0")),
        Topic.SOUND,
    ),
    FOLLOW_UPS(
        "follow_ups", R.string.feature_follow_ups, R.string.feature_follow_ups_about, R.string.feature_follow_ups_use, VisorIcons.Chat,
        listOf(
            Gate.Config("assistant_on_sn.follow_up_chatty_domains_enabled", true),
            Gate.Config("assistant_on_sn.follow_up_inactive_earcon_enabled", true),
        ),
        Topic.META_AI,
    ),
    ;

    val needsConfig: Boolean get() = gates.any { it is Gate.Config }

    /** The gates for glasses whose build names [device]. */
    fun gatesFor(device: String?): List<Gate> = gates.filter { it !is Gate.Config || it.device == null || it.device == device }

    /** Whether [product] has it at all; unknown glasses are given the benefit. */
    fun on(product: Int?): Boolean = product == null || product !in absentOn

    companion object {
        fun byId(id: String): Feature? = entries.firstOrNull { it.id == id }
    }
}
