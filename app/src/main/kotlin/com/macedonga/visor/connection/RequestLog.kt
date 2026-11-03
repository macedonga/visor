package com.macedonga.visor.connection

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One request the glasses had the phone make: [outcome] stays null while it runs. */
data class GlassesRequest(
    val id: Long,
    val at: Long,
    val url: String,
    val source: Int,
    /** What was asked besides the link: image size and format, compression. */
    val options: String = "",
    val outcome: String? = null,
    val failed: Boolean = false,
    val bytes: Long = 0,
    val millis: Long = 0,
    /** How many times in a row the same request ended the same way. */
    val times: Int = 1,
)

/** The requests the glasses made through the phone, newest last, in memory only: their links can carry tokens. */
@Singleton
class RequestLog @Inject constructor() {
    private val ids = AtomicLong()
    private val _items = MutableStateFlow<List<GlassesRequest>>(emptyList())
    val items: StateFlow<List<GlassesRequest>> = _items.asStateFlow()

    fun start(url: String, source: Int, options: String = ""): Long {
        val id = ids.incrementAndGet()
        _items.update { (it + GlassesRequest(id, System.currentTimeMillis(), url, source, options)).takeLast(MAX) }
        return id
    }

    fun finish(id: Long, outcome: String, failed: Boolean, bytes: Long = 0) {
        _items.update { list ->
            val done = list.map { if (it.id == id) it.copy(outcome = outcome, failed = failed, bytes = bytes, millis = System.currentTimeMillis() - it.at) else it }
            // The same request ending the same way as the one before it is counted on that one, not listed again.
            val at = done.indexOfFirst { it.id == id }
            val earlier = done.subList(0, maxOf(at, 0)).lastOrNull { it.url == done[at].url }
            if (at > 0 && earlier != null && earlier.outcome == outcome && earlier.failed == failed) {
                done.mapNotNull { e ->
                    when (e.id) {
                        id -> null
                        earlier.id -> e.copy(times = e.times + 1, at = done[at].at)
                        else -> e
                    }
                }
            } else done
        }
    }

    /** A better name for what an entry reached, such as the server a TLS connection asked for. */
    fun rename(id: Long, url: String) {
        _items.update { list -> list.map { if (it.id == id) it.copy(url = url) else it } }
    }

    fun clear() {
        _items.value = emptyList()
    }

    private companion object {
        const val MAX = 500
    }
}
