package com.phoenix.recover.engine

import java.io.File
import kotlin.math.min

/**
 * Walks accessible storage and surfaces files that are genuinely likely to be deleted:
 * recycle/trash bins, LOST.DIR fragments, hidden media folders, thumbnail caches,
 * and media that is no longer present in the MediaStore index.
 */
class DeepScanner(private val options: DeepScanOptions) {

    private companion object {
        val TRASH_SEGMENTS = setOf(
            ".trashed", ".trash", ".trashes", ".trashbin", ".trash_bin", ".recycle", ".recyclebin",
            ".deleted", ".deleted_files", "recycle bin", "\$recycle.bin", "#recycle",
            "trash", "trashbin", ".files_trash", "recently deleted", "recently_deleted",
            "recentlydeleted", "deleted files", "deleted", "recycle",
        )
        val SKIP_DIRS = setOf(
            "obb", ".git", "node_modules", "__pycache__", "com.phoenix.recover",
            "lost+found", ".thumbnails", "thumbnails",
        )
        val KNOWN_RELATIVE = listOf(
            ".trashed", ".Trash", ".trash", ".trashes", ".recycle", ".recyclebin", ".RecycleBin",
            ".deleted", "\$RECYCLE.BIN", "#recycle", "LOST.DIR", "Trash", "Recycle Bin",
            "Pictures/.trashed", "Pictures/.trash", "Pictures/Trash",
            "Pictures/Recently Deleted", "Movies/.trashed", "Movies/.trash", "Movies/Trash",
            "DCIM/.trashed", "DCIM/.Trash", "DCIM/.trash", "DCIM/Trash",
            "DCIM/.trashes", "DCIM/Recently Deleted", "Download/.trashed", "Download/Trash",
            "MIUI/Gallery/cloud/.trashBin", "MIUI/Gallery/.trashBin", "MIUI/Gallery/.trash",
            "Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
            "Android/data/com.whatsapp/WhatsApp/Media/.Statuses",
            "Android/media/org.telegram.messenger/Telegram/Telegram Images",
        )
        val MEDIA_KINDS = setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO)
    }

    private data class WalkDir(val file: File, val depth: Int)

    private val noMediaCache = HashMap<String, Boolean>(256)

    fun scan(
        isCancelled: () -> Boolean = { false },
        onEvent: (ScanEvent) -> Unit = {},
    ): ScanResult {
        val startedAt = System.currentTimeMillis()
        var dirsVisited = 0
        var filesInspected = 0
        var errorCount = 0
        var cancelled = false
        val visited = HashSet<String>(4096)
        val seenFiles = HashSet<String>(4096)
        val queue = ArrayDeque<WalkDir>()
        val roots = options.roots.map { File(it) }.filter { it.exists() }

        if (options.trashOnly) {
            for (root in roots) {
                for (rel in KNOWN_RELATIVE) {
                    val f = File(root, rel)
                    if (f.isDirectory) queue.addLast(WalkDir(f, 0))
                }
            }
            if (queue.isEmpty()) {
                onEvent(ScanEvent.Status("No filesystem trash folders found — checking system trash…"))
                return ScanResult(0, 0, 0, false, System.currentTimeMillis() - startedAt, 0)
            }
            onEvent(ScanEvent.Status("Scanning system trash bins…"))
        } else {
            for (root in roots) {
                queue.addLast(WalkDir(root, 0))
                for (rel in KNOWN_RELATIVE) {
                    val f = File(root, rel)
                    if (f.isDirectory) queue.addLast(WalkDir(f, 0))
                }
            }
            onEvent(ScanEvent.Status("Deep scan started on ${roots.size} volume(s)…"))
        }

        var lastProgress = 0L

        while (queue.isNotEmpty()) {
            if (isCancelled()) {
                cancelled = true
                break
            }
            if (filesInspected >= options.maxFilesToInspect) break
            if (seenFiles.size >= options.maxResults) break
            val current = queue.removeFirst()
            val dir = current.file
            val key = dir.absolutePath.trimEnd('/').ifEmpty { dir.absolutePath }
            if (!visited.add(key)) continue
            val children = try {
                dir.listFiles()
            } catch (_: SecurityException) {
                errorCount++
                null
            } catch (_: Exception) {
                errorCount++
                null
            }
            if (children == null) continue
            dirsVisited++

            for (child in children) {
                if (isCancelled()) {
                    cancelled = true
                    break
                }
                if (filesInspected >= options.maxFilesToInspect) break
                if (seenFiles.size >= options.maxResults) break
                val name = child.name
                if (child.isDirectory) {
                    if (current.depth < options.maxDepth && name !in SKIP_DIRS && !name.equals("Android", true)) {
                        queue.addLast(WalkDir(child, current.depth + 1))
                    }
                    continue
                }
                filesInspected++
                if (filesInspected % 512 == 0) {
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) {
                    }
                }
                val item = inspect(child, seenFiles) ?: continue
                onEvent(ScanEvent.Found(item))
            }

            val now = System.currentTimeMillis()
            if (now - lastProgress >= 220) {
                lastProgress = now
                onEvent(
                    ScanEvent.Progress(
                        dirs = dirsVisited,
                        filesSeen = filesInspected,
                        found = seenFiles.size,
                        currentPath = dir.absolutePath,
                    ),
                )
            }
            if (cancelled) break
        }

        onEvent(
            ScanEvent.Progress(
                dirs = dirsVisited,
                filesSeen = filesInspected,
                found = seenFiles.size,
                currentPath = "",
            ),
        )

        return ScanResult(
            dirsVisited = dirsVisited,
            filesInspected = filesInspected,
            found = seenFiles.size,
            cancelled = cancelled,
            elapsedMs = System.currentTimeMillis() - startedAt,
            errors = errorCount,
        )
    }

    private fun inspect(file: File, seen: MutableSet<String>): RecoveredFile? {
        val size = try {
            file.length()
        } catch (_: Exception) {
            return null
        }
        if (size < options.minSize || size <= 0) return null

        val absolute = file.absolutePath
        val lower = absolute.lowercase()
        val name = file.name
        val lowerName = name.lowercase()
        val segments = lower.split('/')
        val trashName = lowerName.startsWith(".trashed") || lowerName.startsWith("trashed-") ||
            lowerName.startsWith(".trash-") || lowerName.startsWith(".pending-") ||
            lowerName.contains(".trashed-")
        val inTrash = trashName ||
            segments.any { it in TRASH_SEGMENTS } ||
            lower.contains("miui/gallery/cloud/.trashbin") ||
            lower.contains("/.trashed/")
        val inLost = segments.any { it == "lost.dir" }
        val inThumbs = segments.any { it == ".thumbnails" } || lower.contains("/thumbnails/")
        val hiddenFile = name.startsWith(".")
        val hiddenDir = segments.dropLast(1).any { it.startsWith(".") && it.length > 2 }
        val hasNoMedia = parentHasNoMedia(file)
        val ext = lowerName.substringAfterLast('.', "")

        val hiddenMatch = options.includeHidden && (hiddenDir || hiddenFile || hasNoMedia)
        val candidate = ext in Sniffer.candidateExtensions
        val maybeUnindexed = options.detectUnindexed &&
            options.indexedPaths.isNotEmpty() &&
            absolute !in options.indexedPaths &&
            candidate

        // Skip opening ordinary, fully-indexed media. Only files that can still
        // become a result are sniffed, which keeps large scans fast.
        if (!inTrash && !inLost && !inThumbs && !hiddenMatch && !maybeUnindexed) return null

        val detected = Sniffer.detect(file) ?: return null
        val unindexed = options.detectUnindexed &&
            options.indexedPaths.isNotEmpty() &&
            absolute !in options.indexedPaths &&
            detected.kind in MEDIA_KINDS

        val reportable = inTrash || inLost || inThumbs || hiddenMatch || unindexed
        if (!reportable) return null
        if (options.trashOnly && !inTrash) return null

        if (!seen.add(absolute)) return null

        val origin = when {
            inTrash -> Origin.TRASH
            inLost || inThumbs || hiddenFile || hiddenDir || hasNoMedia -> Origin.HIDDEN
            unindexed -> Origin.UNINDEXED
            else -> Origin.DEEP_SCAN
        }
        val confidence = when {
            inTrash -> 96
            inLost -> 84
            inThumbs -> 58
            hiddenFile -> 72
            detected.confidence >= 80 -> min(90, detected.confidence - 3)
            unindexed -> 62
            else -> detected.confidence
        }
        val detail = when {
            trashName -> "Deleted by the gallery (trash file name)"
            inTrash -> "Found inside a recycle bin"
            inLost -> "Fragment from LOST.DIR"
            inThumbs -> "Cached thumbnail (lower resolution)"
            hasNoMedia -> "Hidden by a .nomedia marker"
            hiddenFile -> "Hidden file"
            hiddenDir -> "Inside a hidden folder"
            unindexed -> "No longer in the media index"
            else -> "Recoverable media"
        }
        val cleaned = if (trashName) {
            name
                .replace(Regex("^\\.?trashed-\\d+-"), "")
                .replace(Regex("^\\.?trashed-"), "")
                .replace(Regex("^\\.?pending-\\d+-"), "")
                .ifBlank { name }
        } else {
            name
        }
        val displayName = if (cleaned.isBlank()) {
            "recovered_${name.hashCode()}.${detected.ext}"
        } else {
            cleaned
        }

        return RecoveredFile(
            id = "deep:$absolute",
            name = displayName,
            kind = detected.kind,
            size = size,
            origin = origin,
            mime = detected.mime,
            confidence = confidence,
            sourcePath = absolute,
            detail = detail,
            format = detected.format,
        )
    }

    private fun parentHasNoMedia(file: File): Boolean {
        val parent = file.parentFile ?: return false
        val key = parent.absolutePath
        return noMediaCache.getOrPut(key) { File(parent, ".nomedia").exists() }
    }
}
