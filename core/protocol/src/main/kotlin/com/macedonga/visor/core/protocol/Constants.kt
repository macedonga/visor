package com.macedonga.visor.core.protocol

/** DataX message ceiling, imposed by the 14-bit length field. */
const val DATAX_MAX_MESSAGE: Int = 16383

/** The BLE key exchange curve. It is NOT x25519. */
const val EC_CURVE: String = "secp256r1"

/** Field 3 of identity.EnableTrust and EnableTrustEC is `provisioningCapabilities`, a varint. */
const val RESIGN_PROVISIONING_CAPABILITIES: Int = 2

/** AirShield substream frame type: the top two bits of the one-byte stream header `[2-bit type | 6-bit stream id]`. */
enum class FrameType(val value: Int) {
    /** Encrypted application data. */
    DATA(0x40),

    /** Cleartext stream control, such as stream 0 LOST. The handshake is bare DataX, not this. */
    COMMAND(0x80),
}

/** Relay substream control codes: the body of a COMMAND frame `[0x80|stream_id][code]`. */
enum class StreamCommand(val value: Int) {
    OPEN(0),
    LOST(1),
    REJECTED(2),
    NO_ROUTE(3),
    OPEN_TO_ADVERTISE(4),
    CONNECT(5),

    /** Long form: `code or 0x80`, then a two-byte length, then a payload of up to 1024 bytes. */
    ADVERTISEMENT(0x80),
}

/** DataX message types on svc5, link setup. */
enum class LinkSetupMsgType(val value: Int) {
    REQUEST_ENCRYPTION(1),
    ENABLE_ENCRYPTION(2),
    END_LINK_SETUP(0x1000),
}

/** DataX service ids. */
enum class ServiceId(val value: Int) {
    LINK_SETUP(5),

    /** Ownership and trust, svc36. */
    IDENTITY(36),

    /** `conn_device_lease`: the Display asks the phone for its band here. */
    CONN_DEVICE_LEASE(150),

    /** `wireless_input_service` in the firmware's registry; the Neural Band's whole RPC set. */
    WIRELESS_INPUT(52822),
}

/** svc20, airship: Meta's file transfer engine. */
const val SVC_AIRSHIP: Int = 20

/** svc29, OTA: firmware and resource packs. */
const val SVC_OTA: Int = 29

/** svc56, the feature lease an app on the glasses runs under. */
const val SVC_LEASE: Int = 56

/** svc72, the translation session. */
const val SVC_TRANSLATION: Int = 72

/** svc51350, media_streaming_control: the live camera stream. */
const val SVC_MEDIA_STREAMING_CONTROL: Int = 51350

/** svc36 message types. */
enum class IdentityMsgType(val value: Int) {
    /** 4096. phone to glasses TX trust `{1: SHA256(app_pubkey)(32), 2: ECDSA sig(64), 3: provisioningCapabilities = 2}`. */
    ENABLE_TRUST(0x1000),

    /** 4097. glasses to phone RX trust `{1: keyHint, 2: sig(64), 3: provisioningCapabilities}`. */
    ENABLE_TRUST_EC(0x1001),

    /** 8192. Request, empty body. */
    SKIP_CHALLENGE(0x2000),

    /** 8193. Response `{1: nonce(16)}`, the device nonce or challenge. */
    SKIP_CHALLENGE_ACK(0x2001),

    /** 8194. ServerPendingOwnershipReceipt `{1: sig(256), 2: json}`. */
    START_CHANGE_OWNER_REQ(0x2002),

    /** 8195. DevicePendingOwnershipReceipt `{1: sig(512), 2: json plus cert}`. */
    START_CHANGE_OWNER_RESP(0x2003),

    /** 8196. ServerFinalOwnershipReceipt `{1: sig(256), 2: json}`. */
    FINISH_CHANGE_OWNER_REQ(0x2004),

    /** 8197. Acknowledgement, empty body. */
    FINISH_CHANGE_OWNER_RESP(0x2005),

    /** 12288. Request, empty body. */
    IDENTITY_REQUEST(0x3000),

    /** 12289. `{1: cert(DER), 2: serial, 4: flag}`. */
    IDENTITY_RESPONSE(0x3001),

    /** 16384. */
    NUX_REGISTRATION_CHALLENGE(0x4000),

    /** 16385. */
    NUX_REGISTRATION_CHALLENGE_RESPONSE(0x4001),

    /** 16386. */
    NUX_INSTALL_DEVICE_IDENTITY(0x4002),

    /** 16387. */
    NUX_INSTALL_DEVICE_IDENTITY_RESPONSE(0x4003),

    /** 20480. ManifestKey `{1: device EC pubkey}`, stored after RX trust. */
    MANIFEST_KEY(0x5000),

    /** 20481. Sent by the receiver of a 4096 after verifying it. Empty body. */
    ENABLE_EC_AUTH(0x5001),
}

/** svc36 channel STATUS and verdict codes. */
enum class IdentityStatus(val value: Int) {
    SUCCESS(0x1000),
    FAILURE(0x1001),
    NOT_SUPPORTED(0x1002),
    UNAVAILABLE(0x1003),
    KEY_NOT_FOUND(0x1010),
    INVALID_RECEIPT(0x1040),
    INVALID_NONCE(0x1041),
    INVALID_USER_ID(0x1042),
    INVALID_APP_IDENTITY(0x1043),
    INVALID_SIGNATURE(0x1044),
    INVALID_TIME(0x1045),
}
