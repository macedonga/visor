package com.macedonga.visor.core.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.LinkState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CaptionsPhase { IDLE, STARTING, LIVE, STOPPING }

enum class CaptionsMode { CAPTIONS, TRANSLATION }

/** One speech turn as the glasses last sent it: [text] to read, [source] what was said when it is a translation. [time] is when it arrived. */
data class CaptionLine(val id: String, val text: String, val source: String?, val done: Boolean, val time: Long)

/** [refusal] is the app manager's code when the glasses would not start it. [earlier] is the turns of the session before. */
data class CaptionsState(
    val phase: CaptionsPhase = CaptionsPhase.IDLE,
    val mode: CaptionsMode = CaptionsMode.CAPTIONS,
    val refusal: Long? = null,
    val lines: List<CaptionLine> = emptyList(),
    val earlier: List<CaptionLine> = emptyList(),
)

/**
 * The Display's live captions and translation, started as the Meta app starts them: svc28 StartApp (2000) for app 31,
 * TRANSLATION, with `action.lst_lc.action` and the two languages as intent data, closed with StopApp (2002) and the
 * matching stop action. The glasses recognise the speech themselves and send each turn back on svc72 as a
 * TwoWayTranslation; they say so when the wearer stops it there.
 */
class Captions(private val connection: GlassesConnection) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(CaptionsState())
    val state: StateFlow<CaptionsState> = _state.asStateFlow()
    private var wait: Job? = null

    init {
        scope.launch {
            connection.serviceData.collect { data ->
                when (data.serviceId) {
                    SVC_COMPANION -> fromAppManager(data.messageType, runCatching { Wire.parse(data.payload).varint(1) }.getOrDefault(0L))
                    SVC_TRANSLATION -> runCatching { fromSession(data.messageType, Wire.parse(data.payload)) }
                }
            }
        }
        scope.launch {
            connection.state.map { it.link == LinkState.READY }.distinctUntilChanged().collect { up ->
                if (!up) _state.update { it.copy(phase = CaptionsPhase.IDLE) }
            }
        }
    }

    /** Start [mode] for a wearer speaking [self] with others speaking [other], codes such as "en"; the same for captions. */
    fun start(mode: CaptionsMode, self: String, other: String) {
        if (connection.state.value.link != LinkState.READY) return
        val data = intent("action.lst_lc.self_lang", self) + intent("action.lst_lc.other_lang", other) +
            intent("action.lst_lc.request_id", UUID.randomUUID().toString()) + intent("action.lst_lc.action", action(mode, "start"))
        _state.value = _state.value.restart(CaptionsPhase.STARTING, mode)
        connection.submit { it.sendOnService(SVC_COMPANION, MT_START_APP, Wire.fieldVarint(1, APP_TRANSLATION) + data) }
        settleAfter { if (it.phase == CaptionsPhase.STARTING) it.copy(phase = CaptionsPhase.LIVE) else it }
    }

    fun stop() {
        val now = _state.value
        if (now.phase == CaptionsPhase.IDLE) return
        _state.update { it.copy(phase = CaptionsPhase.STOPPING) }
        val data = intent("action.lst_lc.action", action(now.mode, "stop")) + intent("action.lst_lc.request_id", UUID.randomUUID().toString())
        connection.submit { it.sendOnService(SVC_COMPANION, MT_STOP_APP, Wire.fieldVarint(1, APP_TRANSLATION) + data) }
        settleAfter { if (it.phase == CaptionsPhase.STOPPING) it.copy(phase = CaptionsPhase.IDLE) else it }
    }

    // Other apps' starts and stops are answered here too; only the one being waited for counts.
    private fun fromAppManager(type: Int, code: Long) {
        val phase = _state.value.phase
        if (type == MT_START_APP_RESPONSE && phase == CaptionsPhase.STARTING) {
            wait?.cancel()
            _state.update {
                if (code == Teleprompter.SNAPP_SUCCESS || code == Teleprompter.SNAPP_ALREADY_STARTED) it.copy(phase = CaptionsPhase.LIVE)
                else it.copy(phase = CaptionsPhase.IDLE, refusal = code)
            }
        } else if (type == MT_STOP_APP_RESPONSE && phase == CaptionsPhase.STOPPING) {
            wait?.cancel()
            _state.update { it.copy(phase = CaptionsPhase.IDLE) }
        }
    }

    private fun fromSession(type: Int, fields: Wire.Fields) {
        when (type) {
            MT_TWO_WAY_TRANSLATION -> turn(fields, _state.value.mode)?.let { line ->
                _state.update { now ->
                    val at = now.lines.indexOfLast { it.id == line.id }
                    val lines = if (at >= 0) now.lines.toMutableList().also { it[at] = line.copy(time = now.lines[at].time) } else (now.lines + line).takeLast(MAX_LINES)
                    now.copy(phase = if (now.phase == CaptionsPhase.IDLE) CaptionsPhase.LIVE else now.phase, lines = lines)
                }
            }
            // Also how a session the wearer started on the glasses shows here. Its category is 1 for captions as well.
            MT_INVITE_TO_JOIN -> _state.update {
                if (it.phase == CaptionsPhase.IDLE) it.restart(CaptionsPhase.LIVE) else it.copy(phase = CaptionsPhase.LIVE)
            }
            MT_SESSION_STATUS -> if (fields.varint(1) == STATUS_IDLE) ended()
            MT_END_FROM_FRAME, MT_STOPPING_FROM_FRAME -> ended()
        }
    }

    // The turns so far become the earlier session's, so a restart keeps the transcript.
    private fun CaptionsState.restart(phase: CaptionsPhase, mode: CaptionsMode = CaptionsMode.CAPTIONS) =
        CaptionsState(phase, mode, earlier = lines.ifEmpty { earlier })

    // A start makes the glasses end the session before it first; that end is not this one's.
    private fun ended() {
        if (_state.value.phase == CaptionsPhase.STARTING) return
        wait?.cancel()
        _state.update { it.copy(phase = CaptionsPhase.IDLE) }
    }

    /**
     * Fields 12 and 11 are what was heard, final then partial, as it is recognised; 10 and 9 (and 4 and 5) what the
     * Display shows, formatted or translated, with "..." while that catches up. A partial goes on with its own space.
     */
    private fun turn(f: Wire.Fields, mode: CaptionsMode): CaptionLine? {
        fun s(fn: Int) = f.bytes(fn).decodeToString()
        fun shown(done: Int, partial: Int) = (s(done) + s(partial).takeUnless { it.trim() == PENDING }.orEmpty()).trim()
        val heard = (s(12) + s(11)).trim()
        val shown = shown(10, 9).ifEmpty { shown(4, 5) }.ifEmpty { s(2).trim() }
        // As the glasses show it; what was heard stands in until their text comes.
        val text = shown.ifEmpty { if (mode == CaptionsMode.CAPTIONS) heard else PENDING }
        if (text.isEmpty() || text == PENDING && heard.isEmpty()) return null
        val id = s(1).ifEmpty { "t${f.varint(6)}" }
        val done = f.varint(13) != 0L || s(11).isEmpty() && s(9).isEmpty() && s(5).isEmpty()
        return CaptionLine(id, text, heard.takeIf { mode == CaptionsMode.TRANSLATION && it.isNotEmpty() && it != text }, done, System.currentTimeMillis())
    }

    private fun settleAfter(change: (CaptionsState) -> CaptionsState) {
        wait?.cancel()
        wait = scope.launch {
            delay(ANSWER_MS)
            _state.update(change)
        }
    }

    private fun action(mode: CaptionsMode, verb: String) =
        if (mode == CaptionsMode.TRANSLATION) "action.speech_translation.$verb" else "action.speech_caption.$verb"

    private fun intent(key: String, value: String): ByteArray =
        Wire.fieldBytes(3, Wire.fieldBytes(1, key.encodeToByteArray()) + Wire.fieldBytes(5, value.encodeToByteArray()))

    private companion object {
        const val SVC_COMPANION = 28
        const val SVC_TRANSLATION = 72
        const val MT_START_APP = 2000
        const val MT_START_APP_RESPONSE = 2001
        const val MT_STOP_APP = 2002
        const val MT_STOP_APP_RESPONSE = 2003
        const val MT_TWO_WAY_TRANSLATION = 1
        const val MT_INVITE_TO_JOIN = 2
        const val MT_END_FROM_FRAME = 4
        const val MT_SESSION_STATUS = 17
        const val MT_STOPPING_FROM_FRAME = 24
        const val STATUS_IDLE = 0L
        const val APP_TRANSLATION = 31L
        const val MAX_LINES = 300
        const val PENDING = "..."
        const val ANSWER_MS = 7_000L
    }
}
