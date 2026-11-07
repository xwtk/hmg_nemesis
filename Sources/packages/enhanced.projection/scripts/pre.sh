#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
. ./scripts/common.sh

log "preinstall enhanced.projection"
# Each of these replaced the same files; its backup holds the stock bytes.
for _old in carplay.session projection.tbt; do
    if [ -d "/data/nemesis/backup/$_old" ]; then
        log "FAIL: uninstall $_old first"
        exit 1
    fi
done
if [ ! -x /system/xbin/nemesis ]; then
    log "FAIL: /system/xbin/nemesis missing"
    exit 1
fi
if [ ! -f "$BP" ]; then
    log "FAIL: $BP missing"
    exit 1
fi
for _n in $ALL_APPS; do
    if [ ! -s "files/patched/${_n}.apk" ]; then
        log "FAIL: missing files/patched/${_n}.apk"
        exit 1
    fi
    if [ ! -s "files/expect/${_n}.odex.md5" ]; then
        log "FAIL: missing files/expect/${_n}.odex.md5"
        exit 1
    fi
    classify_app "$_n" && _st=0 || _st=$?
    if [ "$_st" -eq 1 ]; then
        exit 1
    fi
done
if [ ! -s "$CONV_PAYLOAD" ]; then
    log "FAIL: missing $CONV_PAYLOAD"
    exit 1
fi
classify_receiver && _st=0 || _st=$?
if [ "$_st" -eq 1 ]; then
    exit 1
fi
lib_pattern "$CORE_LIB" core || exit 1
lib_pattern "$AUDIO_LIB" audio || exit 1
lib_pattern "$UTILS_LIB" utils || exit 1
log "preinstall ok"
exit 0
