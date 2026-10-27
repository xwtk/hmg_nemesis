#!/usr/bin/env bash
# Build NEMESIS CLI (x86, for the API 19 emulator) and the Manager APK.
# usage: build.sh <Sources dir> <out dir>   (runs inside the nemesis-api19 image)
set -euo pipefail
SRC=$1
OUT=$2
WORK=$(mktemp -d)
cp -a "$SRC"/. "$WORK"/
# Windows checkouts are CRLF; device shell scripts and make recipes need LF.
find "$WORK" -type f \( -name '*.c' -o -name '*.h' -o -name 'Makefile' -o -name '*.sh' \
    -o -name '*.py' -o -name '*.xml' -o -name '*.java' -o -name '*.security' \) \
    -exec sed -i 's/\r$//' {} +

TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
make -C "$WORK/native" NDK="$NDK" CC="$TC/i686-linux-android19-clang" \
    STRIP="$TC/i686-linux-android-strip" >/dev/null
make -C "$WORK/manager" ANDROID_JAR="$ANDROID_HOME/platforms/android-30/android.jar" \
    >/dev/null 2>&1 || make -C "$WORK/manager" ANDROID_JAR="$ANDROID_HOME/platforms/android-30/android.jar"

mkdir -p "$OUT"
cp "$WORK/native/nemesis" "$WORK/manager/NEMESIS.apk" "$WORK/boot/install-recovery.sh" "$OUT"/
file "$OUT/nemesis"
rm -rf "$WORK"
