package com.macedonga.visor.connection

import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.log.EventLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Web apps' WebMCP tools for the wearer's assistant. Two MobileConfig flags: web_on_hn.webapp_webmcp gives pages
 * `document.modelContext`, and wearables_agentic_nova.forward_webmcp_requests_to_server sends a turn with a web app's
 * tools to the phone, where Visor offers them to the model (ev57 field 54) and calls them (ev311).
 */
@Singleton
class WebAppTools @Inject constructor(
    private val config: GlassesConfig,
    private val log: EventLog,
) {
    /** True when both flags are on, false when not, null before the glasses' configuration was read. */
    val on: Flow<Boolean?> = config.state.map { s ->
        if (s.params.isEmpty()) null else FLAGS.all { s.values[it] == true }
    }.distinctUntilChanged()

    /** Turns both on or off; null once the glasses have it, else why not. Reads the configuration first if needed. */
    suspend fun set(on: Boolean): String? {
        if (!config.readOnce(READ_MS) {}) return "the glasses' configuration could not be read"
        if (FLAGS.any { it !in config.state.value.params }) return "these glasses' software has no web app tools"
        val why = config.send(FLAGS.associateWith { on })
        if (why == null) log.add("Web apps: tools for the assistant ${if (on) "on" else "off"}")
        return why
    }

    companion object {
        val FLAGS = listOf("web_on_hn.webapp_webmcp", "wearables_agentic_nova.forward_webmcp_requests_to_server")
        private const val READ_MS = 5 * 60_000L
    }
}
