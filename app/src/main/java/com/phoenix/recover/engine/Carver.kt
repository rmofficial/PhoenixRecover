package com.phoenix.recover.engine

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Streaming signature carver. Reads a seekable byte source (disk image, raw partition
 * image or any file) and rebuilds files from their magic numbers and structural
 * boundaries. All work is bounded and cancellable.
 */
class Carver(
    private val source: SeekableSource,
    private val options: CarveOptions = CarveOptions(),
) {
    private companion object {
        const val BLOCK = 2 * 1024 * 1024
        const val OVERLAP = 128
        const val CHUNK = 256 * 1024
        val MP3_BITRATE_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val MP3_BITRATE_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        val MP3_BITRATE_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
        val MP3_BITRATE_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
        val AMR_NB = intArrayOf(13, 14, 16, 18, 20, 21, 27, 32, 6, 7, 6, 7, 6, 7, 6, 1)
        val AMR_WB = intArrayOf(18, 24, 33, 37, 41, 47, 51, 59, 61, 6, 7, 7, 7, 7, 7, 1)
        val ZIP_EOCD = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
        val PDF_EOF = "%%EOF".toByteArray(Charsets.ISO_8859_1)
    }

    fun carve(
        outputDir: File?,
        isCancelled: () -> Boolean = { false },
        onEvent: (CarveEvent) -> Unit = {},
    ): CarveResult {
        val startedAt = System.currentTimeMillis()
        outputDir?.mkdirs()

        val totalLength = source.length
        val scanEnd = min(totalLength, if (options.maxBytesToScan == Long.MAX_VALUE) totalLength else options.maxBytesToScan)
        val buffer = ByteArray(BLOCK + OVERLAP)

        var offset = 0L
        var filesFound = 0
        var filesWritten = 0
        var bytesWritten = 0L
        var cancelled = false
        var outputLimitHit = false
        var resultLimitHit = false
        val perKind = linkedMapOf<MediaKind, Int>()
        var lastProgressAt = 0L

        onEvent(CarveEvent.Status("Signature engine ready: ${Signatures.ALL.size} formats"))

        while (offset < scanEnd) {
            if (isCancelled()) {
                cancelled = true
                break
            }
            if (filesFound >= options.maxResults) {
                resultLimitHit = true
                break
            }
            if (outputLimitHit || bytesWritten >= options.maxOutputBytes) {
                outputLimitHit = true
                break
            }

            val want = min((BLOCK + OVERLAP).toLong(), totalLength - offset).toInt()
            if (want <= 0) break
            val n = readFully(offset, buffer, want)
            if (n <= 0) break

            val scanStarts = min(BLOCK, n)
            var i = 0
            var jumped = false

            while (i < scanStarts && offset + i < scanEnd) {
                if (isCancelled()) {
                    cancelled = true
                    break
                }
                val sig = Signatures.match(buffer, i, n)
                if (sig != null) {
                    val fileStart = offset + i - sig.headerOffset
                    if (fileStart >= 0 && fileStart < scanEnd) {
                        val end = findEnd(sig, fileStart, isCancelled)
                        if (end > fileStart + 16) {
                            val info = classify(sig, fileStart, end)
                            val size = end - fileStart
                            val kindAllowed = options.kinds.isEmpty() || info.kind in options.kinds
                            val extAllowed = options.allowedExtensions.isEmpty() ||
                                info.ext.lowercase() in options.allowedExtensions.map { it.lowercase() }
                            if (size >= options.minSize && kindAllowed && extAllowed) {
                                if (bytesWritten + size > options.maxOutputBytes) {
                                    outputLimitHit = true
                                    break
                                }
                                val item = buildItem(info, fileStart, end, outputDir)
                                if (outputDir != null && options.writeOutput) {
                                    val out = writeRange(outputDir, item, fileStart, end, isCancelled)
                                    if (out != null) {
                                        filesWritten++
                                        bytesWritten += out.length()
                                        filesFound++
                                        perKind[info.kind] = (perKind[info.kind] ?: 0) + 1
                                        onEvent(CarveEvent.Found(item.copy(scratchPath = out.absolutePath, size = out.length())))
                                    }
                                } else {
                                    filesFound++
                                    perKind[info.kind] = (perKind[info.kind] ?: 0) + 1
                                    onEvent(CarveEvent.Found(item))
                                }
                                if (filesFound >= options.maxResults) break
                            }
                            val next = maxOf(end, offset + i + 1)
                            if (next > offset) {
                                offset = next
                                jumped = true
                                break
                            }
                        }
                    }
                }
                i++
            }

            if (cancelled) break
            if (!jumped) offset += BLOCK.toLong()

            val now = System.currentTimeMillis()
            if (now - lastProgressAt >= 220) {
                lastProgressAt = now
                onEvent(
                    CarveEvent.Progress(
                        processed = min(offset, scanEnd),
                        total = scanEnd,
                        found = filesFound,
                        bytesWritten = bytesWritten,
                    ),
                )
            }
        }

        onEvent(
            CarveEvent.Progress(
                processed = min(offset, scanEnd),
                total = scanEnd,
                found = filesFound,
                bytesWritten = bytesWritten,
            ),
        )

        return CarveResult(
            bytesExamined = min(offset, scanEnd),
            filesFound = filesFound,
            filesWritten = filesWritten,
            bytesWritten = bytesWritten,
            cancelled = cancelled,
            outputLimitHit = outputLimitHit,
            resultLimitHit = resultLimitHit,
            elapsedMs = System.currentTimeMillis() - startedAt,
            perKind = perKind,
        )
    }

    private data class CarvedInfo(
        val format: String,
        val ext: String,
        val mime: String,
        val kind: MediaKind,
    )

    private fun classify(sig: FileSignature, start: Long, end: Long): CarvedInfo {
        if (sig.format == "ZIP") {
            val length = min(384 * 1024L, end - start).toInt()
            if (length > 0) {
                val head = ByteArray(length)
                val n = readFully(start, head, length)
                if (n > 0) {
                    val text = String(head, 0, n, Charsets.ISO_8859_1)
                    return when {
                        text.contains("AndroidManifest.xml") ->
                            CarvedInfo("APK", "apk", "application/vnd.android.package-archive", MediaKind.ARCHIVE)
                        text.contains("word/") ->
                            CarvedInfo("DOCX", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", MediaKind.DOCUMENT)
                        text.contains("xl/") ->
                            CarvedInfo("XLSX", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", MediaKind.DOCUMENT)
                        text.contains("ppt/") ->
                            CarvedInfo("PPTX", "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", MediaKind.DOCUMENT)
                        text.contains("[Content_Types].xml") ->
                            CarvedInfo("Office", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", MediaKind.DOCUMENT)
                        else -> CarvedInfo("ZIP", "zip", "application/zip", MediaKind.ARCHIVE)
                    }
                }
            }
        }
        return CarvedInfo(sig.format, sig.ext, sig.mime, sig.kind)
    }

    private fun buildItem(info: CarvedInfo, start: Long, end: Long, outputDir: File?): RecoveredFile {
        val size = end - start
        val safeFormat = info.format.replace(' ', '_').replace("-", "")
        val name = "${safeFormat}_${start.toString(16)}_${(start / 1024).toString(16)}.${info.ext}"
        return RecoveredFile(
            id = "carve:${start}:${info.format}:$end",
            name = name,
            kind = info.kind,
            size = size,
            origin = Origin.CARVE,
            mime = info.mime,
            confidence = if (info.format == "JPEG" || info.format == "PNG") 96 else 88,
            offset = start,
            detail = "Offset 0x${start.toString(16)} · ${info.format}",
            format = info.format,
            scratchPath = null,
        )
    }

    private fun writeRange(outputDir: File, item: RecoveredFile, start: Long, end: Long, isCancelled: () -> Boolean): File? {
        val file = File(outputDir, item.name)
        return try {
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                val buf = ByteArray(1024 * 1024)
                var p = start
                while (p < end) {
                    if (isCancelled()) return null
                    val want = min(buf.size.toLong(), end - p).toInt()
                    val n = readFully(p, buf, want)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    p += n
                }
            }
            if (file.length() == end - start) file else {
                file.delete()
                null
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun findEnd(sig: FileSignature, start: Long, isCancelled: () -> Boolean): Long {
        val cap = min(source.length, start + min(sig.maxSize, options.maxFileSize))
        if (cap <= start) return -1
        return try {
            when (sig.endMode) {
                EndMode.FOOTER -> findFooter(start + sig.header.size, sig.footer ?: return -1, cap, isCancelled)
                EndMode.JPEG_SCAN -> parseJpeg(start, cap, isCancelled)
                EndMode.RIFF -> {
                    val head = ByteArray(8)
                    if (readFully(start + 4, head, 4) != 4) return -1
                    val size = Bytes.u32le(head, 0) + 8
                    if (size in 12..(cap - start)) start + size else -1
                }
                EndMode.BMP_SIZE -> {
                    val head = ByteArray(4)
                    if (readFully(start + 2, head, 4) != 4) return -1
                    val size = Bytes.u32le(head, 0)
                    if (size in 26..(cap - start)) start + size else -1
                }
                EndMode.MP4_BOX -> parseBoxes(start, cap, isCancelled)
                EndMode.ZIP_EOCD -> findZipEnd(start, cap, isCancelled)
                EndMode.PDF_EOF -> findPdfEnd(start, cap, isCancelled)
                EndMode.MP3_FRAMES -> parseMp3(start, cap, isCancelled)
                EndMode.OGG_PAGES -> parseOgg(start, cap, isCancelled)
                EndMode.AMR_FRAMES -> parseAmr(start, cap)
                EndMode.EBML -> parseEbml(start, cap, isCancelled)
                EndMode.SQLITE -> parseSqlite(start, cap)
                EndMode.NEXT_SIGNATURE -> findNextHeader(start + sig.header.size, cap, isCancelled)
            }
        } catch (_: Exception) {
            -1
        }
    }

    private fun parseJpeg(start: Long, cap: Long, isCancelled: () -> Boolean): Long {
        var p = start + 2
        val marker = ByteArray(2)
        while (p + 2 <= cap) {
            if (isCancelled()) return -1
            if (readFully(p, marker, 2) < 2) return -1
            if ((marker[0].toInt() and 0xFF) != 0xFF) {
                p++
                continue
            }
            val m = marker[1].toInt() and 0xFF
            if (m == 0xFF) {
                p++
                continue
            }
            if (m == 0xD9) return p + 2
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) {
                p += 2
                continue
            }
            if (p + 4 > cap) return -1
            val lenBytes = ByteArray(2)
            if (readFully(p + 2, lenBytes, 2) < 2) return -1
            val segLen = Bytes.u16be(lenBytes, 0)
            if (segLen < 2) return -1
            if (m == 0xDA) {
                var q = p + 2 + segLen
                val buf = ByteArray(CHUNK + 2)
                while (q < cap) {
                    if (isCancelled()) return -1
                    val want = min((CHUNK + 2).toLong(), source.length - q).toInt()
                    if (want <= 0) return -1
                    val n = readFully(q, buf, want)
                    if (n <= 0) return -1
                    var i = 0
                    while (i + 1 < n) {
                        if ((buf[i].toInt() and 0xFF) == 0xFF) {
                            val nx = buf[i + 1].toInt() and 0xFF
                            if (nx == 0xD9) return q + i + 2
                            if (nx == 0x00 || nx in 0xD0..0xD7) {
                                i += 2
                                continue
                            }
                        }
                        i++
                    }
                    if (q + CHUNK >= cap) return -1
                    q += CHUNK - 1
                }
                return -1
            }
            p += 2 + segLen
        }
        return -1
    }

    private fun findFooter(from: Long, footer: ByteArray, maxEnd: Long, isCancelled: () -> Boolean): Long {
        var pos = from
        val buf = ByteArray(CHUNK + footer.size)
        while (pos < maxEnd) {
            if (isCancelled()) return -1
            val want = min((CHUNK + footer.size).toLong(), source.length - pos).toInt()
            if (want <= 0) return -1
            val n = readFully(pos, buf, want)
            if (n <= 0) return -1
            val idx = Bytes.find(buf, n, footer)
            if (idx >= 0) {
                val end = pos + idx + footer.size
                if (end > maxEnd) return -1
                return end
            }
            if (pos + CHUNK >= maxEnd) return -1
            pos += CHUNK
        }
        return -1
    }

    private fun parseBoxes(start: Long, cap: Long, isCancelled: () -> Boolean): Long {
        var p = start
        var boxes = 0
        val head = ByteArray(16)
        while (p + 8 <= cap && boxes < 100_000) {
            if (isCancelled()) return -1
            val n = readFully(p, head, 8)
            if (n < 8) break
            var size = Bytes.u32be(head, 0)
            val type = Bytes.ascii(head, 4, 4)
            var headerSize = 8L
            if (size == 1L) {
                if (readFully(p + 8, head, 8) != 8) break
                size = 0
                for (k in 0 until 8) size = (size shl 8) or (head[k].toLong() and 0xFF)
                headerSize = 16
            }
            if (size == 0L) return cap
            if (size < headerSize) break
            if (type.any { it.code < 0x20 || it.code > 0x7E }) break
            if (p + size > cap) break
            p += size
            boxes++
            if (type == "mdat" || type == "moov") {
                if (p >= cap) break
            }
        }
        return if (p > start + 16) p else -1
    }

    private fun findZipEnd(start: Long, cap: Long, isCancelled: () -> Boolean): Long {
        var pos = start + 20
        val buf = ByteArray(CHUNK + 4)
        while (pos < cap) {
            if (isCancelled()) return -1
            val want = min((CHUNK + 4).toLong(), source.length - pos).toInt()
            if (want <= 0) return -1
            val n = readFully(pos, buf, want)
            if (n <= 0) return -1
            val idx = Bytes.find(buf, n, ZIP_EOCD)
            if (idx >= 0) {
                val abs = pos + idx
                val tail = ByteArray(22)
                if (readFully(abs, tail, 22) == 22) {
                    val commentLen = Bytes.u8(tail, 20) or (Bytes.u8(tail, 21) shl 8)
                    val end = abs + 22 + commentLen
                    if (end <= source.length && end >= start + 22) return end
                }
            }
            if (pos + CHUNK >= cap) return -1
            pos += CHUNK
        }
        return -1
    }

    private fun findPdfEnd(start: Long, cap: Long, isCancelled: () -> Boolean): Long {
        var pos = start
        val buf = ByteArray(CHUNK + PDF_EOF.size)
        while (pos < cap) {
            if (isCancelled()) return -1
            val want = min((CHUNK + PDF_EOF.size).toLong(), source.length - pos).toInt()
            if (want <= 0) return -1
            val n = readFully(pos, buf, want)
            if (n <= 0) return -1
            val idx = Bytes.find(buf, n, PDF_EOF)
            if (idx >= 0) {
                var end = pos + idx + PDF_EOF.size
                val trail = ByteArray(2)
                if (readFully(end, trail, 2) >= 1) {
                    if (trail[0] == '\r'.code.toByte()) end++
                    if (end < source.length) {
                        if (readFully(end, trail, 1) == 1 && trail[0] == '\n'.code.toByte()) end++
                    }
                }
                return end
            }
            if (pos + CHUNK >= cap) return -1
            pos += CHUNK
        }
        return -1
    }

    private data class MpegFrame(val length: Int)

    private fun mpegFrame(h: ByteArray): MpegFrame? {
        if ((h[0].toInt() and 0xFF) != 0xFF) return null
        val b1 = h[1].toInt() and 0xFF
        if (b1 and 0xE0 != 0xE0) return null
        val version = (b1 shr 3) and 0x03
        val layer = (b1 shr 1) and 0x03
        if (version == 1 || layer == 0) return null
        val b2 = h[2].toInt() and 0xFF
        val b3 = h[3].toInt() and 0xFF
        val bitrateIndex = (b2 shr 4) and 0x0F
        val sampleIndex = (b2 shr 2) and 0x03
        val padding = (b2 shr 1) and 0x01
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleIndex == 3) return null
        val v1 = version == 3
        val bitrate = when (layer) {
            1 -> (if (v1) MP3_BITRATE_V1_L3 else MP3_BITRATE_V2_L3)[bitrateIndex] * 1000
            2 -> (if (v1) MP3_BITRATE_V1_L2 else MP3_BITRATE_V2_L3)[bitrateIndex] * 1000
            else -> (if (v1) MP3_BITRATE_V1_L1 else MP3_BITRATE_V1_L2)[bitrateIndex] * 1000
        }
        if (bitrate <= 0) return null
        val sampleRate = when (version) {
            3 -> intArrayOf(44100, 48000, 32000)[sampleIndex]
            2 -> intArrayOf(22050, 24000, 16000)[sampleIndex]
            else -> intArrayOf(11025, 12000, 8000)[sampleIndex]
        }
        val length = when (layer) {
            1 -> if (v1) 144 * bitrate / sampleRate + padding else 72 * bitrate / sampleRate + padding
            2 -> 144 * bitrate / sampleRate + padding
            else -> (12 * bitrate / sampleRate + padding) * 4
        }
        return if (length in 4..8192) MpegFrame(length) else null
    }

    private fun parseMp3(start: Long, cap: Long, isCancelled: () -> Boolean): Long {
        val head = ByteArray(10)
        if (readFully(start, head, 10) < 10) return -1
        if (Bytes.ascii(head, 0, 3) != "ID3") return -1
        val tagSize = (Bytes.u8(head, 6) shl 21) or (Bytes.u8(head, 7) shl 14) or
            (Bytes.u8(head, 8) shl 7) or Bytes.u8(head, 9)
        val flags = Bytes.u8(head, 5)
        var p = start + 10 + tagSize + if (flags and 0x10 != 0) 10 else 0
        if (p >= cap) return -1
        var last = -1L
        var frames = 0
        var misses = 0
        val h = ByteArray(4)
        while (p + 4 <= cap && misses < 4) {
            if (isCancelled()) return last
            if (readFully(p, h, 4) < 4) break
            val frame = mpegFrame(h)
            if (frame == null) {
                misses++
                p++
                continue
            }
            if (p + frame.length > cap) break
            p += frame.length
            last = p
            frames++
            misses = 0
        }
        return if (frames >= 2 && last > 0) last else -1
    }

    private fun parseOgg(start: Long, cap: Long, isCancelled: () -> Boolean): Long {
        var p = start
        var last = -1L
        val head = ByteArray(27)
        while (p + 27 <= cap) {
            if (isCancelled()) return last
            if (readFully(p, head, 27) < 27) break
            if (Bytes.ascii(head, 0, 4) != "OggS") break
            val segCount = Bytes.u8(head, 26)
            if (segCount < 0) break
            val lacing = ByteArray(segCount)
            if (readFully(p + 27, lacing, segCount) < segCount) break
            var dataLen = 0
            for (k in 0 until segCount) dataLen += Bytes.u8(lacing, k)
            val pageEnd = p + 27 + segCount + dataLen
            if (pageEnd > cap) break
            last = pageEnd
            p = pageEnd
        }
        return last
    }

    private fun parseAmr(start: Long, cap: Long): Long {
        val magic = ByteArray(9)
        if (readFully(start, magic, 9) < 6) return -1
        val wide = Bytes.ascii(magic, 5, 4) == "-WB\n" || Bytes.ascii(magic, 5, 3) == "-WB"
        var p = if (wide) start + 9 else start + 6
        var last = -1L
        val table = if (wide) AMR_WB else AMR_NB
        val one = ByteArray(1)
        while (p < cap) {
            if (readFully(p, one, 1) < 1) break
            val ft = (Bytes.u8(one, 0) shr 3) and 0x0F
            val size = table[ft]
            if (size <= 1 || p + size > cap) break
            p += size
            last = p
        }
        return if (last > start + 8) last else -1
    }

    private fun parseEbml(start: Long, cap: Long, isCancelled: () -> Boolean): Long {
        var p = start + 4
        val v = readVint(p, cap) ?: return -1
        val headerEnd = p + v.second + v.first
        if (headerEnd + 4 > cap) return -1
        p = headerEnd
        val id = ByteArray(4)
        if (readFully(p, id, 4) < 4) return -1
        val segmentId = Bytes.u32be(id, 0)
        if (segmentId != 0x18538067L) {
            return findNextHeader(start + 4, cap, isCancelled)
        }
        p += 4
        val seg = readVint(p, cap) ?: return -1
        val dataStart = p + seg.second
        if (seg.first <= 0) return findNextHeader(dataStart, cap, isCancelled)
        val end = dataStart + seg.first
        return if (end <= cap && end > dataStart) end else findNextHeader(dataStart, cap, isCancelled)
    }

    /** Returns (value, vintLength) or null when the unknown-size marker is present. */
    private fun readVint(offset: Long, cap: Long): Pair<Long, Int>? {
        val first = ByteArray(1)
        if (readFully(offset, first, 1) < 1) return null
        val f = Bytes.u8(first, 0)
        if (f == 0) return null
        var mask = 0x80
        var length = 1
        while (mask and f == 0) {
            mask = mask shr 1
            length++
            if (length > 8) return null
        }
        if (offset + length > cap) return null
        val buf = ByteArray(length)
        if (readFully(offset, buf, length) < length) return null
        var value = (Bytes.u8(buf, 0) and (mask - 1)).toLong()
        var allOnes = value == (mask - 1).toLong()
        for (k in 1 until length) {
            val byte = Bytes.u8(buf, k)
            if (byte != 0xFF) allOnes = false
            value = (value shl 8) or byte.toLong()
        }
        if (allOnes) return null
        return value to length
    }

    private fun parseSqlite(start: Long, cap: Long): Long {
        val head = ByteArray(100)
        if (readFully(start, head, 100) < 100) return -1
        if (Bytes.ascii(head, 0, 15) != "SQLite format 3") return -1
        var pageSize = Bytes.u16be(head, 16)
        if (pageSize == 1) pageSize = 65536
        if (pageSize < 512 || pageSize > 65536) return -1
        val pageCount = Bytes.u32be(head, 28)
        if (pageCount <= 0) return -1
        val end = start + pageSize.toLong() * pageCount
        return if (end <= cap) end else -1
    }

    private fun findNextHeader(from: Long, maxEnd: Long, isCancelled: () -> Boolean): Long {
        val chunk = 64 * 1024
        val buf = ByteArray(chunk + OVERLAP)
        var pos = from
        while (pos < maxEnd) {
            if (isCancelled()) return -1
            val want = min((chunk + OVERLAP).toLong(), source.length - pos).toInt()
            if (want <= 0) return -1
            val n = readFully(pos, buf, want)
            if (n <= 0) return -1
            var i = 0
            while (i < min(chunk, n) && pos + i < maxEnd) {
                val sig = Signatures.match(buf, i, n)
                if (sig != null) {
                    val start = pos + i - sig.headerOffset
                    if (start > from) return start
                }
                i++
            }
            pos += chunk
        }
        return -1
    }

    private fun readFully(offset: Long, dst: ByteArray, count: Int): Int {
        if (count <= 0) return 0
        var total = 0
        while (total < count) {
            val r = source.readAt(offset + total, dst, total, count - total)
            if (r <= 0) break
            total += r
        }
        return total
    }
}
