#!/system/bin/sh
echo "id=enhanced.projection"
echo "persist.sys.iap2.wifi=$(getprop persist.sys.iap2.wifi)"
echo "persist.sys.iap2.btconnection=$(getprop persist.sys.iap2.btconnection)"
/system/xbin/nemesis livepatch status
