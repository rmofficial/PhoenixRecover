package com.phoenix.recover.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.phoenix.recover.engine.RecoveredFile
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportExporter {

    private const val FOLDER = "Phoenix Recover/reports"

    fun export(context: Context, items: List<RecoveredFile>): String? {
        if (items.isEmpty()) return null
        val name = "phoenix_report_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportToMediaStore(context, items, name)
        } else {
            exportLegacy(items, name)
        }
    }

    private fun exportToMediaStore(context: Context, items: List<RecoveredFile>, name: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { write(items, it) } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            "Downloads/$FOLDER/$name"
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun exportLegacy(items: List<RecoveredFile>, name: String): String? {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = uniqueFile(dir, name)
        return try {
            file.outputStream().use { write(items, it) }
            file.absolutePath
        } catch (_: Exception) {
            runCatching { file.delete() }
            null
        }
    }

    private fun write(items: List<RecoveredFile>, out: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
        writer.write("\uFEFF")
        writer.appendLine(
            "Name,Kind,Format,Size bytes,Size,Confidence,Origin,Partial,Offset,Source,Detail",
        )
        items.sortedWith(
            compareByDescending<RecoveredFile> { it.confidence }.thenBy { it.name.lowercase() },
        ).forEach { file ->
            val offset = file.offset?.let { "0x" + it.toString(16).uppercase(Locale.US) } ?: ""
            val source = file.sourcePath ?: file.contentUri ?: ""
            val row = listOf(
                file.name,
                file.kind.label,
                file.format,
                file.size.toString(),
                Fmt.bytes(file.size),
                file.confidence.toString(),
                file.origin.label,
                if (file.partial) "yes" else "no",
                offset,
                source,
                file.detail,
            )
            writer.appendLine(row.joinToString(",") { escape(it) })
        }
        writer.flush()
    }

    private fun escape(value: String): String {
        val clean = value.replace('\r', ' ').replace('\n', ' ')
        return if (clean.contains(',') || clean.contains('"')) {
            "\"" + clean.replace("\"", "\"\"") + "\""
        } else {
            clean
        }
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
}
