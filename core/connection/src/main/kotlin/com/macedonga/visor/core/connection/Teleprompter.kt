package com.macedonga.visor.core.connection

import com.macedonga.visor.core.protocol.SVC_TRANSLATION
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

enum class PrompterPhase { IDLE, STARTING, SHOWING, CLOSING }

/** [refusal] is the app manager's code when the glasses would not open it; [shown] and [look] what they have. */
data class PrompterState(
    val phase: PrompterPhase = PrompterPhase.IDLE,
    val refusal: Long? = null,
    val shown: String? = null,
    val look: PrompterLook? = null,
)

/** How the script shows: its size in sp on the glasses, its scroll speed (the glasses' default is 32), paged or scrolled. */
data class PrompterLook(val sizeSp: Double = 35.0, val speed: Double = 32.0, val paged: Boolean = false, val followVoice: Boolean = false)

/**
 * The Display's teleprompter, run from the phone as the Meta app runs it: svc28 StartApp (2000) for app 35 with the
 * script and its look as intent data, answered by 2001; a start while it shows replaces the text. StopApp (2002)
 * closes it, answered by 2003. Following the voice is the glasses' own `asr_enabled`, which the Meta app never sends.
 * The glasses do not say when the wearer closes it there; captions opening is seen, on svc72.
 */
class Teleprompter(private val connection: GlassesConnection) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(PrompterState())
    val state: StateFlow<PrompterState> = _state.asStateFlow()
    private var wait: Job? = null

    init {
        scope.launch {
            connection.serviceData.collect { data ->
                // Captions or translation opening on the glasses takes the screen from it.
                if (data.serviceId == SVC_TRANSLATION && data.messageType == MT_INVITE_TO_JOIN && _state.value.phase == PrompterPhase.SHOWING) {
                    wait?.cancel()
                    _state.value = PrompterState()
                }
                if (data.serviceId != SVC_STATUS) return@collect
                when (data.messageType) {
                    // Other apps' starts are answered here too; only the one being waited for counts.
                    SnApp.MT_START_APP_RESPONSE -> if (_state.value.phase == PrompterPhase.STARTING) {
                        val code = runCatching { Wire.parse(data.payload).varint(1) }.getOrDefault(0L)
                        wait?.cancel()
                        _state.update {
                            if (SnApp.started(code)) it.copy(phase = PrompterPhase.SHOWING, refusal = null)
                            else PrompterState(refusal = code)
                        }
                    }
                    SnApp.MT_STOP_APP_RESPONSE -> if (_state.value.phase == PrompterPhase.CLOSING) {
                        wait?.cancel()
                        _state.value = PrompterState()
                    }
                }
            }
        }
        scope.launch {
            connection.state.map { it.link == LinkState.READY }.distinctUntilChanged().collect { up -> if (!up) _state.value = PrompterState() }
        }
    }

    /** Put [text] on the glasses, or replace what they show. */
    fun show(text: String, look: PrompterLook) {
        if (connection.state.value.link != LinkState.READY) return
        val data = listOfNotNull(
            SnApp.data("text", text), SnApp.data("layout_type", if (look.paged) LAYOUT_PAGED else LAYOUT_SCROLL),
            SnApp.data("text_size_sp", look.sizeSp), SnApp.data("scroll_speed", look.speed),
            SnApp.data("session_id", UUID.randomUUID().toString()), SnApp.data("app_type", APP_TYPE_NATIVE),
            if (look.followVoice) SnApp.data("asr_enabled", 1) else null,
        )
        _state.update { it.copy(phase = PrompterPhase.STARTING, refusal = null, shown = text, look = look) }
        connection.submit { it.sendOnService(SVC_STATUS, SnApp.MT_START_APP, SnApp.app(APP_TELEPROMPTER, data)) }
        // No answer: the start went out, and the glasses may still be drawing it.
        settleAfter { if (it.phase == PrompterPhase.STARTING) it.copy(phase = PrompterPhase.SHOWING) else it }
    }

    fun close() {
        if (_state.value.phase == PrompterPhase.IDLE) return
        _state.update { it.copy(phase = PrompterPhase.CLOSING) }
        connection.submit { it.sendOnService(SVC_STATUS, SnApp.MT_STOP_APP, SnApp.app(APP_TELEPROMPTER)) }
        settleAfter { if (it.phase == PrompterPhase.CLOSING) PrompterState() else it }
    }

    private fun settleAfter(change: (PrompterState) -> PrompterState) {
        wait?.cancel()
        wait = scope.launch {
            delay(ANSWER_MS)
            _state.update(change)
        }
    }

    private companion object {
        const val MT_INVITE_TO_JOIN = 2
        const val APP_TELEPROMPTER = 35L
        const val LAYOUT_SCROLL = 0
        const val LAYOUT_PAGED = 1
        const val APP_TYPE_NATIVE = 0
        const val ANSWER_MS = 7_000L
    }
}
