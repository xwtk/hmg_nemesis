#!/system/bin/sh
# 1.x edited /system in place; its preuninstall has to put the stock files back first.
if [ -d /data/nemesis/backup/wireless.projection ]; then
    echo "uninstall wireless.projection.legacy (NEMESIS 1.16.9 and earlier) first (it edits /system directly)"
    exit 1
fi
exit 0
