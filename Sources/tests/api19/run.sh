#!/usr/bin/env bash
# Build and test NEMESIS on a stock API 19 emulator in Docker (needs /dev/kvm).
# usage: run.sh [git-ref]   default: working tree. `run.sh 167b71d` tests upstream as a baseline.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
IMAGE=nemesis-api19

docker image inspect $IMAGE >/dev/null 2>&1 || docker build -t $IMAGE "$HERE"

SRC=$(mktemp -d)
trap 'rm -rf "$SRC"' EXIT
if [ $# -ge 1 ]; then
    git -C "$REPO" archive "$1" Sources | tar -x -C "$SRC"
else
    cp -a "$REPO/Sources" "$SRC/"
fi
cp "$HERE/build.sh" "$HERE/emu_test.sh" "$SRC/"
sed -i 's/\r$//' "$SRC/build.sh" "$SRC/emu_test.sh"

docker run --rm --device /dev/kvm -v "$SRC:/src" $IMAGE \
    bash -c 'bash /src/build.sh /src/Sources /src/out && bash /src/emu_test.sh /src/out; rc=$?; chmod -R a+rwX /src; exit $rc'
