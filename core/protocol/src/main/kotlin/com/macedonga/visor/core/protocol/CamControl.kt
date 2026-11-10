package com.macedonga.visor.core.protocol

/**
 * svc151, cam_control: rules for the Display's context awareness manager, which decides which peers in its manifest it
 * links to and with what lease. Sent fire and forget on a channel opened for it, as the Meta app sends them.
 */
object CamControl {
    const val SERVICE = 151
    const val OVERRIDE_RULE = 1

    private const val FAMILY_SMART_GLASSES = 1L
    private const val FAMILY_INPUT = 6L
    private const val SCOPE_LOW = 2L

    /**
     * CamOverrideRule {1 these glasses, on body; 2 the input device whose key hint is [peerHint]; 3 a LOW lease}, its
     * rule type TEMPORARY (0) left out: the rule the Meta app sends when a band is moved to a pair of glasses.
     */
    fun bandRule(peerHint: ByteArray): ByteArray {
        val glasses = family(FAMILY_SMART_GLASSES) + Wire.fieldVarint(6, 1)
        val band = family(FAMILY_INPUT) + Wire.fieldBytes(8, peerHint)
        return Wire.fieldBytes(1, glasses) + Wire.fieldBytes(2, band) + Wire.fieldVarint(3, SCOPE_LOW)
    }

    // DeviceMatch field 1, a packed repeated enum.
    private fun family(value: Long) = Wire.fieldBytes(1, Wire.encodeVarint(value))
}
