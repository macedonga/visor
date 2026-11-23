package com.macedonga.visor.core.protocol

/**
 * svc151, cam_control: rules for the Display's context awareness manager, which decides which peers in its manifest it
 * links to and with what lease. Sent fire and forget on a channel opened for it, as the Meta app sends them.
 */
object CamControl {
    const val SERVICE = 151
    const val OVERRIDE_RULE = 1

    // Scope state.
    const val SCOPE_DISCONNECTED = 1
    const val SCOPE_LOW = 2

    const val SCOPE_RELAY = 5

    // Device family.
    const val FAMILY_SMART_GLASSES = 1
    const val FAMILY_AR_GLASSES = 2
    private const val FAMILY_INPUT = 6L

    /**
     * CamOverrideRule {1 these glasses, on body; 2 the input device whose key hint is [peerHint]; 3 [scope]}, its rule
     * type TEMPORARY (0) left out. With LOW it is the rule the Meta app sends when a band is moved to a pair of glasses.
     */
    fun bandRule(peerHint: ByteArray, scope: Int = SCOPE_LOW, onBody: Boolean = true, glassesFamily: Int = FAMILY_SMART_GLASSES): ByteArray {
        val glasses = family(glassesFamily.toLong()) + (if (onBody) Wire.fieldVarint(6, 1) else ByteArray(0))
        val band = family(FAMILY_INPUT) + Wire.fieldBytes(8, peerHint)
        return Wire.fieldBytes(1, glasses) + Wire.fieldBytes(2, band) + Wire.fieldVarint(3, scope.toLong())
    }

    // DeviceMatch field 1, a packed repeated enum.
    private fun family(value: Long) = Wire.fieldBytes(1, Wire.encodeVarint(value))
}
