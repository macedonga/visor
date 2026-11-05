package com.macedonga.visor.core.identity.token

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** [unreachable]: Meta was not reached at all, so the login itself may well be fine. */
open class TokenError(message: String, val unreachable: Boolean = false) : Exception(message)

class Token(val value: String) {
    /** Never the value. This ends up in logs. */
    override fun toString(): String = "Token(hidden)"
}

/** Meta answered OAuthException 190 to the token: the session behind it has ended. */
class SessionEnded : TokenError("Meta ended the login session; log in again if this repeats")

/** Throws [SessionEnded] when a Meta answer carries error 190. */
fun checkSession(json: JsonObject) {
    val code = (json["error"] as? JsonObject)?.get("code") as? JsonPrimitive
    if (code?.intOrNull == 190) throw SessionEnded()
}

/** Runs [block] with the token. When Meta ended its session, forgets the token and runs [block] once more with a fresh one. */
inline fun <T> WebLoginSource.withToken(block: (Token) -> T): T {
    val token = get()
    return try {
        block(token)
    } catch (error: SessionEnded) {
        forget(token)
        block(get())
    }
}

/** Meta's `error` as text: its message, else the raw value; null when there is none. */
internal fun JsonObject.errorMessage(): String? = when (val e = this["error"]) {
    null -> null
    is JsonObject -> (e["message"] as? JsonPrimitive)?.content ?: e.toString()
    is JsonPrimitive -> e.content
    else -> e.toString()
}

/** The keys, sorted, as `['a', 'b']`. */
internal fun JsonObject.sortedKeys(): String = keys.sorted().joinToString(", ", "[", "]") { "'$it'" }
