@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import com.macedonga.visor.core.assistant.AppAnswer
import com.macedonga.visor.core.assistant.AppClaim
import com.macedonga.visor.core.assistant.AppTool
import com.macedonga.visor.core.assistant.AppTools
import com.macedonga.visor.core.assistant.AppVoices
import com.macedonga.visor.core.assistant.mcp.ToolResult
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.kit.KitException
import com.macedonga.visor.kit.VoiceLaunch
import com.macedonga.visor.kit.protocol.ErrorCode
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.KitJson
import com.macedonga.visor.kit.protocol.Notice
import com.macedonga.visor.kit.protocol.Scope
import com.macedonga.visor.kit.protocol.ToolAnswer
import com.macedonga.visor.kit.protocol.ToolCall
import com.macedonga.visor.kit.protocol.ToolSpec
import com.macedonga.visor.kit.protocol.VoiceReply
import com.macedonga.visor.kit.protocol.VoiceRequest
import com.macedonga.visor.kit.server.Phrases
import com.macedonga.visor.notify.GlassesNotification
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The questions VDK apps answer: each app's phrases while it listens, and the answers it gives. An app that is not
 * running is started for a phrase its activity declares ([VoiceLaunch]), as DAT's voice invocations start one.
 */
@Singleton
class KitVoices @Inject constructor(
    @ApplicationContext private val context: Context,
    private val grants: KitGrants,
) : AppVoices {
    private class Offer(val app: String, val label: String, val phrases: List<String>, val requests: SendChannel<VoiceRequest>) {
        val words = phrases.map(Phrases::words)
    }

    /** An activity that declares phrases, in an app granted the voice scope. */
    private class Declared(val app: String, val label: String, val activity: ComponentName, val phrases: List<String>) {
        val words = phrases.map(Phrases::words)
    }

    private class Waiting(val app: String, val reply: CompletableDeferred<AppAnswer>)

    /** The [AppClaim.key]: the offer that matched, and its phrase that did. */
    private class Claimed(val offer: Offer, val phrase: String)

    /** The [AppClaim.key] of a question that starts an app. */
    private class Launch(val declared: Declared, val phrase: String)

    private val offers = CopyOnWriteArrayList<Offer>()
    private val waiting = ConcurrentHashMap<String, Waiting>()

    // Read again at most every 30 seconds: claim runs for every question and must stay quick.
    @Volatile
    private var declared: List<Declared> = emptyList()

    @Volatile
    private var declaredAt = 0L

    /** [app]'s questions for as long as it collects them. */
    fun offer(app: String, phrases: List<String>): Flow<VoiceRequest> = channelFlow {
        val offer = Offer(app, grants.labelOf(app), phrases, channel)
        if (offer.words.all { it.isEmpty() }) throw KitException(ErrorCode.INVALID_PARAMS, "No phrase has a word in it.")
        offers += offer
        awaitClose { offers -= offer }
    }

    fun reply(app: String, reply: VoiceReply) {
        val question = waiting[reply.request]?.takeIf { it.app == app } ?: throw expired("question", reply.request)
        question.reply.complete(AppAnswer(reply.text, reply.listen))
    }

    override fun claim(question: String): AppClaim? {
        val words = Phrases.words(question)
        val running = offers.mapNotNull { o -> Phrases.best(words, o.words)?.let { o to it } }.maxByOrNull { (o, i) -> o.words[i].size }
        if (running != null) {
            val (offer, phrase) = running
            return AppClaim(offer.label, Claimed(offer, offer.phrases[phrase]))
        }
        // An app that listens now answers its own phrases; the declared ones start an app that does not.
        val listening = offers.map { it.app }.toSet()
        val (launch, phrase) = declared().filter { it.app !in listening }
            .mapNotNull { d -> Phrases.best(words, d.words)?.let { d to it } }
            .maxByOrNull { (d, i) -> d.words[i].size } ?: return null
        return AppClaim(launch.label, Launch(launch, launch.phrases[phrase]))
    }

    override suspend fun answer(claim: AppClaim, id: String, question: String, intent: String, budgetMs: Long): AppAnswer? {
        val key = claim.key
        val app = if (key is Launch) key.declared.app else (key as Claimed).offer.app
        val reply = CompletableDeferred<AppAnswer>()
        waiting[id] = Waiting(app, reply)
        try {
            val sent = when (key) {
                is Launch -> start(key.declared.activity, VoiceRequest(id, question, key.phrase, intent.ifEmpty { null }))
                is Claimed -> key.offer.requests.trySend(VoiceRequest(id, question, key.phrase, intent.ifEmpty { null })).isSuccess
                else -> false
            }
            if (!sent) return null
            return withTimeoutOrNull(budgetMs) { reply.await() }
        } finally {
            waiting.remove(id)
        }
    }

    // Visor's companion device association lets it start an activity from the background.
    private fun start(activity: ComponentName, request: VoiceRequest): Boolean = try {
        context.startActivity(
            Intent(VoiceLaunch.ACTION).setComponent(activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(VoiceLaunch.EXTRA_REQUEST, KitJson.encodeToString(VoiceRequest.serializer(), request)),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun declared(): List<Declared> {
        val now = System.currentTimeMillis()
        if (now - declaredAt < DECLARED_TTL_MS) return declared
        val pm = context.packageManager
        declared = pm.queryIntentActivities(Intent(VoiceLaunch.ACTION), PackageManager.GET_META_DATA).mapNotNull { info ->
            val activity = info.activityInfo
            if (Scope.VOICE !in grants.scopesOf(activity.packageName)) return@mapNotNull null
            val array = activity.metaData?.getInt(VoiceLaunch.PHRASES)?.takeIf { it != 0 } ?: return@mapNotNull null
            val phrases = try {
                pm.getResourcesForApplication(activity.applicationInfo).getStringArray(array).toList()
            } catch (e: Resources.NotFoundException) {
                // The meta-data names something that is not a string array.
                return@mapNotNull null
            }
            Declared(activity.packageName, grants.labelOf(activity.packageName), ComponentName(activity.packageName, activity.name), phrases)
        }
        declaredAt = now
        return declared
    }

    private companion object {
        const val DECLARED_TTL_MS = 30_000L
    }
}

/** The tools VDK apps offer the assistant's model, while they offer them, and the answers to their calls. */
@Singleton
class KitTools @Inject constructor(private val grants: KitGrants) : AppTools {
    private class Offer(val app: String, val label: String, val tools: List<ToolSpec>, val calls: SendChannel<ToolCall>)

    private class Waiting(val app: String, val answer: CompletableDeferred<ToolAnswer>)

    private val offers = CopyOnWriteArrayList<Offer>()
    private val waiting = ConcurrentHashMap<String, Waiting>()

    /** [app]'s [tools] for as long as it collects their calls. */
    fun offer(app: String, tools: List<ToolSpec>): Flow<ToolCall> = channelFlow {
        for (tool in tools) {
            if (!NAME.matches(tool.name)) throw KitException(ErrorCode.INVALID_PARAMS, "The tool name \"${tool.name}\" does not fit $NAME.")
            if ((tool.schema as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull != "object") {
                throw KitException(ErrorCode.INVALID_PARAMS, "The schema of ${tool.name} is not an object schema.")
            }
        }
        if (tools.map { it.name }.toSet().size != tools.size) throw KitException(ErrorCode.INVALID_PARAMS, "Two tools share a name.")
        val offer = Offer(app, grants.labelOf(app), tools, channel)
        offers += offer
        awaitClose { offers -= offer }
    }

    fun answer(app: String, answer: ToolAnswer) {
        val call = waiting[answer.call]?.takeIf { it.app == app } ?: throw expired("call", answer.call)
        call.answer.complete(answer)
    }

    // A name two apps both use is the first one's; the second's model name gets a number.
    override fun tools(): List<AppTool> {
        val used = HashSet<String>()
        return offers.flatMap { offer ->
            offer.tools.map { spec ->
                val name = generateSequence(spec.name) { it + "_" }.first(used::add)
                AppTool(name, offer.label, "[${offer.label}] ${spec.description}", JSONObject(spec.schema.toString())) { input ->
                    call(offer, spec.name, input)
                }
            }
        }
    }

    private suspend fun call(offer: Offer, tool: String, input: JSONObject): ToolResult {
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<ToolAnswer>()
        waiting[id] = Waiting(offer.app, answer)
        try {
            val arguments = KitJson.parseToJsonElement(input.toString())
            if (offer.calls.trySend(ToolCall(id, tool, arguments)).isFailure) return ToolResult("${offer.label} stopped offering $tool", true)
            val done = withTimeoutOrNull(TOOL_MS) { answer.await() } ?: return ToolResult("${offer.label} did not answer in time", true)
            return ToolResult(done.text, done.error)
        } finally {
            waiting.remove(id)
        }
    }

    private companion object {
        // What Claude and OpenAI-compatible APIs take as a tool name, with room for a number.
        val NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,39}")
        const val TOOL_MS = 15_000L
    }
}

/** VDK apps' notifications on the Display; glasses without a display show none of an app's. */
@Singleton
class KitNotices @Inject constructor(private val connection: GlassesConnection, private val grants: KitGrants) {
    // What each shown notification was sent with, which its removal must name.
    private val shown = ConcurrentHashMap<String, String>()

    fun post(app: String, notice: Notice) {
        ready()
        val key = keyOf(app, notice.id)
        val note = GlassesNotification(
            key = key,
            pkg = app,
            appName = grants.labelOf(app),
            title = notice.title,
            text = notice.text,
            postTime = System.currentTimeMillis(),
            type = GlassesNotification.TYPE_MIRRORED,
            provider = GlassesNotification.PROVIDER_MIRRORED,
        )
        shown[key] = note.uuid
        val envelope = note.envelope()
        connection.submit { it.sendOnService(GlassesNotification.SVC, GlassesNotification.MT_UPSERT_CHUNK, envelope) }
    }

    fun remove(app: String, id: String) {
        ready()
        val key = keyOf(app, id)
        val uuid = shown.remove(key) ?: return
        val removal = GlassesNotification.removal(key, GlassesNotification.PROVIDER_MIRRORED, uuid)
        connection.submit { it.sendOnService(GlassesNotification.SVC, GlassesNotification.MT_REMOVE, removal) }
    }

    private fun ready() {
        val state = connection.state.value
        if (state.link != LinkState.READY) throw KitException(ErrorCode.NOT_CONNECTED, "The glasses are not connected.")
        if (!isDisplayBuild(state.info?.build)) throw KitException(ErrorCode.UNSUPPORTED, "Glasses without a display show no notifications of apps.")
    }

    private fun keyOf(app: String, id: String) = "kit:$app:$id"
}

private fun expired(what: String, id: String) =
    KitException(ErrorCode.INVALID_PARAMS, "No $what $id is waiting: it was answered, or the glasses gave up on it.", reason = "expired")
