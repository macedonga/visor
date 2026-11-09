package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.assistant.mcp.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/*
 * The Display's own tools, offered to the model for one turn. Meta's server names them on ev57 field 54; a call goes
 * out as ev311 tools/call and comes back on ev312, with ev387 only acknowledging it. Names and help are Visor's own.
 */

/**
 * One of the glasses' tools as the model sees it: the short name it calls, the glasses' full name, help and schema.
 * [generic] when Visor knows nothing of it, such as a web app's WebMCP tool.
 */
class GlassesTool(val short: String, val full: String, val help: String, val schema: JSONObject, val generic: Boolean = false)

/** A tool's help and input schema as the glasses describe it, on MCP tools/list. */
class ToolDescription(val help: String, val schema: JSONObject)

/** The glasses' tools for one turn, and the call that runs one of them. */
class GlassesToolbox(
    val tools: List<GlassesTool>,
    val call: suspend (full: String, input: JSONObject) -> ToolResult,
    private val describe: (suspend (List<String>) -> Map<String, ToolDescription>)? = null,
) {
    /**
     * These tools, with the glasses' own help and schema for those Visor knows nothing about, when the glasses give
     * them; otherwise as they are, so a turn never waits on a description that does not come.
     */
    suspend fun described(): GlassesToolbox {
        val unknown = tools.filter { it.generic }.map { it.full }
        val describe = describe ?: return this
        if (unknown.isEmpty()) return this
        val found = runCatching { describe(unknown) }.getOrDefault(emptyMap())
        if (found.isEmpty()) return this
        return GlassesToolbox(tools.map { t -> found[t.full]?.let { d -> GlassesTool(t.short, t.full, d.help, d.schema) } ?: t }, call)
    }
}

private class Known(val help: String, val props: Map<String, String> = emptyMap(), val required: List<String> = emptyList())

/** Keyed by the method name. A prop is a JSON type, or "string:a|b" for an enum. */
private val KNOWN: Map<String, Known> = mapOf(
    "setVolume" to Known("Set the glasses' volume, 0 to 100.", mapOf("percent" to "integer"), listOf("percent")),
    "getVolume" to Known("Read the glasses' volume, 0 to 100."),
    "setDisplayBrightness" to Known("Set the display brightness, 0 to 100.", mapOf("percent" to "integer"), listOf("percent")),
    "getDisplayBrightness" to Known("Read the display brightness, 0 to 100."),
    "setDoNotDisturb" to Known("Turn do not disturb on or off.", mapOf("enabled" to "boolean"), listOf("enabled")),
    "getDoNotDisturbState" to Known("Say whether do not disturb is on."),
    "getBatteryLevel" to Known("Read the glasses' battery level."),
    "getNowPlaying" to Known("Read what is playing."),
    "pauseMusic" to Known("Pause the music."),
    "resumeMusic" to Known("Resume the music."),
    "stopMusic" to Known("Stop the music."),
    "skipNext" to Known("Skip to the next track."),
    "skipPrevious" to Known("Go back to the previous track."),
    "setShuffle" to Known("Turn shuffle on or off.", mapOf("enabled" to "boolean"), listOf("enabled")),
    "setRepeatMode" to Known("Set repeat to off, all or one.", mapOf("mode" to "string:off|all|one"), listOf("mode")),
    "likeCurrentTrack" to Known("Like the track playing."),
    "startApp" to Known("Open an app on the glasses by its Android package name.", mapOf("packageName" to "string"), listOf("packageName")),
    "openUrl" to Known("Open a web address in the glasses' browser.", mapOf("url" to "string"), listOf("url")),
    "openMap" to Known("Show a place on the glasses' map.", mapOf("query" to "string"), listOf("query")),
    "searchPlaces" to Known("Search for places near the wearer.", mapOf("query" to "string", "maxResults" to "integer"), listOf("query", "maxResults")),
    "navigateTo" to Known(
        "Start directions to a place, by coordinates.",
        mapOf(
            "latitude" to "number", "longitude" to "number", "destinationName" to "string",
            "travelMode" to "string:WALKING|DRIVING|BICYCLING|TRANSIT",
        ),
        listOf("latitude", "longitude", "destinationName", "travelMode"),
    ),
    "stopNavigation" to Known("Stop the navigation."),
    "getNavStatus" to Known("Say whether navigation is running."),
    "getNextManeuver" to Known("Read the next turn of the route."),
    "camera_capture" to Known(
        "Take a photo with the glasses' camera and return where it is.",
        mapOf("width" to "integer", "height" to "integer", "airshipUpload" to "boolean"),
    ),
)

/**
 * The glasses' tools named in [advertised] (the full names from ev57 field 54), with short names the model can send.
 * Null when the glasses advertise none.
 */
internal fun glassesToolbox(
    advertised: List<String>,
    call: suspend (String, JSONObject) -> ToolResult,
    describe: (suspend (List<String>) -> Map<String, ToolDescription>)? = null,
): GlassesToolbox? {
    if (advertised.isEmpty()) return null
    val tools = mutableListOf<GlassesTool>()
    for (full in advertised.distinct()) {
        val method = full.substringAfterLast('.')
        val base = "glasses_" + method.replace(Regex("[^A-Za-z0-9_-]"), "_").take(50)
        val short = if (tools.any { it.short == base }) "${base}_${tools.size}" else base
        val known = KNOWN[method]
        tools += if (known == null) {
            GlassesTool(short, full, "The glasses' own tool $method. Pass the arguments it takes.", JSONObject().put("type", "object"), generic = true)
        } else {
            GlassesTool(short, full, known.help, schema(known))
        }
    }
    return GlassesToolbox(tools, call, describe)
}

/**
 * A tools/list answer: each tool's description and input schema, by the name the glasses gave, and by its last part
 * too, as field 54 may name it in full.
 */
internal fun toolDescriptions(json: String): Map<String, ToolDescription> {
    val tools = runCatching { JSONObject(json).optJSONObject("result")?.optJSONArray("tools") }.getOrNull() ?: return emptyMap()
    val out = HashMap<String, ToolDescription>()
    for (i in 0 until tools.length()) {
        val t = tools.optJSONObject(i) ?: continue
        val name = t.optString("name").ifEmpty { continue }
        val help = t.optString("description").ifEmpty { t.optString("title") }.ifEmpty { "The glasses' tool $name." }
        val schema = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
        val d = ToolDescription(help, schema)
        out[name] = d
        out.putIfAbsent(name.substringAfterLast('.'), d)
    }
    return out
}

private fun schema(known: Known): JSONObject {
    val props = JSONObject()
    for ((name, spec) in known.props) {
        val type = spec.substringBefore(':')
        val values = spec.substringAfter(':', "").takeIf { it.isNotEmpty() }?.split('|')
        props.put(name, JSONObject().put("type", type).apply { if (values != null) put("enum", JSONArray(values)) })
    }
    return JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(known.required))
}

/** A tools/call answer (ev312): a JSON-RPC error, or a result whose text the model reads. */
internal fun glassesResult(json: String): ToolResult {
    val reply = runCatching { JSONObject(json) }.getOrNull() ?: return ToolResult("unreadable answer from the glasses", true)
    reply.optJSONObject("error")?.let { return ToolResult(it.optString("message").ifEmpty { "failed" }, true) }
    val result = reply.optJSONObject("result") ?: return ToolResult("no result from the glasses", true)
    val content = result.optJSONArray("content") ?: JSONArray()
    val text = (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text")?.takeIf { s -> s.isNotEmpty() } }
    return ToolResult(text.joinToString("\n").ifEmpty { "done" }, result.optBoolean("isError"))
}
