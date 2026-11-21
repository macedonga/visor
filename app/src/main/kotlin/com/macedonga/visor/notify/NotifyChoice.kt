package com.macedonga.visor.notify

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone's notifications go to the glasses at all, on unless the person turns it off,
 * and whether only while the phone is locked.
 */
@Singleton
class NotifyChoice @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("notify", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    // Off unless asked: someone using the phone already sees what came in.
    private val _onlyLocked = MutableStateFlow(prefs.getBoolean(KEY_LOCKED, false))
    val onlyLocked: StateFlow<Boolean> = _onlyLocked.asStateFlow()

    fun set(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(KEY, on).apply()
    }

    fun setOnlyLocked(on: Boolean) {
        _onlyLocked.value = on
        prefs.edit().putBoolean(KEY_LOCKED, on).apply()
    }

    private companion object {
        const val KEY = "mirror"
        const val KEY_LOCKED = "only_locked"
    }
}
