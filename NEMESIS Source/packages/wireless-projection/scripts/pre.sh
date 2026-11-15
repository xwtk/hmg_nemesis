#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
. ./scripts/common.sh

log "preinstall wireless.projection"
if [ ! -x /system/xbin/nemesis ]; then
    log "FAIL: /system/xbin/nemesis missing"
    exit 1
fi
if [ ! -f "$BP" ]; then
    log "FAIL: $BP missing"
    exit 1
fi
for _n in "$WIFI_XML" "$AA_XML" "$CP_XML"; do
    if [ ! -f "files/permissions/$_n" ]; then
        log "FAIL: missing files/permissions/$_n"
        exit 1
    fi
done
log "preinstall ok"
exit 0
