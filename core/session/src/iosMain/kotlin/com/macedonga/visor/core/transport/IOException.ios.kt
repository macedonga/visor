package com.macedonga.visor.core.transport

actual open class IOException actual constructor(message: String?, cause: Throwable?) : Exception(message, cause)
