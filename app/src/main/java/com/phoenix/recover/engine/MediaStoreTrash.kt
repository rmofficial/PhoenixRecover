package com.phoenix.recover.engine

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * Reads Android's own "recently deleted" MediaStore bin. On Android 11+ files deleted by
 * apps that use the MediaStore trash request stay here for up to 30 days — often the only
 * place a freshly deleted camera photo still exists for a non-rooted recoverer.
 */
object MediaStoreTrash {

    fun find(context: Context): List<RecoveredFile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val out = LinkedHashMap<String, RecoveredFile>()
        val volumes = runCatching { MediaStore.getExternalVolumeNames(context) }
            .getOrDefault(setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        for (volume in volumes) {
            val collections = listOf(
                MediaStore.Files.getContentUri(volume),
                MediaStore.Images.Media.getContentUri(volume),
                MediaStore.Video.Media.getContentUri(volume),
                MediaStore.Audio.Media.getContentUri(volume),
            )
            val variants = listOf(
                Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                    )
                },
                Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.MediaColumns.IS_TRASHED} = 1",
                    )
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                    )
                },
            )
            for (collection in collections) {
                val beforeCollection = out.size
                for (args in variants) {
                    val before = out.size
                    try {
                        resolver.query(collection, projection, args, null)?.use { cursor ->
                            readRows(cursor, collection, volume, out)
                        }
                    } catch (_: Exception) {
                        // Provider rejected this query shape — try the next variant.
                    }
                    if (out.size > before) break
                }
                // Some OEM providers reject the Bundle query args but still accept the
                // legacy selection form. Fall back to it before giving up on this table.
                if (out.size == beforeCollection) {
                    try {
                        resolver.query(
                            collection,
                            projection,
                            "${MediaStore.MediaColumns.IS_TRASHED} = 1",
                            null,
                            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                        )?.use { cursor -> readRows(cursor, collection, volume, out) }
                    } catch (_: Exception) {
                        // Table unavailable — move on.
                    }
                }
            }
        }
        return out.values.toList()
    }

    private fun readRows(
        cursor: android.database.Cursor,
        collection: android.net.Uri,
        volume: String,
        out: MutableMap<String, RecoveredFile>,
    ) {
        val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
        val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
        if (idIdx < 0) return
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIdx)
            val rawName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
            val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
            val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
            val data = if (dataIdx >= 0) cursor.getString(dataIdx) else null
            val displayName = cleanName(rawName ?: data?.substringAfterLast('/') ?: "recovered_$id")
            val detected = Sniffer.fromName(displayName)
                ?: mime?.let { detectedFromMime(it, displayName) }
                ?: continue
            // The SIZE column is often 0 for trashed rows even though the underlying
            // file is intact, so fall back to the real file length before dropping it.
            val effectiveSize = when {
                size > 0L -> size
                data != null -> runCatching { File(data).length() }.getOrDefault(0L)
                else -> 0L
            }
            if (effectiveSize <= 0L) continue
            val uri = android.content.ContentUris.withAppendedId(collection, id).toString()
            val file = RecoveredFile(
                id = "trash:media:$volume:$id",
                name = displayName,
                kind = detected.kind,
                size = effectiveSize,
                origin = Origin.TRASH,
                mime = mime ?: detected.mime,
                confidence = 98,
                sourcePath = data,
                contentUri = uri,
                detail = "In system trash — deleted recently",
                format = detected.format,
            )
            out[file.id] = file
        }
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private fun cleanName(raw: String): String {
        var name = raw.trim()
        val patterns = listOf(
            Regex("^\\.?trashed-\\d+-"),
            Regex("^\\.?trashed-"),
            Regex("^\\.?pending-\\d+-"),
        )
        patterns.forEach { pattern -> if (pattern.containsMatchIn(name)) name = pattern.replace(name, "") }
        return name.ifBlank { raw }
    }

    private fun detectedFromMime(mime: String, name: String): DetectedFile? {
        val kind = when {
            mime.startsWith("image/") -> MediaKind.IMAGE
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.AUDIO
            else -> MediaKind.OTHER
        }
        if (kind == MediaKind.OTHER) return null
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            .ifBlank { mime.substringAfterLast('/').lowercase(Locale.US) }
        return DetectedFile(ext.uppercase(Locale.US), ext, mime, kind, 85)
    }
}
