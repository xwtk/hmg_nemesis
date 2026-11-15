#!/system/bin/sh
# projection.tbt — snapshot the live APK and odex, then install a dex-injected APK.
# Uninstall writes that snapshot back. Patched APKs in files/ are the payload.

export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

BACKUP_DIR=/data/nemesis/backup/projection.tbt
LOG=/data/nemesis/projection.tbt.log
APP_DIR=/system/app
LIB_BASE=libautoreceiver_infobank.so
LIB_LIVE=/system/lib/libautoreceiver_infobank.so
# Stock 260507 bytes. A live file that does not match is left alone.
SUM_GPM_APK=c155b0fb97384e645987dc1bd0f85922
SUM_GPM_ODEX=96cd7c27a9db19acb3900f21f57ea9f0
SUM_DIPO_APK=01446fb79dc4fcfc826271ba18158fa4
SUM_DIPO_ODEX=a565e180fdb0b73d6520f28c91cdba63
SUM_IAP_APK=fde208439f993986a60396e759797a01
SUM_IAP_ODEX=03950e4b691f846e5478a5a69818c65e
SUM_NAVI_APK=19559e742b1d21550aa8f0324e8e6d36
SUM_NAVI_ODEX=9cf41f78ce312b3762b354714d12eec3
SUM_LIB=48ee793af8b86052503b8c952b034731

log() {
    local _msg="$1"
    echo "$_msg"
    mkdir -p /data/nemesis 2>/dev/null || true
    echo "$_msg" >> "$LOG" 2>/dev/null || true
}

sys_rw() {
    /system/xbin/nemesis remount rw >/dev/null 2>&1 && return 0
    mount -o remount,rw /system 2>/dev/null && return 0
    mount -o rw,remount /system 2>/dev/null && return 0
    mount -o rw,remount / 2>/dev/null && return 0
    return 1
}

sys_ro() {
    /system/xbin/nemesis remount ro >/dev/null 2>&1 || true
    mount -o remount,ro /system 2>/dev/null || true
    mount -o ro,remount /system 2>/dev/null || true
}

file_md5() {
    local _line
    local _sum
    _line=$(md5 "$1" 2>/dev/null) || return 1
    set -- $_line
    _sum=$1
    if [ -z "$_sum" ]; then
        return 1
    fi
    echo "$_sum"
}

sum_is() {
    local _live="$1"
    local _want="$2"
    local _got
    if [ ! -s "$_live" ]; then
        log "FAIL: $_live missing"
        return 1
    fi
    _got=$(file_md5 "$_live") || {
        log "FAIL: md5 $_live"
        return 1
    }
    if [ "$_got" != "$_want" ]; then
        log "FAIL: $_live checksum $_got want $_want"
        return 1
    fi
    return 0
}

check_sums() {
    sum_is "$APP_DIR/HKMC_GPM.apk" "$SUM_GPM_APK" || return 1
    sum_is "$APP_DIR/HKMC_GPM.odex" "$SUM_GPM_ODEX" || return 1
    sum_is "$APP_DIR/HKMC_DiPO.apk" "$SUM_DIPO_APK" || return 1
    sum_is "$APP_DIR/HKMC_DiPO.odex" "$SUM_DIPO_ODEX" || return 1
    sum_is "$APP_DIR/Iap2ServerApp.apk" "$SUM_IAP_APK" || return 1
    sum_is "$APP_DIR/Iap2ServerApp.odex" "$SUM_IAP_ODEX" || return 1
    sum_is "$APP_DIR/HKMC_Navi_ExSL.apk" "$SUM_NAVI_APK" || return 1
    sum_is "$APP_DIR/HKMC_Navi_ExSL.odex" "$SUM_NAVI_ODEX" || return 1
    sum_is "$LIB_LIVE" "$SUM_LIB" || return 1
    log "checksums ok"
    return 0
}

each_app() {
    echo HKMC_GPM
    echo HKMC_DiPO
    echo Iap2ServerApp
    echo HKMC_Navi_ExSL
}

remove_backup_dir() {
    case "$BACKUP_DIR" in
        /data/nemesis/backup/projection.tbt) ;;
        *)
            log "FAIL: refusing to remove $BACKUP_DIR"
            return 1
            ;;
    esac
    rm -rf "$BACKUP_DIR"
}

chown_system() {
    local _path="$1"
    chown 0.0 "$_path" 2>/dev/null || chown 0:0 "$_path" 2>/dev/null || true
}

receiver_patched() {
    grep navbridge1 "$1" >/dev/null 2>&1
}

snapshot_lib() {
    local _bak
    _bak="$BACKUP_DIR/$LIB_BASE"
    if [ -s "$_bak" ]; then
        log "snapshot exists for receiver - keeping original"
        return 0
    fi
    if [ ! -s "$LIB_LIVE" ]; then
        log "FAIL: $LIB_LIVE missing"
        return 1
    fi
    if receiver_patched "$LIB_LIVE"; then
        log "FAIL: live receiver is already patched and has no snapshot"
        return 1
    fi
    cp -f "$LIB_LIVE" "$_bak" || return 1
    chmod 644 "$_bak" 2>/dev/null || true
    log "backed up $LIB_LIVE"
    return 0
}

apply_lib() {
    local _src
    _src="files/$LIB_BASE"
    if [ ! -s "$_src" ]; then
        log "FAIL: missing $_src"
        return 1
    fi
    if ! receiver_patched "$_src"; then
        log "FAIL: payload receiver has no navbridge marker"
        return 1
    fi
    cp -f "$_src" "$LIB_LIVE" || return 1
    chmod 644 "$LIB_LIVE" 2>/dev/null || true
    chown_system "$LIB_LIVE"
    if [ -x /system/bin/restorecon ]; then
        restorecon "$LIB_LIVE" 2>/dev/null || true
    fi
    if ! cmp "$_src" "$LIB_LIVE" >/dev/null 2>&1; then
        log "FAIL: $LIB_LIVE does not match payload"
        return 1
    fi
    log "OK: $LIB_LIVE patched"
    return 0
}

restore_lib() {
    local _bak
    _bak="$BACKUP_DIR/$LIB_BASE"
    if [ -s "$_bak" ]; then
        cp -f "$_bak" "$LIB_LIVE" || return 1
        chmod 644 "$LIB_LIVE" 2>/dev/null || true
        chown_system "$LIB_LIVE"
        if [ -x /system/bin/restorecon ]; then
            restorecon "$LIB_LIVE" 2>/dev/null || true
        fi
        if ! cmp "$_bak" "$LIB_LIVE" >/dev/null 2>&1; then
            log "FAIL: restored receiver mismatch"
            return 1
        fi
        log "restored receiver library"
        return 0
    fi
    if receiver_patched "$LIB_LIVE"; then
        log "FAIL: patched receiver has no snapshot"
        return 1
    fi
    log "receiver library unchanged"
    return 0
}

wipe_dalvik() {
    rm -f /data/dalvik-cache/*HKMC_GPM* 2>/dev/null || true
    rm -f /data/dalvik-cache/*HKMC_DiPO* 2>/dev/null || true
    rm -f /data/dalvik-cache/*Iap2ServerApp* 2>/dev/null || true
    rm -f /data/dalvik-cache/*HKMC_Navi_ExSL* 2>/dev/null || true
    rm -f /data/dalvik-cache/arm/*HKMC_GPM* 2>/dev/null || true
    rm -f /data/dalvik-cache/arm/*HKMC_DiPO* 2>/dev/null || true
    rm -f /data/dalvik-cache/arm/*Iap2ServerApp* 2>/dev/null || true
    rm -f /data/dalvik-cache/arm/*HKMC_Navi_ExSL* 2>/dev/null || true
    rm -f /cache/dalvik-cache/*HKMC_GPM* 2>/dev/null || true
    rm -f /cache/dalvik-cache/*HKMC_DiPO* 2>/dev/null || true
    rm -f /cache/dalvik-cache/*Iap2ServerApp* 2>/dev/null || true
    rm -f /cache/dalvik-cache/*HKMC_Navi_ExSL* 2>/dev/null || true
}

snapshot_all() {
    local _name
    local _live
    local _odex
    local _bak_apk
    local _bak_odex
    mkdir -p "$BACKUP_DIR" 2>/dev/null || true
    for _name in HKMC_GPM HKMC_DiPO Iap2ServerApp HKMC_Navi_ExSL; do
        _live="$APP_DIR/${_name}.apk"
        _odex="$APP_DIR/${_name}.odex"
        _bak_apk="$BACKUP_DIR/${_name}.apk"
        _bak_odex="$BACKUP_DIR/${_name}.odex"
        if [ -s "$_bak_apk" ]; then
            log "snapshot exists for $_name apk - keeping original"
        else
            if [ ! -s "$_live" ]; then
                log "FAIL: $_live missing"
                return 1
            fi
            cp -f "$_live" "$_bak_apk" || return 1
            chmod 644 "$_bak_apk" 2>/dev/null || true
            log "backed up $_live"
        fi
        if [ -s "$_bak_odex" ]; then
            log "snapshot exists for $_name odex - keeping original"
        else
            if [ ! -s "$_odex" ]; then
                log "FAIL: $_odex missing, cannot snapshot"
                return 1
            fi
            cp -f "$_odex" "$_bak_odex" || return 1
            chmod 644 "$_bak_odex" 2>/dev/null || true
            log "backed up $_odex"
        fi
    done
    snapshot_lib || return 1
    return 0
}

write_odex() {
    local _apk="$1"
    local _odex="$2"
    if [ -z "$BOOTCLASSPATH" ]; then
        export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar
    fi
    if [ ! -x /system/bin/dexopt ]; then
        log "dexopt binary missing"
        return 1
    fi
    log "dexopt $_apk"
    if ! /system/bin/dexopt --preopt "$_apk" "$_odex" "m=y,u=n"; then
        return 1
    fi
    if [ ! -s "$_odex" ]; then
        return 1
    fi
    chmod 644 "$_odex" 2>/dev/null || true
    chown_system "$_odex"
    if [ -x /system/bin/restorecon ]; then
        restorecon "$_odex" 2>/dev/null || true
    fi
    return 0
}

apply_all() {
    local _name
    local _src
    local _live
    local _odex
    for _name in HKMC_GPM HKMC_DiPO Iap2ServerApp HKMC_Navi_ExSL; do
        _src="files/${_name}.apk"
        _live="$APP_DIR/${_name}.apk"
        _odex="$APP_DIR/${_name}.odex"
        if [ ! -s "$_src" ]; then
            log "FAIL: missing $_src"
            return 1
        fi
        cp -f "$_src" "$_live" || return 1
        chmod 644 "$_live" 2>/dev/null || true
        chown_system "$_live"
        if ! cmp "$_src" "$_live" >/dev/null 2>&1; then
            log "FAIL: $_live does not match payload"
            return 1
        fi
        rm -f "$_odex" 2>/dev/null || true
        if [ -f "$_odex" ]; then
            log "FAIL: could not delete $_odex"
            return 1
        fi
        if ! write_odex "$_live" "$_odex"; then
            rm -f "$_odex" 2>/dev/null || true
            log "dexopt failed for $_name; boot will optimize"
        else
            log "OK: $_live patched, odex written"
        fi
    done
    apply_lib || return 1
    wipe_dalvik
    return 0
}

restore_all() {
    local _name
    local _live
    local _odex
    local _bak_apk
    local _bak_odex
    if ! sys_rw; then
        log "FAIL: remount /system rw"
        return 1
    fi
    if ! restore_lib; then
        sys_ro
        return 1
    fi
    for _name in HKMC_GPM HKMC_DiPO Iap2ServerApp HKMC_Navi_ExSL; do
        _live="$APP_DIR/${_name}.apk"
        _odex="$APP_DIR/${_name}.odex"
        _bak_apk="$BACKUP_DIR/${_name}.apk"
        _bak_odex="$BACKUP_DIR/${_name}.odex"
        if [ ! -s "$_bak_apk" ] || [ ! -s "$_bak_odex" ]; then
            log "FAIL: incomplete snapshot for $_name"
            sys_ro
            return 1
        fi
        cp -f "$_bak_apk" "$_live" || { sys_ro; return 1; }
        cp -f "$_bak_odex" "$_odex" || { sys_ro; return 1; }
        chmod 644 "$_live" "$_odex" 2>/dev/null || true
        chown_system "$_live"
        chown_system "$_odex"
        if ! cmp "$_bak_apk" "$_live" >/dev/null 2>&1; then
            log "FAIL: restored $_live mismatch"
            sys_ro
            return 1
        fi
        if ! cmp "$_bak_odex" "$_odex" >/dev/null 2>&1; then
            log "FAIL: restored $_odex mismatch"
            sys_ro
            return 1
        fi
        log "restored $_name apk+odex"
    done
    wipe_dalvik
    sync
    sys_ro
    return 0
}
