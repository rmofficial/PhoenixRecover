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
    };
    static const std::unordered_set<std::string> video = {
        "mp4", "mov", "3gp", "mkv", "webm", "avi", "m4v", "flv", "wmv", "ts",
        "mpg", "mpeg",
    };
    static const std::unordered_set<std::string> audio = {
        "mp3", "wav", "flac", "ogg", "oga", "amr", "m4a", "aac", "opus", "wma",
        "mid",
    };
    static const std::unordered_set<std::string> doc = {
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf",
        "db", "sqlite", "json", "xml",
    };
    static const std::unordered_set<std::string> archive = {
        "zip", "rar", "7z", "apk", "tar", "gz", "iso", "jar",
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

    std::string format = ext;
    for (char& c : format) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));

    out = {format, ext, mime, kind, 35};
    return true;
}

}  // namespace phoenix
