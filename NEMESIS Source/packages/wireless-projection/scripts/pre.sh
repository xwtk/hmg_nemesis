#!/system/bin/sh
# 1.x edited /system in place; its preuninstall has to put the stock files back first.
if [ -d /data/nemesis/backup/wireless.projection ]; then
    echo "uninstall wireless.projection 1.x first (it edits /system directly)"
    exit 1
fi
exit 0
