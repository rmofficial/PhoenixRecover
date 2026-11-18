package com.phoenix.recover.engine

import java.io.File

/**
 * Callback implemented in Kotlin and called from the C++ carver through JNI.
 */
internal interface CarveCallback {
    fun isCancelled(): Boolean
    fun onStatus(message: String)
    fun onProgress(processed: Long, total: Long, found: Int, bytesWritten: Long)
    fun onFound(
        id: String,
        name: String,
        kind: String,
        size: Long,
        mime: String,
        confidence: Int,
        offset: Long,
        detail: String,
        format: String,
        path: String,
    )
}

/**
 * Callback implemented in Kotlin and called from the C++ deep scanner through JNI.
 */
internal interface ScanCallback {
    fun isCancelled(): Boolean
    fun onStatus(message: String)
    fun onProgress(dirs: Long, filesSeen: Long, found: Long, currentPath: String)
    fun onFound(
        id: String,
        name: String,
        kind: String,
        size: Long,
        origin: String,
        mime: String,
        confidence: Int,
        sourcePath: String,
        detail: String,
        format: String,
        ext: String,
        trashName: Boolean,
    )
}

/**
 * JNI bridge to the native recovery engine. When the native library is not
 * present (for example in JVM unit tests) every call returns null and the
 * pure-Kotlin implementation is used instead.
 */
internal object NativeEngine {

    private val loaded: Boolean = try {
        System.loadLibrary("phoenix_engine")
        true
    } catch (_: Throwable) {
        false
    }

    private val trashNumberPrefix = Regex("^\\.?trashed-\\d+-")
    private val trashPrefix = Regex("^\\.?trashed-")
    private val pendingPrefix = Regex("^\\.?pending-\\d+-")

    val available: Boolean get() = loaded

    fun sniff(data: ByteArray, length: Int): DetectedFile? {
        if (!loaded) return null
        val row = runCatching { nativeSniff(data, length) }.getOrNull() ?: return null
        if (row.size < 5) return null
        val kind = runCatching { MediaKind.valueOf(row[3]) }.getOrDefault(MediaKind.OTHER)
        return DetectedFile(row[0], row[1], row[2], kind, row[4].toIntOrNull() ?: 0)
    }

    fun carve(
        source: SeekableSource,
        outputDir: File?,
        options: CarveOptions,
        isCancelled: () -> Boolean,
        onEvent: (CarveEvent) -> Unit,
    ): CarveResult? {
        if (!loaded) return null

        val optionArray = longArrayOf(
            options.minSize,
            options.maxFileSize,
            options.maxResults.toLong(),
            options.maxOutputBytes,
            options.maxBytesToScan,
            if (options.writeOutput) 1L else 0L,
        )
        val extensions = options.allowedExtensions.toTypedArray()
        val kinds = options.kinds.map { it.name }.toTypedArray()
        val output = outputDir?.absolutePath ?: ""

        val callback = object : CarveCallback {
            override fun isCancelled(): Boolean = isCancelled()

            override fun onStatus(message: String) {
                onEvent(CarveEvent.Status(message))
            }

            override fun onProgress(
                processed: Long,
                total: Long,
                found: Int,
                bytesWritten: Long,
            ) {
                onEvent(CarveEvent.Progress(processed, total, found, bytesWritten))
            }

            override fun onFound(
                id: String,
                name: String,
                kind: String,
                size: Long,
                mime: String,
                confidence: Int,
                offset: Long,
                detail: String,
                format: String,
                path: String,
            ) {
                onEvent(
                    CarveEvent.Found(
                        RecoveredFile(
                            id = id,
                            name = name,
                            kind = runCatching { MediaKind.valueOf(kind) }
                                .getOrDefault(MediaKind.OTHER),
                            size = size,
                            origin = Origin.CARVE,
                            mime = mime,
                            confidence = confidence,
                            offset = offset,
                            detail = detail,
                            scratchPath = path.ifEmpty { null },
                            format = format,
                        ),
                    ),
                )
            }
        }

        val result = try {
            val path = source.nativePath()
            if (path != null) {
                nativeCarvePath(path, output, optionArray, extensions, kinds, callback)
            } else {
                val fd = source.nativeFd()
                if (fd < 0) return null
                nativeCarveFd(fd, source.length, output, optionArray, extensions, kinds, callback)
            }
        } catch (_: UnsatisfiedLinkError) {
            return null
        } ?: return null

        if (result.size < 8) return null
        return CarveResult(
            bytesExamined = result[0],
            filesFound = result[1].toInt(),
            filesWritten = result[2].toInt(),
            bytesWritten = result[3],
            cancelled = result[4] != 0L,
            outputLimitHit = result[5] != 0L,
            resultLimitHit = result[6] != 0L,
            elapsedMs = result[7],
            perKind = emptyMap(),
        )
    }

    fun deepScan(
        options: DeepScanOptions,
        isCancelled: () -> Boolean,
        onEvent: (ScanEvent) -> Unit,
    ): ScanResult? {
        if (!loaded) return null

        val optionArray = longArrayOf(
            options.maxDepth.toLong(),
            if (options.includeHidden) 1L else 0L,
            if (options.trashOnly) 1L else 0L,
            options.minSize,
            options.maxResults.toLong(),
            if (options.detectUnindexed) 1L else 0L,
            options.maxFilesToInspect.toLong(),
        )
        val roots = options.roots.toTypedArray()
        val indexed = options.indexedPaths.toTypedArray()

        val callback = object : ScanCallback {
            override fun isCancelled(): Boolean = isCancelled()

            override fun onStatus(message: String) {
                onEvent(ScanEvent.Status(message))
            }

            override fun onProgress(
                dirs: Long,
                filesSeen: Long,
                found: Long,
                currentPath: String,
            ) {
                onEvent(
                    ScanEvent.Progress(
                        dirs = dirs.toInt(),
                        filesSeen = filesSeen.toInt(),
                        found = found.toInt(),
                        currentPath = currentPath,
                    ),
                )
            }

            override fun onFound(
                id: String,
                name: String,
                kind: String,
                size: Long,
                origin: String,
                mime: String,
                confidence: Int,
                sourcePath: String,
                detail: String,
                format: String,
                ext: String,
                trashName: Boolean,
            ) {
                val cleaned = if (trashName) {
                    name
                        .replace(trashNumberPrefix, "")
                        .replace(trashPrefix, "")
                        .replace(pendingPrefix, "")
                        .ifBlank { name }
                } else {
                    name
                }
                val displayName = if (cleaned.isBlank()) {
                    "recovered_${name.hashCode()}.$ext"
                } else {
                    cleaned
                }
                onEvent(
                    ScanEvent.Found(
                        RecoveredFile(
                            id = id,
                            name = displayName,
                            kind = runCatching { MediaKind.valueOf(kind) }
                                .getOrDefault(MediaKind.OTHER),
                            size = size,
                            origin = runCatching { Origin.valueOf(origin) }
                                .getOrDefault(Origin.DEEP_SCAN),
                            mime = mime,
                            confidence = confidence,
                            sourcePath = sourcePath,
                            detail = detail,
                            format = format,
                        ),
                    ),
                )
            }
        }

        val result = try {
            nativeDeepScan(roots, optionArray, indexed, callback)
        } catch (_: UnsatisfiedLinkError) {
            return null
        } ?: return null

        if (result.size < 6) return null
        return ScanResult(
            dirsVisited = result[0].toInt(),
            filesInspected = result[1].toInt(),
            found = result[2].toInt(),
            cancelled = result[3] != 0L,
            elapsedMs = result[4],
            errors = result[5].toInt(),
        )
    }

    private external fun nativeSniff(data: ByteArray, length: Int): Array<String>?

    private external fun nativeCarvePath(
        path: String,
        outputDir: String,
        options: LongArray,
        allowedExtensions: Array<String>,
        kinds: Array<String>,
        callback: CarveCallback,
    ): LongArray?

    private external fun nativeCarveFd(
        fd: Int,
        length: Long,
        outputDir: String,
        options: LongArray,
        allowedExtensions: Array<String>,
        kinds: Array<String>,
        callback: CarveCallback,
    ): LongArray?

    private external fun nativeDeepScan(
        roots: Array<String>,
        options: LongArray,
        indexedPaths: Array<String>,
        callback: ScanCallback,
    ): LongArray?
}
