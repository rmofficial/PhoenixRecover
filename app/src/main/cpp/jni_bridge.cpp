#include "engine_core.h"

#include <jni.h>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <string>
#include <vector>

using namespace phoenix;

namespace {

std::string toStd(JNIEnv* env, jstring value) {
    if (value == nullptr) return std::string();
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return std::string();

    std::string out;
    out.reserve(static_cast<size_t>(length) * 2);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

jstring toJava(JNIEnv* env, const std::string& value) {
    std::vector<jchar> utf16;
    utf16.reserve(value.size());
    size_t i = 0;
    while (i < value.size()) {
        const unsigned char c = static_cast<unsigned char>(value[i++]);
        uint32_t cp = 0xFFFD;
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0 && i < value.size()) {
            cp = ((c & 0x1F) << 6) | (static_cast<unsigned char>(value[i++]) & 0x3F);
        } else if ((c & 0xF0) == 0xE0 && i + 1 < value.size()) {
            cp = ((c & 0x0F) << 12) |
                 ((static_cast<unsigned char>(value[i]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(value[i + 1]) & 0x3F);
            i += 2;
        } else if ((c & 0xF8) == 0xF0 && i + 2 < value.size()) {
            cp = ((c & 0x07) << 18) |
                 ((static_cast<unsigned char>(value[i]) & 0x3F) << 12) |
                 ((static_cast<unsigned char>(value[i + 1]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(value[i + 2]) & 0x3F);
            i += 3;
        }
        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    if (utf16.empty()) return env->NewStringUTF("");
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::vector<std::string> readStrings(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> out;
    if (array == nullptr) return out;
    const jsize count = env->GetArrayLength(array);
    out.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto* item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        out.push_back(toStd(env, item));
        env->DeleteLocalRef(item);
    }
    return out;
}

bool readLongs(JNIEnv* env, jlongArray array, std::vector<jlong>& out) {
    if (array == nullptr) return false;
    const jsize count = env->GetArrayLength(array);
    out.resize(static_cast<size_t>(count));
    if (count > 0) env->GetLongArrayRegion(array, 0, count, out.data());
    return true;
}

jlongArray makeLongs(JNIEnv* env, const std::vector<jlong>& values) {
    jlongArray array = env->NewLongArray(static_cast<jsize>(values.size()));
    if (array != nullptr && !values.empty()) {
        env->SetLongArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    }
    return array;
}

struct CarveBridge {
    JNIEnv* env = nullptr;
    jobject callback = nullptr;
    jmethodID cancel = nullptr;
    jmethodID status = nullptr;
    jmethodID progress = nullptr;
    jmethodID found = nullptr;
    bool broken = false;

    bool failed() {
        if (broken) return true;
        if (env->ExceptionCheck()) {
            broken = true;
            return true;
        }
        return false;
    }

    CarveCallbacks build() {
        CarveCallbacks callbacks;
        callbacks.isCancelled = [this]() -> bool {
            if (failed()) return true;
            const jboolean result = env->CallBooleanMethod(callback, cancel);
            if (env->ExceptionCheck()) {
                broken = true;
                return true;
            }
            return result == JNI_TRUE;
        };
        callbacks.onStatus = [this](const std::string& message) {
            if (failed()) return;
            jstring value = toJava(env, message);
            env->CallVoidMethod(callback, status, value);
            env->DeleteLocalRef(value);
        };
        callbacks.onProgress = [this](int64_t processed, int64_t total, int found,
                                      int64_t bytesWritten) {
            if (failed()) return;
            env->CallVoidMethod(callback, progress, static_cast<jlong>(processed),
                                static_cast<jlong>(total), static_cast<jint>(found),
                                static_cast<jlong>(bytesWritten));
        };
        callbacks.onFound = [this](const std::string& id, const std::string& name,
                                   MediaKind kind, int64_t size, const std::string& mime,
                                   int confidence, int64_t offset,
                                   const std::string& detail, const std::string& format,
                                   const std::string& path) {
            if (failed()) return;
            jstring jid = toJava(env, id);
            jstring jname = toJava(env, name);
            jstring jkind = toJava(env, mediaKindName(kind));
            jstring jmime = toJava(env, mime);
            jstring jdetail = toJava(env, detail);
            jstring jformat = toJava(env, format);
            jstring jpath = toJava(env, path);
            env->CallVoidMethod(callback, found, jid, jname, jkind, static_cast<jlong>(size),
                                jmime, static_cast<jint>(confidence),
                                static_cast<jlong>(offset), jdetail, jformat, jpath);
            env->DeleteLocalRef(jid);
            env->DeleteLocalRef(jname);
            env->DeleteLocalRef(jkind);
            env->DeleteLocalRef(jmime);
            env->DeleteLocalRef(jdetail);
            env->DeleteLocalRef(jformat);
            env->DeleteLocalRef(jpath);
        };
        return callbacks;
    }
};

struct ScanBridge {
    JNIEnv* env = nullptr;
    jobject callback = nullptr;
    jmethodID cancel = nullptr;
    jmethodID status = nullptr;
    jmethodID progress = nullptr;
    jmethodID found = nullptr;
    bool broken = false;

    bool failed() {
        if (broken) return true;
        if (env->ExceptionCheck()) {
            broken = true;
            return true;
        }
        return false;
    }

    ScanCallbacks build() {
        ScanCallbacks callbacks;
        callbacks.isCancelled = [this]() -> bool {
            if (failed()) return true;
            const jboolean result = env->CallBooleanMethod(callback, cancel);
            if (env->ExceptionCheck()) {
                broken = true;
                return true;
            }
            return result == JNI_TRUE;
        };
        callbacks.onStatus = [this](const std::string& message) {
            if (failed()) return;
            jstring value = toJava(env, message);
            env->CallVoidMethod(callback, status, value);
            env->DeleteLocalRef(value);
        };
        callbacks.onProgress = [this](int64_t dirs, int64_t filesSeen, int64_t found,
                                      const std::string& path) {
            if (failed()) return;
            jstring value = toJava(env, path);
            env->CallVoidMethod(callback, progress, static_cast<jlong>(dirs),
                                static_cast<jlong>(filesSeen), static_cast<jlong>(found),
                                value);
            env->DeleteLocalRef(value);
        };
        callbacks.onFound = [this](const std::string& id, const std::string& name,
                                   MediaKind kind, int64_t size, Origin origin,
                                   const std::string& mime, int confidence,
                                   const std::string& sourcePath, const std::string& detail,
                                   const std::string& format, const std::string& ext,
                                   bool trashName) {
            if (failed()) return;
            jstring jid = toJava(env, id);
            jstring jname = toJava(env, name);
            jstring jkind = toJava(env, mediaKindName(kind));
            jstring jorigin = toJava(env, originName(origin));
            jstring jmime = toJava(env, mime);
            jstring jsource = toJava(env, sourcePath);
            jstring jdetail = toJava(env, detail);
            jstring jformat = toJava(env, format);
            jstring jext = toJava(env, ext);
            env->CallVoidMethod(callback, found, jid, jname, jkind, static_cast<jlong>(size),
                                jorigin, jmime, static_cast<jint>(confidence), jsource,
                                jdetail, jformat, jext,
                                static_cast<jboolean>(trashName ? JNI_TRUE : JNI_FALSE));
            env->DeleteLocalRef(jid);
            env->DeleteLocalRef(jname);
            env->DeleteLocalRef(jkind);
            env->DeleteLocalRef(jorigin);
            env->DeleteLocalRef(jmime);
            env->DeleteLocalRef(jsource);
            env->DeleteLocalRef(jdetail);
            env->DeleteLocalRef(jformat);
            env->DeleteLocalRef(jext);
        };
        return callbacks;
    }
};

bool initCarveBridge(CarveBridge& bridge, JNIEnv* env, jobject callback) {
    if (callback == nullptr) return false;
    bridge.env = env;
    bridge.callback = callback;
    jclass cls = env->GetObjectClass(callback);
    bridge.cancel = env->GetMethodID(cls, "isCancelled", "()Z");
    bridge.status = env->GetMethodID(cls, "onStatus", "(Ljava/lang/String;)V");
    bridge.progress = env->GetMethodID(cls, "onProgress", "(JJIJ)V");
    bridge.found = env->GetMethodID(
        cls, "onFound",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;I"
        "JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);
    return bridge.cancel != nullptr && bridge.status != nullptr &&
           bridge.progress != nullptr && bridge.found != nullptr;
}

bool initScanBridge(ScanBridge& bridge, JNIEnv* env, jobject callback) {
    if (callback == nullptr) return false;
    bridge.env = env;
    bridge.callback = callback;
    jclass cls = env->GetObjectClass(callback);
    bridge.cancel = env->GetMethodID(cls, "isCancelled", "()Z");
    bridge.status = env->GetMethodID(cls, "onStatus", "(Ljava/lang/String;)V");
    bridge.progress = env->GetMethodID(cls, "onProgress", "(JJJLjava/lang/String;)V");
    bridge.found = env->GetMethodID(
        cls, "onFound",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;"
        "Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        "Ljava/lang/String;Z)V");
    env->DeleteLocalRef(cls);
    return bridge.cancel != nullptr && bridge.status != nullptr &&
           bridge.progress != nullptr && bridge.found != nullptr;
}

jlongArray runCarve(JNIEnv* env, int fd, int64_t length, jstring outputDir,
                    jlongArray optionsArray, jobjectArray extensionsArray,
                    jobjectArray kindsArray, jobject callback) {
    std::vector<jlong> values;
    if (!readLongs(env, optionsArray, values) || values.size() < 6) {
        if (fd >= 0) ::close(fd);
        return nullptr;
    }

    CarveOptions options;
    options.minSize = values[0];
    options.maxFileSize = values[1];
    options.maxResults = static_cast<int>(values[2]);
    options.maxOutputBytes = values[3];
    options.maxBytesToScan = values[4];
    options.writeOutput = values[5] != 0;
    options.allowedExtensions = readStrings(env, extensionsArray);

    const auto kindNames = readStrings(env, kindsArray);
    for (const auto& name : kindNames) {
        MediaKind kind;
        if (mediaKindFromName(name, kind)) options.kinds.push_back(kind);
    }

    CarveBridge bridge;
    if (!initCarveBridge(bridge, env, callback)) {
        if (fd >= 0) ::close(fd);
        return nullptr;
    }

    const std::string output = toStd(env, outputDir);
    FdSource source(fd, length);
    const CarveResult result = carveImage(source, output, options, bridge.build());

    if (env->ExceptionCheck()) return nullptr;

    return makeLongs(env, {
        static_cast<jlong>(result.bytesExamined),
        static_cast<jlong>(result.filesFound),
        static_cast<jlong>(result.filesWritten),
        static_cast<jlong>(result.bytesWritten),
        static_cast<jlong>(result.cancelled ? 1 : 0),
        static_cast<jlong>(result.outputLimitHit ? 1 : 0),
        static_cast<jlong>(result.resultLimitHit ? 1 : 0),
        static_cast<jlong>(result.elapsedMs),
    });
}

}  // namespace

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_com_phoenix_recover_engine_NativeEngine_nativeSniff(JNIEnv* env, jobject,
                                                         jbyteArray data, jint length) {
    if (data == nullptr) return nullptr;
    jsize arrayLength = env->GetArrayLength(data);
    if (length < 0) return nullptr;
    if (length > arrayLength) length = arrayLength;
    if (length <= 0) return nullptr;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return nullptr;
    DetectedFile detected;
    const bool found = sniffBytes(reinterpret_cast<const uint8_t*>(bytes), length, detected);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    if (!found) return nullptr;

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(5, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    if (out == nullptr) return nullptr;

    const std::string values[5] = {
        detected.format,
        detected.ext,
        detected.mime,
        mediaKindName(detected.kind),
        std::to_string(detected.confidence),
    };
    for (int i = 0; i < 5; ++i) {
        jstring value = toJava(env, values[i]);
        env->SetObjectArrayElement(out, i, value);
        env->DeleteLocalRef(value);
    }
    return out;
}

JNIEXPORT jlongArray JNICALL
Java_com_phoenix_recover_engine_NativeEngine_nativeCarvePath(
    JNIEnv* env, jobject, jstring path, jstring outputDir, jlongArray optionsArray,
    jobjectArray extensionsArray, jobjectArray kindsArray, jobject callback) {
    const std::string filePath = toStd(env, path);
    const int fd = ::open(filePath.c_str(), O_RDONLY);
    if (fd < 0) return nullptr;
    struct stat st;
    if (::fstat(fd, &st) != 0 || st.st_size <= 0) {
        ::close(fd);
        return nullptr;
    }
    return runCarve(env, fd, static_cast<int64_t>(st.st_size), outputDir, optionsArray,
                    extensionsArray, kindsArray, callback);
}

JNIEXPORT jlongArray JNICALL
Java_com_phoenix_recover_engine_NativeEngine_nativeCarveFd(
    JNIEnv* env, jobject, jint fd, jlong length, jstring outputDir, jlongArray optionsArray,
    jobjectArray extensionsArray, jobjectArray kindsArray, jobject callback) {
    if (fd < 0 || length <= 0) {
        if (fd >= 0) ::close(fd);
        return nullptr;
    }
    return runCarve(env, fd, length, outputDir, optionsArray, extensionsArray, kindsArray,
                    callback);
}

JNIEXPORT jlongArray JNICALL
Java_com_phoenix_recover_engine_NativeEngine_nativeDeepScan(
    JNIEnv* env, jobject, jobjectArray rootsArray, jlongArray optionsArray,
    jobjectArray indexedArray, jobject callback) {
    std::vector<jlong> values;
    if (!readLongs(env, optionsArray, values) || values.size() < 7) return nullptr;

    ScanOptions options;
    options.maxDepth = static_cast<int>(values[0]);
    options.includeHidden = values[1] != 0;
    options.trashOnly = values[2] != 0;
    options.minSize = values[3];
    options.maxResults = static_cast<int>(values[4]);
    options.detectUnindexed = values[5] != 0;
    options.maxFilesToInspect = static_cast<int>(values[6]);
    options.roots = readStrings(env, rootsArray);
    for (const auto& path : readStrings(env, indexedArray)) {
        options.indexedPaths.insert(path);
    }

    ScanBridge bridge;
    if (!initScanBridge(bridge, env, callback)) return nullptr;

    const ScanResult result = deepScan(options, bridge.build());
    if (env->ExceptionCheck()) return nullptr;

    return makeLongs(env, {
        static_cast<jlong>(result.dirsVisited),
        static_cast<jlong>(result.filesInspected),
        static_cast<jlong>(result.found),
        static_cast<jlong>(result.cancelled ? 1 : 0),
        static_cast<jlong>(result.elapsedMs),
        static_cast<jlong>(result.errors),
    });
}

}  // extern "C"
