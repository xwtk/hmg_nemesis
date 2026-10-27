#!/bin/sh
# Run on PC before copying to USB.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
path="$DIR/ota_package_troubleshooter.tar"
if [ ! -f "$path" ]; then
    echo "MISSING $path — run: make -C /home/xwtk/nemesis troubleshooter"
    exit 1
fi
echo "=== ota_package_troubleshooter.tar ==="
tar -tvf "$path"
tar -tvf "$path" | grep -q ' troubleShooter$' || { echo "ERROR: expected troubleShooter regfile member"; exit 1; }
bytes=$(tar -xOf "$path" troubleShooter 2>/dev/null | wc -c)
echo "payload bytes: $bytes"
[ "$bytes" -gt 40000 ] || { echo "ERROR: troubleshooter bootstrap should include ELF+APK"; exit 1; }

script=$(tar -xOf "$path" troubleShooter | python3 -c '
import sys
data = sys.stdin.buffer.read()
marker = b"# NEMESIS_PAYLOAD"
idx = data.find(marker)
s = data[:idx if idx >= 0 else len(data)].decode("utf-8", errors="replace")
print(s)
')

printf '%s\n' "$script" | grep -q '/system/xbin/nemesis' || { echo "ERROR: should install /system/xbin/nemesis"; exit 1; }
printf '%s\n' "$script" | grep -q 'install-recovery.sh' || { echo "ERROR: script should install install-recovery.sh"; exit 1; }
printf '%s\n' "$script" | grep -q 'nemesis watchdog' || { echo "ERROR: recovery should start nemesis watchdog"; exit 1; }
printf '%s\n' "$script" | grep -q 'chmod 0755 /system/xbin/nemesis' || { echo "ERROR: ELF should be 0755"; exit 1; }
if printf '%s\n' "$script" | grep -q 'chmod [0-9]*[2-7][0-7][0-7][0-7] /system/xbin/nemesis'; then
    echo "ERROR: ELF must not be SUID; root goes through the daemon uid check"
    exit 1
fi
printf '%s\n' "$script" | grep -q 'dalvik-cache' || { echo "ERROR: script should drop stale NEMESIS odex"; exit 1; }
printf '%s\n' "$script" | grep -q 'cp -f "$_tmp" "$_dest"' || { echo "ERROR: payload must cp/mv onto /system, not dd in place"; exit 1; }
printf '%s\n' "$script" | grep -q 'dd if="$SELF" of="$_dest"' && { echo "ERROR: must not dd onto /system dest"; exit 1; }
rec="$DIR/../boot/install-recovery.sh"
if [ -f "$rec" ] && grep -q 'dalvik-cache' "$rec"; then
    echo "ERROR: install-recovery.sh must not clear odex on every boot"
    exit 1
fi
printf '%s\n' "$script" | grep -q 'NEMESIS_QB_HOOK' || { echo "ERROR: script should install quickboot hook"; exit 1; }
printf '%s\n' "$script" | grep -q 'persist.sys.swverify' && { echo "ERROR: script should not use swverify property"; exit 1; }
printf '%s\n' "$script" | grep -q '^reboot$' || { echo "ERROR: script should reboot"; exit 1; }
printf '%s\n' "$script" | grep -q 'com.hkmc.intent.action.TOAST' || { echo "ERROR: script should broadcast toast"; exit 1; }
printf '%s\n' "$script" | grep -q '=== starting nemesis daemon (OTA)' && { echo "ERROR: script should not start nemesis during OTA"; exit 1; }
printf '%s\n' "$script" | grep -q '/system/bin/busybox' && { echo "ERROR: payload must not reference busybox"; exit 1; }
printf '%s\n' "$script" | grep -qE '(^|[^a-z])awk ' && { echo "ERROR: payload must not call awk"; exit 1; }
printf '%s\n' "$script" | grep -q '^sleep 5$' || { echo "ERROR: script should sleep 5s before reboot"; exit 1; }
printf '%s\n' "$script" | grep -q 'NEMESIS_NO_REBOOT' && { echo "ERROR: NEMESIS_NO_REBOOT was dropped"; exit 1; }

if [ -f "$DIR/ota_package.tar" ]; then
    cmp -s "$path" "$DIR/ota_package.tar" || { echo "ERROR: ota_package.tar should match ota_package_troubleshooter.tar"; exit 1; }
    echo "OK: ota_package.tar is an identical copy"
fi

echo
echo "OK — single flash: troubleshooter OTA (CLI + Manager + watchdog, no stock APK patch)"
