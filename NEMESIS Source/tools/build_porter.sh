#!/bin/bash
# Builds out/porter.jar (dex, for dalvikvm on the unit) and out/porter-host.jar (classes, for
# tools/porter.sh on a PC) from porter/src. Host only.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
P=$ROOT/porter
[ -d "$P/src/porter" ] || { echo "porter/ not in this tree; skipping porter.jar"; exit 0; }
J=${JAVA_LIBS:-/usr/share/java}
SDK=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-34/android.jar}
CP="$J/smali.jar:$J/baksmali.jar:$J/dexlib2.jar:$J/smali-util.jar:$P/lib/guava-27.1-android.jar:$P/lib/failureaccess-1.0.1.jar:$J/antlr3-runtime.jar:$J/stringtemplate.jar"
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
mkdir -p "$W/cls" "$W/dex" "$ROOT/out"
javac --release 8 -Xlint:-options -nowarn -d "$W/cls" -cp "$CP" "$P"/src/porter/*.java
jar cf "$W/porter-cls.jar" -C "$W/cls" porter
cp -f "$W/porter-cls.jar" "$ROOT/out/porter-host.jar"
IFS=: read -r -a JARS <<< "$CP" # split on : only; the tree path has a space ("NEMESIS Source")
d8 --release --min-api 19 --lib "$SDK" --output "$W/dex" "$W/porter-cls.jar" "${JARS[@]}"
python3 - "$W/dex/classes.dex" "$ROOT/out/porter.jar" <<'EOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[2], "w", zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[1], "classes.dex")
EOF
cp -f "$P/porter.out" "$ROOT/out/porter.out"
echo "Wrote $ROOT/out/porter.jar (output version $(cat "$P/porter.out"))"
