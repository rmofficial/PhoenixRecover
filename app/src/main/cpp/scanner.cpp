#include "engine_core.h"

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <deque>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace phoenix {
namespace {

const std::unordered_set<std::string> TRASH_SEGMENTS = {
    ".trashed", ".trash", ".trashes", ".trashbin", ".trash_bin", ".recycle",
    ".recyclebin", ".deleted", ".deleted_files", "recycle bin", "$recycle.bin",
    "#recycle", "trash", "trashbin", ".files_trash", "recently deleted",
    "recently_deleted", "recentlydeleted", "deleted files", "deleted", "recycle",
};

const std::unordered_set<std::string> SKIP_DIRS = {
    "obb", ".git", "node_modules", "__pycache__", "com.phoenix.recover",
    "lost+found", ".thumbnails", "thumbnails",
};

const std::vector<std::string> KNOWN_RELATIVE = {
    ".trashed",
    ".Trash",
    ".trash",
    ".trashes",
    ".recycle",
    ".recyclebin",
    ".RecycleBin",
    ".deleted",
    "$RECYCLE.BIN",
    "#recycle",
    "LOST.DIR",
    "Trash",
    "Recycle Bin",
    "Pictures/.trashed",
    "Pictures/.trash",
    "Pictures/Trash",
    "Pictures/Recently Deleted",
    "Movies/.trashed",
    "Movies/.trash",
    "Movies/Trash",
    "DCIM/.trashed",
    "DCIM/.Trash",
    "DCIM/.trash",
    "DCIM/Trash",
    "DCIM/.trashes",
    "DCIM/Recently Deleted",
    "Download/.trashed",
    "Download/Trash",
    "MIUI/Gallery/cloud/.trashBin",
    "MIUI/Gallery/.trashBin",
    "MIUI/Gallery/.trash",
    "Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
    "Android/data/com.whatsapp/WhatsApp/Media/.Statuses",
    "Android/media/org.telegram.messenger/Telegram/Telegram Images",
};

const std::unordered_set<std::string>& candidateExtensions() {
    static const std::unordered_set<std::string> value = {
        "jpg", "jpeg", "jfif", "png", "gif", "webp", "bmp", "heic", "heif",
        "avif", "tif", "tiff", "dng", "raw", "cr2", "nef", "arw", "orf", "rw2",
        "mp4", "mov", "3gp", "mkv", "webm", "avi", "m4v", "flv", "wmv", "ts",
        "mpg", "mpeg",
        "mp3", "wav", "flac", "ogg", "oga", "amr", "m4a", "aac", "opus", "wma",
        "mid",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf",
        "db", "sqlite", "json", "xml",
        "zip", "rar", "7z", "apk", "tar", "gz", "iso", "jar",
    };
    return value;
}

std::string lower(std::string value) {
    for (char& c : value) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return value;
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

bool startsWith(const std::string& value, const std::string& prefix) {
    return value.size() >= prefix.size() &&
           value.compare(0, prefix.size(), prefix) == 0;
}

bool contains(const std::string& value, const std::string& needle) {
    return value.find(needle) != std::string::npos;
}

std::vector<std::string> splitSegments(const std::string& path) {
    std::vector<std::string> out;
    std::string current;
    for (char c : path) {
        if (c == '/') {
            out.push_back(current);
            current.clear();
        } else {
            current.push_back(c);
        }
    }
    out.push_back(current);
    return out;
}

std::string parentPath(const std::string& path) {
    const size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) return std::string();
    if (slash == 0) return "/";
    return path.substr(0, slash);
}

std::string joinPath(const std::string& base, const std::string& rel) {
    if (base.empty()) return rel;
    if (base.back() == '/') return base + rel;
    return base + "/" + rel;
}

std::string extensionOf(const std::string& lowerName) {
    const size_t dot = lowerName.find_last_of('.');
    if (dot == std::string::npos) return std::string();
    return lowerName.substr(dot + 1);
}

std::string trimTrailingSlash(const std::string& value) {
    std::string out = value;
    while (out.size() > 1 && out.back() == '/') out.pop_back();
    return out;
}

bool pathExists(const std::string& path) {
    struct stat st;
    return ::stat(path.c_str(), &st) == 0;
}

bool pathIsDir(const std::string& path) {
    struct stat st;
    return ::stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

int readHeadCount(const std::string& path, uint8_t* buffer, int size) {
    const int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) return -1;
    int total = 0;
    while (total < size) {
        const ssize_t r = ::read(fd, buffer + total, static_cast<size_t>(size - total));
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (r == 0) break;
        total += static_cast<int>(r);
    }
    ::close(fd);
    return total;
}

bool detectFile(const std::string& path, const std::string& name, DetectedFile& out) {
    uint8_t head[4096];
    const int n = readHeadCount(path, head, static_cast<int>(sizeof(head)));
    if (n > 0 && sniffBytes(head, n, out)) return true;
    return detectFromName(name, out);
}

int64_t elapsedMs(const std::chrono::steady_clock::time_point& started) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now() - started)
        .count();
}

struct WalkDir {
    std::string path;
    int depth;
};

class ScannerImpl {
public:
    ScannerImpl(const ScanOptions& options, const ScanCallbacks& callbacks)
        : options_(options), callbacks_(callbacks) {}

    ScanResult run() {
        const auto started = std::chrono::steady_clock::now();
        int64_t dirsVisited = 0;
        int64_t filesInspected = 0;
        int errorCount = 0;
        bool cancelled = false;
        std::unordered_set<std::string> visited;
        std::unordered_set<std::string> seenFiles;
        std::deque<WalkDir> queue;

        std::vector<std::string> roots;
        for (const auto& root : options_.roots) {
            if (pathExists(root)) roots.push_back(root);
        }

        if (options_.trashOnly) {
            for (const auto& root : roots) {
                for (const auto& rel : KNOWN_RELATIVE) {
                    const std::string path = joinPath(root, rel);
                    if (pathIsDir(path)) queue.push_back({path, 0});
                }
            }
            if (queue.empty()) {
                status("No filesystem trash folders found — checking system trash…");
                ScanResult empty;
                empty.elapsedMs = elapsedMs(started);
                return empty;
            }
            status("Scanning system trash bins…");
        } else {
            for (const auto& root : roots) {
                queue.push_back({root, 0});
                for (const auto& rel : KNOWN_RELATIVE) {
                    const std::string path = joinPath(root, rel);
                    if (pathIsDir(path)) queue.push_back({path, 0});
                }
            }
            status("Deep scan started on " + std::to_string(roots.size()) + " volume(s)…");
        }

        int64_t lastProgress = 0;

        while (!queue.empty()) {
            if (isCancelled()) {
                cancelled = true;
                break;
            }
            if (filesInspected >= options_.maxFilesToInspect) break;
            if (static_cast<int>(seenFiles.size()) >= options_.maxResults) break;

            const WalkDir current = queue.front();
            queue.pop_front();
            const std::string dir = current.path;
            const std::string key = trimTrailingSlash(dir);
            if (!visited.insert(key).second) continue;

            DIR* handle = ::opendir(dir.c_str());
            if (handle == nullptr) {
                ++errorCount;
                continue;
            }
            ++dirsVisited;

            struct dirent* entry = nullptr;
            while ((entry = ::readdir(handle)) != nullptr) {
                if (isCancelled()) {
                    cancelled = true;
                    break;
                }
                if (filesInspected >= options_.maxFilesToInspect) break;
                if (static_cast<int>(seenFiles.size()) >= options_.maxResults) break;

                const std::string name = entry->d_name;
                if (name == "." || name == "..") continue;

                const std::string child = joinPath(dir, name);
                struct stat st;
                const bool statOk = ::stat(child.c_str(), &st) == 0;
                if (statOk && S_ISDIR(st.st_mode)) {
                    if (current.depth < options_.maxDepth &&
                        SKIP_DIRS.find(name) == SKIP_DIRS.end() &&
                        !iequals(name, "Android")) {
                        queue.push_back({child, current.depth + 1});
                    }
                    continue;
                }

                ++filesInspected;
                if (filesInspected % 512 == 0) ::usleep(1000);
                if (!statOk) continue;
                inspect(child, name, st.st_size, seenFiles);
            }
            ::closedir(handle);

            const int64_t now = elapsedMs(started);
            if (now - lastProgress >= 220) {
                lastProgress = now;
                progress(dirsVisited, filesInspected, seenFiles.size(), dir);
            }
            if (cancelled) break;
        }

        progress(dirsVisited, filesInspected, seenFiles.size(), std::string());

        ScanResult result;
        result.dirsVisited = dirsVisited;
        result.filesInspected = filesInspected;
        result.found = static_cast<int64_t>(seenFiles.size());
        result.cancelled = cancelled;
        result.elapsedMs = elapsedMs(started);
        result.errors = errorCount;
        return result;
    }

private:
    bool isCancelled() {
        return callbacks_.isCancelled && callbacks_.isCancelled();
    }

    void status(const std::string& message) {
        if (callbacks_.onStatus) callbacks_.onStatus(message);
    }

    void progress(int64_t dirs, int64_t filesSeen, int64_t found, const std::string& path) {
        if (callbacks_.onProgress) callbacks_.onProgress(dirs, filesSeen, found, path);
    }

    bool parentHasNoMedia(const std::string& path) {
        const std::string parent = parentPath(path);
        if (parent.empty()) return false;
        const auto found = noMediaCache_.find(parent);
        if (found != noMediaCache_.end()) return found->second;
        const bool has = pathExists(joinPath(parent, ".nomedia"));
        noMediaCache_[parent] = has;
        return has;
    }

    void inspect(const std::string& absolute, const std::string& name, int64_t size,
                 std::unordered_set<std::string>& seen) {
        if (size < options_.minSize || size <= 0) return;

        const std::string lowerPath = lower(absolute);
        const std::string lowerName = lower(name);
        const std::vector<std::string> segments = splitSegments(lowerPath);

        const bool trashName =
            startsWith(lowerName, ".trashed") || startsWith(lowerName, "trashed-") ||
            startsWith(lowerName, ".trash-") || startsWith(lowerName, ".pending-") ||
            contains(lowerName, ".trashed-");

        bool segmentTrash = false;
        bool inLost = false;
        bool inThumbs = false;
        for (size_t i = 0; i < segments.size(); ++i) {
            const std::string& segment = segments[i];
            if (TRASH_SEGMENTS.find(segment) != TRASH_SEGMENTS.end()) segmentTrash = true;
            if (segment == "lost.dir") inLost = true;
            if (segment == ".thumbnails") inThumbs = true;
        }
        const bool inTrash = trashName || segmentTrash ||
                             contains(lowerPath, "miui/gallery/cloud/.trashbin") ||
                             contains(lowerPath, "/.trashed/");
        inThumbs = inThumbs || contains(lowerPath, "/thumbnails/");

        const bool hiddenFile = !name.empty() && name[0] == '.';
        bool hiddenDir = false;
        for (size_t i = 0; i + 1 < segments.size(); ++i) {
            const std::string& segment = segments[i];
            if (!segment.empty() && segment[0] == '.' && segment.size() > 2) {
                hiddenDir = true;
                break;
            }
        }
        const bool hasNoMedia = parentHasNoMedia(absolute);
        const std::string ext = extensionOf(lowerName);

        const bool hiddenMatch =
            options_.includeHidden && (hiddenDir || hiddenFile || hasNoMedia);
        const bool candidate =
            candidateExtensions().find(ext) != candidateExtensions().end();
        const bool maybeUnindexed =
            options_.detectUnindexed && !options_.indexedPaths.empty() &&
            options_.indexedPaths.find(absolute) == options_.indexedPaths.end() &&
            candidate;

        if (!inTrash && !inLost && !inThumbs && !hiddenMatch && !maybeUnindexed) return;

        DetectedFile detected;
        if (!detectFile(absolute, name, detected)) return;

        const bool mediaKind = detected.kind == MediaKind::IMAGE ||
                               detected.kind == MediaKind::VIDEO ||
                               detected.kind == MediaKind::AUDIO;
        const bool unindexed =
            options_.detectUnindexed && !options_.indexedPaths.empty() &&
            options_.indexedPaths.find(absolute) == options_.indexedPaths.end() &&
            mediaKind;

        const bool reportable = inTrash || inLost || inThumbs || hiddenMatch || unindexed;
        if (!reportable) return;
        if (options_.trashOnly && !inTrash) return;

        if (!seen.insert(absolute).second) return;

        Origin origin = Origin::DEEP_SCAN;
        if (inTrash) {
            origin = Origin::TRASH;
        } else if (inLost || inThumbs || hiddenFile || hiddenDir || hasNoMedia) {
            origin = Origin::HIDDEN;
        } else if (unindexed) {
            origin = Origin::UNINDEXED;
        }

        int confidence = detected.confidence;
        if (inTrash) {
            confidence = 96;
        } else if (inLost) {
            confidence = 84;
        } else if (inThumbs) {
            confidence = 58;
        } else if (hiddenFile) {
            confidence = 72;
        } else if (detected.confidence >= 80) {
            confidence = std::min(90, detected.confidence - 3);
        } else if (unindexed) {
            confidence = 62;
        }

        std::string detail = "Recoverable media";
        if (trashName) {
            detail = "Deleted by the gallery (trash file name)";
        } else if (inTrash) {
            detail = "Found inside a recycle bin";
        } else if (inLost) {
            detail = "Fragment from LOST.DIR";
        } else if (inThumbs) {
            detail = "Cached thumbnail (lower resolution)";
        } else if (hasNoMedia) {
            detail = "Hidden by a .nomedia marker";
        } else if (hiddenFile) {
            detail = "Hidden file";
        } else if (hiddenDir) {
            detail = "Inside a hidden folder";
        } else if (unindexed) {
            detail = "No longer in the media index";
        }

        if (callbacks_.onFound) {
            callbacks_.onFound("deep:" + absolute, name, detected.kind, size, origin,
                               detected.mime, confidence, absolute, detail, detected.format,
                               detected.ext, trashName);
        }
    }

    const ScanOptions& options_;
    const ScanCallbacks& callbacks_;
    std::unordered_map<std::string, bool> noMediaCache_;
};

}  // namespace

ScanResult deepScan(const ScanOptions& options, const ScanCallbacks& callbacks) {
    ScannerImpl scanner(options, callbacks);
    return scanner.run();
}

}  // namespace phoenix
