package com.macedonga.visor.core.protocol

/**
 * The phone's OS, as each field that carries it numbers it. Four numberings: a zero is the proto3 default, so the
 * field is left out but its message still sent.
 */
enum class PhoneOs(
    /** svc100 AppInfo field 1. The Display's apps size images and pick links by it. */
    val peerPlatform: Int,
    /** svc15: the message type that names the paired phone, PAIRED_DEVICE_IOS 10 or PAIRED_DEVICE_ANDROID 11. */
    val assistantPairedDevice: Int,
    /** svc7009 SessionStartRequest field 7. */
    val datPlatform: Int,
    /** svc11006 PhoneBookSyncResponse field 4. */
    val phoneBookOs: Int,
    /** svc42 NavInitChannelRequest field 1, svc9002 Stats companionOS and svc75 PairedPhoneOS: Apple 0, Android 1. */
    val companionOs: Int,
) {
    ANDROID(peerPlatform = 2, assistantPairedDevice = 11, datPlatform = 2, phoneBookOs = 1, companionOs = 1),
    IOS(peerPlatform = 1, assistantPairedDevice = 10, datPlatform = 1, phoneBookOs = 2, companionOs = 0),
}
