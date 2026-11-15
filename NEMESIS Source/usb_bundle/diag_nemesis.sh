#!/system/bin/sh
# Write NEMESIS state to USB or /data/local/tmp (run on HU as root).

export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

USB=""
for _u in /storage/usb0 /storage/usb1 /storage/usb2 /storage/usb3; do
    [ -d "$_u" ] && USB="$_u" && break
done

OUT=/data/local/tmp/nemesis/diag.log
[ -n "$USB" ] && OUT="$USB/NEMESIS-diag.log"
mkdir -p "$(dirname "$OUT")" 2>/dev/null || true

{
    echo "=== NEMESIS diag $(date) ==="
    id
    echo ""
    echo "--- cli ---"
    ls -la /system/xbin/nemesis /system/app/NEMESIS.apk 2>&1
    if [ -x /system/xbin/nemesis ]; then
        /system/xbin/nemesis pkg-list 2>&1
        /system/xbin/nemesis service list 2>&1
    fi
    echo ""
    echo "--- boot hooks ---"
    ls -la /system/etc/install-recovery.sh 2>&1
    grep -i NEMESIS /system/etc/qb_runtime.sh 2>/dev/null || echo "no qb_runtime hook"
    echo ""
    echo "--- data ---"
    ls -la /data/nemesis /data/nemesis/apps /data/local/tmp/nemesis 2>&1
    echo ""
    echo "--- usb ---"
    for _u in /storage/usb0 /storage/usb1 /storage/usb2 /storage/usb3; do
        echo "[$_u]"
        mount | grep "$_u" || echo "  not mounted"
        ls -la "$_u/NEMESIS.data" "$_u/NEMESIS.sh" 2>&1
    done
} >"$OUT" 2>&1

echo "wrote $OUT"
cat "$OUT"
