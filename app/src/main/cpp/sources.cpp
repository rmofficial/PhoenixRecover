#include "engine_core.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <unistd.h>

namespace phoenix {

const char* mediaKindName(MediaKind kind) {
    switch (kind) {
        case MediaKind::IMAGE: return "IMAGE";
        case MediaKind::VIDEO: return "VIDEO";
        case MediaKind::AUDIO: return "AUDIO";
        case MediaKind::DOCUMENT: return "DOCUMENT";
        case MediaKind::ARCHIVE: return "ARCHIVE";
        case MediaKind::OTHER: return "OTHER";
    }
    return "OTHER";
}

bool mediaKindFromName(const std::string& name, MediaKind& out) {
    if (name == "IMAGE") { out = MediaKind::IMAGE; return true; }
    if (name == "VIDEO") { out = MediaKind::VIDEO; return true; }
    if (name == "AUDIO") { out = MediaKind::AUDIO; return true; }
    if (name == "DOCUMENT") { out = MediaKind::DOCUMENT; return true; }
    if (name == "ARCHIVE") { out = MediaKind::ARCHIVE; return true; }
    if (name == "OTHER") { out = MediaKind::OTHER; return true; }
    return false;
}

const char* originName(Origin origin) {
    switch (origin) {
        case Origin::DEEP_SCAN: return "DEEP_SCAN";
        case Origin::TRASH: return "TRASH";
        case Origin::HIDDEN: return "HIDDEN";
        case Origin::UNINDEXED: return "UNINDEXED";
        case Origin::CARVE: return "CARVE";
        case Origin::ROOT: return "ROOT";
    }
    return "DEEP_SCAN";
}

int MemorySource::readAt(int64_t offset, uint8_t* buffer, int count) {
    if (offset < 0 || offset >= size_ || count <= 0) return -1;
    const int64_t available = size_ - offset;
    const int n = static_cast<int>(std::min<int64_t>(count, available));
    std::memcpy(buffer, data_ + offset, static_cast<size_t>(n));
    return n;
}

FdSource::~FdSource() {
    if (fd_ >= 0) ::close(fd_);
}

int FdSource::readAt(int64_t offset, uint8_t* buffer, int count) {
    if (offset < 0 || offset >= length_ || count <= 0) return -1;
    size_t total = 0;
    const size_t want = static_cast<size_t>(count);
    while (total < want) {
        const ssize_t r = ::pread(fd_, buffer + total, want - total,
                                  static_cast<off_t>(offset + static_cast<int64_t>(total)));
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (r == 0) break;
        total += static_cast<size_t>(r);
    }
    return total == 0 ? -1 : static_cast<int>(total);
}

}  // namespace phoenix
