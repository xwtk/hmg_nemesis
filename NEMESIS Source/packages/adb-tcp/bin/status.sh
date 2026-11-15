#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
. ./scripts/common.sh

echo "id=adb.tcp"
if [ -f "$HIDE" ]; then
    echo "adb_hide=present"
else
    echo "adb_hide=absent"
fi
echo "ro.adb.secure=$(grep '^ro.adb.secure=' "$BP" 2>/dev/null)"
echo "persist.service.adb.tcp.enable=$(getprop persist.service.adb.tcp.enable 2>/dev/null)"
echo "service.adb.tcp.port=$(getprop service.adb.tcp.port 2>/dev/null)"
if [ -d "$BACKUP_DIR" ]; then
    echo "backup=$BACKUP_DIR"
    ls -l "$BACKUP_DIR" 2>/dev/null
else
    echo "backup=none"
fi
exit 0
