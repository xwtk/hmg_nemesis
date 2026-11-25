#!/system/bin/sh
# Remove NEMESIS. Prefer the CLI wipe path.
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

# wipe reboots by itself once everything is removed. Any other exit code
# means a package uninstall failed (3) or core files stayed (1): no reboot.
if [ -x /system/xbin/nemesis ]; then
    /system/xbin/nemesis wipe --confirm
    exit $?
fi

QB_RUNTIME=/system/etc/qb_runtime.sh
NEMESIS_QB_MARKER="NEMESIS_QB_HOOK"
INSTALL_RECOVERY=/system/etc/install-recovery.sh

mount -o remount,rw /system 2>/dev/null || mount -o rw,remount / 2>/dev/null || true
# LivePatch wrapper: put the real zygote binary back, or the framework would not start.
# .real goes back only if it can start the zygote (stock, or a wrapper whose next link exists,
# HMG-API's or Leafia's); otherwise a kept stock copy does.
_ap=/system/bin/app_process
_is_stock() {
    [ -f "$1" ] && grep -q "Usage: app_process" "$1" 2>/dev/null &&
        ! grep -q "/system/bin/app_process[._A-Za-z0-9-]" "$1" 2>/dev/null
}
if [ -f $_ap.real ] && grep -q "# NEMESIS LivePatch" $_ap 2>/dev/null; then
    _live=0
    if _is_stock $_ap.real; then
        _live=1
    else
        # shortcut: one link deep, as the native chain walk isn't here; enough for HMG-API's wrapper
        for _f in $_ap.*; do
            case "$_f" in $_ap.real|*.nemesis-new) continue ;; esac
            [ -f "$_f" ] && grep -q -F "$_f" $_ap.real 2>/dev/null && _live=1
        done
    fi
    # toolbox mv takes no options: a -f flag fails and moves nothing
    if [ $_live = 1 ]; then
        mv $_ap.real $_ap
    else
        for _s in /data/nemesis/stock/app_process /data/hmgapi/stock/system/bin/app_process; do
            _is_stock "$_s" || continue
            rm -f $_ap.nemesis-new
            cp "$_s" $_ap.nemesis-new && cmp "$_s" $_ap.nemesis-new >/dev/null 2>&1 &&
                chmod 755 $_ap.nemesis-new && chown 0:2000 $_ap.nemesis-new &&
                mv $_ap.nemesis-new $_ap && rm -f $_ap.real && break
            rm -f $_ap.nemesis-new
        done
    fi
fi
rm -f "$INSTALL_RECOVERY" /system/xbin/nemesis /system/app/NEMESIS.apk
if [ -f "$QB_RUNTIME" ] && grep -q "$NEMESIS_QB_MARKER" "$QB_RUNTIME" 2>/dev/null; then
    _tmp="${QB_RUNTIME}.nemesis_rm"
    _skip=0
    _depth=0
    : >"$_tmp"
    while read -r _line; do
        case "$_line" in
            *"$NEMESIS_QB_MARKER"*) _skip=1; _depth=0; continue ;;
        esac
        if [ "$_skip" -eq 1 ]; then
            _t="$_line"
            while [ "$_t" != "${_t# }" ] || [ "$_t" != "${_t#	}" ]; do
                _t="${_t# }"
                _t="${_t#	}"
            done
            case "$_t" in
                if|if[\ \	]*) _depth=$((_depth + 1)) ;;
            esac
            case "$_t" in
                fi|fi[\ \	\;\#]*)
                    _depth=$((_depth - 1))
                    if [ "$_depth" -le 0 ]; then _skip=0; fi
                    ;;
            esac
            continue
        fi
        echo "$_line" >>"$_tmp"
    done <"$QB_RUNTIME"
    cp -f "$_tmp" "$QB_RUNTIME" && chmod 644 "$QB_RUNTIME"
    rm -f "$_tmp"
fi
rm -rf /data/nemesis /data/local/tmp/nemesis
reboot
