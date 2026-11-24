#!/system/bin/sh
# wifi_on is a framework setting, not a file; PackageManager reads the permission XMLs only at boot.
mkdir -p state
if [ ! -f state/wifi_on ]; then
    v=$(settings get global wifi_on 2>/dev/null)
    print -r -- "${v:-null}" > state/wifi_on
fi
settings put global wifi_on 1 2>/dev/null || echo "WARN: settings put global wifi_on 1 failed"
echo "rebooting so PackageManager reloads wireless features"
exec /system/bin/reboot
