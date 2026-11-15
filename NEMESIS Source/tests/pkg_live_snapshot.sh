#!/bin/bash
# Host check for adb.tcp and wireless.projection install/uninstall hooks.
# The hooks snapshot whatever is on the live unit and restore those bytes.
# Requires a user+mount namespace (unshare). Does not touch the host /system.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB_PKG=$ROOT/packages/adb-tcp
WIFI_PKG=$ROOT/packages/wireless-projection
TOGGLE_PKG=$ROOT/examples/adb-toggle/package

if [ "${NEMESIS_HOOK_NS:-}" != 1 ]; then
    if ! command -v unshare >/dev/null 2>&1; then
        echo "FAIL: unshare is not installed"
        exit 1
    fi
    if ! unshare --user --map-root-user --mount true; then
        echo "FAIL: unshare user+mount namespace is not available"
        exit 1
    fi
    NEMESIS_HOOK_NS=1
    if command -v mksh >/dev/null 2>&1; then
        HOOK_SH=mksh
    else
        HOOK_SH=bash
    fi
    NEMESIS_PYLIB=$(python3 -c 'import sysconfig; print(sysconfig.get_paths()["stdlib"])')
    NEMESIS_ROOTFS=$(mktemp -d /tmp/nemesis-rootfs.XXXXXX)
    NEMESIS_ROOT=$ROOT
    export NEMESIS_HOOK_NS HOOK_SH NEMESIS_PYLIB NEMESIS_ROOTFS NEMESIS_ROOT
    set +e
    unshare --user --map-root-user --mount bash -c '
        set -euo pipefail
        r="$NEMESIS_ROOTFS/r"
        pylib="$NEMESIS_PYLIB"
        mkdir -p "$r/usr/bin" "$r/usr/lib64" "$r/usr/lib/x86_64-linux-gnu" "$r$pylib" \
            "$r/dev" "$r/system" "$r/data" "$r/tmp" "$r$NEMESIS_ROOT" "$r/etc"
        mount --bind /usr/bin "$r/usr/bin"
        mount --bind /usr/lib64 "$r/usr/lib64"
        mount --bind /usr/lib/x86_64-linux-gnu "$r/usr/lib/x86_64-linux-gnu"
        mount --bind "$pylib" "$r$pylib"
        mount --bind "$NEMESIS_ROOT" "$r$NEMESIS_ROOT"
        for n in null zero urandom random tty; do
            if [ -e "/dev/$n" ]; then
                touch "$r/dev/$n"
                mount --bind "/dev/$n" "$r/dev/$n"
            fi
        done
        ln -s usr/bin "$r/bin"
        ln -s usr/lib64 "$r/lib64"
        ln -s usr/lib "$r/lib"
        if [ -f /etc/ld.so.cache ]; then
            cp /etc/ld.so.cache "$r/etc/ld.so.cache"
        fi
        printf "%s\n" private > "$r/.nemesis-hook-private"
        chroot "$r" /bin/bash "$NEMESIS_ROOT/tests/pkg_live_snapshot.sh"
    '
    status=$?
    set -e
    case "$NEMESIS_ROOTFS" in
        /tmp/nemesis-rootfs.*) rm -rf "$NEMESIS_ROOTFS" ;;
    esac
    exit "$status"
fi

if [ ! -f /.nemesis-hook-private ]; then
    echo "FAIL: refusing to touch /system outside the private root"
    exit 1
fi

HOOK_SH=${HOOK_SH:-bash}
FAKE=$(mktemp -d /tmp/nemesis-hook.XXXXXX)

fail() {
    echo "FAIL: $*"
    exit 1
}

cleanup() {
    case "${FAKE:-}" in
        /tmp/nemesis-hook.*) rm -rf "$FAKE" ;;
    esac
}
trap cleanup EXIT

assert_cmp() {
    cmp -s "$1" "$2" || fail "bytes differ: $1 vs $2"
}

assert_gone() {
    [ ! -e "$1" ] || fail "still exists: $1"
}

assert_grep() {
    grep -q "$1" "$2" || fail "missing [$1] in $2"
}

assert_not_grep() {
    if grep -q "$1" "$2"; then
        fail "unexpected [$1] in $2"
    fi
}

run_hook() {
    local pkg="$1"
    local hook="$2"
    local out="$FAKE/hook.out"
    if ! ( cd "$pkg" && "$HOOK_SH" "./scripts/$hook" ) >"$out" 2>&1; then
        echo "---- $hook ($pkg) ----"
        cat "$out" || true
        fail "$hook failed in $pkg"
    fi
}

# --- zip contents: payload only, no shipped stock backup ---
STAGE=$FAKE/zips
mkdir -p "$STAGE"
python3 "$ROOT/tools/make_nemesis_package.py" "$ADB_PKG" -o "$STAGE/adb.tcp.zip" >/dev/null
python3 "$ROOT/tools/make_nemesis_package.py" "$WIFI_PKG" -o "$STAGE/wireless.projection.zip" >/dev/null
unzip -l "$STAGE/adb.tcp.zip" > "$STAGE/adb.list"
unzip -l "$STAGE/wireless.projection.zip" > "$STAGE/wireless.list"
if grep -q 'adb_hide' "$STAGE/adb.list"; then
    fail "adb package ships adb_hide"
fi
if grep -E 'wpa_supplicant|build\.prop|featurexml|adb_hide|5170' "$STAGE/wireless.list"; then
    fail "wireless package ships a stock config"
fi
grep -q 'android.hardware.wifi.xml' "$STAGE/wireless.list" || fail "wifi payload missing"
grep -q 'androidauto_wireless.xml' "$STAGE/wireless.list" || fail "aa payload missing"
grep -q 'carplay_wireless.xml' "$STAGE/wireless.list" || fail "cp payload missing"
if grep -q 'HKMC_DiPO' "$STAGE/wireless.list" || grep -q 'Iap2ServerApp' "$STAGE/wireless.list"; then
    fail "wireless package ships a firmware-specific apk"
fi
if grep -R -q '5170-5740' "$WIFI_PKG"; then
    fail "hardcoded p2p_no_go_freq in wireless package"
fi

# /system and /data here are the private root created by the chroot above.
mkdir -p /system/bin /system/xbin /data "$FAKE/orig"
printf '%s\n' "$FAKE" > /system/HOOKTEST_ROOT
printf '%s\n' "$FAKE" > /data/HOOKTEST_ROOT

link_tool() {
    local name="$1"
    local src
    src=$(type -P "$name" || true)
    [ -n "$src" ] || fail "host tool missing: $name"
    ln -sf "$src" "/system/bin/$name"
}
for t in mkdir chmod chown cp rm cat grep ls sync date id printf; do
    link_tool "$t"
done
printf '%s\n' '#!/bin/sh' 'exit 0' > /system/bin/mount
chmod 755 /system/bin/mount

cat > /system/bin/getprop << 'EOF'
#!/bin/sh
f="/data/property/$1"
if [ -f "$f" ]; then
    cat "$f"
fi
exit 0
EOF
cat > /system/bin/setprop << 'EOF'
#!/bin/sh
mkdir -p /data/property
printf '%s\n' "$2" > "/data/property/$1"
exit 0
EOF
cat > /system/bin/settings << 'EOF'
#!/bin/sh
cmd="$1"
key="$3"
val="${4:-}"
dir=/data/nemesis/settings
mkdir -p "$dir"
case "$cmd" in
    get)
        if [ -f "$dir/$key" ]; then
            cat "$dir/$key"
        else
            echo null
        fi
        ;;
    put)
        printf '%s\n' "$val" > "$dir/$key"
        ;;
    delete)
        rm -f "$dir/$key"
        ;;
esac
exit 0
EOF
printf '%s\n' '#!/bin/sh' 'exit 0' > /system/bin/am
printf '%s\n' '#!/bin/sh' 'exit 0' > /system/bin/pm
printf '%s\n' '#!/bin/sh' 'exit 0' > /system/xbin/nemesis
cat > /system/bin/reboot << 'EOF'
#!/bin/sh
mkdir -p /data/nemesis
echo reboot >> /data/nemesis/reboot.log
exit 0
EOF
chmod 755 /system/bin/getprop /system/bin/setprop /system/bin/settings \
    /system/bin/am /system/bin/pm /system/bin/mount /system/bin/reboot /system/xbin/nemesis

wipe_unit() {
    [ "$(cat /system/HOOKTEST_ROOT)" = "$FAKE" ] || fail "refusing to wipe /system"
    [ "$(cat /data/HOOKTEST_ROOT)" = "$FAKE" ] || fail "refusing to wipe /data"
    rm -rf /system/etc /system/onebin /system/build.prop /data/nemesis /data/property
}

# overlay: "freq" writes p2p_no_go_freq=2412-2462; "none" omits that line.
# hide: "yes" or "no". ium_prop: "none" or "unset". wifi_on: "0" or "unset".
seed_unit() {
    local overlay="$1"
    local hide="$2"
    local ium_prop="$3"
    local wifi_on="$4"
    local tag="$5"
    wipe_unit
    mkdir -p /system/etc/permissions /system/etc/wifi /system/onebin/cvev_us /system/onebin/other \
        /data/property /data/nemesis/settings "$FAKE/orig/$tag"
    printf '%s\n' '# header' 'ro.keep=1' '' 'persist.ium.support=none' 'ro.adb.secure=1' \
        > /system/build.prop
    printf '%s\n' \
        '# note androidauto_wireless.xml stays a comment' \
        '-/system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml' \
        '-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml' \
        'keep_this=1' \
        > /system/onebin/cvev_us/featurexml.cfg
    printf '%s\n' 'DECOY-UNTOUCHED' > /system/onebin/other/featurexml.cfg
    # No trailing newline: restore must put these exact bytes back.
    printf '%s' $'ctrl_interface=/data/misc/wifi\np2p_disabled=1' > /system/etc/wifi/wpa_supplicant.conf
    if [ "$overlay" = freq ]; then
        printf '%s\n' 'update_config=1' 'p2p_no_go_freq=2412-2462' \
            > /system/etc/wifi/wpa_supplicant_overlay.conf
    else
        printf '%s\n' 'update_config=1' > /system/etc/wifi/wpa_supplicant_overlay.conf
    fi
    printf '%s\n' 'UNIQUE-LIVE-WIFI-9f3a' > /system/etc/permissions/android.hardware.wifi.xml
    printf '%s\n' 'UNIQUE-LIVE-AA-9f3a' \
        > /system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml
    rm -f /system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml
    if [ "$hide" = yes ]; then
        printf '%s\n' 'LIVE-HIDE-UNIQUE-9f3a' \
            '<permissions>' \
            '        <feature name="com.hkmc.software.engineermode.adb_hide" />' \
            '</permissions>' \
            > /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml
    else
        rm -f /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml
    fi
    if [ "$ium_prop" = unset ]; then
        rm -f /data/property/persist.ium.support
    else
        printf '%s\n' none > /data/property/persist.ium.support
    fi
    rm -f /data/property/persist.wifi.state /data/property/persist.service.adb.tcp.enable \
        /data/property/service.adb.tcp.port
    if [ "$wifi_on" = 0 ]; then
        printf '%s\n' 0 > /data/nemesis/settings/wifi_on
    else
        rm -f /data/nemesis/settings/wifi_on
    fi
    cp -a /system/build.prop "$FAKE/orig/$tag/build.prop"
    cp -a /system/onebin/cvev_us/featurexml.cfg "$FAKE/orig/$tag/featurexml.cfg"
    cp -a /system/onebin/other/featurexml.cfg "$FAKE/orig/$tag/decoy.cfg"
    cp -a /system/etc/wifi/wpa_supplicant.conf "$FAKE/orig/$tag/wpa"
    cp -a /system/etc/wifi/wpa_supplicant_overlay.conf "$FAKE/orig/$tag/overlay"
    cp -a /system/etc/permissions/android.hardware.wifi.xml "$FAKE/orig/$tag/wifi.xml"
    cp -a /system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml \
        "$FAKE/orig/$tag/aa.xml"
    if [ "$hide" = yes ]; then
        cp -a /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml \
            "$FAKE/orig/$tag/hide.xml"
    fi
}

reboot_count() {
    if [ -f /data/nemesis/reboot.log ]; then
        grep -c . /data/nemesis/reboot.log
    else
        echo 0
    fi
}

install_both() {
    run_hook "$WIFI_PKG" pre.sh
    run_hook "$WIFI_PKG" post.sh
    rm -rf "$FAKE/snap-wireless"
    cp -a /data/nemesis/backup/wireless.projection "$FAKE/snap-wireless"
    run_hook "$WIFI_PKG" post.sh
    diff -rq "$FAKE/snap-wireless" /data/nemesis/backup/wireless.projection \
        || fail "second wireless install replaced the snapshot"
    run_hook "$ADB_PKG" pre.sh
    run_hook "$ADB_PKG" post.sh
    [ "$(reboot_count)" -eq 3 ] || fail "install did not request reboot for each postinstall"
}

assert_applied() {
    local hide="$1"
    assert_grep '^persist.ium.support=carplay$' /system/build.prop
    assert_grep '^persist.wifi.state=3$' /system/build.prop
    assert_grep '^ro.adb.secure=0$' /system/build.prop
    assert_grep '^persist.service.adb.tcp.enable=1$' /system/build.prop
    assert_grep '^service.adb.tcp.port=5555$' /system/build.prop
    assert_grep '^ro.keep=1$' /system/build.prop
    assert_grep '^#-/system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml$' \
        /system/onebin/cvev_us/featurexml.cfg
    assert_grep '^#-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml$' \
        /system/onebin/cvev_us/featurexml.cfg
    assert_grep '^# note androidauto_wireless.xml stays a comment$' \
        /system/onebin/cvev_us/featurexml.cfg
    assert_grep '^keep_this=1$' /system/onebin/cvev_us/featurexml.cfg
    assert_grep 'p2p_disabled=0' /system/etc/wifi/wpa_supplicant.conf
    [ "$(grep -c 'ctrl_interface' /system/etc/wifi/wpa_supplicant.conf)" = 1 ] || fail "wpa line duplicated"
    [ "$(grep -c 'p2p_disabled' /system/etc/wifi/wpa_supplicant.conf)" = 1 ] || fail "p2p line duplicated"
    [ "$(grep -c '^keep_this=1$' /system/onebin/cvev_us/featurexml.cfg)" = 1 ] || fail "featurexml line duplicated"
    assert_cmp /data/nemesis/backup/wireless.projection/wpa_supplicant.conf \
        "$FAKE/orig/current/wpa"
    assert_cmp /data/nemesis/backup/wireless.projection/wpa_supplicant_overlay.conf \
        "$FAKE/orig/current/overlay"
    assert_cmp /data/nemesis/backup/wireless.projection/featurexml.cvev_us.cfg \
        "$FAKE/orig/current/featurexml.cfg"
    assert_grep '/system/onebin/cvev_us/featurexml.cfg' \
        /data/nemesis/backup/wireless.projection/featurexml.list
    assert_cmp /system/etc/permissions/android.hardware.wifi.xml \
        "$WIFI_PKG/files/permissions/android.hardware.wifi.xml"
    assert_cmp /system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml \
        "$WIFI_PKG/files/permissions/com.hkmc.software.connectivity.carplay_wireless.xml"
    assert_cmp /data/nemesis/backup/wireless.projection/perm.android.hardware.wifi.xml \
        "$FAKE/orig/current/wifi.xml"
    assert_grep 'UNIQUE-LIVE-WIFI-9f3a' \
        /data/nemesis/backup/wireless.projection/perm.android.hardware.wifi.xml
    [ -f /data/nemesis/backup/wireless.projection/perm.com.hkmc.software.connectivity.carplay_wireless.xml.absent ]
    assert_cmp /system/onebin/other/featurexml.cfg "$FAKE/orig/current/decoy.cfg"
    assert_grep '^carplay$' /data/property/persist.ium.support
    assert_grep '^1$' /data/property/persist.service.adb.tcp.enable
    assert_grep '^1$' /data/nemesis/settings/wifi_on
    if [ "$hide" = yes ]; then
        assert_gone /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml
        assert_cmp /data/nemesis/backup/adb.tcp/adb_hide.xml "$FAKE/orig/current/hide.xml"
        assert_grep 'LIVE-HIDE-UNIQUE-9f3a' /data/nemesis/backup/adb.tcp/adb_hide.xml
    else
        assert_gone /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml
        [ -f /data/nemesis/backup/adb.tcp/adb_hide.xml.absent ]
    fi
    assert_not_grep '5170-5740' /system/etc/wifi/wpa_supplicant_overlay.conf
    assert_not_grep '5170-5740' /data/nemesis/backup/wireless.projection/wpa_supplicant_overlay.conf
}

assert_restored() {
    local tag="$1"
    local hide="$2"
    local overlay="$3"
    local ium_prop="$4"
    local wifi_on="$5"
    assert_cmp /system/build.prop "$FAKE/orig/$tag/build.prop"
    assert_cmp /system/onebin/cvev_us/featurexml.cfg "$FAKE/orig/$tag/featurexml.cfg"
    assert_cmp /system/onebin/other/featurexml.cfg "$FAKE/orig/$tag/decoy.cfg"
    assert_cmp /system/etc/wifi/wpa_supplicant.conf "$FAKE/orig/$tag/wpa"
    assert_cmp /system/etc/wifi/wpa_supplicant_overlay.conf "$FAKE/orig/$tag/overlay"
    assert_cmp /system/etc/permissions/android.hardware.wifi.xml "$FAKE/orig/$tag/wifi.xml"
    assert_cmp /system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml \
        "$FAKE/orig/$tag/aa.xml"
    assert_gone /system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml
    assert_gone /data/nemesis/backup/adb.tcp
    assert_gone /data/nemesis/backup/wireless.projection
    assert_not_grep '5170-5740' /system/etc/wifi/wpa_supplicant_overlay.conf
    if [ "$hide" = yes ]; then
        assert_cmp /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml \
            "$FAKE/orig/$tag/hide.xml"
    else
        assert_gone /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml
    fi
    if [ "$overlay" = freq ]; then
        assert_grep '^p2p_no_go_freq=2412-2462$' /system/etc/wifi/wpa_supplicant_overlay.conf
    else
        assert_not_grep 'p2p_no_go_freq' /system/etc/wifi/wpa_supplicant_overlay.conf
    fi
    if [ "$ium_prop" = unset ]; then
        assert_gone /data/property/persist.ium.support
    else
        assert_grep '^none$' /data/property/persist.ium.support
    fi
    assert_gone /data/property/persist.wifi.state
    assert_gone /data/property/persist.service.adb.tcp.enable
    if [ "$wifi_on" = 0 ]; then
        assert_grep '^0$' /data/nemesis/settings/wifi_on
    else
        assert_gone /data/nemesis/settings/wifi_on
    fi
}

uninstall_adb() {
    run_hook "$ADB_PKG" preun.sh
}

uninstall_wireless() {
    run_hook "$WIFI_PKG" preun.sh
}

echo "hooks via $HOOK_SH"

# A: uninstall adb first, then wireless. Overlay has a unit-specific p2p line.
seed_unit freq yes none 0 A
ln -sfn "$FAKE/orig/A" "$FAKE/orig/current"
install_both
assert_applied yes
# Wireless keys survive adb uninstall.
uninstall_adb
assert_grep '^persist.ium.support=carplay$' /system/build.prop
assert_grep '^persist.wifi.state=3$' /system/build.prop
assert_grep '^ro.adb.secure=1$' /system/build.prop
assert_not_grep 'service.adb.tcp.port' /system/build.prop
assert_cmp /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml \
    "$FAKE/orig/A/hide.xml"
assert_gone /data/nemesis/backup/adb.tcp
[ -d /data/nemesis/backup/wireless.projection ]
uninstall_wireless
assert_restored A yes freq none 0
echo "scenario A ok"

# B: uninstall wireless first. Overlay has no p2p_no_go_freq line. Runtime ium was unset.
seed_unit none yes unset unset B
ln -sfn "$FAKE/orig/B" "$FAKE/orig/current"
install_both
assert_applied yes
assert_not_grep 'p2p_no_go_freq' /system/etc/wifi/wpa_supplicant_overlay.conf
uninstall_wireless
assert_grep '^persist.ium.support=none$' /system/build.prop
assert_not_grep 'persist.wifi.state' /system/build.prop
assert_grep '^ro.adb.secure=0$' /system/build.prop
assert_grep '^service.adb.tcp.port=5555$' /system/build.prop
assert_cmp /system/etc/wifi/wpa_supplicant_overlay.conf "$FAKE/orig/B/overlay"
assert_cmp /system/etc/permissions/android.hardware.wifi.xml "$FAKE/orig/B/wifi.xml"
assert_gone /system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml
assert_gone /data/nemesis/backup/wireless.projection
[ -d /data/nemesis/backup/adb.tcp ]
assert_gone /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml
uninstall_adb
assert_restored B yes none unset unset
echo "scenario B ok"

# C: hide XML was already absent. Uninstall must not create one.
seed_unit freq no none 0 C
ln -sfn "$FAKE/orig/C" "$FAKE/orig/current"
run_hook "$ADB_PKG" pre.sh
run_hook "$ADB_PKG" post.sh
assert_gone /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml
[ -f /data/nemesis/backup/adb.tcp/adb_hide.xml.absent ]
assert_grep '^ro.adb.secure=0$' /system/build.prop
[ "$(reboot_count)" -eq 1 ] || fail "adb install did not request reboot"
uninstall_adb
assert_cmp /system/build.prop "$FAKE/orig/C/build.prop"
assert_gone /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml
assert_gone /data/nemesis/backup/adb.tcp
echo "scenario C ok"

# D: every onebin featurexml with wireless lines is patched. The property is ignored.
seed_unit freq no none 0 D
mkdir -p /system/onebin/neev23_us
cp -a /system/onebin/cvev_us/featurexml.cfg /system/onebin/neev23_us/featurexml.cfg
cp -a /system/onebin/neev23_us/featurexml.cfg "$FAKE/orig/D/neev.cfg"
printf '%s\n' /system/onebin/neev23_us > /data/property/ro.mobis.targetdir
ln -sfn "$FAKE/orig/D" "$FAKE/orig/current"
run_hook "$WIFI_PKG" pre.sh
run_hook "$WIFI_PKG" post.sh
assert_grep '^#-/system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml$' \
    /system/onebin/neev23_us/featurexml.cfg
assert_grep '^#-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml$' \
    /system/onebin/neev23_us/featurexml.cfg
assert_grep '^#-/system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml$' \
    /system/onebin/cvev_us/featurexml.cfg
assert_grep '^#-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml$' \
    /system/onebin/cvev_us/featurexml.cfg
assert_cmp /system/onebin/other/featurexml.cfg "$FAKE/orig/D/decoy.cfg"
assert_grep 'cvev_us /system/onebin/cvev_us/featurexml.cfg' \
    /data/nemesis/backup/wireless.projection/featurexml.list
assert_grep 'neev23_us /system/onebin/neev23_us/featurexml.cfg' \
    /data/nemesis/backup/wireless.projection/featurexml.list
assert_not_grep 'other' /data/nemesis/backup/wireless.projection/featurexml.list
assert_cmp /data/nemesis/backup/wireless.projection/featurexml.cvev_us.cfg \
    "$FAKE/orig/D/featurexml.cfg"
assert_cmp /data/nemesis/backup/wireless.projection/featurexml.neev23_us.cfg \
    "$FAKE/orig/D/neev.cfg"
uninstall_wireless
assert_cmp /system/onebin/neev23_us/featurexml.cfg "$FAKE/orig/D/neev.cfg"
assert_cmp /system/onebin/cvev_us/featurexml.cfg "$FAKE/orig/D/featurexml.cfg"
assert_restored D no freq none 0
echo "scenario D ok"

# E: factory CRLF in featurexml.cfg — wireless lines must still be commented.
seed_unit no no none 0 E
mkdir -p /system/onebin/crlf_us
{
    printf '%s\r\n' '# note androidauto_wireless.xml stays a comment'
    printf '%s\r\n' '-/system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml'
    printf '%s\r\n' '-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml'
    printf '%s\r\n' 'keep_this=1'
} > /system/onebin/crlf_us/featurexml.cfg
cp -a /system/onebin/crlf_us/featurexml.cfg "$FAKE/orig/E/featurexml.crlf.cfg"
ln -sfn "$FAKE/orig/E" "$FAKE/orig/current"
run_hook "$WIFI_PKG" pre.sh
run_hook "$WIFI_PKG" post.sh
grep -q $'^#-/system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml\r$' \
    /system/onebin/crlf_us/featurexml.cfg \
    || fail "CRLF featurexml: androidauto line not commented"
grep -q $'^#-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml\r$' \
    /system/onebin/crlf_us/featurexml.cfg \
    || fail "CRLF featurexml: carplay line not commented"
grep -q $'^keep_this=1\r$' /system/onebin/crlf_us/featurexml.cfg \
    || fail "CRLF featurexml: untouched line bytes changed"
uninstall_wireless
assert_cmp /system/onebin/crlf_us/featurexml.cfg "$FAKE/orig/E/featurexml.crlf.cfg"
echo "scenario E ok"

# adb.toggle: enable/disable on demand through adbctl.sh (no reboot, no install-time change).
toggle_ctl() {
    local out="$FAKE/ctl.out"
    TOGGLE_RC=0
    ( "$HOOK_SH" /data/nemesis/apps/adb.toggle/bin/adbctl.sh "$@" ) >"$out" 2>&1 || TOGGLE_RC=$?
}
seed_toggle() {
    seed_unit "$1" "$2" none 0 "$3"
    ln -sfn "$FAKE/orig/$3" "$FAKE/orig/current"
    mkdir -p /data/nemesis/apps
    cp -a "$TOGGLE_PKG" /data/nemesis/apps/adb.toggle
    run_hook "$TOGGLE_PKG" pre.sh
    run_hook "$TOGGLE_PKG" post.sh
    assert_cmp /system/build.prop "$FAKE/orig/$3/build.prop"
}

# F: enable, enable again, disable. Hide XML present on the unit.
seed_toggle freq yes F
[ "$(reboot_count)" -eq 0 ] || fail "toggle install rebooted"
toggle_ctl enable
[ "$TOGGLE_RC" -eq 0 ] || fail "toggle enable rc=$TOGGLE_RC: $(cat "$FAKE/ctl.out")"
assert_grep '^state=enabled$' "$FAKE/ctl.out"
assert_grep '^ro.adb.secure=0$' /system/build.prop
assert_grep '^persist.service.adb.tcp.enable=1$' /system/build.prop
assert_grep '^service.adb.tcp.port=5555$' /system/build.prop
assert_grep '^ro.keep=1$' /system/build.prop
assert_gone /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml
assert_grep '^1$' /data/property/persist.service.adb.tcp.enable
assert_grep '^5555$' /data/property/service.adb.tcp.port
assert_grep 'LIVE-HIDE-UNIQUE-9f3a' /data/nemesis/backup/adb.toggle/adb_hide.xml
[ "$(reboot_count)" -eq 0 ] || fail "toggle enable rebooted"
toggle_ctl enable
[ "$TOGGLE_RC" -eq 0 ] || fail "second enable rc=$TOGGLE_RC"
assert_grep 'LIVE-HIDE-UNIQUE-9f3a' /data/nemesis/backup/adb.toggle/adb_hide.xml
assert_grep '^ro.adb.secure=1$' /data/nemesis/backup/adb.toggle/build.prop
toggle_ctl status
assert_grep '^state=enabled$' "$FAKE/ctl.out"
toggle_ctl disable
[ "$TOGGLE_RC" -eq 0 ] || fail "toggle disable rc=$TOGGLE_RC: $(cat "$FAKE/ctl.out")"
assert_grep '^state=disabled$' "$FAKE/ctl.out"
assert_cmp /system/build.prop "$FAKE/orig/F/build.prop"
assert_cmp /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml "$FAKE/orig/F/hide.xml"
assert_gone /data/property/persist.service.adb.tcp.enable
assert_gone /data/nemesis/backup/adb.toggle
toggle_ctl disable
[ "$TOGGLE_RC" -eq 0 ] || fail "disable when disabled rc=$TOGGLE_RC"
toggle_ctl reboot
[ "$(reboot_count)" -eq 1 ] || fail "adbctl reboot did not call reboot"
echo "scenario F ok"

# G: uninstall while enabled restores; hide XML was absent. adb.tcp present blocks disable.
seed_toggle none no G
toggle_ctl enable
[ "$TOGGLE_RC" -eq 0 ] || fail "G enable rc=$TOGGLE_RC"
run_hook "$TOGGLE_PKG" preun.sh
assert_cmp /system/build.prop "$FAKE/orig/G/build.prop"
assert_gone /system/etc/permissions/com.hkmc.software.engineermode.adb_hide.xml
assert_gone /data/nemesis/backup/adb.toggle
seed_toggle none yes H
run_hook "$ADB_PKG" pre.sh
run_hook "$ADB_PKG" post.sh
cp -a /system/build.prop "$FAKE/orig/H/after_tcp"
toggle_ctl enable
[ "$TOGGLE_RC" -eq 0 ] || fail "H enable rc=$TOGGLE_RC"
assert_grep '^state=enabled-by-adb.tcp$' "$FAKE/ctl.out"
assert_gone /data/nemesis/backup/adb.toggle
toggle_ctl disable
[ "$TOGGLE_RC" -ne 0 ] || fail "disable must refuse while adb.tcp owns the edits"
assert_cmp /system/build.prop "$FAKE/orig/H/after_tcp"
uninstall_adb
assert_cmp /system/build.prop "$FAKE/orig/H/build.prop"
echo "scenario toggle ok"

# preinstall fails when build.prop is missing
rm -f /system/build.prop
set +e
( cd "$ADB_PKG" && "$HOOK_SH" ./scripts/pre.sh ) >"$FAKE/hook.out" 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "preinstall should fail without build.prop"
grep -q 'build.prop missing' "$FAKE/hook.out" || fail "preinstall failure did not name build.prop"
echo "scenario preinstall-miss ok"

echo "ALL LIVE SNAPSHOT HOOK TESTS PASSED ($HOOK_SH)"
