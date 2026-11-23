package com.macedonga.visor.core.connection

import com.macedonga.visor.core.transport.Transport

/** Builds the byte pipe for a link. */
fun interface TransportFactory {

    fun create(link: GlassesLink): Transport
}
