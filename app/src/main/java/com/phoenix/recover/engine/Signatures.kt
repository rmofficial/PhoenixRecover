package com.phoenix.recover.engine

enum class EndMode {
    FOOTER,
    JPEG_SCAN,
    RIFF,
    BMP_SIZE,
    MP4_BOX,
    ZIP_EOCD,
    PDF_EOF,
    MP3_FRAMES,
    OGG_PAGES,
    AMR_FRAMES,
    EBML,
    SQLITE,
    NEXT_SIGNATURE,
}

data class FileSignature(
    val format: String,
    val ext: String,
    val mime: String,
    val kind: MediaKind,
    val header: ByteArray,
    val headerOffset: Int = 0,
    val brands: Set<String>? = null,
    val endMode: EndMode,
    val footer: ByteArray? = null,
    val maxSize: Long,
    val minSize: Long = 2_048L,
    val score: Int = 90,
    val validate: ((ByteArray, Int, Int) -> Boolean)? = null,
)

private fun b(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private fun s(text: String) = text.toByteArray(Charsets.ISO_8859_1)

private fun markerOk(m: Int): Boolean =
    m in 0xE0..0xEF || m == 0xDB || m == 0xC4 || m == 0xCC || m == 0xDD ||
        m == 0xFE || (m in 0xC0..0xCF)

internal object Signatures {

    private val JPEG = b(0xFF, 0xD8, 0xFF)
    private val RIFF = s("RIFF")
    private val FTYP = s("ftyp")
    private val ZIP = b(0x50, 0x4B, 0x03, 0x04)
    private val PNG_FOOTER = b(0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82)

    private val HEIF_BRANDS = setOf(
        "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
        "mif1", "msf1", "avif", "avis",
    )
    private val MP4_BRANDS = setOf(
        "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso7", "iso8",
        "mp41", "mp42", "mp71", "avc1", "M4V ", "M4VP", "dash", "msdh",
        "msix", "mmp4", "f4v ", "F4V ", "XAVC", "mif2",
    )
    private val MOV_BRANDS = setOf("qt  ")
    private val M4A_BRANDS = setOf("M4A ", "M4B ", "M4P ", "F4A ", "F4B ", "M4R ")
    private val GP3_BRANDS = setOf(
        "3gp1", "3gp2", "3gp3", "3gp4", "3gp5", "3gp6", "3gp7", "3gp8",
        "3gp9", "3ge6", "3ge7", "3gg6", "3gs7", "3g2a", "3g2b", "3g2c",
    )

    val ALL: List<FileSignature> = listOf(
        FileSignature(
            "JPEG", "jpg", "image/jpeg", MediaKind.IMAGE,
            header = JPEG, endMode = EndMode.JPEG_SCAN,
            footer = b(0xFF, 0xD9), maxSize = 300L * 1024 * 1024,
            score = 88,
            validate = { buf, i, n -> i + 3 < n && markerOk(Bytes.u8(buf, i + 3)) && Bytes.u8(buf, i + 3) != 0xD8 },
        ),
        FileSignature(
            "PNG", "png", "image/png", MediaKind.IMAGE,
            header = b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            endMode = EndMode.FOOTER, footer = PNG_FOOTER,
            maxSize = 600L * 1024 * 1024, score = 96,
        ),
        FileSignature(
            "GIF", "gif", "image/gif", MediaKind.IMAGE,
            header = s("GIF89a"), endMode = EndMode.FOOTER,
            footer = b(0x00, 0x3B), maxSize = 200L * 1024 * 1024, score = 90,
        ),
        FileSignature(
            "GIF", "gif", "image/gif", MediaKind.IMAGE,
            header = s("GIF87a"), endMode = EndMode.FOOTER,
            footer = b(0x00, 0x3B), maxSize = 200L * 1024 * 1024, score = 88,
        ),
        FileSignature(
            "BMP", "bmp", "image/bmp", MediaKind.IMAGE,
            header = s("BM"), endMode = EndMode.BMP_SIZE,
            maxSize = 200L * 1024 * 1024, score = 80,
        ),
        FileSignature(
            "WebP", "webp", "image/webp", MediaKind.IMAGE,
            header = RIFF, endMode = EndMode.RIFF, maxSize = 300L * 1024 * 1024,
            score = 93, validate = { buf, i, n -> i + 12 <= n && Bytes.ascii(buf, i + 8, 4) == "WEBP" },
        ),
        FileSignature(
            "HEIC", "heic", "image/heic", MediaKind.IMAGE,
            header = FTYP, headerOffset = 4, brands = HEIF_BRANDS,
            endMode = EndMode.MP4_BOX, maxSize = 500L * 1024 * 1024, score = 92,
        ),
        FileSignature(
            "MP4", "mp4", "video/mp4", MediaKind.VIDEO,
            header = FTYP, headerOffset = 4, brands = MP4_BRANDS,
            endMode = EndMode.MP4_BOX, maxSize = 12L * 1024 * 1024 * 1024, score = 92,
        ),
        FileSignature(
            "MOV", "mov", "video/quicktime", MediaKind.VIDEO,
            header = FTYP, headerOffset = 4, brands = MOV_BRANDS,
            endMode = EndMode.MP4_BOX, maxSize = 12L * 1024 * 1024 * 1024, score = 90,
        ),
        FileSignature(
            "3GP", "3gp", "video/3gpp", MediaKind.VIDEO,
            header = FTYP, headerOffset = 4, brands = GP3_BRANDS,
            endMode = EndMode.MP4_BOX, maxSize = 4L * 1024 * 1024 * 1024, score = 90,
        ),
        FileSignature(
            "M4A", "m4a", "audio/mp4", MediaKind.AUDIO,
            header = FTYP, headerOffset = 4, brands = M4A_BRANDS,
            endMode = EndMode.MP4_BOX, maxSize = 2L * 1024 * 1024 * 1024, score = 88,
        ),
        FileSignature(
            "AVI", "avi", "video/x-msvideo", MediaKind.VIDEO,
            header = RIFF, endMode = EndMode.RIFF, maxSize = 8L * 1024 * 1024 * 1024,
            score = 90, validate = { buf, i, n -> i + 12 <= n && Bytes.ascii(buf, i + 8, 4) == "AVI " },
        ),
        FileSignature(
            "WAV", "wav", "audio/wav", MediaKind.AUDIO,
            header = RIFF, endMode = EndMode.RIFF, maxSize = 4L * 1024 * 1024 * 1024,
            score = 92, validate = { buf, i, n -> i + 12 <= n && Bytes.ascii(buf, i + 8, 4) == "WAVE" },
        ),
        FileSignature(
            "Matroska", "mkv", "video/x-matroska", MediaKind.VIDEO,
            header = b(0x1A, 0x45, 0xDF, 0xA3), endMode = EndMode.EBML,
            maxSize = 16L * 1024 * 1024 * 1024, score = 92,
        ),
        FileSignature(
            "MP3", "mp3", "audio/mpeg", MediaKind.AUDIO,
            header = s("ID3"), endMode = EndMode.MP3_FRAMES,
            maxSize = 800L * 1024 * 1024, score = 86,
        ),
        FileSignature(
            "FLAC", "flac", "audio/flac", MediaKind.AUDIO,
            header = s("fLaC"), endMode = EndMode.NEXT_SIGNATURE,
            maxSize = 800L * 1024 * 1024, score = 88,
        ),
        FileSignature(
            "OGG", "ogg", "audio/ogg", MediaKind.AUDIO,
            header = s("OggS"), endMode = EndMode.OGG_PAGES,
            maxSize = 800L * 1024 * 1024, score = 90,
            validate = { buf, i, n -> i + 5 <= n && Bytes.u8(buf, i + 4) == 0 },
        ),
        FileSignature(
            "AMR", "amr", "audio/amr", MediaKind.AUDIO,
            header = s("#!AMR"), endMode = EndMode.AMR_FRAMES,
            maxSize = 80L * 1024 * 1024, score = 88,
        ),
        FileSignature(
            "PDF", "pdf", "application/pdf", MediaKind.DOCUMENT,
            header = s("%PDF-"), endMode = EndMode.PDF_EOF,
            maxSize = 1_500L * 1024 * 1024, score = 94,
        ),
        FileSignature(
            "ZIP", "zip", "application/zip", MediaKind.ARCHIVE,
            header = ZIP, endMode = EndMode.ZIP_EOCD,
            maxSize = 6L * 1024 * 1024 * 1024, score = 90,
        ),
        FileSignature(
            "RAR", "rar", "application/vnd.rar", MediaKind.ARCHIVE,
            header = s("Rar!"), endMode = EndMode.NEXT_SIGNATURE,
            maxSize = 6L * 1024 * 1024 * 1024, score = 88,
        ),
        FileSignature(
            "7-Zip", "7z", "application/x-7z-compressed", MediaKind.ARCHIVE,
            header = b(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C),
            endMode = EndMode.NEXT_SIGNATURE,
            maxSize = 6L * 1024 * 1024 * 1024, score = 92,
        ),
        FileSignature(
            "SQLite", "db", "application/x-sqlite3", MediaKind.DOCUMENT,
            header = s("SQLite format 3\u0000"), endMode = EndMode.SQLITE,
            maxSize = 3L * 1024 * 1024 * 1024, score = 96,
        ),
        FileSignature(
            "OLE", "doc", "application/x-ole-storage", MediaKind.DOCUMENT,
            header = b(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1),
            endMode = EndMode.NEXT_SIGNATURE,
            maxSize = 500L * 1024 * 1024, score = 78,
        ),
    )

    private val byFirstByte: Map<Int, List<FileSignature>> =
        ALL.groupBy { it.header[0].toInt() and 0xFF }

    fun match(buffer: ByteArray, i: Int, n: Int): FileSignature? {
        if (i < 0 || i >= n) return null
        val candidates = byFirstByte[Bytes.u8(buffer, i)] ?: return null
        for (sig in candidates) {
            val headerEnd = i + sig.header.size
            if (headerEnd > n) continue
            var ok = true
            for (k in sig.header.indices) {
                if (buffer[i + k] != sig.header[k]) {
                    ok = false
                    break
                }
            }
            if (!ok) continue
            if (sig.brands != null) {
                if (headerEnd + 4 > n) continue
                val brand = Bytes.ascii(buffer, headerEnd, 4)
                if (brand !in sig.brands) continue
            }
            if (sig.validate != null && !sig.validate(buffer, i, n)) continue
            return sig
        }
        return null
    }

    fun signatureForExtension(ext: String): FileSignature? =
        ALL.firstOrNull { it.ext.equals(ext, ignoreCase = true) }
}
