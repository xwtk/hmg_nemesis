#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
. ./scripts/common.sh

echo "id=projection.tbt"
echo "product=$(getprop ro.build.product 2>/dev/null)"
echo "build.id=$(getprop ro.build.id 2>/dev/null)"
echo "stepofspec=$(getprop ro.product.opt.stepofspec 2>/dev/null)"
for _name in HKMC_GPM HKMC_DiPO Iap2ServerApp HKMC_Navi_ExSL; do
    if [ -f "/system/app/${_name}.odex" ]; then
        echo "odex=present ${_name}"
    else
        echo "odex=absent ${_name}"
    fi
    if [ -f "files/${_name}.apk" ] && cmp "files/${_name}.apk" "/system/app/${_name}.apk" >/dev/null 2>&1; then
        echo "apk=patched ${_name}"
    else
        echo "apk=other ${_name}"
    fi
done
if grep navbridge1 /system/lib/libautoreceiver_infobank.so >/dev/null 2>&1; then
    echo "receiver=navbridge"
else
    echo "receiver=stock"
fi
if [ -d "$BACKUP_DIR" ]; then
    echo "backup=$BACKUP_DIR"
else
    echo "backup=none"
fi
exit 0
