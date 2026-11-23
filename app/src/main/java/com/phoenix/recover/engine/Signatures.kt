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
    TIFF_IFD,
    PSD_SIZE,
    ICO_SIZE,
    FLV_TAGS,
    ASF_OBJECTS,
    TS_PACKETS,
    ADTS_FRAMES,
    MIDI_TRACKS,
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

private fun adtsFrameLength(b: ByteArray, i: Int): Int =
    ((Bytes.u8(b, i + 3) and 0x03) shl 11) or
        (Bytes.u8(b, i + 4) shl 3) or (Bytes.u8(b, i + 5) shr 5)

private fun validAdts(b: ByteArray, i: Int, n: Int): Boolean {
    if (i + 7 > n) return false
    if ((Bytes.u8(b, i + 1) and 0xF6) != 0xF0) return false
    val len = adtsFrameLength(b, i)
    if (len < 7) return false
    val next = i + len
    if (next + 7 <= n) {
        if (Bytes.u8(b, next) != 0xFF || (Bytes.u8(b, next + 1) and 0xF6) != 0xF0) {
            return false
        }
        if (adtsFrameLength(b, next) < 7) return false
    }
    return true
}

internal object Signatures {

    private val JPEG = b(0xFF, 0xD8, 0xFF)
    private val RIFF = s("RIFF")
    private val FTYP = s("ftyp")
    private val ZIP = b(0x50, 0x4B, 0x03, 0x04)
    private val PNG_FOOTER = b(0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82)
    private val CR2 = b(0x49, 0x49, 0x2A, 0x00, 0x10, 0x00, 0x00, 0x00, 0x43, 0x52)
    private val TIFF_LE = b(0x49, 0x49, 0x2A, 0x00)
    private val TIFF_BE = b(0x4D, 0x4D, 0x00, 0x2A)
    private val PSD = s("8BPS")
    private val ICO = b(0x00, 0x00, 0x01, 0x00)
    private val FLV = s("FLV")
    private val ASF = b(
        0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11,
        0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C,
    )
    private val TS = b(0x47)
    private val ADTS_MPEG2 = b(0xFF, 0xF9)
    private val ADTS_MPEG4 = b(0xFF, 0xF1)
    private val MIDI = s("MThd")
    private val GZIP = b(0x1F, 0x8B, 0x08)
    private val BZIP2 = s("BZh")
    private val XZ = b(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)
    private val RTF = s("{\\rtf")

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
        FileSignature(
            "CR2", "cr2", "image/x-canon-cr2", MediaKind.IMAGE,
            header = CR2, endMode = EndMode.TIFF_IFD,
            maxSize = 4L * 1024 * 1024 * 1024, score = 94,
        ),
        FileSignature(
            "TIFF", "tif", "image/tiff", MediaKind.IMAGE,
            header = TIFF_LE, endMode = EndMode.TIFF_IFD,
            maxSize = 4L * 1024 * 1024 * 1024, score = 93,
            validate = { buf, i, n ->
                i + 8 <= n && Bytes.u32le(buf, i + 4) >= 8
            },
        ),
        FileSignature(
            "TIFF", "tif", "image/tiff", MediaKind.IMAGE,
            header = TIFF_BE, endMode = EndMode.TIFF_IFD,
            maxSize = 4L * 1024 * 1024 * 1024, score = 93,
            validate = { buf, i, n ->
                i + 8 <= n && Bytes.u32be(buf, i + 4) >= 8
            },
        ),
        FileSignature(
            "PSD", "psd", "image/vnd.adobe.photoshop", MediaKind.IMAGE,
            header = PSD, endMode = EndMode.PSD_SIZE,
            maxSize = 2L * 1024 * 1024 * 1024, score = 92,
            validate = { buf, i, n ->
                i + 26 <= n &&
                    Bytes.u16be(buf, i + 4) in 1..2 &&
                    Bytes.u16be(buf, i + 12) in 1..56 &&
                    Bytes.u32be(buf, i + 14) > 0 &&
                    Bytes.u32be(buf, i + 18) > 0
            },
        ),
        FileSignature(
            "ICO", "ico", "image/x-icon", MediaKind.IMAGE,
            header = ICO, endMode = EndMode.ICO_SIZE,
            maxSize = 32L * 1024 * 1024, minSize = 512, score = 80,
            validate = { buf, i, n ->
                i + 6 <= n && Bytes.u16le(buf, i + 2).let { it == 1 || it == 2 } &&
                    Bytes.u16le(buf, i + 4) in 1..1024
            },
        ),
        FileSignature(
            "FLV", "flv", "video/x-flv", MediaKind.VIDEO,
            header = FLV, endMode = EndMode.FLV_TAGS,
            maxSize = 8L * 1024 * 1024 * 1024, score = 90,
            validate = { buf, i, n ->
                i + 9 <= n && Bytes.u8(buf, i + 3) == 1 &&
                    Bytes.u32be(buf, i + 5) in 9..4096
            },
        ),
        FileSignature(
            "WMV", "wmv", "video/x-ms-wmv", MediaKind.VIDEO,
            header = ASF, endMode = EndMode.ASF_OBJECTS,
            maxSize = 12L * 1024 * 1024 * 1024, score = 90,
        ),
        FileSignature(
            "MPEG-TS", "ts", "video/mp2t", MediaKind.VIDEO,
            header = TS, endMode = EndMode.TS_PACKETS,
            maxSize = 16L * 1024 * 1024 * 1024, minSize = 188L * 3, score = 84,
            validate = { buf, i, n ->
                i + 188 * 3 <= n && Bytes.u8(buf, i + 188) == 0x47 &&
                    Bytes.u8(buf, i + 376) == 0x47
            },
        ),
        FileSignature(
            "AAC", "aac", "audio/aac", MediaKind.AUDIO,
            header = ADTS_MPEG4, endMode = EndMode.ADTS_FRAMES,
            maxSize = 800L * 1024 * 1024, minSize = 512, score = 84,
            validate = { buf, i, n -> validAdts(buf, i, n) },
        ),
        FileSignature(
            "AAC", "aac", "audio/aac", MediaKind.AUDIO,
            header = ADTS_MPEG2, endMode = EndMode.ADTS_FRAMES,
            maxSize = 800L * 1024 * 1024, minSize = 512, score = 84,
            validate = { buf, i, n -> validAdts(buf, i, n) },
        ),
        FileSignature(
            "MIDI", "mid", "audio/midi", MediaKind.AUDIO,
            header = MIDI, endMode = EndMode.MIDI_TRACKS,
            maxSize = 200L * 1024 * 1024, minSize = 64, score = 90,
            validate = { buf, i, n -> i + 8 <= n && Bytes.u32be(buf, i + 4) == 6L },
        ),
        FileSignature(
            "GZIP", "gz", "application/gzip", MediaKind.ARCHIVE,
            header = GZIP, endMode = EndMode.NEXT_SIGNATURE,
            maxSize = 2L * 1024 * 1024 * 1024, minSize = 512, score = 88,
        ),
        FileSignature(
            "BZIP2", "bz2", "application/x-bzip2", MediaKind.ARCHIVE,
            header = BZIP2, endMode = EndMode.NEXT_SIGNATURE,
            maxSize = 2L * 1024 * 1024 * 1024, minSize = 512, score = 88,
            validate = { buf, i, n ->
                i + 4 <= n && Bytes.u8(buf, i + 3) in '1'.code..'9'.code
            },
        ),
        FileSignature(
            "XZ", "xz", "application/x-xz", MediaKind.ARCHIVE,
            header = XZ, endMode = EndMode.NEXT_SIGNATURE,
            maxSize = 2L * 1024 * 1024 * 1024, minSize = 512, score = 90,
        ),
        FileSignature(
            "RTF", "rtf", "application/rtf", MediaKind.DOCUMENT,
            header = RTF, endMode = EndMode.NEXT_SIGNATURE,
            maxSize = 200L * 1024 * 1024, minSize = 512, score = 82,
            validate = { buf, i, n ->
                i + 6 <= n && Bytes.u8(buf, i + 5) in '0'.code..'9'.code
            },
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
