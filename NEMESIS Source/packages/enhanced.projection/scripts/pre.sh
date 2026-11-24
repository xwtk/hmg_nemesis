#!/system/bin/sh
# Early refusal; LivePatch itself checks every app build and library when it builds the outputs.
for _old in carplay.session projection.tbt enhanced.projection enhanced.projection.universal; do
    if [ -d "/data/nemesis/backup/$_old" ]; then
        echo "uninstall $_old first (it edits /system directly)"
        exit 1
    fi
done
for _a in HKMC_DiPO Iap2ServerApp Bluetooth PhoneProjection HKMC_GPM HKMC_Navi_ExSL; do
    # every build is ported on the unit by LivePatch; only a missing or non-odex target is refused here
    ./bin/livepatch dexsig /system/app/$_a.odex >/dev/null 2>&1 || { echo "$_a.odex missing or not an odex"; exit 1; }
done
for _l in core:CarPlayCore audio:CarPlayAudio utils:CarPlayUtils recv:autoreceiver_infobank; do
    ./bin/livepatch check /system/lib/lib${_l#*:}.so ${_l%%:*} >/dev/null 2>&1
    _rc=$?
    [ "$_rc" -eq 0 ] || [ "$_rc" -eq 10 ] || { echo "lib${_l#*:}.so does not match the ${_l%%:*} pattern ($_rc)"; exit 1; }
done
exit 0
