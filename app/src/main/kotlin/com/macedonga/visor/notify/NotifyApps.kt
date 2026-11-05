package com.macedonga.visor.notify

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which apps' notifications reach the glasses: every app does unless it is muted here, so a new app is heard from
 * the start. Remembers when each app last posted something the glasses would take, to list the busy ones first.
 */
@Singleton
class NotifyApps @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("notify_apps", Context.MODE_PRIVATE)
    private val _muted = MutableStateFlow(prefs.getStringSet(MUTED, emptySet()).orEmpty().toSet())
    val muted: StateFlow<Set<String>> = _muted.asStateFlow()

    private val _seen = MutableStateFlow(
        prefs.getStringSet(SEEN, emptySet()).orEmpty().mapNotNull { e ->
            e.substringBefore('|').takeIf { it.isNotEmpty() }?.let { it to (e.substringAfter('|').toLongOrNull() ?: 0L) }
        }.toMap(),
    )
    val seen: StateFlow<Map<String, Long>> = _seen.asStateFlow()

    fun set(pkg: String, on: Boolean) = setAll(listOf(pkg), on)

    fun setAll(pkgs: Collection<String>, on: Boolean) {
        _muted.value = if (on) _muted.value - pkgs.toSet() else _muted.value + pkgs
        prefs.edit().putStringSet(MUTED, _muted.value).apply()
    }

    fun restore(muted: Set<String>) {
        _muted.value = muted
        prefs.edit().putStringSet(MUTED, muted).apply()
    }

    /** [pkg] posted something the glasses would take; written at most once a minute per app. */
    fun saw(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - (_seen.value[pkg] ?: 0L) < MINUTE) return
        _seen.value = _seen.value + (pkg to now)
        prefs.edit().putStringSet(SEEN, _seen.value.map { (p, t) -> "$p|$t" }.toSet()).apply()
    }

    private companion object {
        const val MUTED = "muted"
        const val SEEN = "seen"
        const val MINUTE = 60_000L
    }
}
