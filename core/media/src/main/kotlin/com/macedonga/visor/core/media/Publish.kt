package com.macedonga.visor.core.media

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Clears [uri]'s pending flag and reads the row back. A row still pending is hidden from every app, this one too,
 * and the system deletes it within a week, so that is an error, not a save. Returns where the file is, its size and
 * its date as MediaStore keeps them, for the log.
 */
internal fun publish(resolver: ContentResolver, uri: Uri): String {
    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
    repeat(2) {
        resolver.update(uri, done, null, null)
        // A plain query leaves pending rows out.
        resolver.query(uri, ROW, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val taken = c.getLong(3).takeIf { it > 0 }?.let { " taken " + TAKEN.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: ", no date"
                return "${c.getString(0).orEmpty()}${c.getString(1).orEmpty()}, ${sizeWords(c.getLong(2))}$taken"
            }
        }
    }
    error("the gallery kept it hidden")
}

private val ROW = arrayOf(
    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME,
    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_TAKEN,
)

private val TAKEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")
