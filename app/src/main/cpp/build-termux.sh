#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/../jniLibs/arm64-v8a"
CXX="${CXX:-aarch64-linux-android-clang++}"

mkdir -p "$OUT"

"$CXX" -std=c++17 -O2 -fPIC -shared -Wall -Wextra -Wl,-soname,libphoenix_engine.so \
    -I"$DIR" \
    -o "$OUT/libphoenix_engine.so" \
    "$DIR/signatures.cpp" \
    "$DIR/sources.cpp" \
    "$DIR/carver.cpp" \
    "$DIR/scanner.cpp" \
    "$DIR/jni_bridge.cpp"

CXX_SHARED="$(dirname "$(command -v "$CXX")")/../lib/libc++_shared.so"
if [ -f "$CXX_SHARED" ]; then
    cp -f "$CXX_SHARED" "$OUT/libc++_shared.so"
fi

echo "Built $OUT/libphoenix_engine.so"
