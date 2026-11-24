#!/bin/bash
# Host check for the adb.toggle example's hooks and adbctl.sh: they snapshot whatever is on the live
# unit and restore those bytes. adb.tcp and wireless.projection are LivePatch packages now
# (tests/pkg_livepatch_check.py and the emulator suite).
# Requires a user+mount namespace (unshare). Does not touch the host /system.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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

echo "hooks via $HOOK_SH"

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
# H: adb.tcp 2.x (LivePatch, no backup dir) is installed: enable and disable leave /system alone.
seed_toggle none yes H
mkdir -p /data/nemesis/apps/adb.tcp
toggle_ctl enable
[ "$TOGGLE_RC" -eq 0 ] || fail "H enable rc=$TOGGLE_RC"
assert_grep '^state=enabled-by-adb.tcp$' "$FAKE/ctl.out"
assert_gone /data/nemesis/backup/adb.toggle
assert_cmp /system/build.prop "$FAKE/orig/H/build.prop"
toggle_ctl disable
[ "$TOGGLE_RC" -ne 0 ] || fail "disable must refuse while adb.tcp owns the settings"
rm -rf /data/nemesis/apps/adb.tcp
echo "scenario toggle ok"

echo "ALL LIVE SNAPSHOT HOOK TESTS PASSED ($HOOK_SH)"
