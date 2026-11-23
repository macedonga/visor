package com.macedonga.visor.core.protocol

import java.nio.ByteBuffer
import java.util.UUID

/** The uuid's 16 bytes, most significant first. */
fun UUID.toBytes(): ByteArray = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
