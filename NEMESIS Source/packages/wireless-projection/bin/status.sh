#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
. ./scripts/common.sh

echo "id=wireless.projection"
echo "persist.ium.support=$(getprop persist.ium.support 2>/dev/null)"
echo "persist.wifi.state=$(getprop persist.wifi.state 2>/dev/null)"
echo "build.prop:"
grep '^persist.ium.support=' "$BP" 2>/dev/null || true
grep '^persist.wifi.state=' "$BP" 2>/dev/null || true
if [ -f "$WPA" ]; then
    echo "wpa:"
    grep '^p2p_disabled=' "$WPA" 2>/dev/null || true
fi
if [ -f "$OVERLAY" ]; then
    echo "overlay:"
    grep '^p2p_no_go_freq=' "$OVERLAY" 2>/dev/null || echo "p2p_no_go_freq=absent"
fi
for _n in "$WIFI_XML" "$AA_XML" "$CP_XML"; do
    if [ -f "$PERM/$_n" ]; then
        echo "perm=present $_n"
    else
        echo "perm=absent $_n"
    fi
done
if [ -f "$BACKUP_DIR/featurexml.path" ]; then
    echo "featurexml=$(cat "$BACKUP_DIR/featurexml.path" 2>/dev/null)"
fi
if [ -d "$BACKUP_DIR" ]; then
    echo "backup=$BACKUP_DIR"
else
    echo "backup=none"
fi
exit 0
