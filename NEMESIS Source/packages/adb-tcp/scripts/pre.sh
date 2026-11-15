#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
. ./scripts/common.sh

log "preinstall adb.tcp"
if [ ! -x /system/xbin/nemesis ]; then
    log "FAIL: /system/xbin/nemesis missing"
    exit 1
fi
if [ ! -f "$BP" ]; then
    log "FAIL: $BP missing"
    exit 1
fi
log "preinstall ok"
exit 0
