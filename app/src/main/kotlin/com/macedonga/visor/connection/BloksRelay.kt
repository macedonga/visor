package com.macedonga.visor.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * svc43, smartglasses_graphql_query: the Display's System UI, Utilities and AI History draw some screens from Meta's
 * server through the phone, the Glanceables (weather, sports, stocks) among them. A Bloks component request (type 9) or
 * action (type 11) names an app id and its params; the phone runs Meta's persisted query with the user's login and
 * hands back the bundle gzipped (type 10 or 12). `com.bloks.fox_edge.*` gets an empty answer, as from the Meta app.
 */
@Singleton
class BloksRelay @Inject constructor(
    private val login: Provider<MetaLogin>,
    private val requests: RequestLog,
    private val log: Provider<EventLog>,
) : Responder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> log.get().add("Bloks: failed: $e") })

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val type = message.info.messageType
        val query = QUERIES[type] ?: return
        val request = runCatching { Request.decode(message.payload) }.getOrNull() ?: return
        val reply = DataXConnection.mirror(message.info.channelId)
        scope.launch { serve(request, query, reply, connection) }
    }

    private suspend fun serve(r: Request, q: Query, reply: Int, mux: DataXConnection) {
        val id = requests.start("bloks://${r.appId}", SOURCE_BLOKS, r.params["params"]?.let { runCatching { JSONObject(it).optString("data_type") }.getOrNull() }.orEmpty())
        if (r.appId.isEmpty() || r.appId.startsWith("com.bloks.fox_edge.")) {
            mux.send(reply, ByteArray(0), messageType = q.answer)
            requests.finish(id, "empty, as the Meta app answers", failed = false)
            return
        }
        try {
            val bundle = fetch(r, q)
            val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bundle.toByteArray()) } }.toByteArray()
            mux.send(reply, Wire.fieldBytes(1, gz), messageType = q.answer)
            requests.finish(id, "from Meta", failed = false, bytes = gz.size.toLong())
        } catch (error: Exception) {
            requests.finish(id, "failed: ${error.message}", failed = true)
            runCatching { mux.sendStatus(reply, INTERNAL_ERROR) }
        }
    }

    private fun fetch(r: Request, q: Query): String {
        val params = JSONObject().put("app_id", r.appId).put("params", JSONObject(r.params as Map<*, *>).toString())
            .put("bloks_versioning_id", r.versioningId)
        if (r.prebundled) params.put("is_prebundled", true)
        val context = JSONObject().put("pixel_ratio", r.pixelRatio).put("bloks_version", r.versioningId)
            .put("theme_params", JSONArray().put(JSONObject().put("design_system_name", r.designSystem).put("value", JSONArray(r.themeValues))))
        val variables = JSONObject().put("params", params).put("bk_context", context)
        val answer = login.get().graphql(q.name, q.doc, variables.toString())
        val json = JSONObject(answer)
        json.optJSONArray("errors")?.let { error(it.optJSONObject(0)?.optString("message") ?: "Meta refused it") }
        // data.<the query's field>.<the bundle>: the first string under the first object, whatever Meta names them.
        val data = json.optJSONObject("data") ?: error(json.optJSONObject("error")?.optString("message") ?: answer.take(300))
        val root = data.keys().asSequence().mapNotNull { data.optJSONObject(it) }.firstOrNull() ?: error("no answer in the reply")
        return root.keys().asSequence().mapNotNull { k -> root.opt(k) as? String }.firstOrNull { it.startsWith("{") } ?: error("no bundle in the reply")
    }

    private class Query(val name: String, val doc: String, val answer: Int)

    /** BloksAsync*Request: 1 device context {1 versioning id, 2 pixel ratio, 3 theme {1 name, 2 values}}, 3 app id, 4 params, 5 prebundled. */
    private class Request(
        val appId: String,
        val params: Map<String, String>,
        val versioningId: String,
        val pixelRatio: Double,
        val designSystem: String,
        val themeValues: List<String>,
        val prebundled: Boolean,
    ) {
        companion object {
            fun decode(payload: ByteArray): Request {
                val f = Wire.parse(payload)
                val device = Wire.parse(f.bytes(1))
                val theme = Wire.parse(device.bytes(3))
                val params = f.repeatedBytes(4).associate { e -> Wire.parse(e).let { it.bytes(1).decodeToString() to it.bytes(2).decodeToString() } }
                return Request(
                    f.bytes(3).decodeToString(), params, device.bytes(1).decodeToString(), device.double(2, 1.0),
                    theme.bytes(1).decodeToString(), theme.repeatedBytes(2).map { it.decodeToString() }, f.varint(5) != 0L,
                )
            }
        }
    }

    companion object {
        const val SVC = 43
        private val QUERIES = mapOf(
            9 to Query("CompanionBloksComponentQuery", "37274295535564761374285150560", 10),
            11 to Query("CompanionBloksAsyncActionQuery", "35737516989272377596036696859", 12),
        )
        private const val INTERNAL_ERROR = 0xC004
        private const val SOURCE_BLOKS = -2
    }
}
