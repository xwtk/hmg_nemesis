#!/system/bin/sh
echo "id=wireless.projection"
echo "persist.ium.support=$(getprop persist.ium.support)"
echo "persist.wifi.state=$(getprop persist.wifi.state)"
/system/xbin/nemesis livepatch status
