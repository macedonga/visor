package com.macedonga.visor.core.connection

import com.macedonga.visor.core.protocol.Wire

/** The app manager's StartApp, StopApp and SNIntent, on svc28 ([SVC_STATUS]) and svc29, and its result codes. */
object SnApp {
    const val MT_START_APP = 2000
    const val MT_START_APP_RESPONSE = 2001
    const val MT_STOP_APP = 2002
    const val MT_STOP_APP_RESPONSE = 2003
    const val MT_SNINTENT = 2300

    const val SUCCESS = 1L
    const val NOT_ENOUGH_BATTERY = 6L
    const val THERMAL_THROTTLING = 7L
    const val ALREADY_STARTED = 12L
    const val DENIED_BY_PEAK_POWER = 15L

    private val WORDS = listOf(
        "unknown", "success", "unknown message", "unhandled message", "payload corrupted", "unsupported app",
        "not enough battery", "thermal throttling", "not enough storage", "start failed", "stop failed",
        "not running", "already started", "not enough priority", "charger not connected", "denied by peak power", "power down",
    )

    /** A start answered [code]: the app runs. */
    fun started(code: Long?): Boolean = code == SUCCESS || code == ALREADY_STARTED

    fun words(code: Long): String = if (code >= 0 && code < WORDS.size) WORDS[code.toInt()] else "error $code"

    /** One SNIntentData: the key in field 1, the value in its type's field (2 bool, 3 int, 4 double, 5 bytes). */
    fun data(key: String, value: Boolean): ByteArray = keyField(key) + Wire.fieldVarint(2, if (value) 1L else 0L)

    fun data(key: String, value: Int): ByteArray = keyField(key) + Wire.fieldVarint(3, value.toLong())

    fun data(key: String, value: Double): ByteArray = keyField(key) + Wire.fieldDouble(4, value)

    fun data(key: String, value: String): ByteArray = keyField(key) + Wire.fieldString(5, value)

    /** StartApp or StopApp: {1 app, 2 policy, 3 each data}. */
    fun app(id: Long, data: List<ByteArray> = emptyList(), policy: ByteArray? = null): ByteArray =
        data.fold(Wire.fieldVarint(1, id) + (policy?.let { Wire.fieldBytes(2, it) } ?: ByteArray(0))) { out, d -> out + Wire.fieldBytes(3, d) }

    /** SNIntent: {1 action, 2 each data}. */
    fun intent(action: String, data: List<ByteArray> = emptyList()): ByteArray =
        data.fold(Wire.fieldString(1, action)) { out, d -> out + Wire.fieldBytes(2, d) }

    private fun keyField(key: String): ByteArray = Wire.fieldString(1, key)
}
