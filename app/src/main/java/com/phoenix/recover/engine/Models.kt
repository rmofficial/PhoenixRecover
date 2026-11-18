package com.phoenix.recover.engine

enum class MediaKind(val label: String) {
    IMAGE("Images"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    DOCUMENT("Documents"),
    ARCHIVE("Archives"),
    OTHER("Other"),
}

enum class Origin(val label: String) {
    DEEP_SCAN("Deep scan"),
    TRASH("System trash"),
    HIDDEN("Hidden folder"),
    UNINDEXED("Orphaned media"),
    CARVE("Signature carve"),
    ROOT("Raw / root image"),
}

data class RecoveredFile(
    val id: String,
    val name: String,
    val kind: MediaKind,
    val size: Long,
    val origin: Origin,
    val mime: String,
    val confidence: Int,
    val sourcePath: String? = null,
    val offset: Long? = null,
    val detail: String = "",
    val scratchPath: String? = null,
    val contentUri: String? = null,
    val savedUri: String? = null,
    val format: String = "",
    val partial: Boolean = false,
)

data class CarveOptions(
    val minSize: Long = 2_048L,
    val maxFileSize: Long = 4L * 1024 * 1024 * 1024,
    val kinds: Set<MediaKind> = emptySet(),
    val allowedExtensions: Set<String> = emptySet(),
    val writeOutput: Boolean = true,
    val maxResults: Int = 5_000,
    val maxOutputBytes: Long = 8L * 1024 * 1024 * 1024,
    val maxBytesToScan: Long = Long.MAX_VALUE,
)

data class CarveResult(
    val bytesExamined: Long,
    val filesFound: Int,
    val filesWritten: Int,
    val bytesWritten: Long,
    val cancelled: Boolean,
    val outputLimitHit: Boolean,
    val resultLimitHit: Boolean,
    val elapsedMs: Long,
    val perKind: Map<MediaKind, Int>,
)

sealed class CarveEvent {
    data class Progress(
        val processed: Long,
        val total: Long,
        val found: Int,
        val bytesWritten: Long,
    ) : CarveEvent()

    data class Found(val item: RecoveredFile) : CarveEvent()
    data class Status(val message: String) : CarveEvent()
}

data class DeepScanOptions(
    val maxDepth: Int = 14,
    val includeHidden: Boolean = true,
    val trashOnly: Boolean = false,
    val minSize: Long = 1L,
    val maxResults: Int = 5_000,
    val detectUnindexed: Boolean = true,
    val maxFilesToInspect: Int = 300_000,
    val indexedPaths: Set<String> = emptySet(),
) {
    val roots: MutableList<String> = mutableListOf()
}

sealed class ScanEvent {
    data class Progress(
        val dirs: Int,
        val filesSeen: Int,
        val found: Int,
        val currentPath: String,
    ) : ScanEvent()

    data class Found(val item: RecoveredFile) : ScanEvent()
    data class Status(val message: String) : ScanEvent()
}

data class ScanResult(
    val dirsVisited: Int,
    val filesInspected: Int,
    val found: Int,
    val cancelled: Boolean,
    val elapsedMs: Long,
    val errors: Int,
)
