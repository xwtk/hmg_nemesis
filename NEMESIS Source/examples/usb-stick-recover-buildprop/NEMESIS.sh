#!/system/bin/sh
# USB root is the working directory. A reboot_recovery file on the stick
# reboots into recovery. Otherwise build.prop on the stick replaces
# /system/build.prop. No firmware check: 260507 has more than one variant.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

BP=/system/build.prop
LOG=/data/nemesis/recover-buildprop.log

log() {
    echo "$1"
    mkdir -p /data/nemesis 2>/dev/null || true
    echo "$1" >> "$LOG" 2>/dev/null || true
    echo "$1" >> ./recover-buildprop.log 2>/dev/null || true
}

sys_rw() {
    /system/xbin/nemesis remount rw >/dev/null 2>&1 && return 0
    mount -o remount,rw /system 2>/dev/null && return 0
    mount -o rw,remount /system 2>/dev/null && return 0
    mount -o rw,remount / 2>/dev/null && return 0
    return 1
}

log "recover start"

if [ -f ./reboot_recovery ]; then
    log "reboot_recovery present; calling reboot recovery"
    sync
    reboot recovery
    log "FAIL: reboot recovery returned"
    exit 1
fi

if [ ! -f ./build.prop ]; then
    log "FAIL: no build.prop on USB"
    exit 1
fi

if ! sys_rw; then
    log "FAIL: remount /system rw"
    exit 1
fi

cp -f ./build.prop "$BP" || {
    log "FAIL: copy build.prop"
    exit 1
}
chmod 644 "$BP" 2>/dev/null || true
chown 0.0 "$BP" 2>/dev/null || chown 0:0 "$BP" 2>/dev/null || true
sync
log "copied USB build.prop onto $BP"
exit 0
