#!/system/bin/sh
# PackageManager reads the permission XMLs (adb_hide) only at boot.
echo "rebooting so PackageManager drops adb_hide"
exec /system/bin/reboot
