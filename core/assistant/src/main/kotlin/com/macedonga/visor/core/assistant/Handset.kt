package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.assistant.mcp.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's own things the model may use for the wearer. Each answer is in words for the model: [HandsetDone.failed]
 * when it did not work, with why.
 */
interface Handset {
    /**
     * Calls [who]: a contact's name, with [kind] (mobile, home, work) when the wearer said it, or a number; on WhatsApp
     * when [whatsApp].
     */
    suspend fun call(who: String, kind: String, whatsApp: Boolean): HandsetDone
}

/**
 * What a phone action did: the words the model reads, whether it failed, and whom it was for, for the diary. [asked]:
 * nothing was done, the wearer has to choose first.
 */
data class HandsetDone(val text: String, val failed: Boolean = false, val subject: String = "", val asked: Boolean = false)

/** [Handset] as tools for the model. */
internal class HandsetTools(private val handset: Handset) {

    val prompt = " On the phone you can also call a contact or a number with call, at once when the wearer asks."

    val tools: List<Triple<String, String, JSONObject>> = listOf(
        Triple(
            "call",
            "Call a contact or a number from the wearer's phone. When several contacts or numbers match, the result lists them: " +
                "ask the wearer which, then call again. Never say a call started unless this tool says so.",
            schema(
                "who" to text("The contact's name as the wearer said it, or a number the wearer said or a search gave."),
                "kind" to text("The number's kind, only when the wearer said it.", "mobile", "home", "work", "other"),
                "app" to text("whatsapp only when the wearer asked for a WhatsApp call.", "phone", "whatsapp"),
                required = listOf("who"),
            ),
        ),
    )

    /** Runs [name] when it is one of these tools: what the model is told, and the diary's line for it. Null for another tool. */
    suspend fun run(name: String, input: JSONObject): Pair<ToolResult, ToolAction?>? = when (name) {
        "call" -> {
            val who = input.optString("who").trim()
            if (who.isEmpty()) ToolResult("no one to call", true) to null
            else acted(handset.call(who, input.optString("kind").trim(), input.optString("app").trim().equals("whatsapp", true)), "Call", "call", who)
        }
        else -> null
    }

    /** The result, and the diary's line "[what] · whom" for an action that went or failed; none when the wearer has to choose. */
    private fun acted(done: HandsetDone, what: String, kind: String, asked: String): Pair<ToolResult, ToolAction?> {
        if (done.asked) return ToolResult(done.text, false) to null
        val whom = done.subject.ifEmpty { asked }
        return ToolResult(done.text, done.failed) to ToolAction("", "$what · $whom", !done.failed, kind = kind, subject = whom)
    }

    private companion object {
        fun text(help: String, vararg options: String): JSONObject = JSONObject().put("type", "string").put("description", help)
            .apply { if (options.isNotEmpty()) put("enum", JSONArray(options.toList())) }

        fun schema(vararg fields: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject = JSONObject().put("type", "object")
            .put("properties", JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } })
            .put("required", JSONArray(required))
    }
}
