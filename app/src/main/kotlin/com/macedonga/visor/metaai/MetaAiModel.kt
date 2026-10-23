package com.macedonga.visor.metaai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.assistant.DayLines
import com.macedonga.visor.core.assistant.Memory
import com.macedonga.visor.core.assistant.TurnRecord
import com.macedonga.visor.core.assistant.TurnSource
import com.macedonga.visor.core.assistant.date
import com.macedonga.visor.core.connection.GlassesConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One conversation as a day shows it: its turns of that day, in order. [startedOn] is set when it began on an earlier day. */
data class DiaryConversation(val id: String, val turns: List<TurnRecord>, val live: Boolean, val startedOn: LocalDate?)

/** One day: its lines, and its conversations, newest first. */
data class DiaryDay(val date: LocalDate, val lines: DayLines?, val conversations: List<DiaryConversation>) {
    val questions: Int get() = conversations.sumOf { it.turns.size }
    /** The photos the glasses took for its questions, in order; Live AI's frames stay in its block. */
    val photos: List<TurnRecord> get() = conversations.filterNot { it.live }.flatMap { it.turns }.filter { it.image.isNotEmpty() }.sortedBy { it.at }
}

@HiltViewModel
class MetaAiModel @Inject constructor(
    val assistant: Assistant,
    val connection: GlassesConnection,
    val store: AssistantConfigStore,
) : ViewModel() {

    private val diary = assistant.diary
    val turns: StateFlow<List<TurnRecord>> = diary.turns
    val memories: StateFlow<List<Memory>> = diary.memories

    val days: StateFlow<List<DiaryDay>> = combine(diary.turns, diary.lines, ::daysOf)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, daysOf(diary.turns.value, diary.lines.value))

    private val _deleted = MutableStateFlow<List<TurnRecord>>(emptyList())
    /** What was just deleted, while it can still be brought back. */
    val deleted: StateFlow<List<TurnRecord>> = _deleted.asStateFlow()
    private var finishing: Job? = null

    private val _forgotten = MutableStateFlow<Memory?>(null)
    val forgotten: StateFlow<Memory?> = _forgotten.asStateFlow()
    private var forgetting: Job? = null

    fun ask(question: String, conversation: String?) = assistant.askFromPhone(question, conversation)

    fun askAgain(id: String) = assistant.askAgain(id)

    fun writeLines(date: LocalDate) = assistant.writeLines(date)

    fun delete(ids: Set<String>) {
        finishDelete()
        val gone = diary.delete(ids)
        if (gone.isEmpty()) return
        _deleted.value = gone
        finishing = viewModelScope.launch {
            delay(UNDO_MS)
            finishDelete()
        }
    }

    fun undoDelete() {
        finishing?.cancel()
        diary.restore(_deleted.value)
        _deleted.value = emptyList()
    }

    /** The deletion stands: its photos go, and the days it touched are written again. */
    private fun finishDelete() {
        finishing?.cancel()
        val gone = _deleted.value
        if (gone.isEmpty()) return
        _deleted.value = emptyList()
        diary.forgetPhotos(gone)
        gone.map { it.date }.toSet().forEach(assistant::writeLines)
    }

    fun remember(text: String) {
        if (text.isNotBlank()) diary.remember(text)
    }

    fun forget(memory: Memory) {
        diary.forget(memory.id) ?: return
        _forgotten.value = memory
        forgetting?.cancel()
        forgetting = viewModelScope.launch {
            delay(UNDO_MS)
            _forgotten.value = null
        }
    }

    fun unforget() {
        forgetting?.cancel()
        _forgotten.value?.let(diary::restoreMemory)
        _forgotten.value = null
    }

    override fun onCleared() = finishDelete()

    companion object {
        const val UNDO_MS = 5_000L

        fun daysOf(turns: List<TurnRecord>, lines: Map<LocalDate, DayLines>): List<DiaryDay> {
            val firstDay = HashMap<String, LocalDate>()
            for (t in turns) firstDay.putIfAbsent(t.conversation, t.date)
            return turns.groupBy { it.date }.toSortedMap(reverseOrder()).map { (date, list) ->
                val conversations = list.groupBy { it.conversation.ifEmpty { it.interactionId } }
                    .map { (id, ts) -> DiaryConversation(id, ts, ts.first().source == TurnSource.LIVE_AI, firstDay[id]?.takeIf { it != date }) }
                    .sortedByDescending { it.turns.last().at }
                DiaryDay(date, lines[date], conversations)
            }
        }
    }
}
