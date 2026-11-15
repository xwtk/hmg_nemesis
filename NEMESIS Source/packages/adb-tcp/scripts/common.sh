#!/system/bin/sh
# adb.tcp — snapshot the live unit, then change it.
# Uninstall writes that snapshot back. This package ships no stock files.

export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

BP=/system/build.prop
HIDE=/system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml
BACKUP_DIR=/data/nemesis/backup/adb.tcp
LOG=/data/nemesis/adb.tcp.log

log() {
    local _msg="$1"
    echo "$_msg"
    mkdir -p /data/nemesis 2>/dev/null || true
    echo "$_msg" >> "$LOG" 2>/dev/null || true
}

# print is an mksh builtin. This HU has no printf. bash host tests use printf.
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

remove_backup_dir() {
    case "$BACKUP_DIR" in
        /data/nemesis/backup/?*) ;;
        *)
            log "FAIL: refusing to remove $BACKUP_DIR"
            return 1
            ;;
    esac
    rm -rf "$BACKUP_DIR"
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

snap_file() {
    local _src="$1"
    local _dst="$2"
    local _abs="$3"
    if [ -s "$_dst" ] || [ -s "$_abs" ]; then
        log "snapshot exists for $_src - keeping original"
        return 0
    fi
    if [ -f "$_src" ]; then
        cp -f "$_src" "$_dst" || return 1
        chmod 644 "$_dst" 2>/dev/null || true
        log "backed up $_src"
    else
        putline absent > "$_abs" || return 1
        log "absent on unit: $_src"
    fi
    return 0
}

restore_owned_file() {
    local _live="$1"
    local _bak="$2"
    local _abs="$3"
    if [ -s "$_bak" ]; then
        cp -f "$_bak" "$_live" || return 1
        chmod 644 "$_live" 2>/dev/null || true
        chown 0.0 "$_live" 2>/dev/null || chown 0:0 "$_live" 2>/dev/null || true
        log "restored $_live"
        return 0
    fi
    if [ -s "$_abs" ]; then
        rm -f "$_live" || return 1
        log "removed $_live (absent on the unit at install)"
        return 0
    fi
    log "FAIL: no snapshot for $_live"
    return 1
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
        case "$_key" in
            persist.*)
                # An empty setprop would write a persist value and override build.prop.
                rm -f "/data/property/$_key" 2>/dev/null || true
                ;;
            *)
                setprop "$_key" "" 2>/dev/null || true
                ;;
        esac
        log "cleared prop $_key (unset at install)"
        return 0
    fi
    log "FAIL: no prop snapshot for $_key"
    return 1
}

snapshot_all() {
    case "$BACKUP_DIR" in
        /data/nemesis/backup/?*) ;;
        *)
            log "FAIL: bad backup dir"
            return 1
            ;;
    esac
    if [ -s "$BACKUP_DIR/saved" ]; then
        log "backup already exists ($BACKUP_DIR) - keeping original"
        return 0
    fi
    if [ ! -s "$BP" ]; then
        log "FAIL: $BP missing or empty"
        return 1
    fi
    remove_backup_dir || return 1
    mkdir -p "$BACKUP_DIR" || return 1
    cp -f "$BP" "$BACKUP_DIR/build.prop" || return 1
    if [ ! -s "$BACKUP_DIR/build.prop" ]; then
        log "FAIL: full build.prop snapshot is empty"
        return 1
    fi
    log "backed up full $BP"
    snap_prop_line "$BP" ro.adb.secure "$BACKUP_DIR/bp.ro.adb.secure" || return 1
    snap_prop_line "$BP" persist.service.adb.tcp.enable "$BACKUP_DIR/bp.persist.service.adb.tcp.enable" || return 1
    snap_prop_line "$BP" service.adb.tcp.port "$BACKUP_DIR/bp.service.adb.tcp.port" || return 1
    snap_getprop persist.service.adb.tcp.enable "$BACKUP_DIR/gp.persist.service.adb.tcp.enable" || return 1
    snap_getprop service.adb.tcp.port "$BACKUP_DIR/gp.service.adb.tcp.port" || return 1
    snap_file "$HIDE" "$BACKUP_DIR/adb_hide.xml" "$BACKUP_DIR/adb_hide.xml.absent" || return 1
    putline 1 > "$BACKUP_DIR/saved" || return 1
    log "snapshotted live unit to $BACKUP_DIR"
    return 0
}

apply_all() {
    if [ ! -s "$BACKUP_DIR/saved" ]; then
        log "FAIL: no snapshot"
        return 1
    fi
    set_prop_line "$BP" ro.adb.secure 0 || return 1
    set_prop_line "$BP" persist.service.adb.tcp.enable 1 || return 1
    set_prop_line "$BP" service.adb.tcp.port 5555 || return 1
    rm -f "$HIDE" || return 1
    log "removed $HIDE"
    setprop persist.service.adb.tcp.enable 1 2>/dev/null || log "WARN: setprop persist.service.adb.tcp.enable failed"
    setprop service.adb.tcp.port 5555 2>/dev/null || log "WARN: setprop service.adb.tcp.port failed"
    setprop ctl.restart adbd 2>/dev/null || log "WARN: could not restart adbd"
    rm -f "$BACKUP_DIR/rewrite.tmp" 2>/dev/null || true
    sync
    log "applied adb.tcp"
    return 0
}

restore_all() {
    local _rc=0
    if [ ! -s "$BACKUP_DIR/saved" ]; then
        log "FAIL: no snapshot to restore"
        return 1
    fi
    if ! sys_rw; then
        log "FAIL: remount /system rw"
        return 1
    fi
    mkdir -p /system/etc/permissions || _rc=1
    restore_prop_line "$BP" ro.adb.secure "$BACKUP_DIR/bp.ro.adb.secure" || _rc=1
    restore_prop_line "$BP" persist.service.adb.tcp.enable "$BACKUP_DIR/bp.persist.service.adb.tcp.enable" || _rc=1
    restore_prop_line "$BP" service.adb.tcp.port "$BACKUP_DIR/bp.service.adb.tcp.port" || _rc=1
    restore_owned_file "$HIDE" "$BACKUP_DIR/adb_hide.xml" "$BACKUP_DIR/adb_hide.xml.absent" || _rc=1
    restore_getprop persist.service.adb.tcp.enable "$BACKUP_DIR/gp.persist.service.adb.tcp.enable" || _rc=1
    restore_getprop service.adb.tcp.port "$BACKUP_DIR/gp.service.adb.tcp.port" || _rc=1
    setprop ctl.restart adbd 2>/dev/null || true
    rm -f "$BACKUP_DIR/rewrite.tmp" 2>/dev/null || true
    if [ "$_rc" -ne 0 ]; then
        sys_ro
        log "FAIL: restore incomplete, snapshot kept at $BACKUP_DIR"
        return 1
    fi
    putline 1 > "$BACKUP_DIR/restored" 2>/dev/null || true
    sync
    sys_ro
    log "restore complete"
    return 0
}
