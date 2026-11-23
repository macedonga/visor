package com.macedonga.visor.core.identity.token

import com.macedonga.visor.core.identity.text
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The glasses' accounts app: the Meta account the Display keeps is minted for this app id and package. */
private const val ACCOUNTS_APP_ID = "1463123261063354"
private const val ACCOUNTS_PACKAGE = "com.meta.smartglass.app.accounts"

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
 * POST /app_token_exchange: an access token for the glasses' accounts app, minted from the user's own Meta login. The
 * user id and the account fields come back with it.
 */
fun exchangeAccountToken(userToken: Token): AccountIdentity {
    val payload = authPost(
        "https://$AR_HOST/app_token_exchange",
        linkedMapOf(
            "target_app_id" to ACCOUNTS_APP_ID,
            "package_name" to ACCOUNTS_PACKAGE,
            "extras" to "account_id,is_employee,is_test_user,name,profile_picture_url,analytics_claim",
        ),
        "OAuth ${userToken.value}",
    )
    checkSession(payload)
    payload.errorMessage()?.let { throw TokenError("token exchange refused: $it") }
    val token = payload.text("access_token")
    val userId = payload.text("user_id")
    if (token.isNullOrEmpty() || userId.isNullOrEmpty()) {
        throw TokenError("no access_token or user_id in the answer; keys: ${payload.sortedKeys()}")
    }
    return AccountIdentity(
        accessToken = token,
        userId = userId,
        appId = payload.text("app_id") ?: ACCOUNTS_APP_ID,
        accountId = payload.text("account_id").orEmpty(),
        name = payload.text("name").orEmpty(),
        profilePictureUrl = payload.text("profile_picture_url").orEmpty(),
        isEmployee = (payload["is_employee"] as? JsonPrimitive)?.booleanOrNull == true,
        isTestUser = (payload["is_test_user"] as? JsonPrimitive)?.booleanOrNull == true,
        analyticsClaim = payload.text("analytics_claim").orEmpty(),
    )
}
