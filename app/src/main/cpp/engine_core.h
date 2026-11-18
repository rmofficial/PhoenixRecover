#ifndef PHOENIX_ENGINE_CORE_H
#define PHOENIX_ENGINE_CORE_H

#include <cstdint>
#include <functional>
#include <limits>
#include <string>
#include <unordered_set>
#include <vector>

namespace phoenix {

enum class MediaKind : int {
    IMAGE = 0,
    VIDEO = 1,
    AUDIO = 2,
    DOCUMENT = 3,
    ARCHIVE = 4,
    OTHER = 5,
};

const char* mediaKindName(MediaKind kind);
bool mediaKindFromName(const std::string& name, MediaKind& out);

enum class Origin : int {
    DEEP_SCAN = 0,
    TRASH = 1,
    HIDDEN = 2,
    UNINDEXED = 3,
    CARVE = 4,
    ROOT = 5,
};

const char* originName(Origin origin);

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
};

enum class Validator {
    NONE,
    JPEG,
    WEBP,
    AVI,
    WAV,
    OGG,
};

struct FileSignature {
    std::string format;
    std::string ext;
    std::string mime;
    MediaKind kind = MediaKind::OTHER;
    std::vector<uint8_t> header;
    int headerOffset = 0;
    std::vector<std::string> brands;
    bool hasBrands = false;
    EndMode endMode = EndMode::NEXT_SIGNATURE;
    std::vector<uint8_t> footer;
    bool hasFooter = false;
    int64_t maxSize = 0;
    int64_t minSize = 2048;
    int score = 90;
    Validator validator = Validator::NONE;
};

const std::vector<FileSignature>& allSignatures();
const FileSignature* matchSignature(const uint8_t* buffer, int index, int length);
const FileSignature* signatureForExtension(const std::string& ext);

struct DetectedFile {
    std::string format;
    std::string ext;
    std::string mime;
    MediaKind kind = MediaKind::OTHER;
    int confidence = 0;
};

bool sniffBytes(const uint8_t* buffer, int length, DetectedFile& out);
bool detectFromName(const std::string& name, DetectedFile& out);

class ByteSource {
public:
    virtual ~ByteSource() = default;
    virtual int64_t length() const = 0;
    virtual int readAt(int64_t offset, uint8_t* buffer, int count) = 0;
};

class MemorySource : public ByteSource {
public:
    MemorySource(const uint8_t* data, size_t size)
        : data_(data), size_(static_cast<int64_t>(size)) {}

    int64_t length() const override { return size_; }
    int readAt(int64_t offset, uint8_t* buffer, int count) override;

private:
    const uint8_t* data_;
    int64_t size_;
};

class FdSource : public ByteSource {
public:
    FdSource(int fd, int64_t length) : fd_(fd), length_(length) {}
    ~FdSource() override;

    int64_t length() const override { return length_; }
    int readAt(int64_t offset, uint8_t* buffer, int count) override;

private:
    int fd_;
    int64_t length_;
};

struct CarveOptions {
    int64_t minSize = 2048;
    int64_t maxFileSize = 4LL * 1024 * 1024 * 1024;
    std::vector<MediaKind> kinds;
    std::vector<std::string> allowedExtensions;
    bool writeOutput = true;
    int maxResults = 5000;
    int64_t maxOutputBytes = 8LL * 1024 * 1024 * 1024;
    int64_t maxBytesToScan = std::numeric_limits<int64_t>::max();
};

struct CarveCallbacks {
    std::function<bool()> isCancelled;
    std::function<void(const std::string&)> onStatus;
    std::function<void(int64_t, int64_t, int, int64_t)> onProgress;
    std::function<void(const std::string& id, const std::string& name, MediaKind kind,
                       int64_t size, const std::string& mime, int confidence, int64_t offset,
                       const std::string& detail, const std::string& format,
                       const std::string& path)> onFound;
};

struct CarveResult {
    int64_t bytesExamined = 0;
    int filesFound = 0;
    int filesWritten = 0;
    int64_t bytesWritten = 0;
    bool cancelled = false;
    bool outputLimitHit = false;
    bool resultLimitHit = false;
    int64_t elapsedMs = 0;
};

CarveResult carveImage(ByteSource& source, const std::string& outputDir,
                       const CarveOptions& options, const CarveCallbacks& callbacks);

struct ScanOptions {
    int maxDepth = 14;
    bool includeHidden = true;
    bool trashOnly = false;
    int64_t minSize = 1;
    int maxResults = 5000;
    bool detectUnindexed = true;
    int maxFilesToInspect = 300000;
    std::unordered_set<std::string> indexedPaths;
    std::vector<std::string> roots;
};

struct ScanCallbacks {
    std::function<bool()> isCancelled;
    std::function<void(const std::string&)> onStatus;
    std::function<void(int64_t, int64_t, int64_t, const std::string&)> onProgress;
    std::function<void(const std::string& id, const std::string& name, MediaKind kind,
                       int64_t size, Origin origin, const std::string& mime, int confidence,
                       const std::string& sourcePath, const std::string& detail,
                       const std::string& format, const std::string& ext,
                       bool trashName)> onFound;
};

struct ScanResult {
    int64_t dirsVisited = 0;
    int64_t filesInspected = 0;
    int64_t found = 0;
    bool cancelled = false;
    int64_t elapsedMs = 0;
    int errors = 0;
};

ScanResult deepScan(const ScanOptions& options, const ScanCallbacks& callbacks);

}  // namespace phoenix

#endif  // PHOENIX_ENGINE_CORE_H
