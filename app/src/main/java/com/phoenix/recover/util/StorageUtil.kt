package com.phoenix.recover.util

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

data class StorageVolume(
    val label: String,
    val path: String,
    val total: Long,
    val free: Long,
) {
    val used: Long get() = (total - free).coerceAtLeast(0)
    val usedPercent: Int get() = if (total <= 0) 0 else (used.toDouble() / total * 100).toInt()
}

object StorageUtil {

    fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    fun volumes(context: Context): List<StorageVolume> {
        val out = LinkedHashMap<String, StorageVolume>()
        val candidates = mutableListOf<File>()
        candidates += Environment.getExternalStorageDirectory()
        try {
            context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
                val p = dir.absolutePath
                val cut = p.indexOf("/Android/")
                val root = if (cut > 0) File(p.substring(0, cut)) else null
                if (root != null) candidates += root
            }
        } catch (_: Exception) {
        }
        for (root in candidates) {
            val key = try {
                root.canonicalPath
            } catch (_: Exception) {
                root.absolutePath
            }
            if (out.containsKey(key)) continue
            try {
                if (!root.exists() || !root.canRead()) continue
                val stat = StatFs(root.absolutePath)
                val total = stat.blockCountLong * stat.blockSizeLong
                val free = stat.availableBlocksLong * stat.blockSizeLong
                if (total <= 0) continue
                val label = when {
                    key.contains("emulated/0") -> "Internal storage"
                    else -> "External storage"
                }
                out[key] = StorageVolume(label, root.absolutePath, total, free)
            } catch (_: Exception) {
            }
        }
        if (out.isEmpty()) {
            val root = Environment.getExternalStorageDirectory()
            return listOf(StorageVolume("Internal storage", root.absolutePath, 0, 0))
        }
        return out.values.toList()
    }

    fun hasStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun allFilesIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        }
    }

    /** Absolute paths of media currently known to the MediaStore index. */
    fun indexedMediaPaths(context: Context): Set<String> {
        val out = HashSet<String>(8192)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?,?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString(),
        )
        try {
            context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx < 0) return@use
                while (c.moveToNext()) {
                    val path = c.getString(idx) ?: continue
                    if (path.isNotEmpty()) out += path
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun freeSpaceFor(path: String): Long = try {
        StatFs(path).availableBlocksLong * StatFs(path).blockSizeLong
    } catch (_: Exception) {
        0L
    }

    fun downloadsCollectionUri(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

    fun mediaUri(id: Long, collection: Uri): Uri = ContentUris.withAppendedId(collection, id)
}
