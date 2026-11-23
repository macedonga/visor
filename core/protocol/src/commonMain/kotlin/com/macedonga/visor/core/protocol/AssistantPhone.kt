package com.macedonga.visor.core.protocol

/** svc15 messages about the paired phone, and the iPhone's own: app launch over iAP2 and the glasses' ANCS relay. */
object AssistantPhone {
    const val SERVICE = 15

    /** Phone to glasses, after the init response: [pairedDevice] under [PhoneOs.assistantPairedDevice]. */
    const val PAIRED_DEVICE_IOS = 10
    const val PAIRED_DEVICE_ANDROID = 11

    /** Phone to glasses: launch an iOS app, a bare UTF-8 bundle id. The glasses send iAP2 RequestAppLaunch. */
    const val APP_LAUNCH_REQUEST = 1013

    /** Glasses to phone: empty on success, else the error's name ("Failure", "NotSupported", "NotAvailable"...). */
    const val APP_LAUNCH_RESPONSE = 1014

    /**
     * Phone to glasses, after [PAIRED_DEVICE_IOS]: the companion's bare UTF-8 bundle id. The glasses launch it over
     * iAP2 once the assistant has disconnected, retrying while iAP2 is down.
     */
    const val COMPANION_APP_BUNDLE_ID = 1015

    /** Phone to glasses, empty: start the glasses' own ANCS client, which reads the iPhone's notifications. */
    const val APPLE_NOTIFICATION_SUBSCRIBE = 1520

    /** Glasses to phone after [APPLE_NOTIFICATION_SUBSCRIBE]: each new notification. Format not yet seen on the wire. */
    const val ANCS_NOTIFICATION = 1521

    /** Glasses to phone: a notification's body once read, identifier and message. Format not yet seen on the wire. */
    const val ANCS_MESSAGE = 1522

    /** Phone to glasses, empty, sent right after the paired device. */
    const val PHONE_STATE_SUBSCRIBE = 1523

    /** PairedDevice {1 "<ms>-<uuid>"}. Type 11 also clears the stored bundle id, so an iPhone never sends it. */
    fun pairedDevice(millis: Long, uuid: String): ByteArray = Wire.fieldString(1, "$millis-$uuid")

    fun bundleId(id: String): ByteArray = id.encodeToByteArray()

    /** A [APP_LAUNCH_RESPONSE]'s error name, or null when the launch went through. */
    fun appLaunchError(payload: ByteArray): String? = if (payload.isEmpty()) null else payload.decodeToString()
}
