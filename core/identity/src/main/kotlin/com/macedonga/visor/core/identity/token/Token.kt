package com.macedonga.visor.core.identity.token

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Where the provisioning token comes from. */
/** [unreachable]: Meta was not reached at all, so the login itself may well be fine. */
open class TokenError(message: String, val unreachable: Boolean = false) : Exception(message)

class Token(
    val value: String,
    /** "ar" or "fb". */
    val universe: String,
) {
    /** Never the value. This ends up in logs. */
    override fun toString(): String = "Token(universe=$universe)"
}

fun interface TokenSource {
    fun get(): Token

    /** Meta ended the session behind [dropped]: the next [get] logs in for a new token. */
    fun forget(dropped: Token) {}
}

/** Meta answered OAuthException 190 to the token: the session behind it has ended. */
class SessionEnded : TokenError("Meta ended the login session; log in again if this repeats")

/** Throws [SessionEnded] when a Meta answer carries error 190. */
fun checkSession(json: JsonObject) {
    val code = (json["error"] as? JsonObject)?.get("code") as? JsonPrimitive
    if (code?.intOrNull == 190) throw SessionEnded()
}

/** Runs [block] with the token. When Meta ended its session, forgets the token and runs [block] once more with a fresh one. */
inline fun <T> TokenSource.withToken(block: (Token) -> T): T {
    val token = get()
    return try {
        block(token)
    } catch (error: SessionEnded) {
        forget(token)
        block(get())
    }
}

/** The user agent both Meta auth calls send. */
internal const val META_USER_AGENT =
    "Dalvik/2.1.0 (Linux; U; Android 15) " +
        "[FBAN/Stella;FBAV/289.0.0.25.162;FBSN/Android;FBSV/15;FBLC/en_US]"

/** A JSON string or number as Python's `str()` would show it, or null when absent. */
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** `sorted(payload)` as Python prints it in an error message: `['a', 'b']`. */
internal fun JsonObject.sortedKeys(): String = keys.sorted().joinToString(", ", "[", "]") { "'$it'" }
