#include "engine_core.h"

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <iomanip>
#include <sstream>
#include <string>
#include <unordered_set>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace phoenix {
namespace {

constexpr int64_t BLOCK = 2 * 1024 * 1024;
constexpr int OVERLAP = 128;
constexpr int CHUNK = 256 * 1024;

const int MP3_BITRATE_V1_L3[] = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
const int MP3_BITRATE_V2_L3[] = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
const int MP3_BITRATE_V1_L2[] = {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};
const int MP3_BITRATE_V1_L1[] = {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448};
const int AMR_NB[] = {13, 14, 16, 18, 20, 21, 27, 32, 6, 7, 6, 7, 6, 7, 6, 1};
const int AMR_WB[] = {18, 24, 33, 37, 41, 47, 51, 59, 61, 6, 7, 7, 7, 7, 7, 1};
const uint8_t ZIP_EOCD[] = {0x50, 0x4B, 0x05, 0x06};
const char PDF_EOF[] = "%%EOF";

int u8(const uint8_t* b, int i) { return static_cast<int>(b[i]); }

int u16be(const uint8_t* b, int i) {
    return (u8(b, i) << 8) | u8(b, i + 1);
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

int findBytes(const uint8_t* hay, int hayLen, const uint8_t* needle, int needleLen,
              int from = 0) {
    if (needleLen <= 0) return -1;
    const int last = hayLen - needleLen;
    if (last < from) return -1;
    const uint8_t first = needle[0];
    for (int i = from; i <= last; ++i) {
        if (hay[i] != first) continue;
        int j = 1;
        while (j < needleLen && hay[i + j] == needle[j]) ++j;
        if (j == needleLen) return i;
    }
    return -1;
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

std::string hexLower(int64_t value) {
    std::ostringstream oss;
    oss << std::hex << value;
    return oss.str();
}

int64_t elapsedMs(const std::chrono::steady_clock::time_point& started) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now() - started)
        .count();
}

struct CarvedInfo {
    std::string format;
    std::string ext;
    std::string mime;
    MediaKind kind;
};

class CarverImpl {
public:
    CarverImpl(ByteSource& source, const CarveOptions& options,
               const CarveCallbacks& callbacks, std::string outputDir)
        : source_(source), options_(options), callbacks_(callbacks),
          outputDir_(std::move(outputDir)) {}

    CarveResult run() {
        const auto started = std::chrono::steady_clock::now();
        if (!outputDir_.empty()) {
            std::error_code ec;
            std::filesystem::create_directories(outputDir_, ec);
        }

        const int64_t totalLength = source_.length();
        const int64_t scanEnd = std::min(totalLength, options_.maxBytesToScan);
        std::vector<uint8_t> buffer(static_cast<size_t>(BLOCK + OVERLAP));

        int64_t offset = 0;
        int filesFound = 0;
        int filesWritten = 0;
        int64_t bytesWritten = 0;
        bool cancelled = false;
        bool outputLimitHit = false;
        bool resultLimitHit = false;
        int64_t lastProgressAt = 0;

        status("Signature engine ready: " + std::to_string(allSignatures().size()) +
               " formats");

        while (offset < scanEnd) {
            if (isCancelled()) {
                cancelled = true;
                break;
            }
            if (filesFound >= options_.maxResults) {
                resultLimitHit = true;
                break;
            }
            if (outputLimitHit || bytesWritten >= options_.maxOutputBytes) {
                outputLimitHit = true;
                break;
            }

            const int64_t want64 = std::min<int64_t>(BLOCK + OVERLAP, totalLength - offset);
            if (want64 <= 0) break;
            const int n = readFully(offset, buffer.data(), static_cast<int>(want64));
            if (n <= 0) break;

            const int scanStarts = std::min<int64_t>(BLOCK, n);
            int i = 0;
            bool jumped = false;

            while (i < scanStarts && offset + i < scanEnd) {
                if (isCancelled()) {
                    cancelled = true;
                    break;
                }
                const FileSignature* sig = matchSignature(buffer.data(), i, n);
                if (sig != nullptr) {
                    const int64_t fileStart = offset + i - sig->headerOffset;
                    if (fileStart >= 0 && fileStart < scanEnd) {
                        bool partial = false;
                        const int64_t end = findEnd(*sig, fileStart, partial);
                        if (end > fileStart + 16) {
                            const CarvedInfo info = classify(*sig, fileStart, end);
                            const int64_t size = end - fileStart;
                            const bool kindAllowed =
                                options_.kinds.empty() ||
                                std::find(options_.kinds.begin(), options_.kinds.end(),
                                          info.kind) != options_.kinds.end();
                            const bool extAllowed =
                                options_.allowedExtensions.empty() ||
                                extensionAllowed(info.ext);
                            const bool accepted =
                                size >= options_.minSize && kindAllowed && extAllowed;
                            if (accepted) {
                                if (bytesWritten + size > options_.maxOutputBytes) {
                                    outputLimitHit = true;
                                    break;
                                }
                                if (!outputDir_.empty() && options_.writeOutput) {
                                    const std::string name = buildName(info, fileStart);
                                    const std::string path = outputDir_ + "/" + name;
                                    if (writeRange(path, fileStart, end)) {
                                        ++filesWritten;
                                        bytesWritten += size;
                                        ++filesFound;
                                        emitFound(info, size, fileStart, end, path, partial);
                                    }
                                } else {
                                    ++filesFound;
                                    emitFound(info, size, fileStart, end, std::string(),
                                              partial);
                                }
                                if (filesFound >= options_.maxResults) break;
                                const int64_t next = std::max(end, offset + i + 1);
                                if (next > offset) {
                                    offset = next;
                                    jumped = true;
                                    break;
                                }
                            }
                        }
                    }
                }
                ++i;
            }

            if (cancelled) break;
            if (!jumped) offset += BLOCK;

            const int64_t now = elapsedMs(started);
            if (now - lastProgressAt >= 220) {
                lastProgressAt = now;
                progress(std::min(offset, scanEnd), scanEnd, filesFound, bytesWritten);
            }
        }

        progress(std::min(offset, scanEnd), scanEnd, filesFound, bytesWritten);

        CarveResult result;
        result.bytesExamined = std::min(offset, scanEnd);
        result.filesFound = filesFound;
        result.filesWritten = filesWritten;
        result.bytesWritten = bytesWritten;
        result.cancelled = cancelled;
        result.outputLimitHit = outputLimitHit;
        result.resultLimitHit = resultLimitHit;
        result.elapsedMs = elapsedMs(started);
        return result;
    }

private:
    bool isCancelled() {
        return callbacks_.isCancelled && callbacks_.isCancelled();
    }

    void status(const std::string& message) {
        if (callbacks_.onStatus) callbacks_.onStatus(message);
    }

    void progress(int64_t processed, int64_t total, int found, int64_t bytesWritten) {
        if (callbacks_.onProgress) callbacks_.onProgress(processed, total, found, bytesWritten);
    }

    void emitFound(const CarvedInfo& info, int64_t size, int64_t start, int64_t end,
                   const std::string& path, bool partial) {
        if (!callbacks_.onFound) return;
        const std::string safeFormat = safeFormatName(info);
        std::string name = safeFormat + "_" + hexLower(start) + "_" +
                           hexLower(start / 1024) + "." + info.ext;
        const std::string id = "carve:" + std::to_string(start) + ":" + info.format + ":" +
                               std::to_string(end);
        int confidence = (info.format == "JPEG" || info.format == "PNG") ? 96 : 88;
        if (partial) confidence = std::max(40, confidence - 18);
        std::string detail = "Offset 0x" + hexLower(start) + " · " + info.format;
        if (partial) detail += " · partial (end marker missing)";
        callbacks_.onFound(id, name, info.kind, size, info.mime, confidence, start, detail,
                           info.format, path);
    }

    static std::string safeFormatName(const CarvedInfo& info) {
        std::string out = info.format;
        for (char& c : out) {
            if (c == ' ') c = '_';
        }
        out.erase(std::remove(out.begin(), out.end(), '-'), out.end());
        return out;
    }

    std::string buildName(const CarvedInfo& info, int64_t start) const {
        return safeFormatName(info) + "_" + hexLower(start) + "_" +
               hexLower(start / 1024) + "." + info.ext;
    }

    bool extensionAllowed(const std::string& ext) const {
        for (const auto& allowed : options_.allowedExtensions) {
            if (iequals(allowed, ext)) return true;
        }
        return false;
    }

    CarvedInfo classify(const FileSignature& sig, int64_t start, int64_t end) {
        if (sig.format == "ZIP") {
            const int64_t length = std::min<int64_t>(384 * 1024, end - start);
            if (length > 0) {
                std::vector<uint8_t> head(static_cast<size_t>(length));
                const int n = readFully(start, head.data(), static_cast<int>(length));
                if (n > 0) {
                    const std::string text(reinterpret_cast<const char*>(head.data()),
                                           static_cast<size_t>(n));
                    if (text.find("AndroidManifest.xml") != std::string::npos) {
                        return {"APK", "apk", "application/vnd.android.package-archive",
                                MediaKind::ARCHIVE};
                    }
                    if (text.find("word/") != std::string::npos) {
                        return {"DOCX", "docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                MediaKind::DOCUMENT};
                    }
                    if (text.find("xl/") != std::string::npos) {
                        return {"XLSX", "xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                MediaKind::DOCUMENT};
                    }
                    if (text.find("ppt/") != std::string::npos) {
                        return {"PPTX", "pptx",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                MediaKind::DOCUMENT};
                    }
                    if (text.find("[Content_Types].xml") != std::string::npos) {
                        return {"Office", "docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                MediaKind::DOCUMENT};
                    }
                }
            }
        }
        return {sig.format, sig.ext, sig.mime, sig.kind};
    }

    int64_t findEnd(const FileSignature& sig, int64_t start, bool& partial) {
        partial = false;
        const int64_t limit = std::min<int64_t>(sig.maxSize, options_.maxFileSize);
        int64_t cap = source_.length();
        if (start <= std::numeric_limits<int64_t>::max() - limit) {
            cap = std::min(cap, start + limit);
        }
        if (cap <= start) return -1;
        int64_t end = -1;
        bool jpegSawSos = false;
        switch (sig.endMode) {
            case EndMode::FOOTER:
                if (sig.hasFooter) {
                    end = findFooter(start + static_cast<int64_t>(sig.header.size()),
                                     sig.footer, cap);
                }
                break;
            case EndMode::JPEG_SCAN:
                end = parseJpeg(start, cap, jpegSawSos);
                break;
            case EndMode::RIFF: {
                uint8_t head[4];
                if (readFully(start + 4, head, 4) == 4) {
                    const int64_t size = u32le(head, 0) + 8;
                    if (size >= 12 && size <= cap - start) end = start + size;
                }
                break;
            }
            case EndMode::BMP_SIZE: {
                uint8_t head[4];
                if (readFully(start + 2, head, 4) == 4) {
                    const int64_t size = u32le(head, 0);
                    if (size >= 26 && size <= cap - start) end = start + size;
                }
                break;
            }
            case EndMode::MP4_BOX:
                end = parseBoxes(start, cap);
                break;
            case EndMode::ZIP_EOCD:
                end = findZipEnd(start, cap);
                break;
            case EndMode::PDF_EOF:
                end = findPdfEnd(start, cap);
                break;
            case EndMode::MP3_FRAMES:
                end = parseMp3(start, cap);
                break;
            case EndMode::OGG_PAGES:
                end = parseOgg(start, cap);
                break;
            case EndMode::AMR_FRAMES:
                end = parseAmr(start, cap);
                break;
            case EndMode::EBML:
                end = parseEbml(start, cap);
                break;
            case EndMode::SQLITE:
                end = parseSqlite(start, cap);
                break;
            case EndMode::TIFF_IFD:
                end = parseTiff(start, cap);
                break;
            case EndMode::PSD_SIZE:
                end = parsePsd(start, cap);
                break;
            case EndMode::ICO_SIZE:
                end = parseIco(start, cap);
                break;
            case EndMode::FLV_TAGS:
                end = parseFlv(start, cap);
                break;
            case EndMode::ASF_OBJECTS:
                end = parseAsf(start, cap);
                break;
            case EndMode::TS_PACKETS:
                end = parseTs(start, cap);
                break;
            case EndMode::ADTS_FRAMES:
                end = parseAdts(start, cap);
                break;
            case EndMode::MIDI_TRACKS:
                end = parseMidi(start, cap);
                break;
            case EndMode::NEXT_SIGNATURE:
                end = findNextHeader(start + static_cast<int64_t>(sig.header.size()), cap);
                break;
        }
        if (end > start + 16) return end;

        // The end marker is gone (very common for deleted files whose tail was
        // overwritten). Recover the readable prefix instead of dropping the file.
        const int64_t fallback = bestEffortEnd(sig, start, cap, jpegSawSos);
        if (fallback > start + 16) {
            partial = true;
            return fallback;
        }
        return -1;
    }

    bool shouldAttemptPartial(const FileSignature& sig) const {
        // "BM" is too weak a magic for false-positive-safe tail recovery.
        return sig.format != "BMP";
    }

    bool allowsTailToCap(const FileSignature& sig, bool jpegSawSos) const {
        if (sig.format == "JPEG") return jpegSawSos;
        static const std::unordered_set<std::string> strong = {
            "PNG", "MP4", "MOV", "3GP", "M4A", "HEIC", "AVI", "WAV", "WebP",
            "SQLite", "Matroska", "TIFF", "CR2", "PSD", "FLV", "WMV",
            "MPEG-TS", "MIDI",
        };
        return strong.find(sig.format) != strong.end();
    }

    int64_t partialCap(const FileSignature& sig, int64_t start, int64_t cap) const {
        int64_t extra = 64LL * 1024 * 1024;
        switch (sig.kind) {
            case MediaKind::IMAGE: extra = 64LL * 1024 * 1024; break;
            case MediaKind::VIDEO: extra = 512LL * 1024 * 1024; break;
            case MediaKind::AUDIO: extra = 128LL * 1024 * 1024; break;
            case MediaKind::DOCUMENT:
            case MediaKind::ARCHIVE: extra = 256LL * 1024 * 1024; break;
            default: break;
        }
        return std::min(cap, start + extra);
    }

    int64_t bestEffortEnd(const FileSignature& sig, int64_t start, int64_t cap,
                          bool jpegSawSos) {
        if (!shouldAttemptPartial(sig)) return -1;
        if (cap - start < std::max<int64_t>(options_.minSize, 4096)) return -1;
        const bool tail = allowsTailToCap(sig, jpegSawSos);
        // NEXT_SIGNATURE already scanned the full window in findEnd; repeating it
        // here would double the work on large footerless files.
        if (!tail && sig.endMode == EndMode::NEXT_SIGNATURE) return -1;
        const int64_t limit = tail ? partialCap(sig, start, cap) : cap;
        const int64_t next =
            findNextHeader(start + static_cast<int64_t>(sig.header.size()), limit);
        if (next > start + 1024) return next;
        if (tail) return limit;
        return -1;
    }

    int64_t parseJpeg(int64_t start, int64_t cap, bool& sawSos) {
        sawSos = false;
        int64_t p = start + 2;
        uint8_t marker[2];
        while (p + 2 <= cap) {
            if (isCancelled()) return -1;
            if (readFully(p, marker, 2) < 2) return -1;
            if (u8(marker, 0) != 0xFF) {
                ++p;
                continue;
            }
            const int m = u8(marker, 1);
            if (m == 0xFF) {
                ++p;
                continue;
            }
            if (m == 0xD9) return p + 2;
            if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                p += 2;
                continue;
            }
            if (p + 4 > cap) return -1;
            uint8_t lenBytes[2];
            if (readFully(p + 2, lenBytes, 2) < 2) return -1;
            const int segLen = u16be(lenBytes, 0);
            if (segLen < 2) return -1;
            if (m == 0xDA) {
                sawSos = true;
                int64_t q = p + 2 + segLen;
                std::vector<uint8_t> buf(static_cast<size_t>(CHUNK + 2));
                while (q < cap) {
                    if (isCancelled()) return -1;
                    const int64_t want =
                        std::min<int64_t>(CHUNK + 2, source_.length() - q);
                    if (want <= 0) return -1;
                    const int n = readFully(q, buf.data(), static_cast<int>(want));
                    if (n <= 0) return -1;
                    int i = 0;
                    while (i + 1 < n) {
                        if (buf[static_cast<size_t>(i)] == 0xFF) {
                            const int nx = buf[static_cast<size_t>(i + 1)];
                            if (nx == 0xD9) return q + i + 2;
                            if (nx == 0x00 || (nx >= 0xD0 && nx <= 0xD7)) {
                                i += 2;
                                continue;
                            }
                        }
                        ++i;
                    }
                    if (q + CHUNK >= cap) return -1;
                    q += CHUNK - 1;
                }
                return -1;
            }
            p += 2 + segLen;
        }
        return -1;
    }

    int64_t findFooter(int64_t from, const std::vector<uint8_t>& footer, int64_t maxEnd) {
        int64_t pos = from;
        const int footerSize = static_cast<int>(footer.size());
        std::vector<uint8_t> buf(static_cast<size_t>(CHUNK + footerSize));
        while (pos < maxEnd) {
            if (isCancelled()) return -1;
            const int64_t want =
                std::min<int64_t>(CHUNK + footerSize, source_.length() - pos);
            if (want <= 0) return -1;
            const int n = readFully(pos, buf.data(), static_cast<int>(want));
            if (n <= 0) return -1;
            const int idx = findBytes(buf.data(), n, footer.data(), footerSize);
            if (idx >= 0) {
                const int64_t end = pos + idx + footerSize;
                if (end > maxEnd) return -1;
                return end;
            }
            if (pos + CHUNK >= maxEnd) return -1;
            pos += CHUNK;
        }
        return -1;
    }

    int64_t parseBoxes(int64_t start, int64_t cap) {
        int64_t p = start;
        int boxes = 0;
        uint8_t head[16];
        while (p + 8 <= cap && boxes < 100000) {
            if (isCancelled()) return -1;
            const int n = readFully(p, head, 8);
            if (n < 8) break;
            int64_t size = u32be(head, 0);
            const std::string type = ascii(head, 4, 4, 8);
            int64_t headerSize = 8;
            if (size == 1) {
                if (readFully(p + 8, head, 8) != 8) break;
                size = 0;
                for (int k = 0; k < 8; ++k) {
                    size = (size << 8) | static_cast<int64_t>(head[k]);
                }
                headerSize = 16;
            }
            if (size == 0) return cap;
            if (size < headerSize) break;
            bool invalidType = type.size() != 4;
            for (char c : type) {
                const unsigned char code = static_cast<unsigned char>(c);
                if (code < 0x20 || code > 0x7E) invalidType = true;
            }
            if (invalidType) break;
            if (p + size > cap) break;
            p += size;
            ++boxes;
            if ((type == "mdat" || type == "moov") && p >= cap) break;
        }
        return p > start + 16 ? p : -1;
    }

    int64_t findZipEnd(int64_t start, int64_t cap) {
        int64_t pos = start + 20;
        std::vector<uint8_t> buf(static_cast<size_t>(CHUNK + 4));
        while (pos < cap) {
            if (isCancelled()) return -1;
            const int64_t want = std::min<int64_t>(CHUNK + 4, source_.length() - pos);
            if (want <= 0) return -1;
            const int n = readFully(pos, buf.data(), static_cast<int>(want));
            if (n <= 0) return -1;
            const int idx = findBytes(buf.data(), n, ZIP_EOCD, 4);
            if (idx >= 0) {
                const int64_t abs = pos + idx;
                uint8_t tail[22];
                if (readFully(abs, tail, 22) == 22) {
                    const int commentLen = u8(tail, 20) | (u8(tail, 21) << 8);
                    const int64_t end = abs + 22 + commentLen;
                    if (end <= source_.length() && end >= start + 22) return end;
                }
            }
            if (pos + CHUNK >= cap) return -1;
            pos += CHUNK;
        }
        return -1;
    }

    int64_t findPdfEnd(int64_t start, int64_t cap) {
        int64_t pos = start;
        const int footerSize = static_cast<int>(std::strlen(PDF_EOF));
        std::vector<uint8_t> buf(static_cast<size_t>(CHUNK + footerSize));
        while (pos < cap) {
            if (isCancelled()) return -1;
            const int64_t want =
                std::min<int64_t>(CHUNK + footerSize, source_.length() - pos);
            if (want <= 0) return -1;
            const int n = readFully(pos, buf.data(), static_cast<int>(want));
            if (n <= 0) return -1;
            const int idx = findBytes(buf.data(), n,
                                      reinterpret_cast<const uint8_t*>(PDF_EOF), footerSize);
            if (idx >= 0) {
                int64_t end = pos + idx + footerSize;
                uint8_t trail[2];
                if (readFully(end, trail, 2) >= 1) {
                    if (trail[0] == '\r') ++end;
                    if (end < source_.length()) {
                        if (readFully(end, trail, 1) == 1 && trail[0] == '\n') ++end;
                    }
                }
                return end;
            }
            if (pos + CHUNK >= cap) return -1;
            pos += CHUNK;
        }
        return -1;
    }

    bool mpegFrame(const uint8_t* h, int& lengthOut) {
        if (u8(h, 0) != 0xFF) return false;
        const int b1 = u8(h, 1);
        if ((b1 & 0xE0) != 0xE0) return false;
        const int version = (b1 >> 3) & 0x03;
        const int layer = (b1 >> 1) & 0x03;
        if (version == 1 || layer == 0) return false;
        const int b2 = u8(h, 2);
        const int bitrateIndex = (b2 >> 4) & 0x0F;
        const int sampleIndex = (b2 >> 2) & 0x03;
        const int padding = (b2 >> 1) & 0x01;
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleIndex == 3) return false;
        const bool v1 = version == 3;
        int bitrate = 0;
        if (layer == 1) {
            bitrate = (v1 ? MP3_BITRATE_V1_L3 : MP3_BITRATE_V2_L3)[bitrateIndex] * 1000;
        } else if (layer == 2) {
            bitrate = (v1 ? MP3_BITRATE_V1_L2 : MP3_BITRATE_V2_L3)[bitrateIndex] * 1000;
        } else {
            bitrate = (v1 ? MP3_BITRATE_V1_L1 : MP3_BITRATE_V1_L2)[bitrateIndex] * 1000;
        }
        if (bitrate <= 0) return false;
        int sampleRate = 0;
        if (version == 3) {
            const int rates[] = {44100, 48000, 32000};
            sampleRate = rates[sampleIndex];
        } else if (version == 2) {
            const int rates[] = {22050, 24000, 16000};
            sampleRate = rates[sampleIndex];
        } else {
            const int rates[] = {11025, 12000, 8000};
            sampleRate = rates[sampleIndex];
        }
        int length = 0;
        if (layer == 1) {
            length = v1 ? 144 * bitrate / sampleRate + padding
                        : 72 * bitrate / sampleRate + padding;
        } else if (layer == 2) {
            length = 144 * bitrate / sampleRate + padding;
        } else {
            length = (12 * bitrate / sampleRate + padding) * 4;
        }
        if (length < 4 || length > 8192) return false;
        lengthOut = length;
        return true;
    }

    int64_t parseMp3(int64_t start, int64_t cap) {
        uint8_t head[10];
        if (readFully(start, head, 10) < 10) return -1;
        if (ascii(head, 0, 3, 10) != "ID3") return -1;
        const int tagSize = (u8(head, 6) << 21) | (u8(head, 7) << 14) |
                            (u8(head, 8) << 7) | u8(head, 9);
        const int flags = u8(head, 5);
        int64_t p = start + 10 + tagSize + ((flags & 0x10) != 0 ? 10 : 0);
        if (p >= cap) return -1;
        int64_t last = -1;
        int frames = 0;
        int misses = 0;
        uint8_t h[4];
        while (p + 4 <= cap && misses < 4) {
            if (isCancelled()) return last;
            if (readFully(p, h, 4) < 4) break;
            int frameLength = 0;
            if (!mpegFrame(h, frameLength)) {
                ++misses;
                ++p;
                continue;
            }
            if (p + frameLength > cap) break;
            p += frameLength;
            last = p;
            ++frames;
            misses = 0;
        }
        return (frames >= 2 && last > 0) ? last : -1;
    }

    int64_t parseOgg(int64_t start, int64_t cap) {
        int64_t p = start;
        int64_t last = -1;
        uint8_t head[27];
        while (p + 27 <= cap) {
            if (isCancelled()) return last;
            if (readFully(p, head, 27) < 27) break;
            if (ascii(head, 0, 4, 27) != "OggS") break;
            const int segCount = u8(head, 26);
            std::vector<uint8_t> lacing(static_cast<size_t>(segCount));
            if (segCount > 0 &&
                readFully(p + 27, lacing.data(), segCount) < segCount) {
                break;
            }
            int dataLen = 0;
            for (int k = 0; k < segCount; ++k) dataLen += u8(lacing.data(), k);
            const int64_t pageEnd = p + 27 + segCount + dataLen;
            if (pageEnd > cap) break;
            last = pageEnd;
            p = pageEnd;
        }
        return last;
    }

    int64_t parseAmr(int64_t start, int64_t cap) {
        uint8_t magic[9];
        if (readFully(start, magic, 9) < 6) return -1;
        const bool wide = ascii(magic, 5, 4, 9) == "-WB\n" ||
                          ascii(magic, 5, 3, 9) == "-WB";
        int64_t p = wide ? start + 9 : start + 6;
        int64_t last = -1;
        const int* table = wide ? AMR_WB : AMR_NB;
        uint8_t one[1];
        while (p < cap) {
            if (readFully(p, one, 1) < 1) break;
            const int ft = (u8(one, 0) >> 3) & 0x0F;
            const int size = table[ft];
            if (size <= 1 || p + size > cap) break;
            p += size;
            last = p;
        }
        return last > start + 8 ? last : -1;
    }

    bool readVint(int64_t offset, int64_t cap, int64_t& value, int& length) {
        uint8_t first;
        if (readFully(offset, &first, 1) < 1) return false;
        const int f = first;
        if (f == 0) return false;
        int mask = 0x80;
        length = 1;
        while ((mask & f) == 0) {
            mask >>= 1;
            ++length;
            if (length > 8) return false;
        }
        if (offset + length > cap) return false;
        std::vector<uint8_t> buf(static_cast<size_t>(length));
        if (readFully(offset, buf.data(), length) < length) return false;
        int64_t result = static_cast<int64_t>(u8(buf.data(), 0) & (mask - 1));
        bool allOnes = result == (mask - 1);
        for (int k = 1; k < length; ++k) {
            const int byte = u8(buf.data(), k);
            if (byte != 0xFF) allOnes = false;
            result = (result << 8) | static_cast<int64_t>(byte);
        }
        if (allOnes) return false;
        value = result;
        return true;
    }

    int64_t parseEbml(int64_t start, int64_t cap) {
        int64_t p = start + 4;
        int64_t value = 0;
        int vlen = 0;
        if (!readVint(p, cap, value, vlen)) return -1;
        const int64_t headerEnd = p + vlen + value;
        if (headerEnd + 4 > cap) return -1;
        p = headerEnd;
        uint8_t id[4];
        if (readFully(p, id, 4) < 4) return -1;
        const int64_t segmentId = u32be(id, 0);
        if (segmentId != 0x18538067LL) return findNextHeader(start + 4, cap);
        p += 4;
        int64_t segValue = 0;
        int segLen = 0;
        if (!readVint(p, cap, segValue, segLen)) return -1;
        const int64_t dataStart = p + segLen;
        if (segValue <= 0) return findNextHeader(dataStart, cap);
        const int64_t end = dataStart + segValue;
        if (end <= cap && end > dataStart) return end;
        return findNextHeader(dataStart, cap);
    }

    int64_t parseSqlite(int64_t start, int64_t cap) {
        uint8_t head[100];
        if (readFully(start, head, 100) < 100) return -1;
        if (ascii(head, 0, 15, 100) != "SQLite format 3") return -1;
        int pageSize = u16be(head, 16);
        if (pageSize == 1) pageSize = 65536;
        if (pageSize < 512 || pageSize > 65536) return -1;
        const int64_t pageCount = u32be(head, 28);
        if (pageCount <= 0) return -1;
        const int64_t end = start + static_cast<int64_t>(pageSize) * pageCount;
        return end <= cap ? end : -1;
    }

    /**
     * TIFF/NEF/ARW/DNG/CR2 all share the TIFF IFD layout. Walk the directory
     * chain plus SubIFD/Exif pointers, and use the strip/tile offset + byte-count
     * tables to find the true end of the image even when the file is huge.
     */
    int64_t parseTiff(int64_t start, int64_t cap) {
        uint8_t head[8];
        if (readFully(start, head, 8) < 8) return -1;
        const bool little = u8(head, 0) == 0x49 && u8(head, 1) == 0x49;
        const bool big = u8(head, 0) == 0x4D && u8(head, 1) == 0x4D;
        if (!little && !big) return -1;
        if ((little && (u8(head, 2) != 0x2A || u8(head, 3) != 0x00)) ||
            (big && (u8(head, 2) != 0x00 || u8(head, 3) != 0x2A))) {
            return -1;
        }

        auto rd16 = [little](const uint8_t* b) -> int {
            return little ? (u8(b, 0) | (u8(b, 1) << 8)) : ((u8(b, 0) << 8) | u8(b, 1));
        };
        auto rd32 = [little](const uint8_t* b) -> int64_t {
            return little ? u32le(b, 0) : u32be(b, 0);
        };

        std::vector<int64_t> pending;
        pending.push_back(rd32(head + 4));
        std::unordered_set<int64_t> visited;
        int64_t best = start + 8;
        int processed = 0;

        while (!pending.empty() && processed++ < 2048) {
            const int64_t ifd = pending.back();
            pending.pop_back();
            if (ifd < 8 || !visited.insert(ifd).second) continue;
            const int64_t dirPos = start + ifd;
            if (dirPos + 2 > cap) continue;
            uint8_t countBytes[2];
            if (readFully(dirPos, countBytes, 2) < 2) continue;
            const int count = rd16(countBytes);
            if (count <= 0 || count > 4096) continue;
            const int64_t entriesLen = static_cast<int64_t>(count) * 12;
            if (dirPos + 2 + entriesLen + 4 > cap) continue;
            std::vector<uint8_t> entries(static_cast<size_t>(entriesLen + 4));
            if (readFully(dirPos + 2, entries.data(), static_cast<int>(entries.size())) <
                static_cast<int>(entries.size())) {
                continue;
            }

            std::vector<int64_t> stripOffsets;
            std::vector<int64_t> stripCounts;
            for (int e = 0; e < count; ++e) {
                const uint8_t* entry = entries.data() + e * 12;
                const int tag = rd16(entry);
                const int type = rd16(entry + 2);
                const int64_t itemCount = rd32(entry + 4);
                if (itemCount <= 0 || itemCount > 100000) continue;
                int typeSize = 0;
                switch (type) {
                    case 1: case 2: case 6: case 7: typeSize = 1; break;
                    case 3: case 8: typeSize = 2; break;
                    case 4: case 9: case 11: typeSize = 4; break;
                    case 5: case 10: case 12: typeSize = 8; break;
                    default: continue;
                }
                const int64_t total = itemCount * typeSize;
                const bool offsetTag = tag == 273 || tag == 324;
                const bool countTag = tag == 279 || tag == 325;
                if (total <= 4) {
                    if (offsetTag || countTag) {
                        const int64_t value =
                            typeSize == 2 ? static_cast<int64_t>(rd16(entry + 8))
                                          : rd32(entry + 8);
                        if (offsetTag) {
                            stripOffsets.push_back(value);
                        } else {
                            stripCounts.push_back(value);
                        }
                    }
                    continue;
                }
                const int64_t dataOffset = rd32(entry + 8);
                if (dataOffset <= 0) continue;

                if (tag == 330 || tag == 34665 || tag == 34853 || tag == 40965) {
                    const int64_t childPos = start + dataOffset;
                    if (childPos + 4 <= cap) {
                        uint8_t child[4];
                        if (readFully(childPos, child, 4) == 4) {
                            pending.push_back(rd32(child));
                        }
                    }
                }

                if (offsetTag || countTag) {
                    const int64_t tableBytes = std::min<int64_t>(total, 8 * 1024 * 1024);
                    const int64_t tablePos = start + dataOffset;
                    if (tablePos + tableBytes > cap) continue;
                    const int entriesToRead =
                        static_cast<int>(tableBytes / typeSize);
                    std::vector<uint8_t> table(static_cast<size_t>(tableBytes));
                    if (readFully(tablePos, table.data(), static_cast<int>(tableBytes)) <
                        static_cast<int>(tableBytes)) {
                        continue;
                    }
                    for (int k = 0; k < entriesToRead; ++k) {
                        const uint8_t* item = table.data() + k * typeSize;
                        const int64_t value =
                            typeSize == 2 ? static_cast<int64_t>(rd16(item))
                                          : rd32(item);
                        if (tag == 273 || tag == 324) {
                            stripOffsets.push_back(value);
                        } else {
                            stripCounts.push_back(value);
                        }
                    }
                    continue;
                }

                const int64_t dataEnd = start + dataOffset + total;
                if (dataEnd > best && dataEnd <= cap) best = dataEnd;
            }

            const size_t pairs = std::min(stripOffsets.size(), stripCounts.size());
            for (size_t k = 0; k < pairs; ++k) {
                if (stripOffsets[k] <= 0 || stripCounts[k] <= 0) continue;
                const int64_t dataEnd = start + stripOffsets[k] + stripCounts[k];
                if (dataEnd > best && dataEnd <= cap) best = dataEnd;
            }

            pending.push_back(rd32(entries.data() + entriesLen));
        }
        return best > start + 16 ? best : -1;
    }

    int64_t parsePsd(int64_t start, int64_t cap) {
        uint8_t head[26];
        if (readFully(start, head, 26) < 26) return -1;
        if (ascii(head, 0, 4, 26) != "8BPS") return -1;
        int64_t p = start + 26;
        for (int section = 0; section < 4; ++section) {
            uint8_t lenBytes[4];
            if (p + 4 > cap) return -1;
            if (readFully(p, lenBytes, 4) < 4) return -1;
            const int64_t len = u32be(lenBytes, 0);
            p += 4;
            if (len < 0 || p + len > cap) return -1;
            p += len;
        }
        return p > start + 26 ? p : -1;
    }

    int64_t parseIco(int64_t start, int64_t cap) {
        uint8_t head[6];
        if (readFully(start, head, 6) < 6) return -1;
        const int count = static_cast<int>(u32le(head, 4) & 0xFFFF);
        if (count <= 0 || count > 1024) return -1;
        const int64_t dirLen = static_cast<int64_t>(count) * 16;
        if (start + 6 + dirLen > cap) return -1;
        std::vector<uint8_t> dir(static_cast<size_t>(dirLen));
        if (readFully(start + 6, dir.data(), static_cast<int>(dirLen)) <
            static_cast<int>(dirLen)) {
            return -1;
        }
        int64_t end = start + 6 + dirLen;
        for (int e = 0; e < count; ++e) {
            const uint8_t* entry = dir.data() + e * 16;
            const int64_t size = u32le(entry, 8);
            const int64_t offset = u32le(entry, 12);
            if (size <= 0 || offset <= 0) continue;
            const int64_t itemEnd = start + offset + size;
            if (itemEnd <= cap && itemEnd > end) end = itemEnd;
        }
        return end > start + 16 ? end : -1;
    }

    int64_t parseFlv(int64_t start, int64_t cap) {
        uint8_t head[13];
        if (readFully(start, head, 13) < 13) return -1;
        if (ascii(head, 0, 3, 13) != "FLV") return -1;
        const int64_t dataOffset = u32be(head, 5);
        if (dataOffset < 9 || dataOffset > 4096) return -1;
        int64_t p = start + dataOffset + 4;
        int64_t last = p;
        int guard = 0;
        while (p + 11 <= cap && guard++ < 4000000) {
            if (isCancelled()) return last > start + 16 ? last : -1;
            uint8_t th[11];
            if (readFully(p, th, 11) < 11) break;
            const int type = u8(th, 0);
            if (type != 8 && type != 9 && type != 18) break;
            const int64_t dataSize = (static_cast<int64_t>(u8(th, 1)) << 16) |
                                     (static_cast<int64_t>(u8(th, 2)) << 8) |
                                     static_cast<int64_t>(u8(th, 3));
            const int64_t tagEnd = p + 11 + dataSize + 4;
            if (tagEnd > cap) break;
            last = tagEnd;
            p = tagEnd;
        }
        return last > start + 16 ? last : -1;
    }

    int64_t parseAsf(int64_t start, int64_t cap) {
        uint8_t head[24];
        if (readFully(start, head, 24) < 24) return -1;
        const int64_t headerSize = u64le(head, 16);
        if (headerSize < 30 || headerSize > cap - start) return -1;
        const int64_t dataPos = start + headerSize;
        if (dataPos + 24 > cap) return dataPos > start + 16 ? dataPos : -1;
        uint8_t dataHead[24];
        if (readFully(dataPos, dataHead, 24) < 24) {
            return dataPos > start + 16 ? dataPos : -1;
        }
        static const uint8_t kDataGuid[16] = {
            0x36, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11,
            0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C,
        };
        const bool isData = std::memcmp(dataHead, kDataGuid, 16) == 0;
        const int64_t dataSize = u64le(dataHead, 16);
        if (isData && dataSize >= 24 && dataPos + dataSize <= cap) {
            return dataPos + dataSize;
        }
        return dataPos > start + 16 ? dataPos : -1;
    }

    int64_t parseTs(int64_t start, int64_t cap) {
        int packet = 188;
        auto syncAt = [this](int64_t pos) {
            uint8_t b = 0;
            return readFully(pos, &b, 1) == 1 && b == 0x47;
        };
        if (!(syncAt(start + 188) && syncAt(start + 376))) {
            if (syncAt(start + 192) && syncAt(start + 384)) {
                packet = 192;
            } else if (syncAt(start + 204) && syncAt(start + 408)) {
                packet = 204;
            }
        }
        const int chunk = 256 * 1024;
        std::vector<uint8_t> buf(static_cast<size_t>(chunk));
        int64_t pos = start;
        int64_t last = start;
        int misses = 0;
        while (pos < cap) {
            if (isCancelled()) return last > start + packet * 3 ? last : -1;
            const int want = static_cast<int>(std::min<int64_t>(chunk, cap - pos));
            const int n = readFully(pos, buf.data(), want);
            if (n < packet) break;
            int i = 0;
            int64_t chunkLast = last;
            while (i + packet <= n) {
                if (buf[static_cast<size_t>(i)] != 0x47) {
                    if (++misses > 2) break;
                    ++i;
                    continue;
                }
                misses = 0;
                i += packet;
                chunkLast = pos + i;
            }
            if (misses > 2) {
                if (chunkLast > last) last = chunkLast;
                break;
            }
            if (chunkLast > last) {
                last = chunkLast;
                pos = last;
            } else {
                const int advance = n - (n % packet);
                if (advance <= 0) break;
                pos += advance;
            }
        }
        return last > start + packet * 3 ? last : -1;
    }

    int64_t parseAdts(int64_t start, int64_t cap) {
        int64_t p = start;
        int64_t last = -1;
        int frames = 0;
        while (p + 7 <= cap) {
            if (isCancelled()) return frames >= 2 ? last : -1;
            uint8_t h[7];
            if (readFully(p, h, 7) < 7) break;
            if (u8(h, 0) != 0xFF || (u8(h, 1) & 0xF6) != 0xF0) break;
            const int frameLen = ((u8(h, 3) & 0x03) << 11) |
                                 (u8(h, 4) << 3) | (u8(h, 5) >> 5);
            if (frameLen < 7 || p + frameLen > cap) break;
            p += frameLen;
            last = p;
            ++frames;
        }
        return (frames >= 2 && last > 0) ? last : -1;
    }

    int64_t parseMidi(int64_t start, int64_t cap) {
        uint8_t head[8];
        if (readFully(start, head, 8) < 8) return -1;
        if (ascii(head, 0, 4, 8) != "MThd") return -1;
        const int64_t headerLen = u32be(head, 4);
        if (headerLen < 6 || start + 8 + headerLen > cap) return -1;
        int64_t p = start + 8 + headerLen;
        int64_t last = p;
        int guard = 0;
        while (p + 8 <= cap && guard++ < 200000) {
            if (isCancelled()) return last > start + 16 ? last : -1;
            uint8_t ch[8];
            if (readFully(p, ch, 8) < 8) break;
            if (ascii(ch, 0, 4, 8) != "MTrk") break;
            const int64_t len = u32be(ch, 4);
            if (len < 0 || p + 8 + len > cap) break;
            p += 8 + len;
            last = p;
        }
        return last > start + 16 ? last : -1;
    }

    int64_t findNextHeader(int64_t from, int64_t maxEnd) {
        const int chunk = 64 * 1024;
        std::vector<uint8_t> buf(static_cast<size_t>(chunk + OVERLAP));
        int64_t pos = from;
        while (pos < maxEnd) {
            if (isCancelled()) return -1;
            const int64_t want = std::min<int64_t>(chunk + OVERLAP, source_.length() - pos);
            if (want <= 0) return -1;
            const int n = readFully(pos, buf.data(), static_cast<int>(want));
            if (n <= 0) return -1;
            int i = 0;
            const int limit = std::min<int64_t>(chunk, n);
            while (i < limit && pos + i < maxEnd) {
                const FileSignature* sig = matchSignature(buf.data(), i, n);
                if (sig != nullptr) {
                    const int64_t start = pos + i - sig->headerOffset;
                    if (start > from) return start;
                }
                ++i;
            }
            pos += chunk;
        }
        return -1;
    }

    bool writeRange(const std::string& path, int64_t start, int64_t end) {
        const int fd = ::open(path.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0600);
        if (fd < 0) return false;
        std::vector<uint8_t> buf(1024 * 1024);
        int64_t p = start;
        int64_t written = 0;
        bool ok = true;
        while (p < end) {
            if (isCancelled()) {
                ok = false;
                break;
            }
            const int want = static_cast<int>(std::min<int64_t>(
                static_cast<int64_t>(buf.size()), end - p));
            const int n = readFully(p, buf.data(), want);
            if (n <= 0) {
                ok = false;
                break;
            }
            int offset = 0;
            while (offset < n) {
                const ssize_t w = ::write(fd, buf.data() + offset,
                                          static_cast<size_t>(n - offset));
                if (w < 0) {
                    if (errno == EINTR) continue;
                    ok = false;
                    break;
                }
                offset += static_cast<int>(w);
                written += w;
            }
            if (!ok) break;
            p += n;
        }
        ::close(fd);
        if (ok && written == end - start) return true;
        ::unlink(path.c_str());
        return false;
    }

    int readFully(int64_t offset, uint8_t* dst, int count) {
        if (count <= 0) return 0;
        int total = 0;
        while (total < count) {
            const int r = source_.readAt(offset + total, dst + total, count - total);
            if (r <= 0) break;
            total += r;
        }
        return total;
    }

    ByteSource& source_;
    const CarveOptions& options_;
    const CarveCallbacks& callbacks_;
    std::string outputDir_;
};

}  // namespace

CarveResult carveImage(ByteSource& source, const std::string& outputDir,
                       const CarveOptions& options, const CarveCallbacks& callbacks) {
    CarverImpl carver(source, options, callbacks, outputDir);
    return carver.run();
}

}  // namespace phoenix
