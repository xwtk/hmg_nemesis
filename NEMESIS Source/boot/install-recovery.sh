#!/system/bin/sh
# Starts NEMESIS watchdog (which starts svcmgr). Stock firmware has no this file.
# The watchdog leaves this process group before we return, so init's oneshot
# cleanup cannot take it down. A pidfile from the previous boot is ignored
# unless that pid is still this watchdog.
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
trap '' HUP
if [ -x /system/xbin/nemesis ]; then
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
exit 0
