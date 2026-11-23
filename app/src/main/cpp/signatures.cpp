#include "engine_core.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstring>
#include <string>
#include <unordered_set>

namespace phoenix {
namespace {

using Bytes = std::vector<uint8_t>;

Bytes bytes(std::initializer_list<int> values) {
    Bytes out;
    out.reserve(values.size());
    for (int v : values) out.push_back(static_cast<uint8_t>(v));
    return out;
}

Bytes text(const char* value, size_t length) {
    Bytes out;
    out.reserve(length);
    for (size_t i = 0; i < length; ++i) out.push_back(static_cast<uint8_t>(value[i]));
    return out;
}

Bytes text(const char* value) { return text(value, std::strlen(value)); }

int u8(const uint8_t* b, int i) { return static_cast<int>(b[i]); }

int u16be(const uint8_t* b, int i) {
    return (u8(b, i) << 8) | u8(b, i + 1);
}

int u16le(const uint8_t* b, int i) {
    return u8(b, i) | (u8(b, i + 1) << 8);
}

int64_t u32be(const uint8_t* b, int i) {
    return (static_cast<int64_t>(u8(b, i)) << 24) |
           (static_cast<int64_t>(u8(b, i + 1)) << 16) |
           (static_cast<int64_t>(u8(b, i + 2)) << 8) |
           static_cast<int64_t>(u8(b, i + 3));
}

int64_t u32le(const uint8_t* b, int i) {
    return static_cast<int64_t>(u8(b, i)) |
           (static_cast<int64_t>(u8(b, i + 1)) << 8) |
           (static_cast<int64_t>(u8(b, i + 2)) << 16) |
           (static_cast<int64_t>(u8(b, i + 3)) << 24);
}

int64_t u64le(const uint8_t* b, int i) {
    int64_t value = 0;
    for (int k = 7; k >= 0; --k) {
        value = (value << 8) | static_cast<int64_t>(u8(b, i + k));
    }
    return value;
}

std::string ascii(const uint8_t* b, int i, int len, int n) {
    if (i < 0 || i + len > n) return std::string();
    std::string out;
    out.reserve(static_cast<size_t>(len));
    for (int k = 0; k < len; ++k) out.push_back(static_cast<char>(b[i + k]));
    return out;
}

bool iequals(const std::string& a, const std::string& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        if (std::tolower(static_cast<unsigned char>(a[i])) !=
            std::tolower(static_cast<unsigned char>(b[i]))) {
            return false;
        }
    }
    return true;
}

bool markerOk(int m) {
    return (m >= 0xE0 && m <= 0xEF) || m == 0xDB || m == 0xC4 || m == 0xCC ||
           m == 0xDD || m == 0xFE || (m >= 0xC0 && m <= 0xCF);
}

const Bytes JPEG = bytes({0xFF, 0xD8, 0xFF});
const Bytes RIFF = text("RIFF");
const Bytes FTYP = text("ftyp");
const Bytes ZIP = bytes({0x50, 0x4B, 0x03, 0x04});
const Bytes PNG_FOOTER = bytes({0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82});
const Bytes CR2 = bytes({0x49, 0x49, 0x2A, 0x00, 0x10, 0x00, 0x00, 0x00, 0x43, 0x52});
const Bytes TIFF_LE = bytes({0x49, 0x49, 0x2A, 0x00});
const Bytes TIFF_BE = bytes({0x4D, 0x4D, 0x00, 0x2A});
const Bytes PSD = text("8BPS");
const Bytes ICO = bytes({0x00, 0x00, 0x01, 0x00});
const Bytes FLV = text("FLV");
const Bytes ASF = bytes({0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11,
                         0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C});
const Bytes TS = bytes({0x47});
const Bytes ADTS_MPEG2 = bytes({0xFF, 0xF9});
const Bytes ADTS_MPEG4 = bytes({0xFF, 0xF1});
const Bytes MIDI = text("MThd");
const Bytes GZIP = bytes({0x1F, 0x8B, 0x08});
const Bytes BZIP2 = text("BZh");
const Bytes XZ = bytes({0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00});
const Bytes RTF = text("{\\rtf");

std::vector<FileSignature> buildSignatures() {
    const std::vector<std::string> heifBrands = {
        "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
        "mif1", "msf1", "avif", "avis",
    };
    const std::vector<std::string> mp4Brands = {
        "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso7", "iso8",
        "mp41", "mp42", "mp71", "avc1", "M4V ", "M4VP", "dash", "msdh",
        "msix", "mmp4", "f4v ", "F4V ", "XAVC", "mif2",
    };
    const std::vector<std::string> movBrands = {"qt  "};
    const std::vector<std::string> m4aBrands = {"M4A ", "M4B ", "M4P ", "F4A ", "F4B ", "M4R "};
    const std::vector<std::string> gp3Brands = {
        "3gp1", "3gp2", "3gp3", "3gp4", "3gp5", "3gp6", "3gp7", "3gp8",
        "3gp9", "3ge6", "3ge7", "3gg6", "3gs7", "3g2a", "3g2b", "3g2c",
    };

    std::vector<FileSignature> out;

    auto add = [&out](FileSignature sig) { out.push_back(std::move(sig)); };

    add({"JPEG", "jpg", "image/jpeg", MediaKind::IMAGE, JPEG, 0, {}, false,
         EndMode::JPEG_SCAN, bytes({0xFF, 0xD9}), true,
         300LL * 1024 * 1024, 2048, 88, Validator::JPEG});
    add({"PNG", "png", "image/png", MediaKind::IMAGE,
         bytes({0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}), 0, {}, false,
         EndMode::FOOTER, PNG_FOOTER, true, 600LL * 1024 * 1024, 2048, 96,
         Validator::NONE});
    add({"GIF", "gif", "image/gif", MediaKind::IMAGE, text("GIF89a"), 0, {}, false,
         EndMode::FOOTER, bytes({0x00, 0x3B}), true, 200LL * 1024 * 1024, 2048, 90,
         Validator::NONE});
    add({"GIF", "gif", "image/gif", MediaKind::IMAGE, text("GIF87a"), 0, {}, false,
         EndMode::FOOTER, bytes({0x00, 0x3B}), true, 200LL * 1024 * 1024, 2048, 88,
         Validator::NONE});
    add({"BMP", "bmp", "image/bmp", MediaKind::IMAGE, text("BM"), 0, {}, false,
         EndMode::BMP_SIZE, {}, false, 200LL * 1024 * 1024, 2048, 80,
         Validator::NONE});
    add({"WebP", "webp", "image/webp", MediaKind::IMAGE, RIFF, 0, {}, false,
         EndMode::RIFF, {}, false, 300LL * 1024 * 1024, 2048, 93, Validator::WEBP});
    add({"HEIC", "heic", "image/heic", MediaKind::IMAGE, FTYP, 4, heifBrands, true,
         EndMode::MP4_BOX, {}, false, 500LL * 1024 * 1024, 2048, 92, Validator::NONE});
    add({"MP4", "mp4", "video/mp4", MediaKind::VIDEO, FTYP, 4, mp4Brands, true,
         EndMode::MP4_BOX, {}, false, 12LL * 1024 * 1024 * 1024, 2048, 92,
         Validator::NONE});
    add({"MOV", "mov", "video/quicktime", MediaKind::VIDEO, FTYP, 4, movBrands, true,
         EndMode::MP4_BOX, {}, false, 12LL * 1024 * 1024 * 1024, 2048, 90,
         Validator::NONE});
    add({"3GP", "3gp", "video/3gpp", MediaKind::VIDEO, FTYP, 4, gp3Brands, true,
         EndMode::MP4_BOX, {}, false, 4LL * 1024 * 1024 * 1024, 2048, 90,
         Validator::NONE});
    add({"M4A", "m4a", "audio/mp4", MediaKind::AUDIO, FTYP, 4, m4aBrands, true,
         EndMode::MP4_BOX, {}, false, 2LL * 1024 * 1024 * 1024, 2048, 88,
         Validator::NONE});
    add({"AVI", "avi", "video/x-msvideo", MediaKind::VIDEO, RIFF, 0, {}, false,
         EndMode::RIFF, {}, false, 8LL * 1024 * 1024 * 1024, 2048, 90, Validator::AVI});
    add({"WAV", "wav", "audio/wav", MediaKind::AUDIO, RIFF, 0, {}, false,
         EndMode::RIFF, {}, false, 4LL * 1024 * 1024 * 1024, 2048, 92, Validator::WAV});
    add({"Matroska", "mkv", "video/x-matroska", MediaKind::VIDEO,
         bytes({0x1A, 0x45, 0xDF, 0xA3}), 0, {}, false, EndMode::EBML, {}, false,
         16LL * 1024 * 1024 * 1024, 2048, 92, Validator::NONE});
    add({"MP3", "mp3", "audio/mpeg", MediaKind::AUDIO, text("ID3"), 0, {}, false,
         EndMode::MP3_FRAMES, {}, false, 800LL * 1024 * 1024, 2048, 86,
         Validator::NONE});
    add({"FLAC", "flac", "audio/flac", MediaKind::AUDIO, text("fLaC"), 0, {}, false,
         EndMode::NEXT_SIGNATURE, {}, false, 800LL * 1024 * 1024, 2048, 88,
         Validator::NONE});
    add({"OGG", "ogg", "audio/ogg", MediaKind::AUDIO, text("OggS"), 0, {}, false,
         EndMode::OGG_PAGES, {}, false, 800LL * 1024 * 1024, 2048, 90, Validator::OGG});
    add({"AMR", "amr", "audio/amr", MediaKind::AUDIO, text("#!AMR"), 0, {}, false,
         EndMode::AMR_FRAMES, {}, false, 80LL * 1024 * 1024, 2048, 88,
         Validator::NONE});
    add({"PDF", "pdf", "application/pdf", MediaKind::DOCUMENT, text("%PDF-"), 0, {},
         false, EndMode::PDF_EOF, {}, false, 1500LL * 1024 * 1024, 2048, 94,
         Validator::NONE});
    add({"ZIP", "zip", "application/zip", MediaKind::ARCHIVE, ZIP, 0, {}, false,
         EndMode::ZIP_EOCD, {}, false, 6LL * 1024 * 1024 * 1024, 2048, 90,
         Validator::NONE});
    add({"RAR", "rar", "application/vnd.rar", MediaKind::ARCHIVE, text("Rar!"), 0, {},
         false, EndMode::NEXT_SIGNATURE, {}, false, 6LL * 1024 * 1024 * 1024, 2048,
         88, Validator::NONE});
    add({"7-Zip", "7z", "application/x-7z-compressed", MediaKind::ARCHIVE,
         bytes({0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C}), 0, {}, false,
         EndMode::NEXT_SIGNATURE, {}, false, 6LL * 1024 * 1024 * 1024, 2048, 92,
         Validator::NONE});
    add({"SQLite", "db", "application/x-sqlite3", MediaKind::DOCUMENT,
         text("SQLite format 3\0", 16), 0, {}, false, EndMode::SQLITE, {}, false,
         3LL * 1024 * 1024 * 1024, 2048, 96, Validator::NONE});
    add({"OLE", "doc", "application/x-ole-storage", MediaKind::DOCUMENT,
         bytes({0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1}), 0, {}, false,
         EndMode::NEXT_SIGNATURE, {}, false, 500LL * 1024 * 1024, 2048, 78,
         Validator::NONE});

    // --- Extended recovery set ------------------------------------------------
    // RAW camera formats (CR2/NEF/ARW/DNG) are TIFF containers. The IFD walker
    // below follows every strip/tile pointer so the full image is recovered.
    add({"CR2", "cr2", "image/x-canon-cr2", MediaKind::IMAGE, CR2, 0, {}, false,
         EndMode::TIFF_IFD, {}, false, 4LL * 1024 * 1024 * 1024, 2048, 94,
         Validator::NONE});
    add({"TIFF", "tif", "image/tiff", MediaKind::IMAGE, TIFF_LE, 0, {}, false,
         EndMode::TIFF_IFD, {}, false, 4LL * 1024 * 1024 * 1024, 2048, 93,
         Validator::TIFF});
    add({"TIFF", "tif", "image/tiff", MediaKind::IMAGE, TIFF_BE, 0, {}, false,
         EndMode::TIFF_IFD, {}, false, 4LL * 1024 * 1024 * 1024, 2048, 93,
         Validator::TIFF});
    add({"PSD", "psd", "image/vnd.adobe.photoshop", MediaKind::IMAGE, PSD, 0, {},
         false, EndMode::PSD_SIZE, {}, false, 2LL * 1024 * 1024 * 1024, 2048, 92,
         Validator::PSD});
    add({"ICO", "ico", "image/x-icon", MediaKind::IMAGE, ICO, 0, {}, false,
         EndMode::ICO_SIZE, {}, false, 32LL * 1024 * 1024, 512, 80, Validator::ICO});
    add({"FLV", "flv", "video/x-flv", MediaKind::VIDEO, FLV, 0, {}, false,
         EndMode::FLV_TAGS, {}, false, 8LL * 1024 * 1024 * 1024, 2048, 90,
         Validator::FLV});
    add({"WMV", "wmv", "video/x-ms-wmv", MediaKind::VIDEO, ASF, 0, {}, false,
         EndMode::ASF_OBJECTS, {}, false, 12LL * 1024 * 1024 * 1024, 2048, 90,
         Validator::ASF});
    add({"MPEG-TS", "ts", "video/mp2t", MediaKind::VIDEO, TS, 0, {}, false,
         EndMode::TS_PACKETS, {}, false, 16LL * 1024 * 1024 * 1024, 188 * 3, 84,
         Validator::TS});
    add({"AAC", "aac", "audio/aac", MediaKind::AUDIO, ADTS_MPEG4, 0, {}, false,
         EndMode::ADTS_FRAMES, {}, false, 800LL * 1024 * 1024, 512, 84,
         Validator::ADTS});
    add({"AAC", "aac", "audio/aac", MediaKind::AUDIO, ADTS_MPEG2, 0, {}, false,
         EndMode::ADTS_FRAMES, {}, false, 800LL * 1024 * 1024, 512, 84,
         Validator::ADTS});
    add({"MIDI", "mid", "audio/midi", MediaKind::AUDIO, MIDI, 0, {}, false,
         EndMode::MIDI_TRACKS, {}, false, 200LL * 1024 * 1024, 64, 90,
         Validator::MIDI});
    add({"GZIP", "gz", "application/gzip", MediaKind::ARCHIVE, GZIP, 0, {}, false,
         EndMode::NEXT_SIGNATURE, {}, false, 2LL * 1024 * 1024 * 1024, 512, 88,
         Validator::NONE});
    add({"BZIP2", "bz2", "application/x-bzip2", MediaKind::ARCHIVE, BZIP2, 0, {},
         false, EndMode::NEXT_SIGNATURE, {}, false, 2LL * 1024 * 1024 * 1024, 512,
         88, Validator::BZIP2});
    add({"XZ", "xz", "application/x-xz", MediaKind::ARCHIVE, XZ, 0, {}, false,
         EndMode::NEXT_SIGNATURE, {}, false, 2LL * 1024 * 1024 * 1024, 512, 90,
         Validator::NONE});
    add({"RTF", "rtf", "application/rtf", MediaKind::DOCUMENT, RTF, 0, {}, false,
         EndMode::NEXT_SIGNATURE, {}, false, 200LL * 1024 * 1024, 512, 82,
         Validator::RTF});

    return out;
}

std::array<std::vector<const FileSignature*>, 256> buildIndex(
    const std::vector<FileSignature>& signatures) {
    std::array<std::vector<const FileSignature*>, 256> index;
    for (const auto& sig : signatures) {
        if (sig.header.empty()) continue;
        index[sig.header[0]].push_back(&sig);
    }
    return index;
}

bool inSet(const std::unordered_set<std::string>& set, const std::string& value) {
    return set.find(value) != set.end();
}

std::string lowerExt(const std::string& name) {
    const size_t dot = name.find_last_of('.');
    if (dot == std::string::npos) return std::string();
    std::string ext = name.substr(dot + 1);
    for (char& c : ext) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return ext;
}

enum class KindHint {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
};

bool kindForExt(const std::string& ext, KindHint& hint) {
    static const std::unordered_set<std::string> image = {
        "jpg", "jpeg", "jfif", "png", "gif", "webp", "bmp", "heic", "heif",
        "avif", "tif", "tiff", "dng", "raw", "cr2", "nef", "arw", "orf", "rw2",
        "psd", "psb", "ico", "cur", "svg", "jxl",
    };
    static const std::unordered_set<std::string> video = {
        "mp4", "mov", "3gp", "mkv", "webm", "avi", "m4v", "flv", "wmv", "ts",
        "mpg", "mpeg", "asf", "mts", "m2ts", "vob", "rmvb", "divx",
    };
    static const std::unordered_set<std::string> audio = {
        "mp3", "wav", "flac", "ogg", "oga", "amr", "m4a", "aac", "opus", "wma",
        "mid", "midi", "m4b", "m4r", "aiff", "aif", "ape", "ac3",
    };
    static const std::unordered_set<std::string> doc = {
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf",
        "db", "sqlite", "json", "xml", "html", "htm", "epub", "odt", "ods",
        "odp", "md", "log",
    };
    static const std::unordered_set<std::string> archive = {
        "zip", "rar", "7z", "apk", "tar", "gz", "iso", "jar", "bz2", "xz", "tgz",
        "tbz2", "zst", "cab",
    };
    if (inSet(image, ext)) { hint = KindHint::IMAGE; return true; }
    if (inSet(video, ext)) { hint = KindHint::VIDEO; return true; }
    if (inSet(audio, ext)) { hint = KindHint::AUDIO; return true; }
    if (inSet(doc, ext)) { hint = KindHint::DOCUMENT; return true; }
    if (inSet(archive, ext)) { hint = KindHint::ARCHIVE; return true; }
    return false;
}

}  // namespace

const std::vector<FileSignature>& allSignatures() {
    static const std::vector<FileSignature> signatures = buildSignatures();
    return signatures;
}

const FileSignature* matchSignature(const uint8_t* buffer, int index, int length) {
    if (index < 0 || index >= length) return nullptr;
    static const auto indexTable = buildIndex(allSignatures());
    const auto& candidates = indexTable[buffer[index]];
    for (const FileSignature* sig : candidates) {
        const int headerEnd = index + static_cast<int>(sig->header.size());
        if (headerEnd > length) continue;
        bool ok = true;
        for (size_t k = 0; k < sig->header.size(); ++k) {
            if (buffer[index + static_cast<int>(k)] != sig->header[k]) {
                ok = false;
                break;
            }
        }
        if (!ok) continue;
        if (sig->hasBrands) {
            if (headerEnd + 4 > length) continue;
            const std::string brand = ascii(buffer, headerEnd, 4, length);
            bool found = false;
            for (const auto& b : sig->brands) {
                if (b == brand) { found = true; break; }
            }
            if (!found) continue;
        }
        switch (sig->validator) {
            case Validator::NONE:
                break;
            case Validator::JPEG:
                if (!(index + 3 < length && markerOk(u8(buffer, index + 3)) &&
                      u8(buffer, index + 3) != 0xD8)) {
                    continue;
                }
                break;
            case Validator::WEBP:
                if (!(index + 12 <= length && ascii(buffer, index + 8, 4, length) == "WEBP")) {
                    continue;
                }
                break;
            case Validator::AVI:
                if (!(index + 12 <= length && ascii(buffer, index + 8, 4, length) == "AVI ")) {
                    continue;
                }
                break;
            case Validator::WAV:
                if (!(index + 12 <= length && ascii(buffer, index + 8, 4, length) == "WAVE")) {
                    continue;
                }
                break;
            case Validator::OGG:
                if (!(index + 5 <= length && u8(buffer, index + 4) == 0)) continue;
                break;
            case Validator::TIFF: {
                if (index + 8 > length) continue;
                const bool little = buffer[index] == 0x49;
                const int64_t ifd = little ? u32le(buffer, index + 4)
                                           : u32be(buffer, index + 4);
                if (ifd < 8) continue;
                break;
            }
            case Validator::PSD: {
                if (index + 26 > length) continue;
                const int version = u16be(buffer, index + 4);
                const int channels = u16be(buffer, index + 12);
                const int64_t height = u32be(buffer, index + 14);
                const int64_t width = u32be(buffer, index + 18);
                if ((version != 1 && version != 2) || channels < 1 || channels > 56) {
                    continue;
                }
                if (height <= 0 || width <= 0) continue;
                break;
            }
            case Validator::ICO: {
                if (index + 6 > length) continue;
                const int type = u16le(buffer, index + 2);
                const int count = u16le(buffer, index + 4);
                if ((type != 1 && type != 2) || count < 1 || count > 1024) continue;
                break;
            }
            case Validator::FLV: {
                if (index + 9 > length) continue;
                if (u8(buffer, index + 3) != 1) continue;
                const int64_t dataOffset = u32be(buffer, index + 5);
                if (dataOffset < 9 || dataOffset > 4096) continue;
                break;
            }
            case Validator::ASF: {
                if (index + 24 > length) continue;
                const int64_t headerSize = u64le(buffer, index + 16);
                if (headerSize < 30) continue;
                break;
            }
            case Validator::TS: {
                if (index + 188 * 3 > length) continue;
                if (buffer[index + 188] != 0x47 || buffer[index + 376] != 0x47) {
                    continue;
                }
                break;
            }
            case Validator::ADTS: {
                if (index + 7 > length) continue;
                if ((u8(buffer, index + 1) & 0xF6) != 0xF0) continue;
                const int frameLen = ((u8(buffer, index + 3) & 0x03) << 11) |
                                     (u8(buffer, index + 4) << 3) |
                                     (u8(buffer, index + 5) >> 5);
                if (frameLen < 7) continue;
                // Require the next frame to carry an ADTS sync word too, which
                // rules out random 0xFFF? byte pairs in unrelated data.
                const int next = index + frameLen;
                if (next + 7 <= length) {
                    if (u8(buffer, next) != 0xFF ||
                        (u8(buffer, next + 1) & 0xF6) != 0xF0) {
                        continue;
                    }
                    const int nextLen = ((u8(buffer, next + 3) & 0x03) << 11) |
                                        (u8(buffer, next + 4) << 3) |
                                        (u8(buffer, next + 5) >> 5);
                    if (nextLen < 7) continue;
                }
                break;
            }
            case Validator::MIDI: {
                if (index + 8 > length) continue;
                if (u32be(buffer, index + 4) != 6) continue;
                break;
            }
            case Validator::BZIP2: {
                if (index + 4 > length) continue;
                const int digit = u8(buffer, index + 3);
                if (digit < '1' || digit > '9') continue;
                break;
            }
            case Validator::RTF: {
                if (index + 6 > length) continue;
                const int digit = u8(buffer, index + 5);
                if (digit < '0' || digit > '9') continue;
                break;
            }
        }
        return sig;
    }
    return nullptr;
}

const FileSignature* signatureForExtension(const std::string& ext) {
    for (const auto& sig : allSignatures()) {
        if (iequals(sig.ext, ext)) return &sig;
    }
    return nullptr;
}

bool sniffBytes(const uint8_t* b, int n, DetectedFile& out) {
    if (n < 4) return false;

    auto u = [b](int i) { return u8(b, i); };
    auto a = [b, n](int i, int len) { return ascii(b, i, len, n); };

    if (u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) {
        out = {"JPEG", "jpg", "image/jpeg", MediaKind::IMAGE, 95};
        return true;
    }
    if (n >= 8 && u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47) {
        out = {"PNG", "png", "image/png", MediaKind::IMAGE, 97};
        return true;
    }
    if (a(0, 4) == "GIF8") {
        out = {"GIF", "gif", "image/gif", MediaKind::IMAGE, 95};
        return true;
    }
    if (a(0, 2) == "BM") {
        out = {"BMP", "bmp", "image/bmp", MediaKind::IMAGE, 80};
        return true;
    }
    if (a(0, 4) == "RIFF") {
        const std::string form = a(8, 4);
        if (form == "WEBP") {
            out = {"WebP", "webp", "image/webp", MediaKind::IMAGE, 95};
            return true;
        }
        if (form == "WAVE") {
            out = {"WAV", "wav", "audio/wav", MediaKind::AUDIO, 95};
            return true;
        }
        if (form == "AVI ") {
            out = {"AVI", "avi", "video/x-msvideo", MediaKind::VIDEO, 90};
            return true;
        }
    }
    if (n >= 12 && a(4, 4) == "ftyp") {
        const std::string brand = a(8, 4);
        if (brand == "heic" || brand == "heix" || brand == "hevc" ||
            brand == "mif1" || brand == "msf1" || brand == "avif" ||
            brand == "avis") {
            out = {"HEIC", "heic", "image/heic", MediaKind::IMAGE, 90};
            return true;
        }
        if (brand == "qt  ") {
            out = {"MOV", "mov", "video/quicktime", MediaKind::VIDEO, 90};
            return true;
        }
        if (brand == "M4A " || brand == "M4B " || brand == "M4P ") {
            out = {"M4A", "m4a", "audio/mp4", MediaKind::AUDIO, 88};
            return true;
        }
        if (brand.size() >= 2 && brand[0] == '3' && brand[1] == 'g') {
            out = {"3GP", "3gp", "video/3gpp", MediaKind::VIDEO, 88};
            return true;
        }
        out = {"MP4", "mp4", "video/mp4", MediaKind::VIDEO, 90};
        return true;
    }
    if (u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3) {
        out = {"Matroska", "mkv", "video/x-matroska", MediaKind::VIDEO, 92};
        return true;
    }
    if (a(0, 3) == "ID3") {
        out = {"MP3", "mp3", "audio/mpeg", MediaKind::AUDIO, 85};
        return true;
    }
    if (u(0) == 0xFF) {
        const int b1 = u(1);
        if ((b1 & 0xE0) == 0xE0) {
            const int version = (b1 >> 3) & 0x03;
            const int layer = (b1 >> 1) & 0x03;
            const int bitrateIndex = (u(2) >> 4) & 0x0F;
            const int sampleIndex = (u(2) >> 2) & 0x03;
            if (version != 1 && layer != 0 && bitrateIndex != 0 &&
                bitrateIndex != 15 && sampleIndex != 3) {
                out = {"MP3", "mp3", "audio/mpeg", MediaKind::AUDIO, 78};
                return true;
            }
        }
    }
    if (a(0, 4) == "fLaC") {
        out = {"FLAC", "flac", "audio/flac", MediaKind::AUDIO, 92};
        return true;
    }
    if (a(0, 4) == "OggS") {
        out = {"OGG", "ogg", "audio/ogg", MediaKind::AUDIO, 90};
        return true;
    }
    if (a(0, 5) == "#!AMR") {
        out = {"AMR", "amr", "audio/amr", MediaKind::AUDIO, 90};
        return true;
    }
    if (a(0, 5) == "%PDF-") {
        out = {"PDF", "pdf", "application/pdf", MediaKind::DOCUMENT, 96};
        return true;
    }
    if (a(0, 4) == "Rar!") {
        out = {"RAR", "rar", "application/vnd.rar", MediaKind::ARCHIVE, 90};
        return true;
    }
    if (u(0) == 0x37 && u(1) == 0x7A && u(2) == 0xBC && u(3) == 0xAF) {
        out = {"7-Zip", "7z", "application/x-7z-compressed", MediaKind::ARCHIVE, 94};
        return true;
    }
    if (a(0, 15) == "SQLite format 3") {
        out = {"SQLite", "db", "application/x-sqlite3", MediaKind::DOCUMENT, 96};
        return true;
    }
    if (u(0) == 0x50 && u(1) == 0x4B &&
        (u(2) == 3 || u(2) == 5 || u(2) == 7)) {
        out = {"ZIP", "zip", "application/zip", MediaKind::ARCHIVE, 88};
        return true;
    }
    if (u(0) == 0xD0 && u(1) == 0xCF && u(2) == 0x11 && u(3) == 0xE0) {
        out = {"OLE", "doc", "application/msword", MediaKind::DOCUMENT, 78};
        return true;
    }
    if (n >= 8 && u(0) == 0x49 && u(1) == 0x49 && u(2) == 0x2A && u(3) == 0x00) {
        if (n >= 10 && a(8, 2) == "CR") {
            out = {"CR2", "cr2", "image/x-canon-cr2", MediaKind::IMAGE, 92};
        } else {
            out = {"TIFF", "tif", "image/tiff", MediaKind::IMAGE, 92};
        }
        return true;
    }
    if (n >= 8 && u(0) == 0x4D && u(1) == 0x4D && u(2) == 0x00 && u(3) == 0x2A) {
        out = {"TIFF", "tif", "image/tiff", MediaKind::IMAGE, 92};
        return true;
    }
    if (a(0, 4) == "8BPS") {
        out = {"PSD", "psd", "image/vnd.adobe.photoshop", MediaKind::IMAGE, 92};
        return true;
    }
    if (u(0) == 0x00 && u(1) == 0x00 && u(2) == 0x01 && u(3) == 0x00) {
        out = {"ICO", "ico", "image/x-icon", MediaKind::IMAGE, 80};
        return true;
    }
    if (a(0, 3) == "FLV") {
        out = {"FLV", "flv", "video/x-flv", MediaKind::VIDEO, 90};
        return true;
    }
    if (n >= 16 && u(0) == 0x30 && u(1) == 0x26 && u(2) == 0xB2 && u(3) == 0x75) {
        out = {"WMV", "wmv", "video/x-ms-wmv", MediaKind::VIDEO, 90};
        return true;
    }
    if (n >= 188 * 3 && u(0) == 0x47 && u(188) == 0x47 && u(376) == 0x47) {
        out = {"MPEG-TS", "ts", "video/mp2t", MediaKind::VIDEO, 84};
        return true;
    }
    if (n >= 7 && u(0) == 0xFF && (u(1) & 0xF6) == 0xF0) {
        out = {"AAC", "aac", "audio/aac", MediaKind::AUDIO, 84};
        return true;
    }
    if (a(0, 4) == "MThd") {
        out = {"MIDI", "mid", "audio/midi", MediaKind::AUDIO, 90};
        return true;
    }
    if (n >= 3 && u(0) == 0x1F && u(1) == 0x8B && u(2) == 0x08) {
        out = {"GZIP", "gz", "application/gzip", MediaKind::ARCHIVE, 88};
        return true;
    }
    if (a(0, 3) == "BZh") {
        out = {"BZIP2", "bz2", "application/x-bzip2", MediaKind::ARCHIVE, 88};
        return true;
    }
    if (n >= 6 && u(0) == 0xFD && u(1) == 0x37 && u(2) == 0x7A && u(3) == 0x58 &&
        u(4) == 0x5A && u(5) == 0x00) {
        out = {"XZ", "xz", "application/x-xz", MediaKind::ARCHIVE, 90};
        return true;
    }
    if (a(0, 5) == "{\\rtf") {
        out = {"RTF", "rtf", "application/rtf", MediaKind::DOCUMENT, 82};
        return true;
    }
    return false;
}

bool detectFromName(const std::string& name, DetectedFile& out) {
    const std::string ext = lowerExt(name);
    if (ext.empty()) return false;

    KindHint hint;
    if (!kindForExt(ext, hint)) return false;

    MediaKind kind = MediaKind::OTHER;
    switch (hint) {
        case KindHint::IMAGE: kind = MediaKind::IMAGE; break;
        case KindHint::VIDEO: kind = MediaKind::VIDEO; break;
        case KindHint::AUDIO: kind = MediaKind::AUDIO; break;
        case KindHint::DOCUMENT: kind = MediaKind::DOCUMENT; break;
        case KindHint::ARCHIVE: kind = MediaKind::ARCHIVE; break;
    }

    std::string mime = "application/octet-stream";
    if (ext == "jpg" || ext == "jpeg") mime = "image/jpeg";
    else if (ext == "png") mime = "image/png";
    else if (ext == "gif") mime = "image/gif";
    else if (ext == "webp") mime = "image/webp";
    else if (ext == "heic" || ext == "heif") mime = "image/heic";
    else if (ext == "mp4" || ext == "m4v") mime = "video/mp4";
    else if (ext == "mov") mime = "video/quicktime";
    else if (ext == "3gp") mime = "video/3gpp";
    else if (ext == "mkv" || ext == "webm") mime = "video/x-matroska";
    else if (ext == "avi") mime = "video/x-msvideo";
    else if (ext == "mp3") mime = "audio/mpeg";
    else if (ext == "wav") mime = "audio/wav";
    else if (ext == "flac") mime = "audio/flac";
    else if (ext == "ogg" || ext == "oga") mime = "audio/ogg";
    else if (ext == "amr") mime = "audio/amr";
    else if (ext == "m4a") mime = "audio/mp4";
    else if (ext == "pdf") mime = "application/pdf";
    else if (ext == "doc" || ext == "docx") mime = "application/msword";
    else if (ext == "xls" || ext == "xlsx") mime = "application/vnd.ms-excel";
    else if (ext == "ppt" || ext == "pptx") mime = "application/vnd.ms-powerpoint";
    else if (ext == "zip") mime = "application/zip";
    else if (ext == "rar") mime = "application/vnd.rar";
    else if (ext == "7z") mime = "application/x-7z-compressed";
    else if (ext == "apk") mime = "application/vnd.android.package-archive";
    else if (ext == "db" || ext == "sqlite") mime = "application/x-sqlite3";
    else if (ext == "tif" || ext == "tiff") mime = "image/tiff";
    else if (ext == "cr2") mime = "image/x-canon-cr2";
    else if (ext == "nef") mime = "image/x-nikon-nef";
    else if (ext == "arw") mime = "image/x-sony-arw";
    else if (ext == "dng") mime = "image/x-adobe-dng";
    else if (ext == "psd" || ext == "psb") mime = "image/vnd.adobe.photoshop";
    else if (ext == "ico" || ext == "cur") mime = "image/x-icon";
    else if (ext == "flv") mime = "video/x-flv";
    else if (ext == "wmv" || ext == "asf") mime = "video/x-ms-wmv";
    else if (ext == "ts" || ext == "mts" || ext == "m2ts") mime = "video/mp2t";
    else if (ext == "aac") mime = "audio/aac";
    else if (ext == "mid" || ext == "midi") mime = "audio/midi";
    else if (ext == "gz" || ext == "tgz") mime = "application/gzip";
    else if (ext == "bz2" || ext == "tbz2") mime = "application/x-bzip2";
    else if (ext == "xz") mime = "application/x-xz";
    else if (ext == "rtf") mime = "application/rtf";
    else if (ext == "epub") mime = "application/epub+zip";
    else if (ext == "html" || ext == "htm") mime = "text/html";
    else if (ext == "txt" || ext == "md" || ext == "log") mime = "text/plain";

    std::string format = ext;
    for (char& c : format) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));

    out = {format, ext, mime, kind, 35};
    return true;
}

}  // namespace phoenix
