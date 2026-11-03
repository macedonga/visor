package com.macedonga.visor.core.protocol

/** Protocol constants: BLE identifiers, PSM, service ids, message types. */

// --- BLE discovery and GATT (notes/10) --------------------------------------

/** Manufacturer-data company id, Meta Platforms. */
const val META_COMPANY_ID: Int = 0x01AB

/** The advertised service UUID, 16-bit 0xFD5F. */
const val SERVICE_UUID: String = "0000fd5f-0000-1000-8000-00805f9b34fb"

/** Read this characteristic to get the L2CAP CoC PSM. */
const val PSM_CHAR_UUID: String = "05acbe9f-6f61-4ca9-80bf-c8bbb52991c0"

const val RELAY_PSM_CHAR_UUID: String = "06eaf36d-40ee-427c-80d6-009d0f6d1e06"

/** Device Info, firmware revision. */
const val FIRMWARE_CHAR_UUID: String = "00002a26-0000-1000-8000-00805f9b34fb"

/** What to request. The effective payload is the MTU less 3. */
const val GATT_MTU: Int = 512

// Product ids, manufacturer data bytes 0 and 1, little endian.

/** 0x0101, Ray-Ban Stories, gen 1. */
const val PRODUCT_ID_STELLA: Int = 257

/** 0x0102, Ray-Ban Meta (2023). Gen 2 is 266, "GreatSupernova". */
const val PRODUCT_ID_SUPERNOVA: Int = 258

/** 0x0103, Meta Ray-Ban Display. */
const val PRODUCT_ID_HYPERNOVA: Int = 259

/** 0x0601, the EMG wristband. */
const val PRODUCT_ID_CERES: Int = 1537

/** DataX message ceiling, imposed by the 14-bit length field (notes/66). */
const val DATAX_MAX_MESSAGE: Int = 16383

/** The BLE key exchange curve. It is NOT x25519. */
const val EC_CURVE: String = "secp256r1"

/** Field 3 of identity.EnableTrust and EnableTrustEC is `provisioningCapabilities`, a varint. */
const val RESIGN_PROVISIONING_CAPABILITIES: Int = 2

/** Legacy alias of [RESIGN_PROVISIONING_CAPABILITIES]. Same wire value. */
const val RESIGN_KEYTYPE_EC_P256: Int = 2

/**
 * Legacy alias of [RESIGN_PROVISIONING_CAPABILITIES], from svc77 and from an earlier wrong
 * assumption about svc36.
 */
const val RESIGN_KEYTYPE_ED25519: Int = 2

/**
 * AirShield substream frame type: the top two bits of the one-byte stream header `[2-bit type |
 * 6-bit stream-id (0..63)]`, from `RelayStreamImpl.streamHeader()`.
 */
enum class FrameType(val value: Int) {
    /** Encrypted application data. */
    DATA(0x40),

    /** Cleartext stream control, such as stream 0 LOST. The handshake is bare DataX, not this. */
    COMMAND(0x80),
    ;

    companion object {
        fun from(value: Int): FrameType? = entries.firstOrNull { it.value == value }
    }
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
    ;

    companion object {
        fun from(value: Int): StreamCommand? = entries.firstOrNull { it.value == value }
    }
}

/** COMMAND-frame message types on serviceId 5, link setup. */
enum class LinkSetupMsgType(val value: Int) {
    REQUEST_ENCRYPTION(1),
    ENABLE_ENCRYPTION(2),

    /**
     * `com.oculus.atc.LinkSetupConfig {1: requiredLinkSetupServices u64}`, the phased-link service-
     * activation message `acceptAuthentication` sends (Ghidra `FUN_00ea2d00`).
     */
    LINK_SETUP_CONFIG(3),

    END_LINK_SETUP(0x1000),
    ;

    companion object {
        fun from(value: Int): LinkSetupMsgType? = entries.firstOrNull { it.value == value }
    }
}

/** Client-relevant DataX service ids. The full catalogue is notes/11 section 6 and notes/17. */
enum class ServiceId(val value: Int) {
    LINK_SETUP(5),
    MANIFEST(8),
    RELAY(9),

    /**
     * LinkManager2: WiFi, SoftAP and NAN, plus the constellation-manifest INSTALL (authority key
     * and manifest file).
     */
    ATC_LINKMGR(10),

    ATC_PAIRING(14),

    /** Oxide. */
    ASSISTANT(15),

    DEVICE_INFO(22),

    CONSTELLATION(25),

    /** Java registry name `smartglasses_appmanager`; carries device status (notes/60). */
    STATUS(28),

    FIRMWARE_OTA(29),
    SETTINGS(31),

    /** Ownership and trust, svc36. */
    IDENTITY(36),

    AIRSHIELD_AUTH(54),
    CONSTELLATION_AUTH(79),

    /** `conn_device_lease`: the Display asks the phone for its band here. */
    CONN_DEVICE_LEASE(150),

    /** `wireless_input_service` in the firmware's registry; the Neural Band's whole RPC set. */
    WIRELESS_INPUT(52822),
    ;

    companion object {
        fun from(value: Int): ServiceId? = entries.firstOrNull { it.value == value }
    }
}

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
    ;

    companion object {
        fun from(value: Int): IdentityMsgType? = entries.firstOrNull { it.value == value }

        // DIVERGENCE: constants.py declares SESSION_CONFIRM and SESSION_CONFIRM_ACK as enum members
        // aliasing MANIFEST_KEY and ENABLE_EC_AUTH.

        /** Legacy alias of [MANIFEST_KEY]. The name was a mis-reading before notes/77. */
        const val SESSION_CONFIRM: Int = 0x5000

        /** Legacy alias of [ENABLE_EC_AUTH]. The name was a mis-reading before notes/77. */
        const val SESSION_CONFIRM_ACK: Int = 0x5001
    }
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
    ;

    companion object {
        fun from(value: Int): IdentityStatus? = entries.firstOrNull { it.value == value }
    }
}

/** svc79, constellationauth, message types. */
enum class ConstellationAuthMsgType(val value: Int) {
    ENABLE_TRUST(1),
    MANIFEST_FILE_TRANSFER_DATA(2),
    MANIFEST_FILE_TRANSFER_DONE(3),
    MANIFEST_FILE_TRANSFER_COMPLETE(4),
    ;

    companion object {
        fun from(value: Int): ConstellationAuthMsgType? = entries.firstOrNull { it.value == value }
    }
}

/** svc10, LinkManager2, see [ServiceId.ATC_LINKMGR], manifest-install message types. */
enum class ManifestFeatureMsgType(val value: Int) {
    MANIFEST_FILE_TRANSFER_DATA(4122),

    /** Removed on-device. Do NOT send it: the glasses answer error 0xd003. */
    MANIFEST_FILE_TRANSFER_DONE(4123),

    MANIFEST_FILE_TRANSFER_COMPLETE(4126),
    SET_MANIFEST_AUTHORITY_KEY(4127),
    GET_MANIFEST_VERSION(4144),
    MANIFEST_VERSION_INFO(4145),
    ;

    companion object {
        fun from(value: Int): ManifestFeatureMsgType? = entries.firstOrNull { it.value == value }
    }
}

/** svc79 DataX error and status codes, from jadx `X.EnumC33736Ipv` (notes/81 section 4). */
enum class ConstellationAuthStatus(val value: Int) {
    FAILURE(1),

    /** Manifest signature verification failed. */
    INVALID_MANIFEST(2),

    /** The peer has a newer manifest, so send it. */
    NEED_MANIFEST(3),

    /** The peer, our app key, is not in the manifest. */
    INVALID_PEER(4),

    /** The peer has an older manifest. */
    MANIFEST_OUT_OF_DATE(5),
    ;

    companion object {
        fun from(value: Int): ConstellationAuthStatus? = entries.firstOrNull { it.value == value }
    }
}
