# Phoenix Recover

Phoenix Recover is an offline Android data recovery app built with Kotlin and
Jetpack Compose. It helps find deleted or orphaned photos, videos, audio files,
documents and archives directly on the device — no account, no cloud and no
uploads.

## Features

- **Deep scan** — walks accessible storage for recycle bins, `.trashed` folders,
  `LOST.DIR` fragments, hidden media, `.nomedia` folders and files missing from
  the MediaStore index.
- **System trash** — reads Android's own recently deleted MediaStore bin on
  Android 11+.
- **Signature carving** — rebuilds files from a raw `.img`/`.dd` image using
  magic bytes and structural boundaries.
- **Root raw imaging** — optionally images a block partition with `dd` when the
  device is rooted. Read-only and cancellable.
- **Smart results** — filter by confidence (strong/likely/fragment), find
  duplicates, and select only high-confidence files in one tap.
- **CSV reports** — export the full recovery report to
  `Downloads/Phoenix Recover/reports`.
- **Vault library** — search recovered files by name, filter by type, and sort
  by newest or largest.
- **Activity history** — recent scans, carves and raw imaging runs are saved
  and shown on the dashboard.
- **Preview and vault** — inspect results, then recover copies to
  `Downloads/Phoenix Recover`.
- **Offline by design** — all processing happens on the device.

## Tech stack

- Kotlin
- Jetpack Compose and Material 3
- AndroidX Lifecycle and Coroutines
- C++17 native engine (signature carver, sniffer and deep scanner) called over JNI
- Gradle (Kotlin DSL for the root build, Groovy for the app module)
- JUnit 4

## Requirements

- Android 7.0 (API 24) or newer
- JDK 17
- Android SDK with API 36
- "All files access" (`MANAGE_EXTERNAL_STORAGE`) for full scanning
- Root is optional and only needed for raw partition imaging

## Build

```bash
git clone https://github.com/rmofficial/PhoenixRecover.git
cd PhoenixRecover
./gradlew assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Building in Termux

If Gradle cannot find a compatible `aapt2`, point it at Termux's binary by
adding this line to your user-level `~/.gradle/gradle.properties`:

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

Do not commit that path to the project `gradle.properties` — it is
machine-specific.

### Native engine

The recovery engine lives in `app/src/main/cpp` and is written in C++17. It
implements the signature table, carver, sniffer and deep scanner, and is
exposed to Kotlin through `NativeEngine.kt`. The UI stays in Jetpack Compose.

A prebuilt `arm64-v8a` library is packaged from
`app/src/main/jniLibs/arm64-v8a`. Rebuild it on Termux with the bundled Android
targeting clang:

```bash
app/src/main/cpp/build-termux.sh
```

On a machine with the Android NDK installed, `app/src/main/cpp/CMakeLists.txt`
can be used with `externalNativeBuild` instead. When the native library is
unavailable the Kotlin engine is used as a fallback, so JVM unit tests and
non-arm64 devices keep working.

## Tests

```bash
./gradlew testDebugUnitTest
```

The engine tests cover signature carving for common formats, false-positive
handling, minimum size rules and deep-scan trash detection.

## Project structure

```text
app/src/main/cpp/
├── engine_core.h            # Engine types and public API
├── signatures.cpp           # Signatures, magic-byte sniffer
├── sources.cpp              # File descriptor and memory sources
├── carver.cpp               # Streaming signature carver
├── scanner.cpp              # Deep filesystem scanner
├── jni_bridge.cpp           # JNI entry points
├── CMakeLists.txt           # Optional NDK build
└── build-termux.sh          # Termux/Android clang build script

app/src/main/java/com/phoenix/recover/
├── AppViewModel.kt          # UI state, scan/carve orchestration
├── MainActivity.kt
├── engine/
│   ├── ByteSource.kt        # Seekable file/byte-array sources
│   ├── Carver.kt            # Streaming signature carver
│   ├── ContentUriSource.kt  # Seekable source for SAF documents
│   ├── DeepScanner.kt       # Filesystem deep scan
│   ├── MediaStoreTrash.kt   # Android MediaStore trash reader
│   ├── Models.kt
│   ├── Signatures.kt        # File signatures and end-of-file parsers
│   └── Sniffer.kt           # Magic-byte detection
├── ui/
│   ├── components/          # Shared Compose components
│   ├── screens/             # Home, scan, carve, results, vault, settings, root
│   └── theme/
└── util/
    ├── RecoverySaver.kt     # Saves recovered files to Downloads
    ├── RootUtil.kt          # Root probe and raw imaging
    ├── StorageUtil.kt       # Volumes, permissions and MediaStore index
    └── Formatters.kt
```

## Limitations

Recovery can only restore data whose blocks have not been overwritten. Deep
scans and carving cannot guarantee resurrection of permanently deleted files,
and the device should be used as little as possible after data loss.

## Author

Rahul Mandal ([@rmofficial](https://github.com/rmofficial))
