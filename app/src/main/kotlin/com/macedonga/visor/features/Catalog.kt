package com.macedonga.visor.features

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.isDisplayProduct
import com.macedonga.visor.core.media.McParam
import com.macedonga.visor.settings.Topic
import com.macedonga.visor.ui.VisorIcons

/** A switch a feature needs, and the value that turns the feature on. */
sealed interface Gate {
    /** A MobileConfig param. [live]: applies without a restart. [device]: that build only. [floor]: a minimum build, sent only where none is set. */
    data class Config(
        val name: String,
        val on: Any,
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

// One-channel hearing DSP (hammerhead-rt600): it refuses the right ear's gain map, so correction never starts.
private val ONE_CHANNEL_HEARING = setOf(258, 265, 266)

// Focus starts only above this model's minimum build; -1 or none is INVALID_DEVICE.
private val FOCUS_FLOORS =
    listOf(
        "hammerhead" to "min_rbm_soc_version", "mako" to "min_paloma_soc_version",
        "greathammerhead" to "min_rbm2_soc_version", "pylades" to "min_paloma_lifestyle_soc_version",
    ).map { (device, param) -> Gate.Config("hearing_on_sn.$param", "0", live = true, device = device, floor = true) } +
        listOf("zebra", "silvertip", "lager").map {
            Gate.Config("hearing_on_sn.cf_min_${it}_soc_version", 0L, live = true, device = it, floor = true)
        }

/** A feature Meta keeps off: its words, switches, topic and the products it cannot run on. */
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
    /** Products it cannot run on. */
    val absentOn: Set<Int> = emptySet(),
    /** Whether a Meta Ray-Ban Display runs it. */
    val onDisplay: Boolean = true,
    /** The one fact its row shows. */
    @StringRes val note: Int? = null,
) {
    // The Display's image has no hearingservice.
    // Without cf_enabled the glasses answer NO_ACCESS and ask for early access.
    CONVERSATION_FOCUS(
        "conversation_focus", R.string.feature_cf, R.string.feature_cf_about, R.string.feature_cf_use, VisorIcons.Ear,
        listOf(Gate.Setting("hearing_boost_nux_completed", "true"), Gate.Config("hearing_on_sn.cf_enabled", true, live = true)) + FOCUS_FLOORS,
        Topic.HEARING,
        page = true,
        onDisplay = false,
    ),
    // Off, the glasses refuse with INVALID_DEVICE; both apply at once.
    HEARING_CORRECTION(
        "hearing_correction", R.string.feature_hc, R.string.feature_hc_about, R.string.feature_hc_use, VisorIcons.HearingAid,
        listOf(
            Gate.Config("hearing_on_sn.hc_device_supported", true, live = true),
            Gate.Config("hearing_on_sn.hc_device_version_passed", true, live = true),
        ),
        Topic.HEARING,
        page = true,
        absentOn = ONE_CHANNEL_HEARING,
        onDisplay = false,
    ),
    // The group app needs the first two; enable_aurachat starts aurachat; without Meta's key the group key is all zeros.
    GROUP_CONVERSATIONS(
        "group_conversations", R.string.feature_gc, R.string.feature_gc_about, R.string.feature_gc_use, VisorIcons.People,
        listOf(
            Gate.Config("wearables_group_conversations.enabled", true),
            Gate.Config("wearables_group_conversations.min_device_version_passed", true),
            Gate.Config("wearable_connectivity_device_features.enable_aurachat", true),
        ),
        Topic.HEARING,
        // The Display ships neither aurachat nor the group app.
        onDisplay = false,
        note = R.string.feature_gc_unencrypted,
    ),
    // The glasses' alarms gate; Visor's timer service keeps the alarms.
    ALARMS(
        "alarms", R.string.feature_alarms, R.string.feature_alarms_about, R.string.feature_alarms_use, VisorIcons.Timer,
        listOf(Gate.Config("assistant_on_sn.sg_alarms_enabled", true)),
        Topic.META_AI,
    ),
    // spatial_media_enabled works only while xn_spatial_media is on.
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
    fun offeredOn(product: Int?): Boolean = product == null || (product !in absentOn && (onDisplay || !isDisplayProduct(product)))

    companion object {
        fun byId(id: String): Feature? = entries.firstOrNull { it.id == id }
    }
}
