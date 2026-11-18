package com.macedonga.visor.core.media

import java.util.zip.ZipFile

internal enum class MobileConfigLayout(val label: String) {
    LITE("lite"),
    AIRSHIELD_16_FULL("AirShield 16 full dump"),
}

internal data class MobileConfigReport(
    val layout: MobileConfigLayout,
    val map: ByteArray,
    val names: ByteArray,
    val values: ByteArray?,
    val session: ByteArray?,
    val timestamp: ByteArray?,
    val boot: ByteArray?,
)

private data class ReportLayout(
    val kind: MobileConfigLayout,
    val map: String,
    val names: String,
    val values: String,
    val session: String,
    val timestamp: String,
    val boot: String,
)

private val REPORT_LAYOUTS = listOf(
    ReportLayout(
        MobileConfigLayout.LITE,
        "mobileconfig/maps/current/merged_params_map_v4_u0.txt",
        "mobileconfig/maps/current/merged_params_names_v4_u0.txt",
        "mobileconfig/current_mc_values.json",
        "mobileconfig/session_info.json",
        "mobileconfig/current_timestamp.txt",
        "settingsservice/boot_session_id",
    ),
    ReportLayout(
        MobileConfigLayout.AIRSHIELD_16_FULL,
        "dumpstate/FS/data/misc/mobileconfig/params_map_v4_u0.txt",
        "dumpstate/FS/data/misc/mobileconfig/params_names_v4_u0.txt",
        "dumpstate/FS/data/misc/mobileconfig/current_mc_values.json",
        "dumpstate/FS/data/misc/mobileconfig/session_info.json",
        "dumpstate/FS/data/misc/mobileconfig/current_timestamp.txt",
        "dumpstate/FS/data/misc/settingsservice/boot_session_id",
    ),
)

/** Reads one complete, known MobileConfig layout. A map and names file are never mixed between layouts. */
internal fun readMobileConfigReport(zip: ZipFile): MobileConfigReport? {
    fun bytes(path: String) = zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
    for (layout in REPORT_LAYOUTS) {
        val map = bytes(layout.map) ?: continue
        val names = bytes(layout.names) ?: continue
        return MobileConfigReport(
            layout.kind,
            map,
            names,
            bytes(layout.values),
            bytes(layout.session),
            bytes(layout.timestamp),
            bytes(layout.boot),
        )
    }
    return null
}

/** Only the exact timestamp of an acknowledged table can verify that table; an older queued report cannot. */
internal fun verifiesAcknowledgedConfig(reportTimestamp: String?, acknowledgedTimestamp: String?): Boolean =
    acknowledgedTimestamp != null && reportTimestamp == acknowledgedTimestamp

/** Preserve a pending write's desired values until its own report arrives; otherwise reports remain authoritative. */
internal fun mayReplaceDesiredConfig(
    reportTimestamp: String?,
    acknowledgedTimestamp: String?,
    verifiedTimestamp: String?,
): Boolean = acknowledgedTimestamp == null || acknowledgedTimestamp == verifiedTimestamp ||
    verifiesAcknowledgedConfig(reportTimestamp, acknowledgedTimestamp)
