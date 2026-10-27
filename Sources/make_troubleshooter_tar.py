#!/usr/bin/env python3
"""OTA package placing bootstrap script at /storage/upgrade/troubleShooter.

Device-tested MAP_PREPARE order (OTAUA_FAIL logs):
  1. bg_ota_pre_descrypt_files (hash misses are non-fatal)
  2. run_bg_troubleShooter  -> execl /storage/upgrade/troubleShooter
  3. _bg_validation_function -> ota_info missing -> 684

Single regfile member troubleShooter (no symlink pair). Self-extractor:
shell header, then raw ELF + Manager APK. Does not patch any stock APK.
"""

from __future__ import annotations

import io
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "usb_bundle" / "ota_package_troubleshooter.tar"
MEMBER = "troubleShooter"
TOAST_ACTION = "com.hkmc.intent.action.TOAST"
TOAST_MSG_EXTRA = "com.hkmc.intent.extra.TOAST_MESSAGE"
TOAST_POPUP_EXTRA = "com.hkmc.intent.extra.TOAST_POPUPSHOWING"
ENG_PKG = "com.hkmc.system.app.engineering"

ELF_PATH = ROOT / "native" / "nemesis"
APK_PATH = ROOT / "manager" / "NEMESIS.apk"
RECOVERY_PATH = ROOT / "boot" / "install-recovery.sh"
QB_HOOK_PATH = ROOT / "boot" / "qb_hook.sh"


def bootstrap_script(elf_skip: int, elf_len: int, apk_skip: int, apk_len: int,
                     recovery: str, qb_hook: str) -> str:
    return f"""#!/system/bin/sh
# execl'd from /storage/upgrade/troubleShooter during OTA MAP_PREPARE (before ota_info check).
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

SELF="$0"
ELF_SKIP={elf_skip}
ELF_LEN={elf_len}
APK_SKIP={apk_skip}
APK_LEN={apk_len}

extract_blob() {{
    _dest="$1"
    _skip="$2"
    _len="$3"
    _tmp="/data/nemesis/.extract.$$"
    # Slice the concatenated payload; never dd onto a live /system path.
    dd if="$SELF" of="$_tmp" bs=1 skip="$_skip" count="$_len" 2>/dev/null
    rm -f "$_dest"
    cp -f "$_tmp" "$_dest"
    rm -f "$_tmp"
}}

mkdir -p /data/nemesis /data/nemesis/apps /data/nemesis/tmp

am start -n {ENG_PKG}/.EngineeringModeMainActivity >/dev/null 2>&1 || true
sleep 1
am broadcast -a {TOAST_ACTION} \\
  -e {TOAST_MSG_EXTRA} "Welcome to NEMESIS." \\
  --ez {TOAST_POPUP_EXTRA} true 2>&1 || true

sleep 2
am broadcast -a {TOAST_ACTION} \\
  -e {TOAST_MSG_EXTRA} "Installing..." \\
  --ez {TOAST_POPUP_EXTRA} true 2>&1 || true

mount -o remount,rw /system 2>&1 || mount -o rw,remount / 2>&1

kill_pidfile() {{
    _pf="$1"
    if [ -f "$_pf" ]; then
        kill -TERM $(cat "$_pf") 2>/dev/null || true
        sleep 1
        kill -KILL $(cat "$_pf") 2>/dev/null || true
    fi
}}
kill_pidfile /data/nemesis/watchdog.pid
kill_pidfile /data/nemesis/svcmgr.pid
kill_pidfile /data/nemesis/pids/usb-handler+usb-handler
kill_pidfile /data/local/tmp/nemesis/watchdog.pid
kill_pidfile /data/local/tmp/nemesis/svcmgr.pid
kill_pidfile /data/local/tmp/nemesis/pids/usb-handler+usb-handler

extract_blob /system/xbin/nemesis "$ELF_SKIP" "$ELF_LEN"
chown root:root /system/xbin/nemesis 2>/dev/null || true
chmod 0755 /system/xbin/nemesis

extract_blob /system/app/NEMESIS.apk "$APK_SKIP" "$APK_LEN"
chmod 644 /system/app/NEMESIS.apk
rm -f /data/dalvik-cache/system@app@NEMESIS.apk@classes.dex \\
      /cache/dalvik-cache/system@app@NEMESIS.apk@classes.dex \\
      /data/dalvik-cache/arm/system@app@NEMESIS.apk@classes.dex

cat > /system/etc/install-recovery.sh << 'RECOVERY_EOF'
{recovery}RECOVERY_EOF
chmod 755 /system/etc/install-recovery.sh

if [ -f /system/etc/qb_runtime.sh ] && ! grep -q NEMESIS_QB_HOOK /system/etc/qb_runtime.sh; then
    cat >> /system/etc/qb_runtime.sh << 'QB_EOF'

{qb_hook}
QB_EOF
fi

sync

am broadcast -a {TOAST_ACTION} \\
  -e {TOAST_MSG_EXTRA} "NEMESIS installation completed successfully, restarting." \\
  --ez {TOAST_POPUP_EXTRA} true 2>&1 || true

sync
# The rest runs from /data. Deleting /storage/upgrade while this file is
# still the shell script cuts it off, and the unit reboots before the toast.
cat > /data/nemesis/ota-finish.sh << 'END'
#!/system/bin/sh
sleep 5
rm -rf /storage/upgrade/*
sync
reboot
END
chmod 755 /data/nemesis/ota-finish.sh
exec /data/nemesis/ota-finish.sh
sleep 5
reboot
exit 0
# NEMESIS_PAYLOAD
"""


def main() -> None:
    if not ELF_PATH.is_file():
        raise SystemExit(f"missing {ELF_PATH} — build native first")
    if not APK_PATH.is_file():
        raise SystemExit(f"missing {APK_PATH} — build manager first")
    recovery = RECOVERY_PATH.read_text(encoding="utf-8")
    if not recovery.endswith("\n"):
        recovery += "\n"
    qb_hook = QB_HOOK_PATH.read_text(encoding="utf-8")
    if not qb_hook.endswith("\n"):
        qb_hook += "\n"

    elf = ELF_PATH.read_bytes()
    apk = APK_PATH.read_bytes()

    align = 4096
    elf_skip = 0
    hdr = b""
    for _ in range(8):
        apk_skip = elf_skip + len(elf)
        script = bootstrap_script(elf_skip, len(elf), apk_skip, len(apk), recovery, qb_hook)
        hdr = script.encode("utf-8")
        if not hdr.endswith(b"\n"):
            hdr += b"\n"
        pad = (align - (len(hdr) % align)) % align
        hdr += b"\n" * pad
        if len(hdr) == elf_skip:
            break
        elf_skip = len(hdr)
    else:
        raise SystemExit("bootstrap header length did not stabilize")

    data = hdr + elf + apk
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(OUT, "w", format=tarfile.GNU_FORMAT) as tar:
        reg = tarfile.TarInfo(name=MEMBER)
        reg.size = len(data)
        reg.mode = 0o755
        reg.uid = reg.gid = 0
        reg.mtime = 0
        tar.addfile(reg, io.BytesIO(data))

    alias = OUT.parent / "ota_package.tar"
    alias.write_bytes(OUT.read_bytes())

    print(f"Wrote {OUT} ({OUT.stat().st_size} bytes) -> /storage/upgrade/{MEMBER}")
    print(f"  also {alias}")
    print(f"  header {len(hdr)}  elf {len(elf)}  apk {len(apk)}")
    print("  install: /system/xbin/nemesis (0755), NEMESIS.apk, watchdog hook (no stock APK patch)")
    print()
    print("Flash: ota_package_troubleshooter.tar (or ota_package.tar) -> DSP OTA")


if __name__ == "__main__":
    main()
