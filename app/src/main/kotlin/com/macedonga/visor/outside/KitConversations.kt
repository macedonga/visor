@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import com.macedonga.visor.core.assistant.Diary
import com.macedonga.visor.core.assistant.TurnRecord
import com.macedonga.visor.kit.KitException
import com.macedonga.visor.kit.protocol.AssistantTurn
import com.macedonga.visor.kit.protocol.ErrorCode
import com.macedonga.visor.kit.protocol.HistoryQuery
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.ToolUse
import com.macedonga.visor.kit.protocol.TurnSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.macedonga.visor.core.assistant.TurnSource as DiarySource

/** The assistant's turns for VDK apps, from the diary Visor keeps on the phone. */
@Singleton
class KitConversations @Inject constructor(private val diary: Diary) {
    fun history(query: HistoryQuery): List<AssistantTurn> {
        if (query.limit !in 1..HistoryQuery.MAX_LIMIT) {
            throw KitException(ErrorCode.INVALID_PARAMS, "The limit must be 1 to ${HistoryQuery.MAX_LIMIT}.")
        }
        val since = query.since
        return diary.turns.value.asReversed().asSequence()
            .filter { since == null || it.at > since }
            .take(query.limit)
            .map(::kitTurn)
            .toList()
    }

    /** Each turn once it is answered or failed, and again when a new answer replaces it. */
    fun turns(): Flow<AssistantTurn> = flow {
        var before: Map<String, Pair<String, String>>? = null
        diary.turns.collect { list ->
            val now = list.associate { it.interactionId to (it.answer to it.problem) }
            before?.let { old ->
                for (turn in list) {
                    val outcome = now.getValue(turn.interactionId)
                    if ((outcome.first.isNotEmpty() || outcome.second.isNotEmpty()) && old[turn.interactionId] != outcome) emit(kitTurn(turn))
                }
            }
            before = now
        }
    }

    private fun kitTurn(t: TurnRecord) = AssistantTurn(
        id = t.interactionId,
        time = t.at,
        question = t.question,
        answer = t.answer,
        failure = t.problem,
        source = when (t.source) {
            DiarySource.GLASSES -> TurnSource.GLASSES
            DiarySource.PHONE -> TurnSource.PHONE
            DiarySource.LIVE_AI -> TurnSource.LIVE_AI
        },
        conversation = t.conversation,
        photo = t.image.isNotEmpty(),
        tools = t.actions.map { ToolUse(it.server, it.what, it.ok) },
    )
}
