#!/usr/bin/env bash
# Security checks for NEMESIS on a stock Android 4.4 (API 19) x86 emulator.
# usage: emu_test.sh <dir with nemesis, NEMESIS.apk, install-recovery.sh>
# Every check runs (no early exit) so an unhardened build shows each hole.
# Callers are simulated with `su <uid>`: the daemon only sees SO_PEERCRED,
# which is the same for an app process and a su'd shell with that uid.
set -uo pipefail
BIN=$1
APP_UID=10123   # any non-Manager app
SHELL_UID=2000
pass=0
failed=0

sh_() { adb shell "$@" | tr -d '\r'; }
ok() { echo "PASS  $1"; pass=$((pass + 1)); }
bad() { echo "FAIL  $1${2:+  -- $2}"; failed=$((failed + 1)); }
check() { if eval "$2"; then ok "$1"; else bad "$1" "${3:-}"; fi; }

wait_boot() {
    adb wait-for-device
    for _ in $(seq 1 180); do
        [ "$(sh_ getprop sys.boot_completed)" = 1 ] && return 0
        sleep 2
    done
    echo "emulator did not boot"; exit 2
}

echo "== boot emulator"
emulator -avd api19 -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
    -writable-system -gpu swiftshader_indirect -accel on >/tmp/emulator.log 2>&1 &
wait_boot
adb root >/dev/null 2>&1; sleep 2; adb wait-for-device

echo "== install as the troubleshooter would"
sh_ mount -o rw,remount /system
adb push "$BIN/nemesis" /system/xbin/nemesis >/dev/null
adb push "$BIN/NEMESIS.apk" /system/app/NEMESIS.apk >/dev/null
adb push "$BIN/install-recovery.sh" /system/etc/install-recovery.sh >/dev/null
# Upstream's installer sets 4755; the hardened one sets 0755. Mirror whichever the boot hook uses.
mode=$(grep -o 'chmod [0-9]* /system/xbin/nemesis' "$BIN/install-recovery.sh" | head -1 | cut -d' ' -f2)
sh_ "chown root:root /system/xbin/nemesis; chmod $mode /system/xbin/nemesis; chmod 644 /system/app/NEMESIS.apk; chmod 755 /system/etc/install-recovery.sh"
sh_ mkdir -p /data/nemesis/apps
# A world-writable bin/ left behind: root package hooks put it first in PATH.
sh_ "mkdir -p /data/nemesis/bin; chmod 777 /data/nemesis/bin"
adb reboot
sleep 5
wait_boot
adb root >/dev/null 2>&1; sleep 2; adb wait-for-device
pinged() { sh_ "/system/xbin/nemesis ping && echo PING_OK" | grep -q PING_OK; }
for _ in $(seq 1 30); do pinged && break; sleep 1; done
MGR=$(sh_ cat /data/system/packages.xml | grep -o '<package name="com.nemesis.manager"[^>]*' | grep -o ' userId="[0-9]*"' | grep -o '[0-9]*')
echo "Manager uid: ${MGR:-<not registered>}"
for _ in $(seq 1 20); do sh_ cat /data/nemesis/packages.db 2>/dev/null | grep -q usb-handler && break; sleep 1; done
sleep 3

echo "== checks"
# API 19 adb shell always exits 0, so every check judges captured output, never a device exit code.
check "watchdog started from install-recovery.sh at boot" 'pinged'

out=$(sh_ su $SHELL_UID am start -W -n com.nemesis.manager/.DialogActivity --es nemesis_mode result_ok 2>&1)
check "other uid cannot start DialogActivity (wipe_run vector)" 'echo "$out" | grep -qi "permission denial\|securityexception"' "$(echo "$out" | head -3)"
sh_ input keyevent 4 >/dev/null

sh_ rm -f /data/nemesis/usb-session/reply
sh_ su $APP_UID sh -c "'echo yes > /data/nemesis/usb-session/reply'" >/dev/null 2>&1
out=$(sh_ ls /data/nemesis/usb-session/)
check "other app cannot forge the USB prompt reply" '! echo "$out" | grep -q reply' "$out"
if [ -n "$MGR" ]; then
    sh_ su "$MGR" sh -c "'echo yes > /data/nemesis/usb-session/reply'" >/dev/null 2>&1
    out=$(sh_ ls /data/nemesis/usb-session/)
    check "Manager uid ($MGR) can still answer the USB prompt" 'echo "$out" | grep -q reply' "$out"
else
    bad "Manager registered in packages.xml"
fi
sh_ rm -f /data/nemesis/usb-session/reply
sh_ su $APP_UID sh -c "'echo forged >> /data/nemesis/nemesis.log'" >/dev/null 2>&1
out=$(sh_ cat /data/nemesis/nemesis.log)
check "other app cannot write the NEMESIS log" '! echo "$out" | grep -q forged' "$(sh_ ls -l /data/nemesis/nemesis.log)"
# apps/ holds package hooks that run as root; the installer pre-creates it with umask 0.
out=$(sh_ "ls -l /data/nemesis /data/nemesis/bin; ls -ld /data/local/tmp/nemesis" 2>/dev/null)
check "no world-writable NEMESIS dirs or files" '! echo "$out" | grep -q "^[d-].......w"' "$(echo "$out" | grep "^[d-].......w")"

echo
echo "passed $pass, failed $failed"
[ -n "${KEEP:-}" ] || adb emu kill >/dev/null 2>&1
[ "$failed" -eq 0 ]
