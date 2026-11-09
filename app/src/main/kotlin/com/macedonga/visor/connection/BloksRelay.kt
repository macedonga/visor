package com.macedonga.visor.connection

import android.content.Context
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.dat.BriefingCards
import com.macedonga.visor.dat.DatLayout
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.log.responderScope
import com.macedonga.visor.login.MetaLogin
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
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
    @ApplicationContext private val context: Context,
    private val login: Provider<MetaLogin>,
    private val cards: BriefingCards,
    private val requests: RequestLog,
    log: EventLog,
) : Responder {
    private val scope = responderScope(log, "Bloks")

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val type = message.info.messageType
        val query = QUERIES[type] ?: return
        val request = runCatching { Request.decode(message.payload) }.getOrNull() ?: return
        val reply = DataXConnection.mirror(message.info.channelId)
        scope.launch { serve(request, query, reply, connection) }
    }

    private suspend fun serve(r: Request, q: Query, reply: Int, mux: DataXConnection) {
        val id = requests.start("bloks://${r.appId}", RequestSource.META, type(r).orEmpty())
        if (r.appId.isEmpty() || r.appId.startsWith("com.bloks.fox_edge.")) {
            mux.send(reply, ByteArray(0), messageType = q.answer)
            requests.finish(id, "empty, as the Meta app answers", failed = false)
            return
        }
        if (r.appId == DatLayout.CARD_CLICK) {
            val clicked = cardClick(r)
            val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(DONE.toByteArray()) } }.toByteArray()
            mux.send(reply, Wire.fieldBytes(1, gz), messageType = q.answer)
            requests.finish(id, clicked?.let { "a click on ${it.second} for ${it.first.app}" } ?: "a click on a card no app has", failed = clicked == null)
            return
        }
        val app = card(r)
        if (app != null) {
            val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(detail(r, app).toByteArray()) } }.toByteArray()
            mux.send(reply, Wire.fieldBytes(1, gz), messageType = q.answer)
            requests.finish(id, "from ${app.app}", failed = false, bytes = gz.size.toLong())
            return
        }
        try {
            val bundle = fetch(r, q)
            // Debug builds keep the Briefing's detail screens, the model for apps' own.
            if (BuildConfig.DEBUG && r.appId.contains("Glanceables")) {
                runCatching { context.filesDir.resolve("bloks-${r.appId.substringAfterLast('.')}${type(r)?.let { "-$it" }.orEmpty()}.json").writeText(JSONObject().put("params", JSONObject(r.params as Map<*, *>)).put("type", q.answer).put("bundle", bundle).toString()) }
            }
            val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bundle.toByteArray()) } }.toByteArray()
            mux.send(reply, Wire.fieldBytes(1, gz), messageType = q.answer)
            requests.finish(id, "from Meta", failed = false, bytes = gz.size.toLong())
        } catch (error: Exception) {
            requests.finish(id, "failed: ${error.message}", failed = true)
            runCatching { mux.sendStatus(reply, INTERNAL_ERROR) }
        }
    }

    // The card a Briefing request is for, named by the data type its click preloaded.
    private fun type(r: Request): String? =
        r.params["params"]?.let { runCatching { JSONObject(it).optString("data_type") }.getOrNull() }?.takeIf { it.isNotEmpty() }

    // An app's Briefing card whose detail the Briefing asks for.
    private fun card(r: Request): BriefingCards.Card? {
        if (!r.appId.endsWith(DETAIL_QUERY)) return null
        return type(r)?.let(cards::byKey)?.takeIf { it.detail != null }
    }

    // A click in the detail of an app's card: {params: {server_params: {card, id}}}, passed on to the app.
    private fun cardClick(r: Request): Pair<BriefingCards.Card, String>? {
        val params = r.params["params"]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val server = params.optJSONObject("server_params") ?: return null
        val key = server.optString("card")
        val element = server.optString("id").ifEmpty { return null }
        if (!cards.click(key, element)) return null
        return cards.byKey(key)?.let { it to element }
    }

    // The detail as Meta's server answers it: one component, its tree the app's.
    private fun detail(r: Request, card: BriefingCards.Card): String {
        val tree = JSONObject().put("data", JSONArray()).put("tree", JSONObject(card.detail!!))
        val component = JSONObject().put("id", "component").put("payload", JSONObject().put("layout", JSONObject().put("bloks_payload", tree)))
        val consumed = JSONObject()
        r.params["params"]?.let { runCatching { JSONObject(it) }.getOrNull() }?.keys()?.forEach { consumed.put(it, it) }
        val answer = JSONObject().put("payload", JSONObject().put("components", JSONArray().put(component)).toString())
            .put("consumed_params", consumed).put("app_id", r.appId)
        return JSONObject().put("component_query_responses", JSONArray().put(answer)).toString()
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
        private const val DETAIL_QUERY = "GlanceablesDetailQuery"

        private const val TAKE_LAST = 18231

        // The answer to a card click: an action that does nothing more, so the Briefing runs the click's own callback.
        // Not seen on the glasses yet: if the Display cannot read it, the click still reached the app.
        private val DONE = JSONObject().put(
            "layout",
            JSONObject().put("bloks_payload", JSONObject().put("data", JSONArray()).put("ft", JSONObject()).put("action", "\t(${TAKE_LAST.toString(36)} 1)")),
        ).toString()
    }
}
