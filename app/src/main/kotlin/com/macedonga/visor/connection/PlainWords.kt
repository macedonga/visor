package com.macedonga.visor.connection

import com.macedonga.visor.core.identity.Diagnosis
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.transport.ConnectFailure

/** The link's values in plain words, for the event log. */
fun LinkState.words(): String = when (this) {
    LinkState.DISCONNECTED -> "not connected"
    LinkState.CONNECTING -> "reaching the glasses"
    LinkState.LINK_SETUP -> "setting up the encrypted link"
    LinkState.TRUST -> "proving this phone's key"
    LinkState.READY -> "ready"
    LinkState.LOST -> "lost them, trying again"
}

fun ConnectFailure.words(): String = when (this) {
    ConnectFailure.BLUETOOTH_UNSUPPORTED -> "this phone has no Bluetooth"
    ConnectFailure.BLUETOOTH_OFF -> "Bluetooth is off"
    ConnectFailure.PERMISSION_REFUSED -> "the app is not allowed to use Bluetooth"
    ConnectFailure.BAD_ADDRESS -> "the stored Bluetooth address is not valid"
    ConnectFailure.NOT_PAIRED -> "the glasses are not paired with this phone"
    ConnectFailure.NO_ANSWER -> "nothing answered: asleep, in the case or out of range"
}

fun Diagnosis.words(): String = when (this) {
    Diagnosis.HEALTHY -> "the glasses accepted this phone's key and answered"
    Diagnosis.EVICTED -> "the glasses answered but no longer accept this phone's key"
    Diagnosis.LINK_DOWN -> "nothing answered, so nothing is known about the key"
    Diagnosis.UNKNOWN -> "the key was accepted but the glasses did not answer the check"
}
