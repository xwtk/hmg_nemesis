#!/bin/bash
# Runs porter on a PC, e.g. tools/porter.sh pack <src> <out> <apk dir> <app>... Build it first with make porter.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
J=${JAVA_LIBS:-/usr/share/java}
P=$ROOT/porter
# shortcut: same class path as build_porter.sh, keep the two in step.
CP="$J/smali.jar:$J/baksmali.jar:$J/dexlib2.jar:$J/smali-util.jar:$P/lib/guava-27.1-android.jar:$P/lib/failureaccess-1.0.1.jar:$J/antlr3-runtime.jar:$J/stringtemplate.jar"
[ -f "$ROOT/out/porter-host.jar" ] || { echo "out/porter-host.jar missing: run make porter" >&2; exit 1; }
exec java -cp "$ROOT/out/porter-host.jar:$CP" porter.Main "$@"
