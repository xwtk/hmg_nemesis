#!/bin/bash
# Host only. A fingerprint pack (porter convert) must port exactly like the smali pack it came from:
# for every build in the corpus, batch (device mode) gives the same report and the same ported classes,
# and serve (LivePatch's mode) gives the same reply. Skipped when the corpus is not on this PC.
#   tests/porter_fp_test.sh [smali pack dir]   (default: the enhanced.projection pack)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
U=${UNIVERSAL:-$HOME/universal}
PK=${1:-$ROOT/packages/enhanced.projection/files/porter}
[ -d "$U/tgt" ] || { echo "SKIP: no corpus at $U (set UNIVERSAL)"; exit 0; }
[ -d "$PK/ref" ] || { echo "no smali pack at $PK" >&2; exit 1; }
bash "$ROOT/tools/build_porter.sh" > /dev/null 2>&1
P="$ROOT/tools/porter.sh"
W=$(mktemp -d) # kept on failure for the logs
export PORTER_DEODEX=$W/deodex # shared: both packs read the same deodexed odex
mkdir -p "$PORTER_DEODEX"
APPS=$(cd "$PK/ref" && ls *.zip | sed 's/\.zip$//')

"$P" selftest
"$P" convert "$PK" "$W/fp" $APPS > /dev/null
echo "smali pack $(du -sh "$PK" | cut -f1), fingerprint pack $(du -sh "$W/fp" | cut -f1)"

same_report() { # drop the timings, compare the rest
    python3 -I -c '
import json, sys
a, b = (json.load(open(p)) for p in sys.argv[1:])
for r in (a, b):
    r.pop("ms", None)
sys.exit(a != b)' "$1" "$2"
}

fail=0
for t in $(ls "$U/tgt"); do
    S=$U/tgt/$t/system
    for e in smali fp; do
        mkdir -p "$W/$t/$e"
        : > "$W/$t/$e/jobs"
        for a in $APPS; do
            echo "$a $S/app/$a.odex $S/app/$a.apk $W/$t/$e/$a.dz $W/$t/$e/$a.json" >> "$W/$t/$e/jobs"
        done
    done
    s=$(date +%s%N)
    PORTER_FILES=$W/$t/smali/files "$P" batch "$PK" "$W/$t/smali/jobs" "$S/framework" > "$W/$t/smali.log" 2>&1 || true
    m=$(date +%s%N)
    PORTER_FILES=$W/$t/fp/files "$P" batch "$W/fp" "$W/$t/fp/jobs" "$S/framework" > "$W/$t/fp.log" 2>&1 || true
    e=$(date +%s%N)
    bad=""
    for a in $APPS; do
        [ -f "$W/$t/smali/$a.json" ] || { bad="$bad $a:no-report"; continue; }
        same_report "$W/$t/smali/$a.json" "$W/$t/fp/$a.json" || bad="$bad $a:report"
        diff -rq "$W/$t/smali/files/$a" "$W/$t/fp/files/$a" > /dev/null 2>&1 || bad="$bad $a:classes"
        { [ -f "$W/$t/smali/$a.dz" ] && [ -f "$W/$t/fp/$a.dz" ]; } || { [ ! -f "$W/$t/smali/$a.dz" ] && [ ! -f "$W/$t/fp/$a.dz" ]; } || bad="$bad $a:dz"
    done
    ok=$(grep -c ' ok ' "$W/$t/smali.log" || true)
    echo "$t: smali $(( (m - s) / 1000000 )) ms, fp $(( (e - m) / 1000000 )) ms, $ok ok, ${bad:-same}"
    [ -z "$bad" ] || fail=1
done

# serve: the same request against each pack, reply and report compared (lab build, one cross build)
for t in ev6_usa neev_can; do
    [ -d "$U/tgt/$t" ] || continue
    S=$U/tgt/$t/system
    for a in $APPS; do
        rm -f "$W"/serve_*
        for e in smali fp; do
            pk=$PK; [ $e = fp ] && pk=$W/fp
            printf 'port\t%s\t%s\t%s\t%s\t%s\t%s\t1\t-\t%s\t0\t100\t%s\n' "$a" "$pk" "$S/app/$a.odex" "$S/app/$a.odex" \
                "$S/app/$a.apk" "$W/serve_$e.dz" "$W/none" "$W/serve_$e.json" \
                | "$P" serve "$S/framework" 2> /dev/null | grep '^R ' > "$W/serve_$e.reply" || true
        done
        if cmp -s "$W/serve_smali.reply" "$W/serve_fp.reply" && same_report "$W/serve_smali.json" "$W/serve_fp.json"; then
            echo "serve $t $a: same ($(cat "$W/serve_fp.reply"))"
        else
            echo "serve $t $a: DIFFERS ($(cat "$W/serve_smali.reply") / $(cat "$W/serve_fp.reply"))"
            fail=1
        fi
    done
done
[ $fail = 0 ] || { echo "porter_fp_test: FAIL (logs in $W)"; exit 1; }
rm -rf "$W"
echo "porter_fp_test: PASS"
