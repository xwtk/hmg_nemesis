#!/usr/bin/env bash
# Build the car (ARM) release in the nemesis-api19 Docker image:
# native/nemesis, manager/NEMESIS.apk, usb_bundle/ota_package.tar (+ verify_tars.sh).
# usage: release.sh <out dir> <keystore dir>
# The Manager keystore is kept outside the repo and reused, so later builds keep the
# same signature.
set -euo pipefail
OUT=$(realpath -m "$1")
KEYS=$(realpath -m "$2")
SRC=$(cd "$(dirname "$0")/.." && pwd)
IMAGE=nemesis-api19
mkdir -p "$OUT" "$KEYS"

docker image inspect $IMAGE >/dev/null 2>&1 || docker build -t $IMAGE "$SRC/tests/api19"

docker run --rm -v "$SRC:/ro:ro" -v "$OUT:/out" -v "$KEYS:/keys" $IMAGE bash -c '
set -euo pipefail
cp -a /ro /w
# Windows checkouts are CRLF; device scripts and make recipes need LF.
find /w -type f \( -name "*.c" -o -name "*.h" -o -name Makefile -o -name "*.sh" \
    -o -name "*.py" -o -name "*.xml" -o -name "*.java" -o -name "*.security" \) \
    -exec sed -i "s/\r$//" {} +
cd /w
make -C native NDK="$NDK" >/dev/null
make -C manager ANDROID_JAR="$ANDROID_HOME/platforms/android-30/android.jar" \
    KEYSTORE=/keys/nemesis.keystore >/dev/null
python3 make_troubleshooter_tar.py
sh usb_bundle/verify_tars.sh
cp native/nemesis manager/NEMESIS.apk usb_bundle/ota_package.tar /out/
cd /out && file nemesis && sha256sum nemesis NEMESIS.apk ota_package.tar | tee SHA256SUMS
chmod -R a+rwX /out /keys
'
