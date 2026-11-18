package com.phoenix.recover

import android.app.Application
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.recover.engine.CarveEvent
import com.phoenix.recover.engine.CarveOptions
import com.phoenix.recover.engine.Carver
import com.phoenix.recover.engine.ContentUriSource
import com.phoenix.recover.engine.DeepScanOptions
import com.phoenix.recover.engine.DeepScanner
import com.phoenix.recover.engine.FileSource
import com.phoenix.recover.engine.MediaKind
import com.phoenix.recover.engine.MediaStoreTrash
import com.phoenix.recover.engine.RecoveredFile
import com.phoenix.recover.engine.ScanEvent
import com.phoenix.recover.engine.SeekableSource
import com.phoenix.recover.ui.ActivityEntry
import com.phoenix.recover.ui.ActivityType
import com.phoenix.recover.ui.ConfidenceFilter
import com.phoenix.recover.ui.Screen
import com.phoenix.recover.ui.Settings
import com.phoenix.recover.ui.SortMode
import com.phoenix.recover.ui.UiState
import com.phoenix.recover.util.BlockDevice
import com.phoenix.recover.util.Fmt
import com.phoenix.recover.util.RecoverySaver
import com.phoenix.recover.util.ReportExporter
import com.phoenix.recover.util.RootUtil
import com.phoenix.recover.util.SavedItem
import com.phoenix.recover.util.StorageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class RecoverViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext
    private val prefs = context.getSharedPreferences("phoenix_recover", 0)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val stack = mutableListOf(Screen.HOME)
    private val resultsMap = LinkedHashMap<String, RecoveredFile>()
    private val scanCancelled = AtomicBoolean(false)
    private val carveCancelled = AtomicBoolean(false)
    private val rootCancelled = AtomicBoolean(false)
    private var scanJob: Job? = null
    private var carveJob: Job? = null
    private var volumesJob: Job? = null
    private val publishLock = Any()
    @Volatile private var lastPublish = 0L

    init {
        _state.value = UiState(
            settings = loadSettings(),
            hasAccess = StorageUtil.hasStorageAccess(context),
            carveKinds = MediaKind.entries.toSet(),
            history = loadHistory(),
        )
        viewModelScope.launch {
            val volumes = withBackgroundIo {
                runCatching { StorageUtil.volumes(context) }.getOrDefault(emptyList())
            }
            val recovered = withBackgroundIo { readRecovered() }
            _state.update { it.copy(volumes = volumes, recovered = recovered) }
        }
    }

    // ---------------------------------------------------------------- navigation

    private fun applyScreen() {
        _state.update { it.copy(screen = stack.lastOrNull() ?: Screen.HOME) }
    }

    fun push(screen: Screen) {
        if (stack.lastOrNull() != screen) stack.add(screen)
        applyScreen()
    }

    fun goTab(screen: Screen) {
        stack.clear()
        if (screen != Screen.HOME) stack.add(Screen.HOME)
        stack.add(screen)
        applyScreen()
    }

    fun back(): Boolean {
        if (_state.value.showPreview != null) {
            dismissPreview()
            return true
        }
        val current = _state.value.screen
        if (current == Screen.SCAN && _state.value.scanning) cancelScan()
        if (current == Screen.CARVE && _state.value.carving) cancelCarve()
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            applyScreen()
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- permissions / storage

    fun refreshAccess() {
        val access = StorageUtil.hasStorageAccess(context)
        _state.update {
            it.copy(
                hasAccess = access,
                permissionDismissed = it.permissionDismissed || access,
            )
        }
        volumesJob?.cancel()
        volumesJob = viewModelScope.launch {
            val volumes = withBackgroundIo {
                runCatching { StorageUtil.volumes(context) }.getOrDefault(emptyList())
            }
            _state.update { it.copy(volumes = volumes) }
        }
    }

    fun dismissPermission() {
        _state.update { it.copy(permissionDismissed = true) }
    }

    fun resetPermissionGate() {
        _state.update { it.copy(permissionDismissed = false) }
    }

    // ---------------------------------------------------------------- deep scan

    fun startDeepScan(trashOnly: Boolean) {
        val snapshot = _state.value
        if (snapshot.scanning) return
        if (snapshot.carving) {
            toast("Finish or stop the carve before starting a scan")
            return
        }
        if (!snapshot.hasAccess) {
            toast("Enable All files access to scan storage")
            _state.update { it.copy(permissionDismissed = false) }
            return
        }
        scanCancelled.set(false)
        resultsMap.clear()
        publishResults(true)
        push(Screen.SCAN)
        _state.update {
            it.copy(
                scanning = true,
                scanDirs = 0,
                scanFiles = 0,
                scanPath = "",
                scanStartedAt = SystemClock.elapsedRealtime(),
                scanStatus = if (trashOnly) "Locating system trash bins…" else "Preparing deep scan…",
                lastScan = null,
                selectedIds = emptySet(),
                filter = null,
                confidenceFilter = ConfidenceFilter.ALL,
                duplicatesOnly = false,
                query = "",
            )
        }
        scanJob = viewModelScope.launch {
            try {
                val trashItems = if (MediaStoreTrash.isSupported()) {
                    withBackgroundIo {
                        runCatching { MediaStoreTrash.find(context) }.getOrDefault(emptyList())
                    }
                } else {
                    emptyList()
                }
                if (trashItems.isNotEmpty()) {
                    synchronized(resultsMap) { trashItems.forEach { resultsMap[it.id] = it } }
                    publishResults(true)
                    _state.update {
                        it.copy(scanStatus = "Found ${Fmt.count(trashItems.size)} in the system trash bin")
                    }
                }
                val settings = _state.value.settings
                val indexed = if (settings.detectUnindexed) {
                    withBackgroundIo {
                        runCatching { StorageUtil.indexedMediaPaths(context) }.getOrDefault(emptySet())
                    }
                } else {
                    emptySet()
                }
                val volumes = withBackgroundIo {
                    runCatching { StorageUtil.volumes(context) }.getOrDefault(emptyList())
                }
                if (volumes.isNotEmpty() && volumes != _state.value.volumes) {
                    _state.update { it.copy(volumes = volumes) }
                }
                val options = DeepScanOptions(
                    maxDepth = settings.maxDepth,
                    includeHidden = settings.includeHidden,
                    trashOnly = trashOnly,
                    minSize = max(1L, settings.minSizeKb.toLong() * 1024L),
                    maxResults = 5000,
                    detectUnindexed = settings.detectUnindexed,
                    indexedPaths = indexed,
                )
                volumes.forEach { options.roots.add(it.path) }
                if (options.roots.isEmpty()) options.roots.add(StorageUtil.primaryRoot().absolutePath)

                val result = withBackgroundIo {
                    DeepScanner(options).scan({ scanCancelled.get() }) { event ->
                        when (event) {
                            is ScanEvent.Found -> {
                                synchronized(resultsMap) { resultsMap[event.item.id] = event.item }
                                publishResults(false)
                            }
                            is ScanEvent.Progress -> {
                                _state.update {
                                    it.copy(
                                        scanDirs = event.dirs,
                                        scanFiles = event.filesSeen,
                                        scanPath = event.currentPath,
                                        scanStatus = if (scanCancelled.get()) "Stopping…" else "Scanning storage…",
                                    )
                                }
                                publishResults(false)
                            }
                            is ScanEvent.Status -> {
                                _state.update { it.copy(scanStatus = event.message) }
                            }
                        }
                    }
                }
                dedupeResults()
                publishResults(true)
                val found = synchronized(resultsMap) { resultsMap.size }
                val summary = when {
                    result.cancelled -> "Scan stopped · ${Fmt.count(found)} files found"
                    found == 0 -> "No recoverable copy detected"
                    else -> "Scan complete · ${Fmt.count(found)} recoverable files"
                }
                _state.update {
                    it.copy(
                        scanning = false,
                        scanPath = "",
                        scanStatus = summary,
                        lastScan = result,
                    )
                }
                recordActivity(
                    type = if (trashOnly) ActivityType.TRASH else ActivityType.SCAN,
                    found = found,
                    elapsedMs = result.elapsedMs,
                    cancelled = result.cancelled,
                    detail = "${Fmt.count(result.dirsVisited)} folders · ${Fmt.count(result.filesInspected)} files",
                )
                if (!result.cancelled && found == 0) {
                    toast("No recoverable copy found. Check the gallery recycle bin, or use Root/raw imaging for permanently deleted files.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                publishResults(true)
                _state.update {
                    it.copy(
                        scanning = false,
                        scanPath = "",
                        scanStatus = "Scan failed. Try again.",
                    )
                }
                toast("Scan failed: ${t.message ?: "unknown error"}")
            }
        }
    }

    private fun dedupeResults() {
        synchronized(resultsMap) {
            val best = LinkedHashMap<String, RecoveredFile>()
            for (item in resultsMap.values) {
                val key = item.sourcePath?.lowercase() ?: item.contentUri ?: item.id
                val current = best[key]
                if (current == null || item.confidence > current.confidence) best[key] = item
            }
            resultsMap.clear()
            best.values.forEach { resultsMap[it.id] = it }
        }
    }

    fun cancelScan() {
        if (!_state.value.scanning) return
        scanCancelled.set(true)
        _state.update { it.copy(scanStatus = "Stopping scan…") }
    }

    // ---------------------------------------------------------------- carving

    fun openCarve() {
        push(Screen.CARVE)
    }

    fun setCarveKinds(kinds: Set<MediaKind>) {
        _state.update { it.copy(carveKinds = kinds) }
    }

    fun startCarve(uri: Uri, displayName: String) {
        if (_state.value.carving) return
        if (_state.value.scanning) {
            toast("Stop the current scan before carving an image")
            return
        }
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) {
                runCatching { ContentUriSource(context, uri) }.getOrNull()
            }
            if (source == null || source.length <= 0) {
                source?.close()
                toast("Cannot read this image. Pick an .img or .dd file.")
                return@launch
            }
            beginCarve(source, displayName)
        }
    }

    fun startCarveFromFile(file: File, displayName: String) {
        if (_state.value.carving) return
        if (_state.value.scanning) {
            toast("Stop the current scan before carving an image")
            return
        }
        if (!file.exists() || file.length() <= 0) {
            toast("Image file is missing or empty")
            return
        }
        val source = runCatching { FileSource(file) }.getOrNull() ?: run {
            toast("Cannot open image")
            return
        }
        beginCarve(source, displayName)
    }

    private fun beginCarve(source: SeekableSource, displayName: String) {
        carveCancelled.set(false)
        resultsMap.clear()
        publishResults(true)
        push(Screen.CARVE)
        val total = source.length
        val settings = _state.value.settings
        val free = StorageUtil.freeSpaceFor((context.getExternalFilesDir(null) ?: context.filesDir).absolutePath)
        if (free in 1 until 96L * 1024 * 1024) {
            source.close()
            toast("Not enough free space to stage recovered files")
            return
        }
        _state.update {
            it.copy(
                carving = true,
                carveProcessed = 0,
                carveTotal = total,
                carveFound = 0,
                carveBytes = 0,
                carveStatus = "Scanning ${Fmt.bytes(total)} for file signatures…",
                carveSourceName = displayName,
                carveStartedAt = SystemClock.elapsedRealtime(),
                lastCarve = null,
                selectedIds = emptySet(),
                filter = null,
                confidenceFilter = ConfidenceFilter.ALL,
                duplicatesOnly = false,
                query = "",
            )
        }
        val kinds = _state.value.carveKinds
        carveJob = viewModelScope.launch {
            try {
                val dir = withBackgroundIo { prepareStaging() }
                val maxOutput = min(
                    max(64L, settings.maxOutputMb.toLong()) * 1024L * 1024L,
                    if (free > 0) (free - 64L * 1024 * 1024).coerceAtLeast(64L * 1024 * 1024) else Long.MAX_VALUE,
                )
                val options = CarveOptions(
                    minSize = max(512L, settings.minSizeKb.toLong() * 1024L),
                    maxFileSize = max(64L, settings.maxCarveFileMb.toLong()) * 1024L * 1024L,
                    kinds = kinds,
                    writeOutput = true,
                    maxResults = 5000,
                    maxOutputBytes = maxOutput,
                )
                val result = withBackgroundIo {
                    Carver(source, options).carve(dir, { carveCancelled.get() }) { event ->
                        when (event) {
                            is CarveEvent.Progress -> {
                                _state.update {
                                    it.copy(
                                        carveProcessed = event.processed,
                                        carveTotal = event.total,
                                        carveFound = event.found,
                                        carveBytes = event.bytesWritten,
                                        carveStatus = if (carveCancelled.get()) "Stopping carve…" else "Carving signatures…",
                                    )
                                }
                                publishResults(false)
                            }
                            is CarveEvent.Found -> {
                                synchronized(resultsMap) { resultsMap[event.item.id] = event.item }
                                publishResults(false)
                            }
                            is CarveEvent.Status -> {
                                _state.update { it.copy(carveStatus = event.message) }
                            }
                        }
                    }
                }
                publishResults(true)
                val found = synchronized(resultsMap) { resultsMap.size }
                val summary = when {
                    result.cancelled -> "Carve stopped · ${Fmt.count(found)} files rebuilt"
                    result.outputLimitHit -> "Output limit reached · ${Fmt.count(found)} files rebuilt"
                    result.resultLimitHit -> "Result limit reached · ${Fmt.count(found)} files rebuilt"
                    found == 0 -> "No file signatures found"
                    else -> "Carve complete · ${Fmt.count(found)} files · ${Fmt.bytes(result.bytesWritten)}"
                }
                _state.update {
                    it.copy(
                        carving = false,
                        carveStatus = summary,
                        lastCarve = result,
                    )
                }
                recordActivity(
                    type = ActivityType.CARVE,
                    found = found,
                    elapsedMs = result.elapsedMs,
                    cancelled = result.cancelled,
                    detail = displayName,
                )
                if (!result.cancelled && found == 0) {
                    toast("No signatures found. Look for a raw .img/.dd created with dd.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                publishResults(true)
                _state.update {
                    it.copy(
                        carving = false,
                        carveStatus = "Carve failed. Try another image.",
                    )
                }
                toast("Carve failed: ${t.message ?: "unknown error"}")
            } finally {
                runCatching { source.close() }
            }
        }
    }

    fun cancelCarve() {
        if (!_state.value.carving) return
        carveCancelled.set(true)
        _state.update { it.copy(carveStatus = "Stopping carve…") }
    }

    private fun prepareStaging(): File {
        val parent = File(context.getExternalFilesDir(null) ?: context.filesDir, "carve")
        parent.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        val dir = File(parent, "run_${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }

    // ---------------------------------------------------------------- results & recovery

    private fun publishResults(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        synchronized(publishLock) {
            if (!force && now - lastPublish < 500) return
            lastPublish = now
        }
        val list = synchronized(resultsMap) { resultsMap.values.toList() }
        _state.update { it.copy(results = list) }
    }

    fun toggleSelect(id: String) {
        _state.update {
            val selected = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id
            it.copy(selectedIds = selected)
        }
    }

    fun selectAll(ids: Collection<String>) {
        _state.update { it.copy(selectedIds = ids.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet()) }
    }

    fun setFilter(kind: MediaKind?) {
        _state.update { it.copy(filter = kind) }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setSort(sort: SortMode) {
        _state.update { it.copy(sort = sort) }
    }

    fun setConfidenceFilter(filter: ConfidenceFilter) {
        _state.update { it.copy(confidenceFilter = filter) }
    }

    fun setDuplicatesOnly(enabled: Boolean) {
        _state.update { it.copy(duplicatesOnly = enabled) }
    }

    fun exportReport() {
        if (_state.value.reporting) return
        val items = _state.value.results
        if (items.isEmpty()) {
            toast("Nothing to export yet")
            return
        }
        _state.update { it.copy(reporting = true) }
        viewModelScope.launch {
            val location = withBackgroundIo {
                runCatching { ReportExporter.export(context, items) }.getOrNull()
            }
            _state.update { it.copy(reporting = false) }
            toast(
                if (location != null) "Report saved to $location"
                else "Could not write the report",
            )
        }
    }

    fun showPreview(file: RecoveredFile) {
        _state.update { it.copy(showPreview = file) }
    }

    fun dismissPreview() {
        _state.update { it.copy(showPreview = null) }
    }

    fun recoverSelected() {
        val snapshot = _state.value
        val items = snapshot.results.filter { it.id in snapshot.selectedIds }
        if (items.isEmpty()) {
            toast("Select files to recover first")
            return
        }
        recoverItems(items)
    }

    fun recoverOne(file: RecoveredFile) {
        recoverItems(listOf(file))
    }

    private fun recoverItems(items: List<RecoveredFile>) {
        if (items.isEmpty() || _state.value.recovering) return
        _state.update {
            it.copy(
                recovering = true,
                recoverText = "Recovering ${items.size} file(s)…",
                selectedIds = emptySet(),
                showPreview = null,
            )
        }
        viewModelScope.launch {
            var done = 0
            val saved = withContext(Dispatchers.IO) {
                val list = mutableListOf<SavedItem>()
                var lastUpdate = 0L
                items.forEachIndexed { index, item ->
                    val result = RecoverySaver.save(context, item) { bytes ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate > 350) {
                            lastUpdate = now
                            _state.update {
                                it.copy(recoverText = "Recovering ${index + 1}/${items.size} · ${Fmt.bytes(bytes)}")
                            }
                        }
                    }
                    if (result != null) list += result
                    done++
                    _state.update {
                        it.copy(recoverText = "Recovered $done/${items.size} files…")
                    }
                }
                list
            }
            _state.update {
                it.copy(
                    recovering = false,
                    recoverText = "",
                    recovered = (saved + it.recovered).distinctBy { r -> r.uri ?: r.path ?: r.id },
                )
            }
            goTab(Screen.VAULT)
            saveRecovered()
            if (saved.isEmpty()) {
                toast("Recovery failed — the source may be unavailable")
            } else {
                toast("Recovered ${saved.size} file(s) to Downloads/Phoenix Recover")
            }
        }
    }

    // ---------------------------------------------------------------- root

    fun openRoot() {
        push(Screen.ROOT)
        if (_state.value.rootStatus == null && !_state.value.rootBusy) probeRoot()
    }

    fun probeRoot() {
        if (_state.value.rootBusy) return
        _state.update { it.copy(rootBusy = true, rootProgress = "Checking for root…") }
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { runCatching { RootUtil.probe() }.getOrDefault(com.phoenix.recover.util.RootStatus(false, "Root probe failed")) }
            val devices = if (status.rooted) {
                withContext(Dispatchers.IO) { runCatching { RootUtil.listBlockDevices() }.getOrDefault(emptyList()) }
            } else {
                emptyList()
            }
            _state.update {
                it.copy(
                    rootBusy = false,
                    rootStatus = status,
                    rootDevices = devices,
                    rootProgress = "",
                )
            }
        }
    }

    fun createRawImage(device: BlockDevice, sizeMb: Int) {
        val snapshot = _state.value
        if (snapshot.rootBusy) return
        if (snapshot.rootStatus?.rooted != true) {
            toast("Root access is required for raw partition imaging")
            return
        }
        rootCancelled.set(false)
        _state.update { it.copy(rootBusy = true, rootProgress = "Starting raw read…") }
        viewModelScope.launch {
            val rawDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "raw")
            rawDir.mkdirs()
            val needed = sizeMb.toLong() * 1024L * 1024L
            val free = StorageUtil.freeSpaceFor(rawDir.absolutePath)
            if (free in 1 until needed + 128L * 1024 * 1024) {
                _state.update { it.copy(rootBusy = false, rootProgress = "") }
                toast("Not enough free space for a ${sizeMb} MB image")
                return@launch
            }
            val dest = File(rawDir, "raw_${device.name}_${System.currentTimeMillis()}.img")
            val started = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) {
                RootUtil.createImage(
                    devicePath = device.target,
                    dest = dest,
                    maxBytes = needed,
                    isCancelled = { rootCancelled.get() },
                ) { bytes ->
                    _state.update { it.copy(rootProgress = "Reading partition · ${Fmt.bytes(bytes)}") }
                }
            }
            if (result.isSuccess) {
                _state.update { it.copy(rootBusy = false, rootProgress = "") }
                recordActivity(
                    type = ActivityType.ROOT,
                    found = 0,
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                    cancelled = false,
                    detail = "${device.name} · ${sizeMb} MB",
                )
                startCarveFromFile(result.getOrThrow(), "${device.name}.img")
            } else {
                _state.update { it.copy(rootBusy = false, rootProgress = "") }
                toast(result.exceptionOrNull()?.message ?: "Raw imaging failed")
            }
        }
    }

    fun cancelRoot() {
        rootCancelled.set(true)
        _state.update { it.copy(rootProgress = "Stopping…") }
    }

    // ---------------------------------------------------------------- settings

    private fun loadSettings(): Settings = Settings(
        accentKey = prefs.getString("accent", "aurora") ?: "aurora",
        maxDepth = prefs.getInt("depth", 14),
        includeHidden = prefs.getBoolean("hidden", true),
        detectUnindexed = prefs.getBoolean("unindexed", true),
        minSizeKb = prefs.getInt("min_kb", 2),
        maxOutputMb = prefs.getInt("max_out_mb", 2048),
        maxCarveFileMb = prefs.getInt("max_file_mb", 4096),
    )

    fun updateSettings(settings: Settings) {
        prefs.edit()
            .putString("accent", settings.accentKey)
            .putInt("depth", settings.maxDepth)
            .putBoolean("hidden", settings.includeHidden)
            .putBoolean("unindexed", settings.detectUnindexed)
            .putInt("min_kb", settings.minSizeKb)
            .putInt("max_out_mb", settings.maxOutputMb)
            .putInt("max_file_mb", settings.maxCarveFileMb)
            .apply()
        _state.update { it.copy(settings = settings) }
    }

    // ---------------------------------------------------------------- persistence

    private fun saveRecovered() {
        runCatching {
            val array = JSONArray()
            _state.value.recovered.take(300).forEach { item ->
                array.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("kind", item.kind.name)
                        put("size", item.size)
                        put("uri", item.uri)
                        put("path", item.path)
                        put("savedAt", item.savedAt)
                    },
                )
            }
            prefs.edit().putString("recovered_json", array.toString()).apply()
        }
    }

    private fun readRecovered(): List<SavedItem> = runCatching {
        val raw = prefs.getString("recovered_json", null) ?: return@runCatching emptyList()
        val array = JSONArray(raw)
        val items = mutableListOf<SavedItem>()
        for (i in 0 until min(array.length(), 300)) {
            val o = array.getJSONObject(i)
            items += SavedItem(
                id = o.optString("id"),
                name = o.optString("name"),
                kind = runCatching { MediaKind.valueOf(o.optString("kind")) }.getOrDefault(MediaKind.OTHER),
                size = o.optLong("size"),
                uri = o.optString("uri").takeIf { it.isNotEmpty() && it != "null" },
                path = o.optString("path").takeIf { it.isNotEmpty() && it != "null" },
                savedAt = o.optLong("savedAt"),
            )
        }
        items
    }.getOrDefault(emptyList())

    private fun recordActivity(
        type: ActivityType,
        found: Int,
        elapsedMs: Long,
        cancelled: Boolean,
        detail: String,
    ) {
        val entry = ActivityEntry(
            type = type,
            found = found,
            elapsedMs = elapsedMs,
            timestamp = System.currentTimeMillis(),
            cancelled = cancelled,
            detail = detail,
        )
        val history = (listOf(entry) + _state.value.history).take(12)
        _state.update { it.copy(history = history) }
        saveHistory(history)
    }

    private fun saveHistory(history: List<ActivityEntry>) {
        runCatching {
            val array = JSONArray()
            history.forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put("type", entry.type.name)
                        put("found", entry.found)
                        put("elapsed", entry.elapsedMs)
                        put("timestamp", entry.timestamp)
                        put("cancelled", entry.cancelled)
                        put("detail", entry.detail)
                    },
                )
            }
            prefs.edit().putString("activity_json", array.toString()).apply()
        }
    }

    private fun loadHistory(): List<ActivityEntry> = runCatching {
        val raw = prefs.getString("activity_json", null) ?: return@runCatching emptyList()
        val array = JSONArray(raw)
        val items = mutableListOf<ActivityEntry>()
        for (i in 0 until min(array.length(), 12)) {
            val o = array.getJSONObject(i)
            items += ActivityEntry(
                type = runCatching { ActivityType.valueOf(o.optString("type")) }
                    .getOrDefault(ActivityType.SCAN),
                found = o.optInt("found"),
                elapsedMs = o.optLong("elapsed"),
                timestamp = o.optLong("timestamp"),
                cancelled = o.optBoolean("cancelled"),
                detail = o.optString("detail"),
            )
        }
        items
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- misc

    /**
     * Runs heavy storage work on the IO pool at background thread priority so
     * scanning or carving never competes with the UI thread for CPU time.
     * The previous priority is restored because IO threads are shared.
     */
    private suspend fun <T> withBackgroundIo(block: () -> T): T = withContext(Dispatchers.IO) {
        val previous = runCatching { Process.getThreadPriority(Process.myTid()) }
            .getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            block()
        } finally {
            runCatching { Process.setThreadPriority(previous) }
        }
    }

    fun toast(message: String) {
        _state.update { it.copy(toast = message) }
    }

    fun clearToast() {
        _state.update { it.copy(toast = null) }
    }

    override fun onCleared() {
        scanCancelled.set(true)
        carveCancelled.set(true)
        rootCancelled.set(true)
        super.onCleared()
    }
}
