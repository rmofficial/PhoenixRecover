package com.phoenix.recover.ui

import com.phoenix.recover.engine.CarveResult
import com.phoenix.recover.engine.MediaKind
import com.phoenix.recover.engine.RecoveredFile
import com.phoenix.recover.engine.ScanResult
import com.phoenix.recover.engine.Origin
import com.phoenix.recover.util.BlockDevice
import com.phoenix.recover.util.RootStatus
import com.phoenix.recover.util.SavedItem
import com.phoenix.recover.util.StorageVolume

enum class Screen {
    HOME, SCAN, CARVE, RESULTS, VAULT, SETTINGS, ROOT,
}

enum class SortMode(val label: String) {
    CONFIDENCE("Best match"),
    SIZE("Size"),
    NAME("Name"),
    ORIGIN("Source"),
}

enum class ConfidenceFilter(val label: String) {
    ALL("All"),
    STRONG("Strong"),
    LIKELY("Likely"),
    FRAGMENT("Fragments"),
    ;

    fun matches(confidence: Int): Boolean = when (this) {
        ALL -> true
        STRONG -> confidence >= 82
        LIKELY -> confidence in 55..81
        FRAGMENT -> confidence < 55
    }
}

enum class ActivityType(val label: String) {
    SCAN("Deep scan"),
    TRASH("Trash scan"),
    CARVE("Signature carve"),
    ROOT("Raw partition"),
}

data class ActivityEntry(
    val type: ActivityType,
    val found: Int,
    val elapsedMs: Long,
    val timestamp: Long,
    val cancelled: Boolean,
    val detail: String = "",
)

val RecoveredFile.duplicateKey: String
    get() = "${size}|${format.lowercase()}|${name.lowercase()}"

data class Settings(
    val accentKey: String = "aurora",
    val maxDepth: Int = 14,
    val includeHidden: Boolean = true,
    val detectUnindexed: Boolean = true,
    val minSizeKb: Int = 2,
    val maxOutputMb: Int = 2048,
    val maxCarveFileMb: Int = 4096,
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val volumes: List<StorageVolume> = emptyList(),
    val hasAccess: Boolean = false,
    val permissionDismissed: Boolean = false,
    val results: List<RecoveredFile> = emptyList(),
    val recovered: List<SavedItem> = emptyList(),
    val scanning: Boolean = false,
    val scanDirs: Int = 0,
    val scanFiles: Int = 0,
    val scanPath: String = "",
    val scanStatus: String = "",
    val scanStartedAt: Long = 0L,
    val lastScan: ScanResult? = null,
    val carving: Boolean = false,
    val carveProcessed: Long = 0L,
    val carveTotal: Long = 0L,
    val carveFound: Int = 0,
    val carveBytes: Long = 0L,
    val carveStatus: String = "Ready",
    val carveSourceName: String = "",
    val carveStartedAt: Long = 0L,
    val carveKinds: Set<MediaKind> = MediaKind.entries.toSet(),
    val lastCarve: CarveResult? = null,
    val recovering: Boolean = false,
    val recoverText: String = "",
    val selectedIds: Set<String> = emptySet(),
    val filter: MediaKind? = null,
    val confidenceFilter: ConfidenceFilter = ConfidenceFilter.ALL,
    val duplicatesOnly: Boolean = false,
    val query: String = "",
    val sort: SortMode = SortMode.CONFIDENCE,
    val reporting: Boolean = false,
    val history: List<ActivityEntry> = emptyList(),
    val settings: Settings = Settings(),
    val rootStatus: RootStatus? = null,
    val rootDevices: List<BlockDevice> = emptyList(),
    val rootBusy: Boolean = false,
    val rootProgress: String = "",
    val showPreview: RecoveredFile? = null,
    val toast: String? = null,
) {
    val foundCount: Int get() = results.size
    val selectedCount: Int get() = selectedIds.size
    val totalFoundBytes: Long get() = results.sumOf { it.size }
    val bestConfidence: Int get() = results.maxOfOrNull { it.confidence } ?: 0
    val strongCount: Int get() = results.count { it.confidence >= 82 }
    val duplicateGroups: Map<String, Int>
        get() = results.groupingBy { it.duplicateKey }.eachCount().filterValues { it > 1 }
    val duplicateCount: Int get() = results.count { it.duplicateKey in duplicateGroups }
    val vaultBytes: Long get() = recovered.sumOf { it.size }
    val allKinds: List<MediaKind> get() = MediaKind.entries
    val originSummary: Map<Origin, Int> get() = results.groupingBy { it.origin }.eachCount()
}
