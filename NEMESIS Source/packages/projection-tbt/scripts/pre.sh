#!/system/bin/sh
# LivePatch checks each stock file when it builds the outputs; this only refuses the old /system-writing install.
if [ -d /data/nemesis/backup/projection.tbt ]; then
    echo "uninstall the old projection.tbt first (it edits /system directly)"
    exit 1
fi
exit 0
