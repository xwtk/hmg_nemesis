#!/system/bin/sh
# NEMESIS core update: scripts/migrate.sh (tools/make_update_zip.py). The updater already on the
# unit runs it as root, from the update's root folder, after it placed the core files.
# Updaters before 1.17 place only nemesis, NEMESIS.apk and install-recovery.sh, so the LivePatch
# dex engine goes in here; 1.17 and newer placed the same two files already (nothing to do).
# A non-zero exit makes the updater put the previous version back.
umask 077
# NEMESIS 1.17+ refuses units it would break (old HMG-API, a modified app_process): the updater then
# puts the previous version back. scripts/compat.sh is boot/compat.sh.
unset NEMESIS_COMPAT_ROOT   # a test-only setting must not come in with the caller's environment
if ! . ./scripts/compat.sh; then
    echo "migrate: this update has no compatibility check"
    exit 1
fi
if ! nemesis_compat; then
    # The Manager's "update failed" popup (in-app or from a stick) shows ui.error under its text.
    /system/xbin/nemesis ui error "$NEMESIS_COMPAT_MSG" >/dev/null 2>&1
    print -r -- "$NEMESIS_COMPAT_MSG"
    exit 1
fi
E=/data/nemesis/livepatch/engine
if [ ! -f core/porter.jar ] || [ ! -f core/porter.out ]; then
    echo "migrate: this update has no LivePatch engine"
    exit 1
fi
mkdir -p "$E" || exit 1
for f in porter.jar porter.out; do
    cmp "core/$f" "$E/$f" >/dev/null 2>&1 && continue
    rm -f "$E/$f.new"
    if ! cp "core/$f" "$E/$f.new" || ! chmod 644 "$E/$f.new" || ! mv "$E/$f.new" "$E/$f"; then
        rm -f "$E/$f.new"
        echo "migrate: could not place $E/$f"
        exit 1
    fi
done
chmod 711 /data/nemesis/livepatch
echo "migrate: LivePatch engine in $E"
exit 0
