package com.phoenix.recover.engine

import java.io.File
import java.io.FileInputStream

data class DetectedFile(
    val format: String,
    val ext: String,
    val mime: String,
    val kind: MediaKind,
    val confidence: Int,
)

/**
 * Identifies a real file from its magic bytes. Extension is only used as a fallback,
 * so renamed or extension-less deleted files are still classified correctly.
 */
object Sniffer {

    private val imageExt = setOf("jpg", "jpeg", "jfif", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff", "dng", "raw", "cr2", "nef", "arw", "orf", "rw2")
    private val videoExt = setOf("mp4", "mov", "3gp", "mkv", "webm", "avi", "m4v", "flv", "wmv", "ts", "mpg", "mpeg")
    private val audioExt = setOf("mp3", "wav", "flac", "ogg", "oga", "amr", "m4a", "aac", "opus", "wma", "mid")
    private val docExt = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "db", "sqlite", "json", "xml")
    private val archiveExt = setOf("zip", "rar", "7z", "apk", "tar", "gz", "iso", "jar")

    /** Extensions worth opening during a scan; avoids reading every random file. */
    val candidateExtensions: Set<String> = imageExt + videoExt + audioExt + docExt + archiveExt

    fun detect(file: File): DetectedFile? {
        val head = ByteArray(4096)
        val n = try {
            FileInputStream(file).use { stream ->
                var total = 0
                while (total < head.size) {
                    val read = stream.read(head, total, head.size - total)
                    if (read <= 0) break
                    total += read
                }
                total
            }
        } catch (_: Exception) {
            -1
        }
        if (n > 0) {
            sniff(head, n)?.let { return it }
        }
        return fromName(file.name)
    }

    fun fromName(name: String): DetectedFile? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        val kind = when (ext) {
            in imageExt -> MediaKind.IMAGE
            in videoExt -> MediaKind.VIDEO
            in audioExt -> MediaKind.AUDIO
            in docExt -> MediaKind.DOCUMENT
            in archiveExt -> MediaKind.ARCHIVE
            else -> return null
        }
        val mime = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "mkv", "webm" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "amr" -> "audio/amr"
            "m4a" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "apk" -> "application/vnd.android.package-archive"
            "db", "sqlite" -> "application/x-sqlite3"
            else -> "application/octet-stream"
        }
        return DetectedFile(ext.uppercase(), ext, mime, kind, 35)
    }

    fun sniff(b: ByteArray, n: Int): DetectedFile? {
        if (n < 4) return null
        fun u(i: Int) = b[i].toInt() and 0xFF
        fun a(i: Int, len: Int): String {
            if (i + len > n) return ""
            return buildString { for (k in 0 until len) append(u(i + k).toChar()) }
        }
        if (u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) {
            return DetectedFile("JPEG", "jpg", "image/jpeg", MediaKind.IMAGE, 95)
        }
        if (n >= 8 && u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47) {
            return DetectedFile("PNG", "png", "image/png", MediaKind.IMAGE, 97)
        }
        if (a(0, 4) == "GIF8") return DetectedFile("GIF", "gif", "image/gif", MediaKind.IMAGE, 95)
        if (a(0, 2) == "BM") return DetectedFile("BMP", "bmp", "image/bmp", MediaKind.IMAGE, 80)
        if (a(0, 4) == "RIFF") {
            when (a(8, 4)) {
                "WEBP" -> return DetectedFile("WebP", "webp", "image/webp", MediaKind.IMAGE, 95)
                "WAVE" -> return DetectedFile("WAV", "wav", "audio/wav", MediaKind.AUDIO, 95)
                "AVI " -> return DetectedFile("AVI", "avi", "video/x-msvideo", MediaKind.VIDEO, 90)
            }
        }
        if (n >= 12 && a(4, 4) == "ftyp") {
            val brand = a(8, 4)
            return when {
                brand in setOf("heic", "heix", "hevc", "mif1", "msf1", "avif", "avis") ->
                    DetectedFile("HEIC", "heic", "image/heic", MediaKind.IMAGE, 90)
                brand == "qt  " -> DetectedFile("MOV", "mov", "video/quicktime", MediaKind.VIDEO, 90)
                brand in setOf("M4A ", "M4B ", "M4P ") -> DetectedFile("M4A", "m4a", "audio/mp4", MediaKind.AUDIO, 88)
                brand.startsWith("3g") -> DetectedFile("3GP", "3gp", "video/3gpp", MediaKind.VIDEO, 88)
                else -> DetectedFile("MP4", "mp4", "video/mp4", MediaKind.VIDEO, 90)
            }
        }
        if (u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3) {
            return DetectedFile("Matroska", "mkv", "video/x-matroska", MediaKind.VIDEO, 92)
        }
        if (a(0, 3) == "ID3") return DetectedFile("MP3", "mp3", "audio/mpeg", MediaKind.AUDIO, 85)
        if (u(0) == 0xFF) {
            val b1 = u(1)
            if ((b1 and 0xE0) == 0xE0) {
                val version = (b1 shr 3) and 0x03
                val layer = (b1 shr 1) and 0x03
                val bitrateIndex = (u(2) shr 4) and 0x0F
                val sampleIndex = (u(2) shr 2) and 0x03
                if (version != 1 && layer != 0 && bitrateIndex != 0 && bitrateIndex != 15 && sampleIndex != 3) {
                    return DetectedFile("MP3", "mp3", "audio/mpeg", MediaKind.AUDIO, 78)
                }
            }
        }
        if (a(0, 4) == "fLaC") return DetectedFile("FLAC", "flac", "audio/flac", MediaKind.AUDIO, 92)
        if (a(0, 4) == "OggS") return DetectedFile("OGG", "ogg", "audio/ogg", MediaKind.AUDIO, 90)
        if (a(0, 5) == "#!AMR") return DetectedFile("AMR", "amr", "audio/amr", MediaKind.AUDIO, 90)
        if (a(0, 5) == "%PDF-") return DetectedFile("PDF", "pdf", "application/pdf", MediaKind.DOCUMENT, 96)
        if (a(0, 4) == "Rar!") return DetectedFile("RAR", "rar", "application/vnd.rar", MediaKind.ARCHIVE, 90)
        if (u(0) == 0x37 && u(1) == 0x7A && u(2) == 0xBC && u(3) == 0xAF) {
            return DetectedFile("7-Zip", "7z", "application/x-7z-compressed", MediaKind.ARCHIVE, 94)
        }
        if (a(0, 15) == "SQLite format 3") {
            return DetectedFile("SQLite", "db", "application/x-sqlite3", MediaKind.DOCUMENT, 96)
        }
        if (u(0) == 0x50 && u(1) == 0x4B && (u(2) == 3 || u(2) == 5 || u(2) == 7)) {
            return DetectedFile("ZIP", "zip", "application/zip", MediaKind.ARCHIVE, 88)
        }
        if (u(0) == 0xD0 && u(1) == 0xCF && u(2) == 0x11 && u(3) == 0xE0) {
            return DetectedFile("OLE", "doc", "application/msword", MediaKind.DOCUMENT, 78)
        }
        return null
    }
}
