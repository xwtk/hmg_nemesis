#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
. ./scripts/common.sh

log "preinstall projection.tbt"
if [ ! -x /system/xbin/nemesis ]; then
    log "FAIL: /system/xbin/nemesis missing"
    exit 1
fi
check_sums || exit 1
for _name in HKMC_GPM HKMC_DiPO Iap2ServerApp HKMC_Navi_ExSL; do
    if [ ! -s "files/${_name}.apk" ]; then
        log "FAIL: missing files/${_name}.apk"
        exit 1
    fi
    if [ ! -s "/system/app/${_name}.apk" ]; then
        log "FAIL: /system/app/${_name}.apk missing"
        exit 1
    fi
done
if [ ! -s "files/libautoreceiver_infobank.so" ]; then
    log "FAIL: missing files/libautoreceiver_infobank.so"
    exit 1
fi
if [ ! -s /system/lib/libautoreceiver_infobank.so ]; then
    log "FAIL: /system/lib/libautoreceiver_infobank.so missing"
    exit 1
fi
log "preinstall ok"
exit 0
