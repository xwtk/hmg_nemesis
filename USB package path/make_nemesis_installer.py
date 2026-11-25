#!/usr/bin/env python3
"""Build the USB NEMESIS *installation* package set.

The stock HKMC system-package extractor is a dumb `tar -x` with cwd =
/storage/upgrade/ that honors `..` in member names (confirmed on the stock
tars: `./usb_news/../../../data/property/persist.sys.usb.config` lands at
/data/property/persist.sys.usb.config). We reuse the same mechanism — our
tars keep every innocuous stock member (`update.info`, `usb_news/*`) and
replace the one `persist.sys.usb.config` member with four path-traversal
members:

  ./usb_news/../../../data/local/tmp/nemesis/nemesis             control script (init runs this)
  ./usb_news/../../../data/local/tmp/nemesis/nemesis.bin         native ELF (staged for install)
  ./usb_news/../../../data/local/tmp/nemesis/NEMESIS.apk         Manager APK (staged for install)
  ./usb_news/../../../data/app/NEMESIS-Installer.apk             Installer APK (PM auto-scans /data/app/ on boot)
  ./usb_news/../../../data/local/tmp/nemesis/porter.jar          LivePatch dex engine (staged for install)

No `troubleShooter` member, no self-extracting header — the stock extractor
itself does the writes. The reboot after extraction is the HKMC flow's own
(see uagentd `android_reboot(... "recovery")` strings); we do not arrange it.

Trigger: the Installer pokes `service call com.hkmc.misc 6 s16 <10-char>`;
transaction 6 reads an int equal to the s16 arg's length, so 10 ->
setSystem(10) sets persist.sys.swverify=nemesis. init.daudio.rc's
`on property:persist.sys.swverify=nemesis` then starts the oneshot root
service /data/local/tmp/nemesis/nemesis. The control script's teardown pokes
the 11-char OFF path (setSystem(11) clears swverify + engineer mode) and
restores persist.sys.QB.use.allow, which the OFF path leaves disabled.

Runtime state lives directly in /storage/upgrade/ (0777 root:root, so the
init-run root script reads it and the app writes it with WRITE_EXTERNAL_STORAGE):
the Installer drops NEMESISAGREEMENT there, and the control script writes its
finisher there. /data/local/tmp/ is 0771 shell:shell — the stock extractor
(full-cap OTA root) writes the staged files there and init (full-cap) executes
them, but a runtime app UID and the capability-stripped `nemesis root` cannot.
"""

from __future__ import annotations

import argparse
import io
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE_ROOT = ROOT.parent / "NEMESIS Source"
sys.path.insert(0, str(SOURCE_ROOT / "tools"))
from common import cacerts_heredocs  # noqa: E402

ELF_PATH = SOURCE_ROOT / "native" / "nemesis"
MANAGER_APK = SOURCE_ROOT / "manager" / "NEMESIS.apk"
INSTALLER_APK = ROOT / "installer" / "NEMESIS-Installer.apk"
RECOVERY_PATH = SOURCE_ROOT / "boot" / "install-recovery.sh"
QB_HOOK_PATH = SOURCE_ROOT / "boot" / "qb_hook.sh"
CACERTS_DIR = SOURCE_ROOT / "boot" / "cacerts"
PORTER_PATH = SOURCE_ROOT / "out" / "porter.jar"  # LivePatch dex engine (make porter)
COMPAT_PATH = SOURCE_ROOT / "boot" / "compat.sh"  # units NEMESIS 1.17+ refuses
# Verbatim ancillaries that sit on the USB stick alongside the tars (splash PNG
# and the RSA-signed tar list). Copied as-is; we do not regenerate them.
STICK_EXTRAS = ROOT / "installer" / "stick_extras"

# Stick matrix, including the SE product codes.
# Regions outside {USA,CAN,KOR,MES,BRA,IDN,IND,JPN} do not appear in real
# Gen5W firmware. Edit these lists to widen or narrow coverage.
BRANDS = ["HMC", "KMC", "HMCSE", "KMCSE", "ECOHMC", "ECOHMCSE", "ECOKMC", "ECOKMCSE",
          "GENESIS", "GENESISSE", "ECOGENESIS", "ECOGENESISSE", "HYUNDAISE"]
REGIONS = ["USA", "CAN", "KOR", "MES", "BRA", "IDN", "IND", "JPN"]
VARIANTS = ["0000", "1000"]
VERSION = "V099.999.999999"

TOAST_ACTION = "com.hkmc.intent.action.TOAST"
TOAST_MSG = "com.hkmc.intent.extra.TOAST_MESSAGE"
TOAST_POP = "com.hkmc.intent.extra.TOAST_POPUPSHOWING"
STAGE = "/data/local/tmp/nemesis"
RUNTIME_DIR = "/storage/upgrade"
AGREEMENT = RUNTIME_DIR + "/NEMESISAGREEMENT"
FINISHER = RUNTIME_DIR + "/nemesis-finish.sh"
# init.daudio.rc's `on property:persist.sys.swverify=nemesis` starts the oneshot
# root service at exactly this path. The misc service's transaction 6 reads an
# int equal to the s16 arg's *length*: a 10-char arg -> setSystem(10) sets
# swverify=nemesis (ON); an 11-char arg -> setSystem(11) clears it (OFF).
CONTROL_SCRIPT_DEST = STAGE + "/nemesis"
# Any-10-char and any-11-char tokens; only the length is read by setSystem().
MISC_SVC_ON = "NEMESISSVC"   # 10 chars -> ON  (swverify=nemesis, starts the service)
MISC_SVC_OFF = "NEMESISSVCX"  # 11 chars -> OFF (clears swverify + engineer mode)
assert len(MISC_SVC_ON) == 10 and len(MISC_SVC_OFF) == 11, "misc arg lengths select setSystem()"
INSTALLER_APK_DEST = "/data/app/NEMESIS-Installer.apk"
INSTALLER_PKG = "com.nemesis.installer"
# Three `..` hops escape the extractor's cwd (/storage/upgrade/usb_news/) to /.
# Prefix for every payload member; keeps the stock `./usb_news/` look.
TRAVERSAL_PREFIX = "./usb_news/../../.."

# Pidfiles a previous NEMESIS may have left; killed before overwriting the ELF.
PIDFILES = [
    "/data/nemesis/watchdog.pid",
    "/data/nemesis/svcmgr.pid",
    "/data/nemesis/pids/usb-handler+usb-handler",
]


def control_script(recovery: str, qb_hook: str, cacerts: str, por_out: str) -> str:
    """The `nemesis` control script staged at /data/local/tmp/nemesis/nemesis."""
    return f"""#!/system/bin/sh
# NEMESIS install control script. Run as root (via the HKMC misc service the
# Installer app pokes) after the user accepts/declines. Reads {AGREEMENT}:
# 1 = install NEMESIS from the staged payload; 0 = clean up the Installer.
# Stays system-user-safe: it never changes its own uid.
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

STAGE={STAGE}

toast() {{
    am broadcast -a {TOAST_ACTION} -e {TOAST_MSG} "$1" --ez {TOAST_POP} true >/dev/null 2>&1 || true
}}

# ---- boot/compat.sh: units NEMESIS 1.17+ must not be installed on (the Installer app checks first)
{{compat}}

kill_pidfile() {{
    _pf="$1"
    [ -f "$_pf" ] || return 0
    read _p _rest < "$_pf"
    case "$_p" in ''|0|1|*[!0-9]*) return 0 ;; esac
    grep -q nemesis "/proc/$_p/cmdline" 2>/dev/null || return 0
    kill -TERM "$_p" 2>/dev/null || true
    sleep 1
    grep -q nemesis "/proc/$_p/cmdline" 2>/dev/null || return 0
    kill -KILL "$_p" 2>/dev/null || true
}}

cleanup_installer() {{
    # Residue the USB Installer left; never touches real /data/nemesis state.
    pm uninstall {INSTALLER_PKG} >/dev/null 2>&1 || true
    rm -f {INSTALLER_APK_DEST}
    rm -f {AGREEMENT}
}}

teardown() {{
    # The ON poke set swverify=nemesis (persists across reboot and would re-fire
    # this service) and QB.use.allow=false. The misc OFF path (11-char arg)
    # clears swverify + engineer mode but NOT QB.use.allow, so restore that too.
    service call com.hkmc.misc 6 s16 {MISC_SVC_OFF} >/dev/null 2>&1 || true
    setprop persist.sys.QB.use.allow true
}}

# Remove the stage dir and (on install) reboot from a finisher that lives
# directly in /storage/upgrade/ (its 0777 lets root write here; this unit's
# root lacks CAP_DAC_OVERRIDE so /data/local/tmp/ rejects even root writes).
finish() {{
    teardown
    FIN={FINISHER}
    cat > $FIN << 'FIN_EOF'
#!/system/bin/sh
sleep 3
rm -rf {STAGE}
rm -f {FINISHER}
FIN_EOF
    if [ "$1" = 1 ]; then
        print -r -- "sync" >> $FIN
        print -r -- "reboot" >> $FIN
    fi
    chmod 755 $FIN
    exec /system/bin/sh $FIN
}}

install_nemesis() {{
    toast "Installing NEMESIS..."
    mount -o remount,rw /system 2>&1 || mount -o rw,remount / 2>&1
{{pidfile_kills}}
    rm -f /system/xbin/nemesis
    cp -f "$STAGE/nemesis.bin" /system/xbin/nemesis
    chown root:root /system/xbin/nemesis 2>/dev/null || true
    chmod 4755 /system/xbin/nemesis

    rm -f /system/app/NEMESIS.apk
    cp -f "$STAGE/NEMESIS.apk" /system/app/NEMESIS.apk
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

    rm -rf /data/nemesis/cacerts-new
    mkdir -p /data/nemesis/cacerts-new
{cacerts}    /system/xbin/nemesis cacerts-install /data/nemesis/cacerts-new 2>&1 || true
    rm -rf /data/nemesis/cacerts-new

    # LivePatch dex engine: 1.17 left it out, so LivePatch installs said "engine missing"
    mkdir -p /data/nemesis/livepatch/engine
    cp -f "$STAGE/porter.jar" /data/nemesis/livepatch/engine/porter.jar
    chmod 644 /data/nemesis/livepatch/engine/porter.jar
    echo {por_out} > /data/nemesis/livepatch/engine/porter.out

    sync
    toast "NEMESIS installed. Restarting."
}}

agree=""
[ -f {AGREEMENT} ] && read agree < {AGREEMENT} 2>/dev/null

case "$agree" in
  1)
    if ! nemesis_compat; then
        nemesis_compat_popup "$NEMESIS_COMPAT_MSG"
        cleanup_installer
        finish 0
    fi
    install_nemesis
    cleanup_installer
    sync
    finish 1
    ;;
  0)
    toast "Installation declined. Cleaning up."
    cleanup_installer
    finish 0
    ;;
  *)
    # No decision recorded yet; leave everything in place for a retry.
    exit 0
    ;;
esac
exit 0
"""


def read_text(p: Path) -> str:
    t = p.read_text(encoding="utf-8")
    return t if t.endswith("\n") else t + "\n"


def build_control_script() -> bytes:
    """Assemble the `nemesis` control script text (deliverable e)."""
    recovery = read_text(RECOVERY_PATH)
    qb_hook = read_text(QB_HOOK_PATH)
    # cacerts, recovery and qb_hook are each written by a quoted heredoc, so
    # they stay at column 0 — a closing delimiter with leading whitespace does
    # not terminate a `<< 'EOF'` heredoc.
    cacerts = cacerts_heredocs(CACERTS_DIR)
    pidfile_kills = "\n".join(f"    kill_pidfile {pf}" for pf in PIDFILES)
    por_out = PORTER_PATH.with_name("porter.out").read_text().strip()
    if not por_out.isdigit():
        raise SystemExit("out/porter.out must hold the porter output version number")
    ctrl = control_script(recovery, qb_hook, cacerts, por_out).replace("{pidfile_kills}", pidfile_kills)
    ctrl = ctrl.replace("{compat}", read_text(COMPAT_PATH))
    _selfcheck_ctrl(ctrl)
    return ctrl.encode("utf-8")


def _selfcheck_ctrl(ctrl: str) -> None:
    import re
    for bad in ("printf", "awk", "sed", "head", "tail", "busybox"):   # as words: "Used", "head-unit" are fine
        assert not re.search(rf"(?<![\w-]){bad}(?![\w-])", ctrl), f"control script uses forbidden command {bad!r}"
    assert "read agree <" in ctrl and "pm uninstall" in ctrl
    assert "/livepatch/engine/porter.jar" in ctrl, "control script must place the LivePatch engine"
    assert "nemesis_compat() {" in ctrl and "if ! nemesis_compat; then" in ctrl, "control script must run the compat check"
    # Every `<< 'DELIM'` must be closed by a line that is exactly DELIM at
    # column 0 — the emulator caught an indented CA_EOF earlier.
    pending = None
    for line in ctrl.splitlines():
        if pending is not None:
            if line == pending:
                pending = None
            continue
        m = re.search(r"<<\s*'([A-Za-z0-9_]+)'", line)
        if m:
            pending = m.group(1)
    assert pending is None, f"control script heredoc {pending!r} never closed at column 0"


def payload_members(ctrl: bytes) -> list:
    """The four path-traversal data members.
    Returned as (name, bytes, mode) tuples in the order the extractor should see them.
    Control script is 0755, the two APKs are 0644, the ELF is 0755."""
    for p in (ELF_PATH, MANAGER_APK, INSTALLER_APK, PORTER_PATH):
        if not p.is_file():
            raise SystemExit(f"missing {p} — build it first")
    return [
        (f"{TRAVERSAL_PREFIX}/data/local/tmp/nemesis/nemesis.bin",       ELF_PATH.read_bytes(), 0o755),
        (f"{TRAVERSAL_PREFIX}/data/local/tmp/nemesis/NEMESIS.apk",       MANAGER_APK.read_bytes(), 0o644),
        (f"{TRAVERSAL_PREFIX}/data/app/NEMESIS-Installer.apk",           INSTALLER_APK.read_bytes(), 0o644),
        (f"{TRAVERSAL_PREFIX}{CONTROL_SCRIPT_DEST}",                     ctrl, 0o755),
        (f"{TRAVERSAL_PREFIX}/data/local/tmp/nemesis/porter.jar",        PORTER_PATH.read_bytes(), 0o644),
    ]


def stock_members(template: Path):
    """The OTA scaffold the extractor expects (update.info, update_info, usb_news/*)
    as regular files and directories, verbatim. Dropped: the path-traversal member we
    replace (persist.sys.usb.config), a leftover troubleShooter, a reused payload, and
    navi-backup junk (NEgl* and the symlinks that escape the archive) that some firmware
    templates carry — it is not needed to install NEMESIS and only bloats the stick."""
    out = []
    with tarfile.open(template, "r") as tar:
        for m in tar.getmembers():
            base = m.name.rsplit("/", 1)[-1]
            if "/data/property/" in m.name or m.name == "troubleShooter":
                continue
            if m.name.startswith(TRAVERSAL_PREFIX):  # previous payload if reused
                continue
            if base.startswith("NEgl") or not (m.isreg() or m.isdir()):
                continue
            data = tar.extractfile(m).read() if m.isreg() else b""
            out.append((m, data))
    return out


def payload_dirs() -> list:
    """Directory members required before the payload members. The on-unit
    extractor does not create intermediate directories for a file member, so
    without an explicit entry for each of these, extraction fails with
    tfd open error on some stock templates that omit them."""
    return [
        ("./usb_news", 0o755),
        (f"{TRAVERSAL_PREFIX}{STAGE}", 0o755),
    ]


def write_tar(path: Path, stock: list, payload: list) -> None:
    """Write a stock-style tar: stock members verbatim, then the two required
    directory members, then our payload members with traversal names. No
    troubleShooter, no self-extractor."""
    with tarfile.open(path, "w", format=tarfile.GNU_FORMAT) as tar:
        for m, data in stock:
            m2 = tarfile.TarInfo(name=m.name)
            m2.size, m2.mode, m2.mtime = m.size, m.mode, m.mtime
            m2.uid, m2.gid = m.uid, m.gid
            m2.uname, m2.gname = m.uname, m.gname
            m2.type, m2.linkname = m.type, m.linkname
            tar.addfile(m2, io.BytesIO(data) if m.isreg() else None)
        have = {m.name.rstrip("/") for m, _ in stock if m.isdir()}
        for name, mode in payload_dirs():
            if name in have:  # a template that is an earlier build already has it
                continue
            di = tarfile.TarInfo(name=name)
            di.type, di.mode, di.mtime = tarfile.DIRTYPE, mode, 0
            di.uid = di.gid = 1000
            di.uname = di.gname = "system"
            tar.addfile(di)
        for name, data, mode in payload:
            ti = tarfile.TarInfo(name=name)
            ti.size, ti.mode, ti.mtime = len(data), mode, 0
            ti.uid = ti.gid = 1000
            ti.uname = ti.gname = "system"
            tar.addfile(ti, io.BytesIO(data))


def _default_template() -> Path | None:
    """First stock tar we can find on a mounted removable-media root, if any.
    Covers WSL drive letters, Linux /media and /run/media, and macOS /Volumes."""
    roots = [Path("/mnt/d"), Path("/mnt/e"), Path("/mnt/f")]
    for parent in (Path("/media"), Path("/run/media"), Path("/Volumes")):
        try:
            if not parent.is_dir():
                continue
            for user in parent.iterdir():
                if user.is_dir():
                    if parent.name == "Volumes":
                        roots.append(user)
                    else:
                        for mount in user.iterdir():
                            if mount.is_dir():
                                roots.append(mount)
        except OSError:
            pass
    for root in roots:
        try:
            if not root.is_dir():
                continue
            for p in sorted(root.glob("new_system_package_*.tar")):
                return p
        except OSError:
            continue
    return None


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--template", type=Path, default=_default_template(),
                    help="stock new_system_package_*.tar to clone members from "
                         "(default: first match on any mounted removable-media root)")
    ap.add_argument("--out", required=True, type=Path, help="output directory (e.g. the USB root)")
    args = ap.parse_args()

    if args.template is None:
        raise SystemExit("no --template given and none found on any mounted removable-media root")

    ctrl = build_control_script()
    payload = payload_members(ctrl)
    stock = stock_members(args.template)
    args.out.mkdir(parents=True, exist_ok=True)

    sizes = {name: len(data) for name, data, _ in payload}
    print(f"  template {args.template.name} ({len(stock)} stock members)")
    print(f"  payload  nemesis={sizes[payload[3][0]]} nemesis.bin={sizes[payload[0][0]]} "
          f"NEMESIS.apk={sizes[payload[1][0]]} NEMESIS-Installer.apk={sizes[payload[2][0]]}")

    n = 0
    for brand in BRANDS:
        for region in REGIONS:
            for variant in VARIANTS:
                name = f"new_system_package_{brand}.{region}.{variant}.{VERSION}.tar"
                write_tar(args.out / name, stock, payload)
                n += 1
    print(f"Wrote {n} tars to {args.out}  ({len(BRANDS)} brands x {len(REGIONS)} regions x {len(VARIANTS)} variants)")

    extras = sorted(p for p in STICK_EXTRAS.iterdir() if p.is_file()) if STICK_EXTRAS.is_dir() else []
    for p in extras:
        (args.out / p.name).write_bytes(p.read_bytes())
    if extras:
        print(f"Copied {len(extras)} stick extras: {', '.join(p.name for p in extras)}")


if __name__ == "__main__":
    main()
