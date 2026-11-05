package com.macedonga.visor.core.settings

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder

/** svc33, RLDrive: the replicated store the glasses keep their settings in. */
const val SVC_RLDRIVE = 33
const val MT_SYNC_MESSAGE = 1

private const val TYPE_SYNC_MESSAGE = 1L
private const val TYPE_CHUNKED_SYNC_MESSAGE = 5L
private const val API_VERSION = 1L

const val TYPE_KV = "kv"
private val ALL_TYPES = listOf(TYPE_KV, "sync_metadata", "media")

// Our writer id, shaped like the glasses' own (a uuid). Reads claim it at version 0; writes count it up.
const val LOCAL_WRITER = "5f1c0de0-7615-4a0e-9d1e-000000000001"

/** Writer id to version, per object type. */
typealias Versions = Map<String, Map<String, Long>>

/** An object to hand the glasses as another replica of a namespace would: [data] is the type's own encoding. */
class PushObject(val uri: String, val type: String, val data: ByteArray, val deleted: Boolean = false)

/** [raw] is the whole InternalObject as it came, for looking at what this code does not decode. */
class DriveObject(val uri: String, val type: String, val data: String, val deleted: Boolean, val raw: ByteArray = ByteArray(0), val bytes: ByteArray = ByteArray(0))

data class SyncReply(
    val namespace: String,
    val round: Long,
    val terminated: Boolean,
    val requestId: String,
    val versions: Versions,
    val objects: List<DriveObject>,
    // The use case tag of a sync the sender started.
    val usecaseTag: String,
)

/**
 * One SYNC_MESSAGE. We claim [known] (their writers at the versions last seen) plus ourselves at 0:
 * nothing known asks for everything, the versions from last time ask for what changed since.
 */
fun buildSync(
    namespace: String,
    requestId: String,
    round: Long,
    known: Versions = emptyMap(),
    types: Collection<String> = (ALL_TYPES + known.keys).distinct(),
    // Objects to hand the glasses in round 2, as another replica of the namespace would.
    objects: List<PushObject> = emptyList(),
    terminated: Boolean = false,
    usecaseTag: String = "",
): ByteArray {
    var sync = ByteArray(0)
    for (type in (types + objects.map { it.type }).distinct()) {
        val theirs = known[type].orEmpty()
        val base = theirs[LOCAL_WRITER] ?: 0L
        val mine = if (round == 0L) emptyList() else objects.filter { it.type == type }
        // Each object we add is one step of our writer's counter.
        val own = base + mine.size
        val writers = theirs + (LOCAL_WRITER to (if (round == 0L) 0L else own))
        var typeData = ByteArray(0)
        for ((writer, version) in writers) {
            typeData += Wire.fieldBytes(11, Wire.fieldString(1, writer) + Wire.fieldVarint(2, version))
        }
        typeData += Wire.fieldString(12, type)
        mine.forEachIndexed { i, o ->
            val obj = Wire.fieldString(1, o.uri) + Wire.fieldString(2, o.type) + Wire.fieldBytes(3, o.data) +
                (if (o.deleted) Wire.fieldVarint(8, 1) else ByteArray(0))
            val version = Wire.fieldString(1, LOCAL_WRITER) + Wire.fieldVarint(2, base + i + 1)
            typeData += Wire.fieldBytes(13, Wire.fieldBytes(6, obj) + Wire.fieldBytes(7, version))
        }
        sync += Wire.fieldBytes(16, typeData)
    }
    sync += Wire.fieldVarint(17, round) +
        Wire.fieldString(18, requestId) +
        Wire.fieldVarint(19, API_VERSION) +
        Wire.fieldString(20, namespace) +
        Wire.fieldString(21, LOCAL_WRITER) +
        (if (terminated) Wire.fieldVarint(22, 1) else ByteArray(0)) +
        (if (usecaseTag.isNotEmpty()) Wire.fieldString(31, usecaseTag) else ByteArray(0))
    return Wire.fieldVarint(1, TYPE_SYNC_MESSAGE) + Wire.fieldBytes(2, sync) + Wire.fieldString(6, namespace)
}

/**
 * [message] as the glasses send theirs: whole up to a kilobyte of SyncMessage, beyond that
 * CHUNKED_SYNC_MESSAGE slices tied by an id and counted from 0.
 */
fun chunks(message: ByteArray, max: Int = 1024): List<ByteArray> {
    val outer = Wire.parse(message)
    val data = outer.bytes(2)
    if (data.size <= max) return listOf(message)
    val id = java.util.UUID.randomUUID().toString()
    val slices = (data.indices step max).map { data.copyOfRange(it, minOf(it + max, data.size)) }
    return slices.mapIndexed { i, slice ->
        Wire.fieldVarint(1, TYPE_CHUNKED_SYNC_MESSAGE) + Wire.fieldBytes(2, slice) + Wire.fieldString(5, id) +
            Wire.fieldBytes(6, outer.bytes(6)) + Wire.fieldVarint(7, slices.size.toLong()) + Wire.fieldVarint(8, i.toLong())
    }
}

private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

fun decodeSync(payload: ByteArray): SyncReply {
    val outer = Wire.parse(payload)
    val sync = Wire.parse(outer.bytes(2))
    val versions = HashMap<String, Map<String, Long>>()
    val objects = ArrayList<DriveObject>()
    for (blob in sync.repeatedBytes(16)) {
        val typeData = Wire.parse(blob)
        val type = text(typeData.bytes(12))
        versions[type] = typeData.repeatedBytes(11).associate { Wire.parse(it).let { e -> text(e.bytes(1)) to e.varint(2) } }
        for (internal in typeData.repeatedBytes(13)) {
            val obj = Wire.parse(Wire.parse(internal).bytes(6))
            if (!obj.has(1)) continue
            objects += DriveObject(text(obj.bytes(1)), text(obj.bytes(2)), text(obj.bytes(3)), obj.varint(8) != 0L, internal, obj.bytes(3))
        }
    }
    return SyncReply(
        namespace = text(outer.bytes(6)).ifEmpty { text(sync.bytes(20)) },
        round = sync.varint(17),
        terminated = sync.varint(22) != 0L,
        requestId = text(sync.bytes(18)),
        versions = versions,
        objects = objects,
        usecaseTag = text(sync.bytes(31)),
    )
}

/**
 * Reassembles chunked replies and hands each whole one to [handler]. A reply over about a kilobyte
 * comes as CHUNKED_SYNC_MESSAGE slices of one serialized SyncMessage, tied by field 5 and counted
 * in 7 and 8.
 */
class RlDriveResponder : Responder {

    @Volatile
    var handler: (suspend (SyncReply, DataXConnection) -> Unit)? = null

    private val parts = HashMap<String, MutableMap<Long, ByteArray>>()

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.messageType != MT_SYNC_MESSAGE) return
        val whole = assemble(message.payload) ?: return
        handler?.invoke(decodeSync(whole), connection)
    }

    private fun assemble(payload: ByteArray): ByteArray? {
        val f = Wire.parse(payload)
        val total = f.varint(7)
        if (f.varint(1) != TYPE_CHUNKED_SYNC_MESSAGE || total <= 1) return payload
        val id = text(f.bytes(5))
        val chunks = parts.getOrPut(id) { HashMap() }
        chunks[f.varint(8)] = f.bytes(2)
        if (chunks.size < total) return null
        parts.remove(id)
        val data = chunks.toSortedMap().values.fold(ByteArray(0)) { acc, b -> acc + b }
        return Wire.fieldVarint(1, TYPE_SYNC_MESSAGE) + Wire.fieldBytes(2, data) + Wire.fieldBytes(6, f.bytes(6))
    }
}
