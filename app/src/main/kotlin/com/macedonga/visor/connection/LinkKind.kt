package com.macedonga.visor.connection

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which Bluetooth link the session rides on: RFCOMM, or the LE channel the glasses keep while asleep. */
@Singleton
class LinkKind @Inject constructor() {
    private val _overLe = MutableStateFlow(false)
    val overLe: StateFlow<Boolean> = _overLe.asStateFlow()

    fun set(le: Boolean) {
        _overLe.value = le
    }
}
