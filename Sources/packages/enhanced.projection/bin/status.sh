#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
. ./scripts/common.sh

echo "id=enhanced.projection"
echo "persist.sys.iap2.wifi=$(getprop persist.sys.iap2.wifi 2>/dev/null)"
echo "persist.sys.iap2.btconnection=$(getprop persist.sys.iap2.btconnection 2>/dev/null)"
grep '^persist.sys.iap2.wifi=' "$BP" 2>/dev/null || true
grep '^persist.sys.iap2.btconnection=' "$BP" 2>/dev/null || true
for _n in $ALL_APPS; do
    if [ -f "/system/app/${_n}.apk" ]; then
        echo "apk=present ${_n}"
    else
        echo "apk=absent ${_n}"
    fi
    if [ -f "/system/app/${_n}.odex" ]; then
        echo "odex=present ${_n}"
    else
        echo "odex=absent ${_n}"
    fi
done
for _n in libCarPlayCore.so libCarPlayAudio.so libCarPlayUtils.so libCarPlayConverter.so libautoreceiver_infobank.so; do
    if [ -f "/system/lib/$_n" ]; then
        echo "lib=present $_n"
    else
        echo "lib=absent $_n"
    fi
done
if [ -d "$BACKUP_DIR" ]; then
    echo "backup=$BACKUP_DIR"
else
    echo "backup=none"
fi
exit 0
