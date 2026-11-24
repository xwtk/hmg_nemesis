#!/system/bin/sh
v=null
[ -f state/wifi_on ] && read v < state/wifi_on
case "$v" in
    ""|null) settings delete global wifi_on 2>/dev/null ;;
    *) settings put global wifi_on "$v" 2>/dev/null ;;
esac
exit 0
