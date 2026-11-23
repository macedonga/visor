package com.macedonga.visor.core.identity

/** What one trust check says about the key. */
enum class Diagnosis {
    /** Trust completed and the canary answered. Nothing is wrong. */
    HEALTHY,

    /**
     * The glasses answered, the canary or their own credential, and did not accept ours. They no longer know
     * this key.
     */
    EVICTED,

    /** Nothing answered. This says nothing about the key, because nothing was asked. */
    LINK_DOWN,

    /** Trust reported success and the canary said nothing. */
    UNKNOWN,
}

/** The verdict from trust, the canary and the glasses' own credential. */
fun diagnose(trustOk: Boolean, canaryAnswered: Boolean, credentialReceived: Boolean): Diagnosis {
    if (!canaryAnswered) {
        // Glasses that refuse the key also withhold their EndLinkSetup, so the canary is never asked. Their own
        // credential arriving is the answer then: identity is working, and ours is what it turned down.
        if (!trustOk && credentialReceived) return Diagnosis.EVICTED
        // Nothing below identity is working, so nothing can be concluded about the key.
        return if (!trustOk) Diagnosis.LINK_DOWN else Diagnosis.UNKNOWN
    }
    return if (trustOk) Diagnosis.HEALTHY else Diagnosis.EVICTED
}

/** The bundle's recorded state after a check. */
fun stateAfter(previousState: String, diagnosis: Diagnosis): String = when (diagnosis) {
    Diagnosis.HEALTHY -> BundleState.VERIFIED
    Diagnosis.EVICTED -> BundleState.EVICTED
    else -> previousState
}
