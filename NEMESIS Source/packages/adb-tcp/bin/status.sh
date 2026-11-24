#!/system/bin/sh
echo "id=adb.tcp"
echo "ro.adb.secure=$(getprop ro.adb.secure)"
echo "persist.service.adb.tcp.enable=$(getprop persist.service.adb.tcp.enable)"
echo "service.adb.tcp.port=$(getprop service.adb.tcp.port)"
/system/xbin/nemesis livepatch status
