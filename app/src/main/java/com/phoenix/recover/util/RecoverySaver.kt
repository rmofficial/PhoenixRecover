package com.phoenix.recover.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.phoenix.recover.engine.MediaKind
import com.phoenix.recover.engine.RecoveredFile
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

data class SavedItem(
    val id: String,
    val name: String,
    val kind: MediaKind,
    val size: Long,
    val uri: String?,
    val path: String?,
    val savedAt: Long,
)

object RecoverySaver {

    private const val FOLDER = "Phoenix Recover"

    fun save(context: Context, item: RecoveredFile, onBytes: (Long) -> Unit = {}): SavedItem? {
        val file = listOfNotNull(item.scratchPath, item.sourcePath)
            .map(::File)
            .firstOrNull { it.exists() && it.canRead() }
        val uri = item.contentUri?.let(Uri::parse)
        if (file == null && uri == null) return null
        val sourceSize = file?.length() ?: item.size
        val open: () -> java.io.InputStream? = if (file != null) {
            { runCatching { FileInputStream(file) }.getOrNull() }
        } else {
            { runCatching { context.contentResolver.openInputStream(uri!!) }.getOrNull() }
        }
        if (file == null && open() == null) return null
        val displayName = sanitize(item.name, item.kind)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(context, open, sourceSize, displayName, item, onBytes)
            } else {
                saveLegacy(context, open, sourceSize, displayName, item, onBytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitize(name: String, kind: MediaKind): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank {
            "recovered_${System.currentTimeMillis()}"
        }
        val ext = cleaned.substringAfterLast('.', "")
        if (ext.isEmpty()) {
            val fallback = when (kind) {
                MediaKind.IMAGE -> "jpg"
                MediaKind.VIDEO -> "mp4"
                MediaKind.AUDIO -> "mp3"
                MediaKind.DOCUMENT -> "pdf"
                MediaKind.ARCHIVE -> "zip"
                MediaKind.OTHER -> "bin"
            }
            return "$cleaned.$fallback"
        }
        return cleaned
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(
        context: Context,
        open: () -> java.io.InputStream?,
        sourceSize: Long,
        displayName: String,
        item: RecoveredFile,
        onBytes: (Long) -> Unit,
    ): SavedItem? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime.ifBlank { "application/octet-stream" })
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER,
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return null
        try {
            val input = open()
            if (input == null) {
                resolver.delete(uri, null, null)
                return null
            }
            var written = sourceSize
            input.use { source ->
                resolver.openOutputStream(uri)?.use { out ->
                    written = copy(source, out, onBytes)
                } ?: run {
                    resolver.delete(uri, null, null)
                    return null
                }
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return SavedItem(
                id = item.id + ":" + System.currentTimeMillis(),
                name = displayName,
                kind = item.kind,
                size = written,
                uri = uri.toString(),
                path = null,
                savedAt = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
    }

    private fun saveLegacy(
        context: Context,
        open: () -> java.io.InputStream?,
        sourceSize: Long,
        displayName: String,
        item: RecoveredFile,
        onBytes: (Long) -> Unit,
    ): SavedItem? {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return null
        val dest = uniqueFile(dir, displayName)
        val input = open() ?: return null
        val written = try {
            input.use { source ->
                dest.outputStream().use { out ->
                    copy(source, out, onBytes)
                }
            }
        } catch (_: Exception) {
            runCatching { dest.delete() }
            return null
        }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(item.mime), null)
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", dest).toString()
        }.getOrNull()
        return SavedItem(
            id = item.id + ":" + System.currentTimeMillis(),
            name = dest.name,
            kind = item.kind,
            size = if (written > 0) written else sourceSize,
            uri = uri,
            path = dest.absolutePath,
            savedAt = System.currentTimeMillis(),
        )
    }

    private fun copy(input: java.io.InputStream, out: OutputStream, onBytes: (Long) -> Unit): Long {
        val buf = ByteArray(1024 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
            onBytes(total)
        }
        out.flush()
        return total
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 2
        while (true) {
            val next = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            candidate = File(dir, next)
            if (!candidate.exists()) return candidate
            i++
        }
    }

    fun openIntent(context: Context, saved: SavedItem): Intent? {
        val uri = saved.uri?.let(Uri::parse)
            ?: saved.path?.let {
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.files", File(it))
                }.getOrNull()
            }
            ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(saved))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun shareIntent(context: Context, saved: SavedItem): Intent? {
        val uri = saved.uri?.let(Uri::parse)
            ?: saved.path?.let {
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.files", File(it))
                }.getOrNull()
            }
            ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(saved)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun mimeFor(saved: SavedItem): String = when (saved.name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "amr" -> "audio/amr"
        "m4a" -> "audio/mp4"
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        "apk" -> "application/vnd.android.package-archive"
        "db", "sqlite" -> "application/x-sqlite3"
        else -> "application/octet-stream"
    }
}
