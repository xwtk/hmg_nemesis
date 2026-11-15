# NEMESIS_QB_HOOK: qb_runtime.sh runs on quickboot_fs before Android is up.
if [ -x /system/xbin/nemesis ]; then
    trap '' HUP
    chown root:root /system/xbin/nemesis 2>/dev/null || true
    chmod 4755 /system/xbin/nemesis 2>/dev/null || true
    _try=0
    while [ "$_try" -lt 5 ]; do
        /system/xbin/nemesis watchdog
        if /system/xbin/nemesis ping; then
            break
        fi
        _try=$((_try + 1))
        sleep 1
    done
fi
