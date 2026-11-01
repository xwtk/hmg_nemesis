#!/bin/sh
# Cross-check nemesis/troubleshooter against binaries present on system.ext4.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
SYS="${ROOT}/system.ext4"
if [ ! -f "$SYS" ] && [ -n "${SYSTEM_EXT4:-}" ]; then
    SYS=$SYSTEM_EXT4
fi
NEMESIS="${ROOT}/native/nemesis"

if [ ! -f "$SYS" ]; then
    echo "SKIP: $SYS not found"
    exit 0
fi

have_bin() {
    debugfs -R "stat /bin/$1" "$SYS" 2>&1 | grep -qE 'Type: (regular|symlink)'
}

missing=""
for t in sh mksh logcat grep mount sleep chmod date id kill mkdir cp mv rm cat reboot am toolbox dd; do
    have_bin "$t" || missing="$missing $t"
done

if [ -n "$missing" ]; then
    echo "ERROR: missing on system.ext4 /bin:$missing"
    exit 1
fi

for bad in busybox awk inotifywait tail mkfifo unzip base64; do
    if have_bin "$bad"; then
        echo "NOTE: /bin/$bad exists (nemesis may use it optionally)"
    else
        echo "OK: no /bin/$bad on system.ext4"
    fi
done

if [ -f "$NEMESIS" ]; then
    if strings "$NEMESIS" | grep -q '/system/bin/busybox'; then
        echo "ERROR: nemesis references busybox"
        exit 1
    fi
    echo "OK: native ELF $NEMESIS"
fi

echo "OK: nemesis tools match system.ext4 (toolbox/mksh/logcat/dd, no busybox/awk)"
