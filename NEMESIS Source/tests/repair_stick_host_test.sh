#!/bin/bash
# Host-only check of usb_repair_app_process/NEMESIS.sh on a fake /system and /data tree.
# Usage: tests/repair_stick_host_test.sh <path to NEMESIS.sh>
set -e
SRC=${1:?NEMESIS.sh path}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p $T/system/bin $T/system/xbin $T/data/nemesis/stock $T/data/hmgapi/stock/system/bin $T/usb $T/stub
printf '#!/bin/sh\nexit 0\n' > $T/stub/chown
printf '#!/bin/sh\nexit 0\n' > $T/system/xbin/nemesis
chmod +x $T/stub/chown $T/system/xbin/nemesis
sed -e "s#\([ =\"]\)/system/#\1$T/system/#g" -e "s#\([ =\"]\)/data/#\1$T/data/#g" \
    -e "s#^export PATH=.*#export PATH=$T/stub:\$PATH#" "$SRC" > $T/run.sh
run() { (cd $T/usb && sh $T/run.sh | tail -1); }
fails=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }
# host mv accepts -f, HU toolbox mv fails on it and moves nothing (QEMU, 1.17.1)
HERE=$(cd "$(dirname "$0")/.." && pwd)
check "no 'mv -f' (toolbox mv has no options)" '! grep -n "mv -f" "$SRC" "$HERE/usb_bundle/uninstall_nemesis.sh"'
STOCK='ELF Usage: app_process [java-options] cmd-dir start-class-name /system/bin/app_process'
B=$T/system/bin

# S1 broken: NEMESIS head, .real = HMG wrapper naming a missing .hmgapi-stock
printf '#!/system/bin/sh\n# NEMESIS LivePatch\n' > $B/app_process
echo "exec $B/app_process.hmgapi-stock \"\$@\"" > $B/app_process.real
echo "$STOCK" > $T/data/hmgapi/stock/system/bin/app_process
out=$(run)
check "broken .real replaced from HMG backup" 'grep -q Usage $B/app_process.real && [[ $out == OK:* ]]'
check "old .real saved on the stick" 'grep -q hmgapi-stock $T/usb/app_process.before'
check "head left alone" 'grep -q "NEMESIS LivePatch" $B/app_process'

out=$(run)
check "stock .real untouched" '[[ $out == *"nothing changed"* ]]'

echo "exec $B/app_process.hmgapi-stock \"\$@\"" > $B/app_process.real
echo "$STOCK" > $B/app_process.hmgapi-stock
out=$(run)
check "wrapper with live link untouched" '[[ $out == *"nothing changed"* ]]'

rm $B/app_process.hmgapi-stock $T/data/hmgapi/stock/system/bin/app_process
out=$(run)
check "no stock -> FAIL, .real kept" '[[ $out == "FAIL: no stock"* ]] && grep -q hmgapi-stock $B/app_process.real'

echo "$STOCK" > $T/usb/app_process
out=$(run)
check "stock from the stick used first" '[[ $out == *"from ./app_process"* ]] && grep -q Usage $B/app_process.real'

# no NEMESIS: a broken HMG wrapper at the head itself
echo "exec $B/app_process.hmgapi-stock \"\$@\"" > $B/app_process
rm -f $B/app_process.real
out=$(run)
check "broken head replaced when NEMESIS is gone" 'grep -q Usage $B/app_process && [ ! -e $B/app_process.real ]'

echo "$fails failed"
[ $fails = 0 ]
