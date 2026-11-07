#!/system/bin/sh
# enhanced.projection — snapshot the live APK, odex, and libraries, then install
# the patched session and cluster turn-by-turn payload when the checksums match.
# Uninstall writes that snapshot back. This is not wireless.projection.

export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

BP=/system/build.prop
BACKUP_DIR=/data/nemesis/backup/enhanced.projection
LOG=/data/nemesis/enhanced.projection.log
DIPO_APP=HKMC_DiPO
IAP_APP=Iap2ServerApp
BT_APP=Bluetooth
PP_APP=PhoneProjection
GPM_APP=HKMC_GPM
NAVI_APP=HKMC_Navi_ExSL
CORE_LIB=/system/lib/libCarPlayCore.so
AUDIO_LIB=/system/lib/libCarPlayAudio.so
UTILS_LIB=/system/lib/libCarPlayUtils.so
CONV_LIB=/system/lib/libCarPlayConverter.so
CONV_PAYLOAD=files/libCarPlayConverter.so
ALL_APPS="$DIPO_APP $IAP_APP $BT_APP $PP_APP $GPM_APP $NAVI_APP"
RECV_LIB=/system/lib/libautoreceiver_infobank.so
RECV_BASE=libautoreceiver_infobank.so
RECV_PAYLOAD=files/libautoreceiver_infobank.so
RECV_EXPECT=files/expect/libautoreceiver_infobank.so.md5

log() {
    local _msg="$1"
    echo "$_msg"
    mkdir -p /data/nemesis 2>/dev/null || true
    echo "$_msg" >> "$LOG" 2>/dev/null || true
}

putline() {
    print -r -- "$1" 2>/dev/null && return 0
    printf '%s\n' "$1"
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

commit_tmp() {
    local _dst="$1"
    local _tmp="$2"
    if [ -s "$_dst" ] && [ ! -s "$_tmp" ]; then
        log "FAIL: refuse empty rewrite of $_dst"
        rm -f "$_tmp" 2>/dev/null || true
        return 1
    fi
    cp -f "$_tmp" "$_dst" || return 1
    chmod 644 "$_dst" 2>/dev/null || true
    chown 0.0 "$_dst" 2>/dev/null || chown 0:0 "$_dst" 2>/dev/null || true
    rm -f "$_tmp" 2>/dev/null || true
    return 0
}

snap_prop_line() {
    local _file="$1"
    local _key="$2"
    local _base="$3"
    local _line
    local _got=0
    if [ -s "${_base}.line" ] || [ -s "${_base}.absent" ]; then
        log "snapshot exists for $_key - keeping original"
        return 0
    fi
    while IFS= read -r _line || [ -n "$_line" ]; do
        case "$_line" in
            "${_key}="*)
                if [ "$_got" -eq 0 ]; then
                    putline "$_line" > "${_base}.line" || return 1
                    _got=1
                fi
                ;;
        esac
    done < "$_file"
    if [ "$_got" -eq 0 ]; then
        putline absent > "${_base}.absent" || return 1
    fi
    return 0
}

set_prop_line() {
    local _file="$1"
    local _key="$2"
    local _val="$3"
    local _tmp="$BACKUP_DIR/rewrite.tmp"
    local _line
    local _seen=0
    local _in=0
    local _out=0
    : > "$_tmp" || return 1
    while IFS= read -r _line || [ -n "$_line" ]; do
        _in=$((_in + 1))
        case "$_line" in
            "${_key}="*)
                putline "${_key}=${_val}" >> "$_tmp" || return 1
                _seen=1
                _out=$((_out + 1))
                ;;
            *)
                putline "$_line" >> "$_tmp" || return 1
                _out=$((_out + 1))
                ;;
        esac
    done < "$_file"
    if [ "$_seen" -eq 0 ]; then
        putline "${_key}=${_val}" >> "$_tmp" || return 1
        _out=$((_out + 1))
    fi
    if [ "$_out" -lt "$_in" ]; then
        log "FAIL: rewrite of $_file dropped lines ($_out < $_in)"
        rm -f "$_tmp" 2>/dev/null || true
        return 1
    fi
    commit_tmp "$_file" "$_tmp" || return 1
    log "set ${_key}=${_val} in $_file"
    return 0
}

restore_prop_line() {
    local _file="$1"
    local _key="$2"
    local _base="$3"
    local _tmp="$BACKUP_DIR/rewrite.tmp"
    local _line
    local _saved=""
    local _mode=drop
    local _seen=0
    if [ -s "${_base}.line" ]; then
        _mode=replace
        IFS= read -r _saved < "${_base}.line" || true
    elif [ -s "${_base}.absent" ]; then
        _mode=drop
    else
        log "FAIL: no snapshot for $_key"
        return 1
    fi
    : > "$_tmp" || return 1
    while IFS= read -r _line || [ -n "$_line" ]; do
        case "$_line" in
            "${_key}="*)
                if [ "$_mode" = replace ] && [ "$_seen" -eq 0 ]; then
                    putline "$_saved" >> "$_tmp" || return 1
                    _seen=1
                fi
                ;;
            *)
                putline "$_line" >> "$_tmp" || return 1
                ;;
        esac
    done < "$_file"
    if [ "$_mode" = replace ] && [ "$_seen" -eq 0 ]; then
        putline "$_saved" >> "$_tmp" || return 1
    fi
    commit_tmp "$_file" "$_tmp" || return 1
    log "restored $_key in $_file"
    return 0
}

snap_getprop() {
    local _key="$1"
    local _base="$2"
    local _v
    if [ -s "$_base" ] || [ -s "${_base}.unset" ]; then
        log "prop snapshot exists for $_key - keeping original"
        return 0
    fi
    _v=$(getprop "$_key" 2>/dev/null) || _v=""
    if [ -n "$_v" ]; then
        putline "$_v" > "$_base" || return 1
    else
        putline unset > "${_base}.unset" || return 1
    fi
    return 0
}

restore_getprop() {
    local _key="$1"
    local _base="$2"
    local _v
    if [ -s "$_base" ]; then
        IFS= read -r _v < "$_base" || true
        setprop "$_key" "$_v" 2>/dev/null || log "WARN: setprop $_key failed"
        log "restored prop $_key"
        return 0
    fi
    if [ -s "${_base}.unset" ]; then
        rm -f "/data/property/$_key" 2>/dev/null || true
        log "cleared prop $_key (unset at install)"
        return 0
    fi
    log "FAIL: no prop snapshot for $_key"
    return 1
}

hash_file() {
    local _bin _line _hex
    if [ -x /system/bin/md5 ]; then
        _bin=/system/bin/md5
    elif [ -x /system/bin/md5sum ]; then
        _bin=/system/bin/md5sum
    elif [ -x /bin/md5sum ]; then
        _bin=/bin/md5sum
    else
        echo "FAIL: md5 missing" >> "$LOG" 2>/dev/null || true
        return 1
    fi
    _line=$("$_bin" "$1" 2>/dev/null) || return 1
    case "$_line" in
        *"="*)
            _hex=${_line##* }
            ;;
        *)
            _hex=${_line%% *}
            ;;
    esac
    case "$_hex" in
        "")
            echo "FAIL: empty hash of $1" >> "$LOG" 2>/dev/null || true
            return 1
            ;;
    esac
    putline "$_hex"
    return 0
}

wipe_dalvik() {
    local _name="$1"
    rm -f /data/dalvik-cache/*"$_name"* \
        /data/dalvik-cache/arm/*"$_name"* \
        /data/dalvik-cache/arm64/*"$_name"* 2>/dev/null || true
    return 0
}

# 0 = live apk is already this payload, 10 = stock odex matches, 1 = refuse.
classify_app() {
    local _name="$1"
    local _apk="/system/app/${_name}.apk"
    local _odex="/system/app/${_name}.odex"
    local _patch="files/patched/${_name}.apk"
    local _expect="files/expect/${_name}.odex.md5"
    local _bak_apk="$BACKUP_DIR/${_name}.apk"
    local _bak_odex="$BACKUP_DIR/${_name}.odex"
    local _want _got
    if [ ! -s "$_patch" ] || [ ! -s "$_expect" ]; then
        log "FAIL: missing payload for $_name"
        return 1
    fi
    if [ ! -f "$_apk" ]; then
        log "FAIL: $_apk missing"
        return 1
    fi
    if [ ! -f "$_odex" ] && [ -s "$_bak_apk" ] && [ -s "$_bak_odex" ]; then
        if cmp "$_patch" "$_apk" >/dev/null 2>&1; then
            log "$_name already patched"
            return 0
        fi
        log "FAIL: $_odex missing and $_apk is not the payload"
        return 1
    fi
    if [ ! -s "$_odex" ]; then
        log "FAIL: $_odex missing"
        return 1
    fi
    IFS= read -r _want < "$_expect" || return 1
    _want=${_want%$'\r'}
    _got=$(hash_file "$_odex") || {
        log "FAIL: hash $_odex"
        return 1
    }
    if [ "$_got" != "$_want" ]; then
        log "FAIL: $_odex checksum $_got != $_want"
        return 1
    fi
    return 10
}

install_app() {
    local _name="$1"
    local _apk="/system/app/${_name}.apk"
    local _odex="/system/app/${_name}.odex"
    local _patch="files/patched/${_name}.apk"
    local _bak_apk="$BACKUP_DIR/${_name}.apk"
    local _bak_odex="$BACKUP_DIR/${_name}.odex"
    if [ ! -s "$_bak_apk" ]; then
        cp -f "$_apk" "$_bak_apk" || return 1
        chmod 644 "$_bak_apk" 2>/dev/null || true
    fi
    if [ ! -s "$_bak_odex" ]; then
        cp -f "$_odex" "$_bak_odex" || return 1
        chmod 644 "$_bak_odex" 2>/dev/null || true
    fi
    if cmp "$_patch" "$_bak_apk" >/dev/null 2>&1; then
        log "FAIL: backup of $_name is the payload"
        return 1
    fi
    cp -f "$_patch" "$_apk" || return 1
    chmod 644 "$_apk" 2>/dev/null || true
    chown 0.0 "$_apk" 2>/dev/null || chown 0:0 "$_apk" 2>/dev/null || true
    rm -f "$_odex" || return 1
    wipe_dalvik "$_name"
    log "patched $_name"
    return 0
}

apply_apps() {
    local _n
    local _st
    for _n in $ALL_APPS; do
        classify_app "$_n" && _st=0 || _st=$?
        if [ "$_st" -eq 1 ]; then
            return 1
        fi
    done
    for _n in $ALL_APPS; do
        classify_app "$_n" && _st=0 || _st=$?
        if [ "$_st" -eq 10 ]; then
            install_app "$_n" || return 1
        fi
    done
    return 0
}

restore_owned_file() {
    local _live="$1"
    local _bak="$2"
    if [ ! -s "$_bak" ]; then
        log "FAIL: no snapshot for $_live"
        return 1
    fi
    cp -f "$_bak" "$_live" || return 1
    chmod 644 "$_live" 2>/dev/null || true
    chown 0.0 "$_live" 2>/dev/null || chown 0:0 "$_live" 2>/dev/null || true
    log "restored $_live"
    return 0
}

restore_app() {
    local _name="$1"
    restore_owned_file "/system/app/${_name}.apk" "$BACKUP_DIR/${_name}.apk" || return 1
    restore_owned_file "/system/app/${_name}.odex" "$BACKUP_DIR/${_name}.odex" || return 1
    wipe_dalvik "$_name"
    return 0
}

# 0 = live receiver is already this payload, 10 = stock bytes, 1 = refuse.
classify_receiver() {
    local _want _got
    if [ ! -s "$RECV_PAYLOAD" ] || [ ! -s "$RECV_EXPECT" ]; then
        log "FAIL: missing payload for $RECV_BASE"
        return 1
    fi
    if [ ! -s "$RECV_LIB" ]; then
        log "FAIL: $RECV_LIB missing"
        return 1
    fi
    if cmp "$RECV_PAYLOAD" "$RECV_LIB" >/dev/null 2>&1; then
        return 0
    fi
    IFS= read -r _want < "$RECV_EXPECT" || return 1
    _want=${_want%$'\r'}
    _got=$(hash_file "$RECV_LIB") || {
        log "FAIL: hash $RECV_LIB"
        return 1
    }
    if [ "$_got" != "$_want" ]; then
        log "FAIL: $RECV_LIB checksum $_got != $_want"
        return 1
    fi
    return 10
}

cpatch_bin() {
    if [ -n "${NEMESIS_CPATCH:-}" ] && [ -x "$NEMESIS_CPATCH" ]; then
        echo "$NEMESIS_CPATCH"
        return 0
    fi
    if [ -f ./bin/cpatch ]; then
        chmod 755 ./bin/cpatch 2>/dev/null || true
    fi
    if [ -x ./bin/cpatch ]; then
        echo ./bin/cpatch
        return 0
    fi
    log "FAIL: cpatch missing"
    return 1
}

lib_pattern() {
    local _file="$1"
    local _kind="$2"
    local _bin _rc
    _bin=$(cpatch_bin) || return 1
    "$_bin" check "$_file" "$_kind"
    _rc=$?
    if [ "$_rc" -eq 0 ] || [ "$_rc" -eq 10 ]; then
        return 0
    fi
    log "FAIL: $_file pattern $_rc"
    return 1
}

stage_lib() {
    local _live="$1"
    local _kind="$2"
    local _tmp="$3"
    local _bin _rc
    _bin=$(cpatch_bin) || return 1
    "$_bin" check "$_live" "$_kind"
    _rc=$?
    if [ "$_rc" -eq 10 ]; then
        rm -f "$_tmp" 2>/dev/null || true
        return 10
    fi
    if [ "$_rc" -ne 0 ]; then
        log "FAIL: $_live pattern $_rc"
        return 1
    fi
    "$_bin" apply "$_live" "$_tmp" "$_kind"
    _rc=$?
    if [ "$_rc" -ne 0 ]; then
        log "FAIL: patch $_live ($_rc)"
        rm -f "$_tmp" 2>/dev/null || true
        return 1
    fi
    "$_bin" check "$_tmp" "$_kind"
    _rc=$?
    if [ "$_rc" -ne 10 ]; then
        log "FAIL: $_live patch did not take"
        rm -f "$_tmp" 2>/dev/null || true
        return 1
    fi
    return 0
}

snap_libs() {
    local _n
    for _n in libCarPlayCore.so libCarPlayAudio.so libCarPlayUtils.so; do
        if [ ! -s "/system/lib/$_n" ]; then
            log "FAIL: /system/lib/$_n missing"
            return 1
        fi
        cp -f "/system/lib/$_n" "$BACKUP_DIR/$_n" || return 1
        chmod 644 "$BACKUP_DIR/$_n" 2>/dev/null || true
    done
    cp -f "$RECV_LIB" "$BACKUP_DIR/$RECV_BASE" || return 1
    chmod 644 "$BACKUP_DIR/$RECV_BASE" 2>/dev/null || true
    if [ -s "$CONV_LIB" ]; then
        cp -f "$CONV_LIB" "$BACKUP_DIR/libCarPlayConverter.so" || return 1
        chmod 644 "$BACKUP_DIR/libCarPlayConverter.so" 2>/dev/null || true
        putline present > "$BACKUP_DIR/converter.state" || return 1
    else
        putline absent > "$BACKUP_DIR/converter.state" || return 1
    fi
    return 0
}

restore_libs() {
    local _state
    restore_owned_file "$RECV_LIB" "$BACKUP_DIR/$RECV_BASE" || return 1
    restore_owned_file "$UTILS_LIB" "$BACKUP_DIR/libCarPlayUtils.so" || return 1
    restore_owned_file "$AUDIO_LIB" "$BACKUP_DIR/libCarPlayAudio.so" || return 1
    restore_owned_file "$CORE_LIB" "$BACKUP_DIR/libCarPlayCore.so" || return 1
    if [ ! -s "$BACKUP_DIR/converter.state" ]; then
        log "FAIL: no converter snapshot"
        return 1
    fi
    IFS= read -r _state < "$BACKUP_DIR/converter.state" || return 1
    _state=${_state%$'\r'}
    case "$_state" in
        absent)
            rm -f "$CONV_LIB" || return 1
            ;;
        present)
            restore_owned_file "$CONV_LIB" "$BACKUP_DIR/libCarPlayConverter.so" || return 1
            ;;
        *)
            log "FAIL: converter snapshot $_state"
            return 1
            ;;
    esac
    rm -f "$BACKUP_DIR/core.tmp" "$BACKUP_DIR/audio.tmp" "$BACKUP_DIR/utils.tmp" 2>/dev/null || true
    return 0
}

apply_libs() {
    local _core=0
    local _audio=0
    local _utils=0
    local _recv=0
    classify_receiver && _recv=0 || _recv=$?
    if [ "$_recv" -eq 1 ]; then
        return 1
    fi
    if [ ! -s "$CONV_PAYLOAD" ]; then
        log "FAIL: missing $CONV_PAYLOAD"
        return 1
    fi
    stage_lib "$CORE_LIB" core "$BACKUP_DIR/core.tmp" && _core=0 || _core=$?
    if [ "$_core" -eq 1 ]; then
        return 1
    fi
    stage_lib "$AUDIO_LIB" audio "$BACKUP_DIR/audio.tmp" && _audio=0 || _audio=$?
    if [ "$_audio" -eq 1 ]; then
        return 1
    fi
    stage_lib "$UTILS_LIB" utils "$BACKUP_DIR/utils.tmp" && _utils=0 || _utils=$?
    if [ "$_utils" -eq 1 ]; then
        return 1
    fi
    cp -f "$CONV_PAYLOAD" "$CONV_LIB" || {
        restore_libs
        return 1
    }
    chmod 644 "$CONV_LIB" 2>/dev/null || true
    chown 0.0 "$CONV_LIB" 2>/dev/null || chown 0:0 "$CONV_LIB" 2>/dev/null || true
    if [ "$_recv" -eq 10 ]; then
        cp -f "$RECV_PAYLOAD" "$RECV_LIB" || {
            restore_libs
            return 1
        }
        chmod 644 "$RECV_LIB" 2>/dev/null || true
        chown 0.0 "$RECV_LIB" 2>/dev/null || chown 0:0 "$RECV_LIB" 2>/dev/null || true
    fi
    if [ "$_core" -eq 0 ]; then
        commit_tmp "$CORE_LIB" "$BACKUP_DIR/core.tmp" || {
            restore_libs
            return 1
        }
    fi
    if [ "$_audio" -eq 0 ]; then
        commit_tmp "$AUDIO_LIB" "$BACKUP_DIR/audio.tmp" || {
            restore_libs
            return 1
        }
    fi
    if [ "$_utils" -eq 0 ]; then
        commit_tmp "$UTILS_LIB" "$BACKUP_DIR/utils.tmp" || {
            restore_libs
            return 1
        }
    fi
    rm -f "$BACKUP_DIR/core.tmp" "$BACKUP_DIR/audio.tmp" "$BACKUP_DIR/utils.tmp" 2>/dev/null || true
    log "patched carplay libraries"
    return 0
}

snap_props() {
    snap_prop_line "$BP" persist.sys.iap2.wifi "$BACKUP_DIR/bp.persist.sys.iap2.wifi" || return 1
    snap_prop_line "$BP" persist.sys.iap2.btconnection "$BACKUP_DIR/bp.persist.sys.iap2.btconnection" || return 1
    snap_getprop persist.sys.iap2.wifi "$BACKUP_DIR/gp.persist.sys.iap2.wifi" || return 1
    snap_getprop persist.sys.iap2.btconnection "$BACKUP_DIR/gp.persist.sys.iap2.btconnection" || return 1
    return 0
}

snapshot_all() {
    case "$BACKUP_DIR" in
        /data/nemesis/backup/enhanced.projection) ;;
        *)
            log "FAIL: bad backup dir"
            return 1
            ;;
    esac
    if [ -s "$BACKUP_DIR/saved" ]; then
        if [ ! -s "$BACKUP_DIR/libCarPlayCore.so" ] || [ ! -s "$BACKUP_DIR/libCarPlayAudio.so" ] || \
           [ ! -s "$BACKUP_DIR/libCarPlayUtils.so" ] || [ ! -s "$BACKUP_DIR/$RECV_BASE" ] || \
       [ ! -s "$BACKUP_DIR/converter.state" ]; then
            log "FAIL: backup has no library snapshots; uninstall enhanced.projection first"
            return 1
        fi
        snap_props || return 1
        log "backup already exists ($BACKUP_DIR) - keeping original"
        return 0
    fi
    if [ ! -s "$BP" ]; then
        log "FAIL: $BP missing or empty"
        return 1
    fi
    rm -rf "$BACKUP_DIR"
    mkdir -p "$BACKUP_DIR" || return 1
    snap_props || return 1
    snap_libs || return 1
    putline 1 > "$BACKUP_DIR/saved" || return 1
    log "snapshotted live unit to $BACKUP_DIR"
    return 0
}

apply_all() {
    if [ ! -s "$BACKUP_DIR/saved" ]; then
        log "FAIL: no snapshot"
        return 1
    fi
    apply_libs || return 1
    apply_apps || return 1
    set_prop_line "$BP" persist.sys.iap2.wifi t || return 1
    set_prop_line "$BP" persist.sys.iap2.btconnection t || return 1
    setprop persist.sys.iap2.wifi t 2>/dev/null || log "WARN: setprop persist.sys.iap2.wifi failed"
    setprop persist.sys.iap2.btconnection t 2>/dev/null || log "WARN: setprop persist.sys.iap2.btconnection failed"
    am force-stop com.daudio.app.dipo 2>/dev/null || true
    am force-stop com.tcc.iap2server 2>/dev/null || true
    am force-stop com.android.bluetooth 2>/dev/null || true
    am force-stop com.daudio.app.phoneprojection 2>/dev/null || true
    rm -f "$BACKUP_DIR/rewrite.tmp" 2>/dev/null || true
    sync
    log "applied enhanced.projection"
    return 0
}

restore_all() {
    local _rc=0
    local _n
    if [ ! -s "$BACKUP_DIR/saved" ]; then
        log "FAIL: no snapshot to restore"
        return 1
    fi
    if ! sys_rw; then
        log "FAIL: remount /system rw"
        return 1
    fi
    restore_prop_line "$BP" persist.sys.iap2.wifi "$BACKUP_DIR/bp.persist.sys.iap2.wifi" || _rc=1
    restore_prop_line "$BP" persist.sys.iap2.btconnection "$BACKUP_DIR/bp.persist.sys.iap2.btconnection" || _rc=1
    restore_libs || _rc=1
    for _n in $ALL_APPS; do
        restore_app "$_n" || _rc=1
    done
    restore_getprop persist.sys.iap2.wifi "$BACKUP_DIR/gp.persist.sys.iap2.wifi" || _rc=1
    restore_getprop persist.sys.iap2.btconnection "$BACKUP_DIR/gp.persist.sys.iap2.btconnection" || _rc=1
    am force-stop com.daudio.app.dipo 2>/dev/null || true
    am force-stop com.tcc.iap2server 2>/dev/null || true
    am force-stop com.android.bluetooth 2>/dev/null || true
    am force-stop com.daudio.app.phoneprojection 2>/dev/null || true
    am force-stop com.mnsoft.navi 2>/dev/null || true
    rm -f "$BACKUP_DIR/rewrite.tmp" 2>/dev/null || true
    if [ "$_rc" -ne 0 ]; then
        sys_ro
        log "FAIL: restore incomplete, snapshot kept at $BACKUP_DIR"
        return 1
    fi
    sync
    sys_ro
    log "restore complete"
    return 0
}

remove_backup_dir() {
    case "$BACKUP_DIR" in
        /data/nemesis/backup/enhanced.projection) ;;
        *)
            log "FAIL: refusing to remove $BACKUP_DIR"
            return 1
            ;;
    esac
    rm -rf "$BACKUP_DIR"
}
