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
out=$(sh_ ls -l /system/xbin/nemesis)
check "CLI is not SUID" '! echo "$out" | grep -q rws' "$out"

out=$(sh_ su 1000 /system/xbin/nemesis root -c id)
check "system uid (1000) gets root via daemon" 'echo "$out" | grep -q "uid=0"' "$out"
if [ -n "$MGR" ]; then
    out=$(sh_ su "$MGR" /system/xbin/nemesis root -c id)
    check "Manager uid ($MGR) gets root via daemon" 'echo "$out" | grep -q "uid=0"' "$out"
else
    bad "Manager registered in packages.xml at /system/app"
fi
out=$(sh_ su $APP_UID /system/xbin/nemesis root -c id)
check "other app uid ($APP_UID) refused root" '! echo "$out" | grep -q "uid=0"' "$out"
out=$(sh_ su $SHELL_UID /system/xbin/nemesis root -c id)
check "shell uid ($SHELL_UID, adb) refused root" '! echo "$out" | grep -q "uid=0"' "$out"

out=$(sh_ su $SHELL_UID am start -W -n com.nemesis.manager/.DialogActivity --es nemesis_mode result_ok 2>&1)
check "other uid cannot start DialogActivity (wipe_run vector)" 'echo "$out" | grep -qi "permission denial\|securityexception"' "$(echo "$out" | head -3)"
sh_ input keyevent 4 >/dev/null

sh_ rm -f /data/nemesis/usb-session/reply
sh_ su $APP_UID sh -c "'echo yes > /data/nemesis/usb-session/reply'" >/dev/null 2>&1
out=$(sh_ ls /data/nemesis/usb-session/)
check "other app cannot forge the USB prompt reply" '! echo "$out" | grep -q reply' "$out"
sh_ rm -f /data/nemesis/usb-session/reply
sh_ su $APP_UID sh -c "'echo forged >> /data/nemesis/nemesis.log'" >/dev/null 2>&1
out=$(sh_ cat /data/nemesis/nemesis.log)
check "other app cannot write the NEMESIS log" '! echo "$out" | grep -q forged' "$(sh_ ls -l /data/nemesis/nemesis.log)"
# apps/ holds package hooks that run as root; the installer pre-creates it with umask 0.
out=$(sh_ "ls -l /data/nemesis /data/nemesis/bin; ls -ld /data/local/tmp/nemesis" 2>/dev/null)
check "no world-writable NEMESIS dirs or files" '! echo "$out" | grep -q "^[d-].......w"' "$(echo "$out" | grep "^[d-].......w")"

# Simulated stick: bind mount on /storage/usb0 like the car. The mount point lives on
# read-only rootfs, so create it once. Files are pushed: 4.4 has no printf.
USB=/storage/usb0
sh_ "mount -o rw,remount /; mkdir -p $USB; mount -o ro,remount /"
usb_stick() {  # $1 = marker text
    local t
    sh_ "umount $USB 2>/dev/null; rm -rf /data/local/tmp/usb-sim; mkdir -p /data/local/tmp/usb-sim $USB"
    t=$(mktemp -d)
    printf '[nemesis]\nversion=1.0\ntype=execute\npath=NEMESIS.sh\nid=%s\n' "$1" >"$t/NEMESIS.data"
    printf '#!/system/bin/sh\necho %s > /data/nemesis/usb-ran\n' "$1" >"$t/NEMESIS.sh"
    adb push "$t/NEMESIS.data" "$t/NEMESIS.sh" /data/local/tmp/usb-sim/ >/dev/null
    rm -rf "$t"
    sh_ rm -f /data/nemesis/usb-ran
    sh_ mount -o bind /data/local/tmp/usb-sim $USB
}
usb_mounted() { sh_ cat /proc/mounts | grep -q " $USB " && sh_ ls $USB/ | grep -q NEMESIS.data; }
ran() { sh_ cat /data/nemesis/usb-ran 2>/dev/null | grep -q "$1"; }

echo "== USB while Android is down (sys.boot_completed=0)"
sh_ rm -f /data/nemesis/usb-recovery
sh_ setprop sys.boot_completed 0
usb_stick held
check "simulated USB stick is mounted" 'usb_mounted' "$(sh_ cat /proc/mounts | grep usb)"
sleep 10
check "stick inserted while Android down is NOT run unattended" '! ran held'
sh_ setprop sys.boot_completed 1
for _ in $(seq 1 20); do sh_ cat /data/nemesis/nemesis.log | grep -q 'usb prompt confirm_execute' && break; sleep 1; done
# The handler clears any old reply after `am start` returns, so answer until it runs,
# as the Manager's Yes button would.
for _ in $(seq 1 15); do
    sh_ su "${MGR:-1000}" sh -c "'echo yes > /data/nemesis/usb-session/reply'" >/dev/null 2>&1
    sleep 1
    ran held && break
done
check "held stick prompts once Android is up and runs on Manager 'yes'" 'ran held'
# Dismiss the result dialog (handler waits for OK or unplug), then unplug.
for _ in 1 2 3; do sh_ su "${MGR:-1000}" sh -c "'echo ok > /data/nemesis/usb-session/reply'" >/dev/null 2>&1; sleep 1; done
sh_ umount $USB
sleep 3

sh_ touch /data/nemesis/usb-recovery
sh_ setprop sys.boot_completed 0
usb_stick flagged
for _ in $(seq 1 15); do ran flagged && break; sleep 1; done
check "owner recovery flag allows unattended run" 'ran flagged'
sh_ "umount $USB; rm -f /data/nemesis/usb-recovery"
sh_ setprop sys.boot_completed 1

echo
echo "passed $pass, failed $failed"
[ -n "${KEEP:-}" ] || adb emu kill >/dev/null 2>&1
[ "$failed" -eq 0 ]
