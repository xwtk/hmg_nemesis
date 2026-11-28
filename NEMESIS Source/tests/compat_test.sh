#!/usr/bin/env bash
# Host test of boot/compat.sh (the install check of NEMESIS 1.17+): HMG-API versions and the
# app_process rules. bash stands in for the unit's mksh (print is mksh's). The stock app_process
# comes from a stock system image (NEMESIS_STOCK_SYSTEM, ext4, read with debugfs), never the repo.
# The real check on the unit: tests/emu_coexist (compat phase).
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
IMG=${NEMESIS_STOCK_SYSTEM:-/home/xwtk/EV6_260507/system.ext4}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
print() { [ "$1" = -r ] && shift; [ "$1" = -- ] && shift; printf '%s\n' "$*"; }
. "$HERE/boot/compat.sh"

# The installer app has the same rules in Java; checked too when it is built (make in installer/).
INST="$HERE/../USB package path/installer"
JAVA_CP=
if [ -d "$INST/classes/com/nemesis/installer" ] && command -v javac >/dev/null; then
    mkdir -p "$T/j/com/nemesis/installer"
    cat > "$T/j/com/nemesis/installer/CompatRun.java" << 'EOF'
package com.nemesis.installer;
public class CompatRun {
    public static void main(String[] a) {
        if (a[0].equals("older")) System.out.println(InstallerActivity.Compat.older(a[1], a[2]) ? "older" : "not");
        else if (a[0].equals("lp")) System.out.println(InstallerActivity.Compat.livePatch(a[1]) ? "lp" : "none");
        else System.out.println(InstallerActivity.Compat.stockAppProcess(a[1]) ? "ok" : "refused");
    }
}
EOF
    ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-30/android.jar}
    javac -cp "$INST/classes:$ANDROID_JAR" -d "$T/j" "$T/j/com/nemesis/installer/CompatRun.java" \
        && JAVA_CP="$T/j:$INST/classes:$ANDROID_JAR"
fi
[ -n "$JAVA_CP" ] || echo "skip  Java cases (installer not built)"

# ---- versions
older() {
    local s; s=$(compat_older "$1" "$2" && echo older || echo not)
    [ -z "$JAVA_CP" ] || [ "$(java -cp "$JAVA_CP" com.nemesis.installer.CompatRun older "$1" "$2")" = "$s" ] || s="$s, java differs"
    echo "$s"
}
check "the floor is rc.10" 1.1.0-rc.10 "$NEMESIS_HMGAPI_MIN"
check "1.0.2 < rc.10" older "$(older 1.0.2 1.1.0-rc.10)"
check "beta.4 < rc.10" older "$(older 1.1.0-beta.4 1.1.0-rc.10)"
check "rc.9 < rc.10 (numbers, not text)" older "$(older 1.1.0-rc.9 1.1.0-rc.10)"
check "rc.10 = rc.10" not "$(older 1.1.0-rc.10 1.1.0-rc.10)"
check "rc.11 > rc.10" not "$(older 1.1.0-rc.11 1.1.0-rc.10)"
check "1.1.0 > rc.10" not "$(older 1.1.0 1.1.0-rc.10)"
check "1.2.0-beta.1 > 1.1.0-rc.10" not "$(older 1.2.0-beta.1 1.1.0-rc.10)"
check "2.0 > rc.10" not "$(older 2.0 1.1.0-rc.10)"

# ---- the unit
unit() {   # unit <app_process file> [app_process.real file]: a fresh tree, no HMG-API
    rm -rf "$T/r"; mkdir -p "$T/r/system/bin" "$T/r/data"
    cp "$1" "$T/r/system/bin/app_process"
    [ -n "${2:-}" ] && cp "$2" "$T/r/system/bin/app_process.real"
    return 0
}
verdict() {
    local s=refused j
    NEMESIS_COMPAT_ROOT=$T/r
    if nemesis_compat; then s=ok; echo ok; else echo "$NEMESIS_COMPAT_MSG"; fi
    # the installer app picks the HMG-API message by the same sign of NEMESIS 1.17
    if [ -n "$JAVA_CP" ] && [ "$NEMESIS_COMPAT_MSG" = "$NEMESIS_MSG_HMGAPI_LP" ]; then
        [ "$(java -cp "$JAVA_CP" com.nemesis.installer.CompatRun lp "$T/r/system/bin")" = lp ] || echo "java says no NEMESIS"
    elif [ -n "$JAVA_CP" ] && [ "$NEMESIS_COMPAT_MSG" = "$NEMESIS_MSG_HMGAPI" ]; then
        [ "$(java -cp "$JAVA_CP" com.nemesis.installer.CompatRun lp "$T/r/system/bin")" = none ] || echo "java says NEMESIS"
    fi
    # the installer app's copy (InstallerActivity.Compat) must agree on app_process
    if [ -n "$JAVA_CP" ] && [ ! -e "$T/r/data/hmgapi" ] && [ ! -e "$T/r/system/bin/hmgapid" ]; then
        j=$(java -cp "$JAVA_CP" com.nemesis.installer.CompatRun ap "$T/r/system/bin")
        [ "$j" = "$s" ] || echo "java says $j"
    fi
}
hmgapi() { mkdir -p "$T/r/data/hmgapi"; echo "version $1" > "$T/r/data/hmgapi/installed.txt"; }

if ! debugfs -R "dump /bin/app_process $T/stock" "$IMG" >/dev/null 2>&1 || [ ! -s "$T/stock" ]; then
    echo "skip  app_process cases (no stock system image at $IMG)"
    exit $fail
fi
printf '#!/system/bin/sh\n# NEMESIS LivePatch: apply patches before the zygote starts, then run the real binary.\nexec /system/bin/app_process.real "$@"\n' > "$T/lp"
printf '#!/system/bin/sh\nexec /system/bin/app_process.orig "$@"\n' > "$T/script"
{ cat "$T/stock"; printf 'LD_PRELOAD\0/system/bin/app_process.hmgapi-stock\0'; } > "$T/wrapped"   # a wrapper built on the stock code

unit "$T/stock"
check "stock app_process: installs" ok "$(verdict)"
unit "$T/lp" "$T/stock"
check "NEMESIS 1.17's wrapper over stock (.real): installs" ok "$(verdict)"
unit "$T/lp" "$T/wrapped"
check "NEMESIS's wrapper over a modified .real: refused" "$NEMESIS_MSG_APP_PROCESS" "$(verdict)"
unit "$T/lp"
check "NEMESIS's wrapper with no .real: refused" "$NEMESIS_MSG_APP_PROCESS" "$(verdict)"
unit "$T/script"
check "a shell-script wrapper: refused" "$NEMESIS_MSG_APP_PROCESS" "$(verdict)"
unit "$T/wrapped"
check "a binary that preloads or runs another app_process: refused" "$NEMESIS_MSG_APP_PROCESS" "$(verdict)"
head -c 4000 "$T/stock" > "$T/cut"; unit "$T/cut"
check "a cut-off binary: refused" "$NEMESIS_MSG_APP_PROCESS" "$(verdict)"

unit "$T/wrapped"; hmgapi 1.1.0-rc.8
check "HMG-API rc.8: refused, whatever app_process is" "$NEMESIS_MSG_HMGAPI" "$(verdict)"
unit "$T/wrapped"; hmgapi 1.0.2
check "HMG-API 1.0.2: refused" "$NEMESIS_MSG_HMGAPI" "$(verdict)"
unit "$T/wrapped"; hmgapi 1.1.0-rc.9
check "HMG-API rc.9, no NEMESIS 1.17: refused, update or uninstall" "$NEMESIS_MSG_HMGAPI" "$(verdict)"
unit "$T/wrapped"; hmgapi 1.1.0-rc.10
check "HMG-API rc.10 (its wrapper in app_process): installs" ok "$(verdict)"
unit "$T/lp" "$T/wrapped"; hmgapi 1.1.0-rc.9
check "HMG-API rc.9 under NEMESIS 1.17's wrapper: refused, update and don't uninstall" "$NEMESIS_MSG_HMGAPI_LP" "$(verdict)"
unit "$T/wrapped" "$T/stock"; hmgapi 1.1.0-rc.8
check "HMG-API rc.8 over NEMESIS 1.17 (app_process.real kept): the 1.17 message" "$NEMESIS_MSG_HMGAPI_LP" "$(verdict)"
unit "$T/lp" "$T/wrapped"; hmgapi 1.1.0-rc.10
check "HMG-API rc.10 under NEMESIS's wrapper: installs" ok "$(verdict)"
unit "$T/lp" "$T/wrapped"; hmgapi 1.1.0
check "HMG-API 1.1.0 under NEMESIS's wrapper: installs" ok "$(verdict)"
unit "$T/wrapped"; touch "$T/r/system/bin/hmgapid"
check "hmgapid with no installed.txt (version unknown): refused" "$NEMESIS_MSG_HMGAPI" "$(verdict)"
unit "$T/lp" "$T/wrapped"; touch "$T/r/system/bin/hmgapid"
check "hmgapid, version unknown, under NEMESIS 1.17: the 1.17 message" "$NEMESIS_MSG_HMGAPI_LP" "$(verdict)"

# ---- native/stock.c: lp_wrapper_install trusts app_process(.real) by the same rules
if cc -Wall -Wextra -Werror -DSTOCK_MAIN -o "$T/stock_check" "$HERE/native/stock.c"; then
    : > "$T/empty"
    head -c 8192 "$T/stock" > "$T/cut8k"   # every byte the loader maps, no section headers
    for f in stock lp script wrapped cut empty cut8k; do
        s=$(compat_stock_app_process "$T/$f" && echo ok || echo refused)
        check "stock.c agrees with compat.sh: $f ($s)" "$s" "$("$T/stock_check" "$T/$f")"
    done
    check "stock.c: the stock binary" ok "$("$T/stock_check" "$T/stock")"
    check "stock.c: an empty .real" refused "$("$T/stock_check" "$T/empty")"
    check "stock.c: NEMESIS's own script" refused "$("$T/stock_check" "$T/lp")"
else
    echo "FAIL  native/stock.c does not build on the host"; fail=1
fi
exit $fail
