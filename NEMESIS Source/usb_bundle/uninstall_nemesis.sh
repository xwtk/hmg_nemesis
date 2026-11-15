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
