package com.macedonga.visor.core.identity.token

import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.pyUrlencode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** The glasses' accounts app: the Meta account the Display keeps is minted for this app id and package. */
const val ACCOUNTS_APP_ID = "1463123261063354"
const val ACCOUNTS_PACKAGE = "com.meta.smartglass.app.accounts"

/** What the token exchange answers for the Meta account. The access token is a secret: never log it. */
class AccountIdentity(
    val accessToken: String,
    val userId: String,
    val appId: String,
    val accountId: String,
    val name: String,
    val profilePictureUrl: String,
    val isEmployee: Boolean,
    val isTestUser: Boolean,
    val analyticsClaim: String,
)

/**
 * POST /app_token_exchange: an access token for [appId] and [packageName], minted from the user's own Meta login. The
 * user id and the account fields come back with it.
 */
fun exchangeAccountToken(
    userToken: Token,
    appId: String = ACCOUNTS_APP_ID,
    packageName: String = ACCOUNTS_PACKAGE,
    http: HttpPost = UrlConnectionPost,
): AccountIdentity {
    val body = pyUrlencode(
        linkedMapOf(
            "target_app_id" to appId,
            "package_name" to packageName,
            "extras" to "account_id,is_employee,is_test_user,name,profile_picture_url,analytics_claim",
        ),
    ).toByteArray(Charsets.US_ASCII)
    val reply = try {
        http.post(
            "https://$DEFAULT_AR_HOST/app_token_exchange",
            mapOf(
                "Authorization" to "OAuth ${userToken.value}",
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to META_USER_AGENT,
            ),
            body,
        )
    } catch (error: Exception) {
        throw TokenError("app_token_exchange: ${error.javaClass.simpleName}: ${error.message}", unreachable = true)
    }
    val payload: JsonObject = try {
        Json.parseToJsonElement(String(reply.body, Charsets.UTF_8)).jsonObject
    } catch (error: Exception) {
        throw TokenError("app_token_exchange: response was not JSON")
    }
    payload["error"]?.let { throw TokenError("token exchange refused: ${(it as? JsonPrimitive)?.content ?: it}") }
    val token = payload.text("access_token")
    val userId = payload.text("user_id")
    if (token.isNullOrEmpty() || userId.isNullOrEmpty()) {
        throw TokenError("no access_token or user_id in the answer; keys: ${payload.sortedKeys()}")
    }
    return AccountIdentity(
        accessToken = token,
        userId = userId,
        appId = payload.text("app_id") ?: appId,
        accountId = payload.text("account_id").orEmpty(),
        name = payload.text("name").orEmpty(),
        profilePictureUrl = payload.text("profile_picture_url").orEmpty(),
        isEmployee = (payload["is_employee"] as? JsonPrimitive)?.booleanOrNull == true,
        isTestUser = (payload["is_test_user"] as? JsonPrimitive)?.booleanOrNull == true,
        analyticsClaim = payload.text("analytics_claim").orEmpty(),
    )
}
