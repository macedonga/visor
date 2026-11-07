package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.assistant.mcp.ToolResult
import org.json.JSONObject

/** Apps on this phone that answer some of the wearer's questions themselves, through the VDK. */
interface AppVoices {
    /** The app whose phrase [question] holds, or null when the model answers it. Asked for every question: quick. */
    fun claim(question: String): AppClaim?

    /** The app's answer to [question], or null when it gives none within [budgetMs]. */
    suspend fun answer(claim: AppClaim, id: String, question: String, intent: String, budgetMs: Long): AppAnswer?
}

/** An app's hold on one question. [app] names the app, to show and to say; [key] is the app's own handle. */
class AppClaim(val app: String, val key: Any)

/** What the glasses say for the app, and whether they listen for a follow-up after it. */
class AppAnswer(val text: String, val listen: Boolean)

/** Tools that apps on this phone offer the model, through the VDK. */
fun interface AppTools {
    /** The tools offered now, with names unique among them. */
    fun tools(): List<AppTool>
}

/** One app's tool as the model sees it, with the call that runs it. [app] names the app. */
class AppTool(val name: String, val app: String, val help: String, val schema: JSONObject, val run: suspend (JSONObject) -> ToolResult)
