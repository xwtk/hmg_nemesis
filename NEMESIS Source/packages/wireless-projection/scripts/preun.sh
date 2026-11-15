#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
. ./scripts/common.sh

log "preuninstall wireless.projection"
if ! restore_all; then
    log "keeping $BACKUP_DIR"
    exit 1
fi
remove_backup_dir || exit 1
log "removed $BACKUP_DIR"
sync
exit 0
