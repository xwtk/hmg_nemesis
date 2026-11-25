# NEMESIS 1.17 and later: units NEMESIS must not be installed on (head-unit mksh, sourced).
# Used by the USB installer's control script, the troubleshooter and the core update's migrate.sh;
# the installer app has the same rules in Java (InstallerActivity.Compat).
# nemesis_compat returns 0 when the unit is fine, else 1 with NEMESIS_COMPAT_MSG for the owner:
#  - HMG-API installed and older than 1.1.0-rc.9 (it writes app_process itself, under LivePatch's
#    wrapper only from rc.9), or of unknown version;
#  - no HMG-API, and app_process (the binary behind NEMESIS's own LivePatch wrapper, when that's
#    there) isn't the stock AOSP binary. The unit keeps no list of its build's files, so "stock" is
#    what the build's binary is: the dynamic AOSP app_process starting ZygoteInit, with nothing that
#    hands over to another binary or preloads a library.

NEMESIS_HMGAPI_MIN=1.1.0-rc.9
NEMESIS_COMPAT_ROOT=${NEMESIS_COMPAT_ROOT:-}   # tests only: a tree standing in for /
NEMESIS_MSG_HMGAPI="NEMESIS 1.17 and later are not compatible with your HMG-API version, update it or uninstall to continue installation."
NEMESIS_MSG_APP_PROCESS="NEMESIS 1.17 and later utilize a critical system component (app_process binary) for additional features, which has been modified, restore it to factory state to continue installation."

# compat_num <s>: s if it is all digits, else 0
compat_num() {
    case $1 in
        '' | *[!0-9]*) print -r -- 0 ;;
        *) print -r -- "$1" ;;
    esac
}

# compat_key <version>: "major minor patch rank n" (release ranks above rc, beta, alpha)
compat_key() {
    local v=$1 core pre t n r
    core=${v%%-*}
    pre=
    [ "$core" != "$v" ] && pre=${v#*-}
    local IFS=.
    set -- $core
    IFS=' '
    if [ -z "$pre" ]; then
        r=9
        n=0
    else
        t=${pre%%.*}
        n=0
        [ "$t" != "$pre" ] && n=${pre#*.}
        case $t in
            rc) r=3 ;;
            beta) r=2 ;;
            alpha) r=1 ;;
            *) r=0 ;;
        esac
    fi
    print -r -- "$(compat_num "${1:-}") $(compat_num "${2:-}") $(compat_num "${3:-}") $r $(compat_num "$n")"
}

# compat_older <a> <b>: a is an older version than b
compat_older() {
    local i=0
    set -- $(compat_key "$1") $(compat_key "$2")
    while [ $i -lt 5 ]; do   # field i of a is $1, of b $6, after i shifts
        [ "$1" -lt "$6" ] && return 0
        [ "$1" -gt "$6" ] && return 1
        shift
        i=$((i + 1))
    done
    return 1
}

# compat_hmgapi_version: HMG-API's version from installed.txt ("version 1.1.0-rc.9"), or nothing
compat_hmgapi_version() {
    local k v
    [ -f "$NEMESIS_COMPAT_ROOT/data/hmgapi/installed.txt" ] || return 0
    while read -r k v; do
        [ "$k" = version ] && { print -r -- "$v"; return 0; }
    done < "$NEMESIS_COMPAT_ROOT/data/hmgapi/installed.txt"
}

# compat_stock_app_process <file>
compat_stock_app_process() {
    local f=$1 s
    [ -f "$f" ] || return 1
    for s in /system/bin/linker com.android.internal.os.ZygoteInit NO_ADDR_COMPAT_LAYOUT_FIXUP; do
        grep -q "$s" "$f" 2>/dev/null || return 1
    done
    # a wrapper names what it runs next or preloads; the AOSP binary names only itself
    for s in 'app_process\.' LD_PRELOAD /data/ hmgapi HMG-API leafia Leafia xposed Xposed; do
        grep -q "$s" "$f" 2>/dev/null && return 1
    done
    return 0
}

nemesis_compat() {
    local v a b R=$NEMESIS_COMPAT_ROOT ap=$NEMESIS_COMPAT_ROOT/system/bin/app_process
    NEMESIS_COMPAT_MSG=
    if [ -f "$R/data/hmgapi/installed.txt" ] || [ -f "$R/system/bin/hmgapid" ]; then
        v=$(compat_hmgapi_version)
        if [ -z "$v" ] || compat_older "$v" "$NEMESIS_HMGAPI_MIN"; then
            NEMESIS_COMPAT_MSG=$NEMESIS_MSG_HMGAPI
            return 1
        fi
        return 0
    fi
    a=
    b=
    { read -r a; read -r b; } 2>/dev/null < "$ap"   # stderr first: a missing file's error comes from the < itself
    case $a$b in
        '#!/system/bin/sh# NEMESIS LivePatch'*) ap=$R/system/bin/app_process.real ;;
    esac
    compat_stock_app_process "$ap" && return 0
    NEMESIS_COMPAT_MSG=$NEMESIS_MSG_APP_PROCESS
    return 1
}
