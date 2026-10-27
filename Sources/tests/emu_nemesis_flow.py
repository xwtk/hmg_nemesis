#!/usr/bin/env python3
"""Console-only QEMU tests for NEMESIS CLI + USB Handler (no zygote / no Manager UI).

USB insert/eject is simulated the same way the handler sees the car: an entry
for /storage/usb0 in /proc/mounts plus NEMESIS.data on that mount. QEMU has no
Vold/MICOM USB stack; bind-mounting a directory is the contract NEMESIS uses.
"""
from __future__ import annotations

import os
import re
import socket
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EMU = Path("/home/xwtk/EV6_260507/emulator")
sys.path.insert(0, str(Path(__file__).resolve().parent))
from guest import Guest  # noqa: E402

QEMU_PID = None
STARTED = False
RECOVERY_FLAG = "/data/nemesis/usb-recovery"
USB_SIM = "/data/local/tmp/usb-sim"
SESSION_REPLY = "/data/nemesis/usb-session/reply"
SESSION_LOG = "/data/nemesis/logs/usb-session.log"
NEMESIS_LOG = "/data/nemesis/nemesis.log"
WD_PID = "/data/nemesis/watchdog.pid"
SM_PID = "/data/nemesis/svcmgr.pid"
UH_PID = "/data/nemesis/pids/usb-handler+usb-handler"


def port_open() -> bool:
    s = socket.socket()
    s.settimeout(0.5)
    try:
        s.connect(("127.0.0.1", 4446))
        return True
    except OSError:
        return False
    finally:
        s.close()


def make_fixture_zips() -> None:
    out = ROOT / "out"
    out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out / "bad-id.zip", "w") as z:
        z.writestr(
            "NEMESIS.data",
            "[nemesis]\nid=../../../tmp-evil\nname=evil\nversion=1\n",
        )
    with zipfile.ZipFile(out / "zip-slip.zip", "w") as z:
        z.writestr("NEMESIS.data", "[nemesis]\nid=slippkg\nname=slip\nversion=1\n")
        z.writestr("../tmp/pwned", "pwned\n")
    with zipfile.ZipFile(out / "empty-meta.zip", "w") as z:
        z.writestr("README.txt", "no metadata\n")
    (out / "garbage.bin").write_bytes(b"not a zip\x00\x01\x02")
    apk_only = (
        "[nemesis]\nversion=1.0\nid=launch.apk\nname=Launch APK\ntypes=application\n\n"
        "[app.0]\nkind=apk\npath=app/stub.apk\n"
    )
    with_comp = (
        "[nemesis]\nversion=1.0\nid=launch.comp\nname=Launch Component\n"
        "types=application\n\n"
        "[app.0]\nkind=apk\npath=app/stub.apk\n"
        "component=com.dotgears.flappybird/com.dotgears.flappy.SplashScreen\n"
        "package=com.dotgears.flappybird\n"
    )
    for name, meta in (("launch-apk.zip", apk_only), ("launch-comp.zip", with_comp)):
        with zipfile.ZipFile(out / name, "w") as z:
            z.writestr("NEMESIS.data", meta)
            z.writestr("app/stub.apk", b"PK\x03\x04stub")


def stage_cache() -> None:
    img = EMU / "images/cache.img"
    files = [
        (ROOT / "native/nemesis", "nemesis"),
        (ROOT / "out/hello.tool.zip", "hello.tool.zip"),
        (ROOT / "out/hello.tool-v2.zip", "hello.tool-v2.zip"),
        (ROOT / "out/usb-handler.zip", "usb-handler.zip"),
        (ROOT / "out/bad-id.zip", "bad-id.zip"),
        (ROOT / "out/zip-slip.zip", "zip-slip.zip"),
        (ROOT / "out/empty-meta.zip", "empty-meta.zip"),
        (ROOT / "out/garbage.bin", "garbage.bin"),
        (ROOT / "out/launch-apk.zip", "launch-apk.zip"),
        (ROOT / "out/launch-comp.zip", "launch-comp.zip"),
        (ROOT / "boot/install-recovery.sh", "install-recovery.sh"),
        (ROOT / "boot/qb_hook.sh", "qb_hook.sh"),
        (ROOT / "tests/qb_runtime.stock.sh", "qb_runtime.stock.sh"),
        (ROOT / "manager/NEMESIS.apk", "NEMESIS.apk"),
    ]
    cmds = ["cd /"]
    for src, dest in files:
        if not src.is_file():
            fail(f"missing stage file {src}")
        cmds.append(f"rm {dest}")
        cmds.append(f"write {src} {dest}")
    tf = tempfile.NamedTemporaryFile("w", delete=False)
    tf.write("\n".join(cmds) + "\n")
    tf.close()
    subprocess.run(
        ["debugfs", "-w", "-f", tf.name, str(img)],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    os.unlink(tf.name)


def fail(msg: str, out: str = "") -> None:
    print(f"FAIL: {msg}", flush=True)
    if out:
        print("---- output ----", flush=True)
        print(out, flush=True)
    sys.exit(1)


def expect(out: str, needle: str, msg: str) -> None:
    if needle not in out:
        fail(msg, out)
    print(f"OK: {msg}", flush=True)


def expect_not(out: str, needle: str, msg: str) -> None:
    if needle in out:
        fail(msg, out)
    print(f"OK: {msg}", flush=True)


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def assert_qemu(msg: str) -> None:
    if STARTED and QEMU_PID and not _alive(QEMU_PID):
        _qemu_died()
    print(f"OK: QEMU still running ({msg})", flush=True)


def assert_guest(g: Guest, msg: str) -> None:
    out = g.cmd("echo NEMESIS_ALIVE", timeout=12)
    if "NEMESIS_ALIVE" not in out:
        fail(f"guest unresponsive ({msg})", out)
    print(f"OK: guest alive ({msg})", flush=True)


def _qemu_died() -> None:
    log = EMU / "logs/qemu.stderr"
    serial = EMU / "logs/serial.log"
    print("QEMU exited early", flush=True)
    if log.is_file():
        print(log.read_text(errors="replace")[-2000:], flush=True)
    if serial.is_file():
        print(serial.read_text(errors="replace")[-2000:], flush=True)
    sys.exit(1)


def wait_shell(deadline: float) -> Guest:
    last = ""
    while time.time() < deadline:
        if STARTED and QEMU_PID and not _alive(QEMU_PID):
            _qemu_died()
        if port_open():
            try:
                g = Guest()
                out = g.cmd("echo NEMESIS_PING", timeout=12)
                last = out
                if "NEMESIS_PING" in out:
                    return g
                g.close()
            except (OSError, SystemExit) as e:
                last = str(e)
        time.sleep(2)
    fail("timeout waiting for guest shell", last)
    raise SystemExit(1)


def cleanup() -> None:
    if STARTED and QEMU_PID:
        try:
            os.kill(QEMU_PID, 15)
        except OSError:
            pass


def stop_all_qemu() -> None:
    subprocess.run(["pkill", "-f", "qemu-system-arm"], check=False)
    for _ in range(30):
        if not port_open():
            return
        time.sleep(0.5)


def start_qemu() -> None:
    global STARTED, QEMU_PID
    cache = EMU / "images/cache.img"
    print("fsck cache.img...", flush=True)
    subprocess.run(["e2fsck", "-fy", str(cache)], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("staging files into cache.img via debugfs...", flush=True)
    stage_cache()
    print("starting QEMU DISPLAY_MODE=none...", flush=True)
    (EMU / "logs").mkdir(parents=True, exist_ok=True)
    (EMU / "logs/serial.log").write_text("")
    proc = subprocess.Popen(
        ["./run.sh"],
        cwd=str(EMU),
        env={**os.environ, "DISPLAY_MODE": "none"},
        stdout=open(EMU / "logs/qemu.stdout", "w"),
        stderr=open(EMU / "logs/qemu.stderr", "w"),
    )
    QEMU_PID = proc.pid
    STARTED = True


def host_verify_tar() -> None:
    print("== host tar verify ==", flush=True)
    rc = subprocess.run(["sh", str(ROOT / "usb_bundle/verify_tars.sh")], cwd=str(ROOT))
    if rc.returncode != 0:
        fail("verify_tars.sh failed")
    script = subprocess.check_output(
        ["tar", "-xOf", str(ROOT / "usb_bundle/ota_package.tar"), "troubleShooter"]
    )
    text = script.decode("utf-8", errors="replace")
    prefix = text.split("# NEMESIS_PAYLOAD")[0]
    for bad in ("rm -rf /system",):
        if bad in prefix:
            fail(f"troubleshooter bootstrap contains {bad}")
    if any(ln.strip() in ("rm -rf /", "rm -rf /data") for ln in prefix.splitlines()):
        fail("troubleshooter bootstrap contains a dangerous rm -rf")
    print("OK: troubleshooter tar has no rm -rf /system", flush=True)


def install_cli(g: Guest, native: Path) -> None:
    print("installing CLI onto /system...", flush=True)
    for c in (
        "mount -o remount,rw /system",
        "rm -rf /data/nemesis /data/local/tmp/nemesis",
        "mkdir -p /data/nemesis",
        "mkdir -p /data/local/tmp/nemesis",
        "mkdir -p /system/xbin /system/app /system/etc",
        "cp -f /cache/nemesis /system/xbin/nemesis",
        "chmod 4755 /system/xbin/nemesis",
        "cp -f /cache/hello.tool.zip /data/local/tmp/",
        "cp -f /cache/hello.tool-v2.zip /data/local/tmp/",
        "cp -f /cache/usb-handler.zip /data/local/tmp/",
        "cp -f /cache/bad-id.zip /data/local/tmp/",
        "cp -f /cache/zip-slip.zip /data/local/tmp/",
        "cp -f /cache/empty-meta.zip /data/local/tmp/",
        "cp -f /cache/garbage.bin /data/local/tmp/",
        "cp -f /cache/launch-apk.zip /data/local/tmp/",
        "cp -f /cache/launch-comp.zip /data/local/tmp/",
        "cp -f /cache/NEMESIS.apk /system/app/NEMESIS.apk",
        "chmod 644 /system/app/NEMESIS.apk",
        "cp -f /cache/install-recovery.sh /system/etc/install-recovery.sh",
        "chmod 755 /system/etc/install-recovery.sh",
        "cp -f /cache/qb_runtime.stock.sh /system/etc/qb_runtime.sh",
        "chmod 644 /system/etc/qb_runtime.sh",
        "cp -f /system/etc/qb_runtime.sh /cache/qb.orig",
    ):
        print(">>", c, flush=True)
        print(g.cmd(c, timeout=20), flush=True)
    out = g.cmd("ls -l /system/xbin/nemesis", timeout=10)
    print(out, flush=True)
    if "No such file" in out:
        fail("copy to /system/xbin/nemesis failed", out)
    expect(out, str(native.stat().st_size), f"guest ELF size {native.stat().st_size}")
    hook = g.cmd("cat /cache/qb_hook.sh", timeout=10)
    if "NEMESIS_QB_HOOK" not in hook:
        fail("qb_hook.sh missing from cache", hook)
    g.cmd("cat /cache/qb_hook.sh >> /system/etc/qb_runtime.sh", timeout=10)
    out = g.cmd("grep NEMESIS_QB_HOOK /system/etc/qb_runtime.sh", timeout=10)
    expect(out, "NEMESIS_QB_HOOK", "qb_runtime.sh received hook")


def write_text(g: Guest, path: str, text: str) -> None:
    tmp = "/data/local/tmp/nemesis-write.tmp"
    first = True
    lines = text.splitlines() or [""]
    for line in lines:
        esc = line.replace("'", "'\\''")
        redir = ">" if first else ">>"
        first = False
        g.cmd(f"echo '{esc}' {redir} {tmp}", timeout=10)
    out = g.cmd(f"wc -c {tmp}", timeout=10)
    if out.strip().startswith("0 "):
        fail(f"wrote 0-byte file {tmp}", out)
    # toolbox mv -f is unreliable; cp onto the final path after the temp is complete.
    g.cmd(f"cp -f {tmp} {path} && rm -f {tmp}", timeout=10)


def guest_mounts(g: Guest) -> str:
    out = g.cmd("echo MOUNTDUMP; cat /proc/mounts", timeout=10)
    idx = out.rfind("MOUNTDUMP")
    return out[idx:] if idx >= 0 else out


def usb0_is_mounted(g: Guest) -> bool:
    mounts = guest_mounts(g)
    return " /storage/usb0 " in mounts or " /mnt/media_rw/usb0 " in mounts


def usb_eject(g: Guest, require_gone: bool = True) -> None:
    g.cmd("umount -l /storage/usb0 >/dev/null 2>&1 || umount /storage/usb0 >/dev/null 2>&1 || true", timeout=10)
    g.cmd("umount -l /mnt/media_rw/usb0 >/dev/null 2>&1 || umount /mnt/media_rw/usb0 >/dev/null 2>&1 || true", timeout=10)
    deadline = time.time() + (12 if require_gone else 3)
    while time.time() < deadline:
        if not usb0_is_mounted(g):
            return
        time.sleep(0.4)
        g.cmd("umount -l /storage/usb0 >/dev/null 2>&1 || umount /storage/usb0 >/dev/null 2>&1 || true", timeout=10)
    if require_gone and usb0_is_mounted(g):
        fail("failed to eject /storage/usb0", guest_mounts(g))


def usb_insert(g: Guest) -> None:
    """Mount USB_SIM onto /storage/usb0. Caller writes files into USB_SIM first."""
    usb_eject(g)
    g.cmd(f"mkdir -p {USB_SIM} /storage/usb0 /mnt/media_rw/usb0", timeout=10)
    out = g.cmd(f"mount -o bind {USB_SIM} /storage/usb0", timeout=15)
    g.cmd(f"mount -o bind {USB_SIM} /mnt/media_rw/usb0 >/dev/null 2>&1 || true", timeout=15)
    mounts = guest_mounts(g)
    if " /storage/usb0 " not in mounts and " /mnt/media_rw/usb0 " not in mounts:
        g.cmd("mount -t tmpfs -o size=8m tmpfs /storage/usb0", timeout=15)
        g.cmd(f"cp -a {USB_SIM}/. /storage/usb0/ 2>/dev/null || true", timeout=15)
        mounts = guest_mounts(g)
        if " /storage/usb0 " not in mounts:
            fail("could not mount simulated usb0", out + "\n" + mounts)
    print("OK: usb0 mounted", flush=True)
    print(mounts, flush=True)


def session_log(g: Guest) -> str:
    return g.cmd(f"cat {SESSION_LOG} 2>/dev/null || true", timeout=10)


def usb_wait_handled(g: Guest, prev: str, timeout: float = 15.0) -> str:
    """Wait until a new 'handled type=' line is appended. A reread of the same
    log must not count: guest cat output is not byte-stable."""
    deadline = time.time() + timeout
    last = prev
    need = prev.count("handled type=") + 1
    while time.time() < deadline:
        last = session_log(g)
        if last.count("handled type=") >= need:
            return last
        time.sleep(0.3)
    fail(
        "usb-handler did not finish",
        last
        + "\n--- ls /storage/usb0 ---\n"
        + g.cmd("ls -la /storage/usb0 /mnt/media_rw/usb0 2>/dev/null || true", timeout=10)
        + "\n--- NEMESIS.data ---\n"
        + g.cmd("cat /storage/usb0/NEMESIS.data /mnt/media_rw/usb0/NEMESIS.data 2>/dev/null || true", timeout=10)
        + "\n--- mounts ---\n"
        + g.cmd("grep usb0 /proc/mounts || true", timeout=10)
        + "\n--- nemesis.log ---\n"
        + g.cmd(f"cat {NEMESIS_LOG} 2>/dev/null || true", timeout=10),
    )
    return last


def pid_of(g: Guest, path: str) -> str:
    out = g.cmd(f"echo P:$(cat {path} 2>/dev/null)", timeout=10)
    found = re.findall(r"P:(\d+)", out)
    return found[-1] if found else ""


def pid_alive_guest(g: Guest, pid: str) -> bool:
    if not pid or not pid.isdigit():
        return False
    out = g.cmd(f"kill -0 {pid} 2>/dev/null && echo ALIVE || echo DEAD", timeout=10)
    return "ALIVE" in out


def wait_restarted(g: Guest, path: str, old: str, label: str, timeout: int = 20) -> str:
    new = ""
    for _ in range(timeout):
        time.sleep(1)
        new = pid_of(g, path)
        if new and new != old and pid_alive_guest(g, new):
            print(f"OK: {label} restarted (old={old} now={new})", flush=True)
            return new
    fail(f"{label} did not restart after kill", f"old={old} new={new}")
    return new


def _write_stub(g: Guest, name: str) -> None:
    g.cmd(f"mv /system/bin/{name} /data/nemesis/{name}.stock", timeout=15)
    g.cmd(f"echo '#!/system/bin/sh' > /system/bin/{name}", timeout=10)
    g.cmd(
        f"echo 'echo {name}:$* >> /data/nemesis/launch.log' >> /system/bin/{name}",
        timeout=10,
    )
    if name == "pm":
        # KitKat pm prints Success/Failure and exits 0 either way. NEMESIS
        # trusts the text, so the stub must say Success on install.
        g.cmd(
            "echo 'case \"$1\" in install) echo Success;; esac' >> /system/bin/pm",
            timeout=10,
        )
    g.cmd(f"echo 'exit 0' >> /system/bin/{name}", timeout=10)
    g.cmd(f"chmod 755 /system/bin/{name}", timeout=10)


def _restore_stub(g: Guest, name: str) -> None:
    g.cmd(f"rm -f /system/bin/{name}", timeout=10)
    g.cmd(f"mv /data/nemesis/{name}.stock /system/bin/{name}", timeout=15)
    g.cmd(f"chmod 755 /system/bin/{name}", timeout=10)


def run_pkg_launch_apk_tests(g: Guest) -> None:
    """kind=apk alone has nothing to launch. component= runs am start -n."""
    print("== pkg-launch android app ==", flush=True)
    g.cmd("/system/xbin/nemesis remount rw", timeout=20)
    monkey = g.cmd("ls /system/bin/monkey >/dev/null 2>&1 && echo HAVE || echo MISS", timeout=10)
    have_monkey = "HAVE" in monkey
    _write_stub(g, "am")
    _write_stub(g, "pm")
    if have_monkey:
        _write_stub(g, "monkey")
    try:
        g.cmd("rm -f /data/nemesis/launch.log", timeout=10)
        out = g.cmd("/system/xbin/nemesis install launch-apk.zip", timeout=40)
        print(out, flush=True)
        expect(out, "ok installed launch.apk", "install apk-only package")
        g.cmd("rm -f /data/nemesis/launch.log", timeout=10)
        out = g.cmd("/system/xbin/nemesis pkg-launch launch.apk; echo LAUNCH_RC=$?", timeout=20)
        print(out, flush=True)
        expect(out, "package has nothing to launch: launch.apk", "apk without component does not launch")
        expect(out, "LAUNCH_RC=1", "apk-only launch fails")
        log = g.cmd("cat /data/nemesis/launch.log 2>/dev/null || echo NO_LAUNCH_LOG", timeout=10)
        body = "\n".join(
            ln for ln in log.splitlines()
            if "com.nemesis.manager/.MainActivity" not in ln and ln.strip() != "NO_LAUNCH_LOG"
        ).strip()
        if body:
            fail("apk-only launch called am", body)
        print("OK: apk-only launch did not call am", flush=True)
        g.cmd("/system/xbin/nemesis uninstall launch.apk", timeout=30)

        g.cmd("rm -f /data/nemesis/launch.log", timeout=10)
        out = g.cmd("/system/xbin/nemesis install launch-comp.zip", timeout=40)
        print(out, flush=True)
        expect(out, "ok installed launch.comp", "install component package")
        g.cmd("rm -f /data/nemesis/launch.log", timeout=10)
        out = g.cmd("/system/xbin/nemesis pkg-launch launch.comp; echo LAUNCH_RC=$?", timeout=20)
        print(out, flush=True)
        expect(out, "ok launched launch.comp", "component package launches")
        expect(out, "LAUNCH_RC=0", "component launch succeeds")
        log = g.cmd("cat /data/nemesis/launch.log", timeout=10)
        print(log, flush=True)
        expect(log, "am:start -n", "launch ran am start -n")
        expect(
            log,
            "com.dotgears.flappybird/com.dotgears.flappy.SplashScreen",
            "launch targeted the Flappy Bird activity",
        )
        expect_not(log, "monkey:", "component launch did not call monkey")
        g.cmd("echo '#!/system/bin/sh' > /system/bin/am", timeout=10)
        g.cmd("echo 'echo am:$* >> /data/nemesis/launch.log' >> /system/bin/am", timeout=10)
        g.cmd("echo 'exit 1' >> /system/bin/am", timeout=10)
        g.cmd("chmod 755 /system/bin/am", timeout=10)
        g.cmd("rm -f /data/nemesis/launch.log", timeout=10)
        out = g.cmd("/system/xbin/nemesis pkg-launch launch.comp; echo LAUNCH_RC=$?", timeout=20)
        print(out, flush=True)
        expect(out, "activity launch failed:", "failed am start is reported")
        expect(out, "LAUNCH_RC=1", "failed am start does not count as launched")
        if "ok launched" in out:
            fail("failed am start still printed ok launched", out)
        g.cmd("/system/xbin/nemesis uninstall launch.comp", timeout=30)
        listed = g.cmd("/system/xbin/nemesis pkg-list", timeout=15)
        if "launch.apk|" in listed or "launch.comp|" in listed:
            fail("launch fixtures still installed", listed)
        print("OK: launch fixtures removed", flush=True)

        # HMG blocks new installs while sys.boot_completed=1. NEMESIS clears it
        # for the pm call only and must put the previous value back. The lab
        # normally has boot_completed unset; leave it that way for later tests.
        orig_bc = g.cmd(
            "getprop sys.boot_completed; echo BC_END",
            timeout=10,
        )
        orig_bc = "\n".join(
            ln.strip() for ln in orig_bc.splitlines()
            if ln.strip() and ln.strip() != "BC_END"
        ).strip()
        g.cmd("setprop sys.boot_completed 1", timeout=10)
        before = g.cmd("getprop sys.boot_completed", timeout=10)
        expect(before, "1", "boot_completed set before apk install")
        g.cmd("rm -f /data/nemesis/launch.log", timeout=10)
        out = g.cmd("/system/xbin/nemesis install launch-comp.zip", timeout=40)
        print(out, flush=True)
        expect(out, "ok installed launch.comp", "install with boot_completed cleared briefly")
        after = g.cmd("getprop sys.boot_completed", timeout=10)
        expect(after, "1", "boot_completed restored after apk install")
        nlog = g.cmd("grep cleared.sys.boot_completed /data/nemesis/nemesis.log", timeout=10)
        print(nlog, flush=True)
        expect(nlog, "cleared sys.boot_completed for install", "install cleared boot_completed")
        g.cmd("/system/xbin/nemesis uninstall launch.comp", timeout=30)
        if orig_bc:
            g.cmd(f"setprop sys.boot_completed {orig_bc}", timeout=10)
        else:
            # Property cannot be deleted; empty string is the lab default.
            g.cmd("setprop sys.boot_completed ''", timeout=10)
        print("OK: boot_completed restored after apk install", flush=True)
    finally:
        _restore_stub(g, "am")
        _restore_stub(g, "pm")
        if have_monkey:
            _restore_stub(g, "monkey")


def run_cli_tests(g: Guest) -> None:
    print("== stale watchdog pid must not block boot ==", flush=True)
    g.cmd("mkdir -p /data/nemesis", timeout=10)
    g.cmd("echo '1 999999999' > /data/nemesis/watchdog.pid", timeout=10)
    out = g.cmd("/system/etc/install-recovery.sh; echo RECOVERY_RC=$?", timeout=40)
    expect(out, "RECOVERY_RC=0", "install-recovery exits with a stale watchdog pid")
    wd = ""
    for _ in range(15):
        time.sleep(1)
        wd = pid_of(g, WD_PID)
        if wd and wd != "1" and pid_alive_guest(g, wd):
            break
    if not wd or wd == "1" or not pid_alive_guest(g, wd):
        fail(
            "watchdog did not replace stale pid 1",
            f"pid={wd}\n" + g.cmd(f"cat {NEMESIS_LOG} 2>/dev/null || true", timeout=10),
        )
    exe = g.cmd(f"ls -l /proc/{wd}/exe 2>/dev/null || true", timeout=10)
    expect(exe, "nemesis", "replacement watchdog is /system/xbin/nemesis")
    log = g.cmd(f"cat {NEMESIS_LOG} 2>/dev/null || true", timeout=10)
    expect(log, "stale pidfile", "stale watchdog pidfile was logged")
    out = g.cmd("/system/xbin/nemesis ping; echo PING_RC=$?", timeout=15)
    expect(out, "PING_RC=0", "watchdog socket answers ping after stale start")
    print(f"OK: watchdog replaced stale pid with {wd}", flush=True)

    print("starting watchdog via install-recovery (init oneshot path)...", flush=True)
    out = g.cmd("/system/etc/install-recovery.sh; echo RECOVERY_RC=$?", timeout=20)
    expect(out, "RECOVERY_RC=0", "install-recovery.sh exits 0")
    wd = ""
    for _ in range(12):
        time.sleep(1)
        wd = pid_of(g, WD_PID)
        if pid_alive_guest(g, wd):
            break
    if not pid_alive_guest(g, wd):
        fail("watchdog not alive after install-recovery.sh exited", f"pid={wd}")
    print(f"OK: watchdog alive after oneshot exit pid={wd}", flush=True)
    out = g.cmd("grep dalvik-cache /system/etc/install-recovery.sh || echo NO_BOOT_ODEX", timeout=10)
    expect(out, "NO_BOOT_ODEX", "install-recovery does not clear odex every boot")
    g.cmd(f"kill -HUP '{wd}' || true", timeout=10)
    time.sleep(1)
    if not pid_alive_guest(g, wd):
        fail("watchdog died on SIGHUP (init oneshot would kill it)")
    print("OK: watchdog ignored SIGHUP", flush=True)
    g.cmd("cd /data/local/tmp", timeout=10)
    out = g.cmd("ls -l /system/xbin/nemesis", timeout=10)
    expect_not(out, "rws", "nemesis CLI is not SUID")
    out = g.cmd("/system/xbin/nemesis pkg-list", timeout=20)
    expect(out, "usb-handler", "seeded locked usb-handler")
    expect(out, "usb-handler|USB Handler|1|", "usb-handler is locked")

    print("== root daemon client (nosuid /data, like the Manager app) ==", flush=True)
    out = g.cmd(
        "ls -l /dev/nemesis.sock /data/nemesis/root.sock 2>/dev/null; echo SOCK_LS_DONE",
        timeout=10,
    )
    if "nemesis.sock" not in out and "root.sock" not in out:
        fail("watchdog did not bind a root socket", out)
    g.cmd(
        "cp /system/xbin/nemesis /data/local/tmp/nemesis-app; chmod 0755 /data/local/tmp/nemesis-app",
        timeout=10,
    )
    out = g.cmd(
        "/system/xbin/nemesis root 1000 -c '/data/local/tmp/nemesis-app pkg-list'; echo SYS_RC=$?",
        timeout=20,
    )
    if "daemon not running" in out:
        fail("system-uid client pkg-list", out)
    expect(out, "usb-handler|USB Handler|1|", "pkg-list via daemon as uid 1000")
    expect(out, "SYS_RC=0", "system uid allowed")
    # Only root, system and the /system/app Manager may use the daemon.
    for uid in (2000, 10208, 12345):
        out = g.cmd(
            f"/system/xbin/nemesis root {uid} -c '/data/local/tmp/nemesis-app pkg-list'; echo DENY_RC=$?",
            timeout=20,
        )
        expect(out, "caller not allowed", f"uid {uid} refused by daemon")
        expect_not(out, "DENY_RC=0", f"uid {uid} client exits non-zero")
    out = g.cmd(
        "grep -o '<package name=\"com.nemesis.manager\"[^>]*' /data/system/packages.xml; echo PX_DONE",
        timeout=10,
    )
    m = re.search(r' userId="(\d+)"', out)
    if m and 'codePath="/system/app/NEMESIS.apk"' in out:
        out = g.cmd(
            f"/system/xbin/nemesis root {m.group(1)} -c '/data/local/tmp/nemesis-app pkg-list'; echo MGR_RC=$?",
            timeout=20,
        )
        expect(out, "MGR_RC=0", "Manager uid allowed")
    else:
        print("SKIP: Manager not registered in packages.xml", flush=True)

    print("== qb_runtime hook must return and keep watchdog ==", flush=True)
    t0 = time.time()
    out = g.cmd(
        "/system/bin/sh /system/etc/qb_runtime.sh not_a_real_func >/cache/qb_hook_test.log 2>&1; echo QB_RC=$?",
        timeout=20,
    )
    expect(out, "QB_RC=0", "qb_runtime.sh with hook exits")
    if time.time() - t0 > 15:
        fail("qb_runtime.sh hung after hook", out)
    wd2 = pid_of(g, WD_PID)
    if not pid_alive_guest(g, wd2):
        fail("watchdog died during qb_runtime.sh")
    print("OK: qb hook did not kill watchdog", flush=True)

    print("== install-recovery must return immediately when already running ==", flush=True)
    t0 = time.time()
    out = g.cmd("/system/etc/install-recovery.sh; echo RECOVERY_RC=$?", timeout=20)
    expect(out, "RECOVERY_RC=0", "install-recovery.sh exits 0")
    if time.time() - t0 > 8:
        fail("install-recovery.sh blocked in watchdog loop", out)
    assert_guest(g, "after install-recovery.sh")
    assert_qemu("after install-recovery.sh")

    print("== fresh install ==", flush=True)
    out = g.cmd("/system/xbin/nemesis install hello.tool.zip", timeout=40)
    print(out, flush=True)
    expect(out, "ok installed hello.tool", "fresh install hello.tool")
    out = g.cmd("cat /data/nemesis/hello-pre.flag", timeout=10)
    expect(out, "pre", "preinstall hook ran")
    out = g.cmd("cat /data/nemesis/hello-post.flag", timeout=10)
    expect(out, "post", "postinstall hook ran")
    out = g.cmd("/system/xbin/nemesis service status hello.tool/hello-daemon", timeout=15)
    expect(out, "hello.tool/hello-daemon", "prefixed service name")
    out = g.cmd("/system/xbin/nemesis pkg-launch hello.tool", timeout=20)
    expect(out, "ok launched hello.tool", "pkg-launch hello.tool")
    out = g.cmd("/system/xbin/nemesis log dump", timeout=15)
    if "unknown log source" in out:
        fail("log dump", out)
    print("OK: log dump", flush=True)

    print("== already installed without --update ==", flush=True)
    out = g.cmd("/system/xbin/nemesis install hello.tool.zip", timeout=20)
    expect(out, "already installed", "install without --update refused")

    print("== update ==", flush=True)
    out = g.cmd("/system/xbin/nemesis install --update hello.tool-v2.zip", timeout=40)
    expect(out, "ok updated hello.tool", "update with --update")
    out = g.cmd("cat /data/nemesis/hello-preun.flag", timeout=10)
    expect(out, "preun", "old preuninstall ran on update")
    out = g.cmd("cat /data/nemesis/hello-postun.flag", timeout=10)
    expect(out, "postun", "old postuninstall ran on update")
    out = g.cmd("cat /data/nemesis/hello-post.flag", timeout=10)
    expect(out, "post2", "new postinstall ran on update")

    print("== uninstall hooks ==", flush=True)
    out = g.cmd("/system/xbin/nemesis uninstall hello.tool", timeout=30)
    print(out, flush=True)
    expect(out, "uninstalled hello.tool", "uninstall hello.tool")
    out = g.cmd("cat /data/nemesis/hello-preun.flag", timeout=10)
    expect(out, "preun", "preuninstall on uninstall")
    out = g.cmd("cat /data/nemesis/hello-postun.flag", timeout=10)
    expect(out, "postun", "postuninstall on uninstall")
    out = g.cmd("/system/xbin/nemesis pkg-list", timeout=15)
    listed = [ln for ln in out.splitlines() if ln.startswith("hello.tool|")]
    if listed:
        fail("hello.tool still listed after uninstall", out)
    print("OK: hello.tool removed from pkg-list", flush=True)

    run_pkg_launch_apk_tests(g)

    print("== locked usb-handler ==", flush=True)
    out = g.cmd("/system/xbin/nemesis uninstall usb-handler", timeout=20)
    expect(out, "package is locked", "uninstall usb-handler refused")
    out = g.cmd("/system/xbin/nemesis install --update usb-handler.zip", timeout=20)
    expect(out, "cannot be updated", "update usb-handler refused")
    assert_guest(g, "after CLI package tests")
    assert_qemu("after CLI package tests")


def run_corner_tests(g: Guest) -> None:
    print("== corner: bad CLI args ==", flush=True)
    out = g.cmd("/system/xbin/nemesis wipe", timeout=15)
    expect(out, "wipe --confirm", "wipe without --confirm refused")
    out = g.cmd("/system/xbin/nemesis install /data/local/tmp/missing.zip", timeout=15)
    expect(out, "missing zip", "install missing zip refused")
    out = g.cmd("/system/xbin/nemesis install /data/local/tmp/garbage.bin", timeout=15)
    expect(out, "invalid package metadata", "garbage zip refused")
    out = g.cmd("/system/xbin/nemesis install /data/local/tmp/empty-meta.zip", timeout=15)
    expect(out, "invalid package metadata", "zip without NEMESIS.data refused")
    out = g.cmd("/system/xbin/nemesis install /data/local/tmp/bad-id.zip", timeout=15)
    expect(out, "invalid package id", "path-traversal package id refused")
    out = g.cmd("/system/xbin/nemesis install /data/local/tmp/zip-slip.zip", timeout=15)
    if "unzip failed" not in out and "invalid package metadata" not in out:
        fail("zip-slip members should be refused", out)
    print("OK: zip-slip members refused", flush=True)
    out = g.cmd("ls /tmp/pwned /data/tmp/pwned 2>/dev/null || true", timeout=10)
    expect_not(out, "pwned", "zip-slip did not write outside stage")
    out = g.cmd("/system/xbin/nemesis uninstall not-installed", timeout=15)
    expect(out, "package not installed", "uninstall unknown package refused")
    out = g.cmd("/system/xbin/nemesis service start nosuch/svc", timeout=15)
    expect(out, "unknown service", "unknown service refused")
    out = g.cmd("/system/xbin/nemesis pkg-info /data/local/tmp/hello.tool.zip", timeout=15)
    expect(out, "id=hello.tool", "pkg-info hello.tool")
    out = g.cmd("/system/xbin/nemesis root -c 'echo ROOT_OK'", timeout=15)
    expect(out, "ROOT_OK", "nemesis root -c works")
    out = g.cmd("/system/xbin/nemesis remount rw; echo REMOUNT_RC=$?", timeout=15)
    expect(out, "REMOUNT_RC=0", "nemesis remount rw")
    expect(out, "remount /system rw ok", "nemesis remount rw printed ok")
    out = g.cmd("/system/xbin/nemesis root -c 'mount -o remount,rw /system'; echo MNT_RC=$?", timeout=15)
    expect(out, "MNT_RC=0", "root mount remount /system")
    if "Operation not permitted" in out:
        fail("toolbox remount still EPERM via nemesis root", out)
    print("OK: remount via CLI and intercepted mount", flush=True)

    print("== corner: second watchdog exits ==", flush=True)
    wd = pid_of(g, WD_PID)
    if not wd:
        fail("watchdog pidfile missing before second start")
    t0 = time.time()
    out = g.cmd("/system/xbin/nemesis watchdog >/cache/wd2.log 2>&1; echo WD2_RC=$?", timeout=8)
    expect(out, "WD2_RC=0", "second watchdog exits instead of taking over")
    if time.time() - t0 > 5:
        fail("second watchdog blocked in the main loop (took over)", out)
    wd2 = pid_of(g, WD_PID)
    if wd2 and wd != wd2:
        fail(f"second watchdog replaced pid {wd} -> {wd2}")
    print("OK: second watchdog did not replace the running one", flush=True)
    assert_guest(g, "after corner cases")
    assert_qemu("after corner cases")


def run_usb_tests(g: Guest) -> None:
    print("== USB: wait for handler ==", flush=True)
    deadline = time.time() + 20
    while time.time() < deadline:
        log = g.cmd(f"cat {NEMESIS_LOG} 2>/dev/null || true", timeout=10)
        if "usb-handler start" in log:
            break
        time.sleep(1)
    else:
        fail("usb-handler never logged start", g.cmd(f"cat {NEMESIS_LOG} 2>/dev/null || true", timeout=10))
    print("OK: usb-handler started", flush=True)
    usb_eject(g)

    print("== USB: mount with no NEMESIS.data is idle ==", flush=True)
    g.cmd(f"rm -rf {USB_SIM}; mkdir -p {USB_SIM}", timeout=10)
    write_text(g, f"{USB_SIM}/NEMESIS.sh", "#!/system/bin/sh\necho should-not-run > /data/nemesis/bare-sh.ok\n")
    prev = session_log(g)
    usb_insert(g)
    time.sleep(4)
    log = session_log(g)
    if log != prev and "handled type=" in log[len(prev) :]:
        fail("bare NEMESIS.sh without NEMESIS.data was handled", log)
    bare = g.cmd("cat /data/nemesis/bare-sh.ok 2>/dev/null || true", timeout=10)
    expect_not(bare, "should-not-run", "bare NEMESIS.sh ignored")
    print("OK: USB without NEMESIS.data stays idle", flush=True)

    print("== USB: NEMESIS.data appears while already mounted (type=open) ==", flush=True)
    prev = session_log(g)
    write_text(
        g,
        f"{USB_SIM}/NEMESIS.data",
        "[nemesis]\ntype=open\nid=open-manager\nname=Open NEMESIS Manager\nversion=1\ncompat=gen5w\n",
    )
    g.cmd(f"cp -f {USB_SIM}/NEMESIS.data /storage/usb0/NEMESIS.data 2>/dev/null || true", timeout=10)
    log = usb_wait_handled(g, prev)
    expect(log, "handled type=open", "type=open handled")
    expect(log, "reply=skip-ui", "open skipped while Android is down")
    nlog = g.cmd(f"grep 'cannot open Manager' {NEMESIS_LOG} || true", timeout=10)
    expect(nlog, "cannot open Manager", "open does not block on am when Android is down")
    assert_qemu("after type=open")

    print("== USB: same descriptor is not handled twice ==", flush=True)
    prev = session_log(g)
    time.sleep(4)
    log = session_log(g)
    extra = log[len(prev) :] if log.startswith(prev) else log
    if "handled type=" in extra:
        fail("handler re-ran the same NEMESIS.data without a change", log)
    print("OK: same insertion is not handled twice", flush=True)

    print("== USB: replacing NEMESIS.data without eject re-handles ==", flush=True)
    write_text(
        g,
        f"{USB_SIM}/NEMESIS.sh",
        "#!/system/bin/sh\necho replace-ok > /data/nemesis/usb-replace.ok\nexit 0\n",
    )
    g.cmd(f"cp -f {USB_SIM}/NEMESIS.sh /storage/usb0/NEMESIS.sh 2>/dev/null || true", timeout=10)
    prev = session_log(g)
    write_text(
        g,
        f"{USB_SIM}/NEMESIS.data",
        "[nemesis]\ntype=execute\npath=NEMESIS.sh\nversion=1\n",
    )
    g.cmd(f"cp -f {USB_SIM}/NEMESIS.data /storage/usb0/NEMESIS.data 2>/dev/null || true", timeout=10)
    log = usb_wait_handled(g, prev)
    expect(log, "handled type=execute", "replaced descriptor handled without eject")
    out = g.cmd("cat /data/nemesis/usb-replace.ok 2>/dev/null || true", timeout=10)
    expect(out, "replace-ok", "execute ran after in-place NEMESIS.data replace")
    print("OK: descriptor change re-arms the handler", flush=True)

    print("== USB: android down without recovery flag holds the stick ==", flush=True)
    usb_eject(g)
    g.cmd(f"rm -f {RECOVERY_FLAG} /data/nemesis/usb-exec.ok", timeout=10)
    g.cmd(f"rm -rf {USB_SIM}; mkdir -p {USB_SIM}", timeout=10)
    write_text(
        g,
        f"{USB_SIM}/NEMESIS.data",
        "[nemesis]\nversion=1.0\ntype=execute\npath=NEMESIS.sh\n",
    )
    write_text(
        g,
        f"{USB_SIM}/NEMESIS.sh",
        "#!/system/bin/sh\necho held-ran > /data/nemesis/usb-exec.ok\nexit 0\n",
    )
    prev = session_log(g)
    usb_insert(g)
    log = usb_wait_handled(g, prev, timeout=6)
    if log.count("handled type=") > prev.count("handled type="):
        fail("stick handled while Android down and no recovery flag", log)
    out = g.cmd("cat /data/nemesis/usb-exec.ok 2>/dev/null; echo HELD_DONE", timeout=10)
    expect_not(out, "held-ran", "execute did not run unattended")
    g.cmd(f"touch {RECOVERY_FLAG}", timeout=10)
    log = usb_wait_handled(g, prev, timeout=15)
    expect(log, "reply=recovery", "held stick runs once the owner sets the recovery flag")

    print("== USB: eject + reinsert execute (android-down recovery) ==", flush=True)
    usb_eject(g)
    g.cmd(f"rm -rf {USB_SIM}; mkdir -p {USB_SIM}", timeout=10)
    write_text(
        g,
        f"{USB_SIM}/NEMESIS.data",
        "[nemesis]\nversion=1.0\ntype=execute\npath=NEMESIS.sh\n",
    )
    write_text(
        g,
        f"{USB_SIM}/NEMESIS.sh",
        "#!/system/bin/sh\nmkdir -p /data/nemesis\necho execute-ok > /data/nemesis/usb-exec.ok\nexit 0\n",
    )
    prev = session_log(g)
    t0 = time.time()
    usb_insert(g)
    log = usb_wait_handled(g, prev, timeout=15)
    elapsed = time.time() - t0
    expect(log, "handled type=execute", "type=execute handled")
    expect(log, "reply=recovery", "execute auto-confirmed while Android is down")
    out = g.cmd("cat /data/nemesis/usb-exec.ok", timeout=10)
    expect(out, "execute-ok", "execute script ran as root without Manager")
    if elapsed > 20:
        fail(f"recovery execute took too long ({elapsed:.1f}s) — handler likely blocked on am")
    print(f"OK: recovery execute in {elapsed:.1f}s (not blocked on Android)", flush=True)

    print("== USB: unplug during a running script does not kill the guest ==", flush=True)
    usb_eject(g)
    g.cmd("rm -f /data/nemesis/usb-exec.ok", timeout=10)
    write_text(
        g,
        f"{USB_SIM}/NEMESIS.sh",
        "#!/system/bin/sh\nsleep 8\necho late > /data/nemesis/usb-exec.ok\nexit 0\n",
    )
    prev = session_log(g)
    usb_insert(g)
    time.sleep(1)
    usb_eject(g, require_gone=False)
    assert_guest(g, "after unplug during execute")
    assert_qemu("after unplug during execute")
    log = usb_wait_handled(g, prev, timeout=12)
    expect(log, "handled type=execute", "execute still recorded after unplug")

    print("== USB: invalid type / traversal / missing execute file ==", flush=True)
    usb_eject(g)
    g.cmd(f"rm -rf {USB_SIM}; mkdir -p {USB_SIM}", timeout=10)
    write_text(g, f"{USB_SIM}/NEMESIS.data", "[nemesis]\ntype=explode\npath=NEMESIS.sh\n")
    prev = session_log(g)
    usb_insert(g)
    log = usb_wait_handled(g, prev)
    expect(log, "handled type=explode", "unknown type handled as invalid")
    usb_eject(g)
    write_text(g, f"{USB_SIM}/NEMESIS.data", "[nemesis]\ntype=execute\npath=../../system/bin/reboot\n")
    prev = session_log(g)
    usb_insert(g)
    log = usb_wait_handled(g, prev)
    expect(log, "handled type=execute", "path traversal still handled (rejected)")
    assert_qemu("reboot path traversal did not kill QEMU")
    usb_eject(g)
    write_text(g, f"{USB_SIM}/NEMESIS.data", "[nemesis]\ntype=execute\npath=/system/bin/reboot\n")
    prev = session_log(g)
    usb_insert(g)
    log = usb_wait_handled(g, prev)
    expect(log, "handled type=execute", "absolute execute path rejected")
    assert_qemu("absolute reboot path did not kill QEMU")
    usb_eject(g)
    write_text(g, f"{USB_SIM}/NEMESIS.data", "[nemesis]\ntype=execute\npath=missing.sh\n")
    prev = session_log(g)
    usb_insert(g)
    log = usb_wait_handled(g, prev)
    expect(log, "handled type=execute", "missing execute script handled")
    usb_eject(g)
    write_text(g, f"{USB_SIM}/NEMESIS.data", "this is not ini {{{")
    prev = session_log(g)
    usb_insert(g)
    log = usb_wait_handled(g, prev)
    expect(log, "handled type=", "malformed NEMESIS.data handled as invalid")

    print("== USB: type=install (android-down recovery) ==", flush=True)
    usb_eject(g)
    g.cmd(f"rm -rf {USB_SIM}; mkdir -p {USB_SIM}/packages", timeout=10)
    write_text(
        g,
        f"{USB_SIM}/NEMESIS.data",
        "[nemesis]\nversion=1.0\ntype=install\npath=packages/hello.tool.zip\n",
    )
    g.cmd(f"cp -f /cache/hello.tool.zip {USB_SIM}/packages/hello.tool.zip", timeout=10)
    prev = session_log(g)
    usb_insert(g)
    log = usb_wait_handled(g, prev, timeout=25)
    expect(log, "handled type=install", "type=install handled")
    expect(log, "reply=recovery", "install auto-confirmed while Android is down")
    out = g.cmd("/system/xbin/nemesis pkg-list", timeout=15)
    expect(out, "hello.tool|", "hello.tool installed via USB")
    out = g.cmd("cat /data/nemesis/hello-pre.flag", timeout=10)
    expect(out, "pre", "USB install ran preinstall hook")

    print("== USB: type=install already present auto-updates when Android is down ==", flush=True)
    usb_eject(g)
    prev = session_log(g)
    usb_insert(g)
    log = usb_wait_handled(g, prev, timeout=25)
    expect(log, "handled type=install", "install-already-present handled")
    out = g.cmd("/system/xbin/nemesis pkg-list", timeout=15)
    expect(out, "hello.tool|", "hello.tool still installed after recovery update")

    print("== USB: type=install locked usb-handler ==", flush=True)
    usb_eject(g)
    g.cmd(f"rm -rf {USB_SIM}; mkdir -p {USB_SIM}", timeout=10)
    write_text(
        g,
        f"{USB_SIM}/NEMESIS.data",
        "[nemesis]\nversion=1.0\ntype=install\npath=usb-handler.zip\n",
    )
    g.cmd(f"cp -f /cache/usb-handler.zip {USB_SIM}/usb-handler.zip", timeout=10)
    prev = session_log(g)
    usb_insert(g)
    log = usb_wait_handled(g, prev, timeout=15)
    expect(log, "handled type=install", "locked package install handled")
    out = g.cmd("/system/xbin/nemesis pkg-list", timeout=15)
    expect(out, "usb-handler|USB Handler|1|", "usb-handler still locked after USB install attempt")

    print("== USB: rapid eject/insert ==", flush=True)
    for i in range(3):
        usb_eject(g)
        usb_insert(g)
        time.sleep(0.5)
    usb_eject(g)
    assert_guest(g, "after rapid USB cycles")
    assert_qemu("after rapid USB cycles")
    uh = pid_of(g, UH_PID)
    if uh:
        alive = g.cmd(f"kill -0 {uh} 2>/dev/null && echo UH_ALIVE || echo UH_DEAD", timeout=10)
        expect(alive, "UH_ALIVE", "usb-handler still alive after USB churn")
    print("OK: USB connect/disconnect suite done", flush=True)

    print("== usb-handler restart after USB tests ==", flush=True)
    uh = pid_of(g, UH_PID)
    if not uh:
        fail("usb-handler pidfile missing", g.cmd(f"ls {UH_PID} /data/nemesis/pids 2>/dev/null || true", timeout=10))
    g.cmd(f"kill -9 '{uh}' || true", timeout=10)
    uh = wait_restarted(g, UH_PID, uh, "usb-handler")

    print("== usb-handler survives many kills (locked, no restart limit) ==", flush=True)
    for _ in range(6):
        uh = pid_of(g, UH_PID)
        if not uh:
            fail("usb-handler pidfile missing during restart-limit test")
        g.cmd(f"kill -9 '{uh}' || true", timeout=10)
        uh = wait_restarted(g, UH_PID, uh, "usb-handler")
    print("OK: usb-handler came back after 6 kills", flush=True)

    print("== USB execute still works after handler restart ==", flush=True)
    usb_eject(g)
    g.cmd("rm -f /data/nemesis/usb-exec.ok", timeout=10)
    write_text(
        g,
        f"{USB_SIM}/NEMESIS.data",
        "[nemesis]\nversion=1.0\ntype=execute\npath=NEMESIS.sh\n",
    )
    write_text(
        g,
        f"{USB_SIM}/NEMESIS.sh",
        "#!/system/bin/sh\necho after-restart > /data/nemesis/usb-exec.ok\nexit 0\n",
    )
    prev = session_log(g)
    usb_insert(g)
    log = usb_wait_handled(g, prev, timeout=10)
    expect(log, "handled type=execute", "execute after usb-handler restart")
    expect(log, "reply=recovery", "execute still auto-confirms while Android is down")
    out = g.cmd("cat /data/nemesis/usb-exec.ok", timeout=10)
    expect(out, "after-restart", "USB recovery script ran after handler restart")
    usb_eject(g)
    g.cmd(f"rm -f {RECOVERY_FLAG}", timeout=10)

    print("== stale svcmgr pid must be replaced ==", flush=True)
    sm = pid_of(g, SM_PID)
    if not sm:
        fail("svcmgr pidfile missing before stale-pid test")
    g.cmd(
        f"kill -9 '{sm}' 2>/dev/null; echo '1 999999999' > {SM_PID}; echo STALE_SM",
        timeout=10,
    )
    sm = wait_restarted(g, SM_PID, "1", "svcmgr")
    if sm == "1":
        fail("svcmgr left the stale pid in place", sm)
    uh = pid_of(g, UH_PID)
    if uh:
        alive = g.cmd(f"kill -0 {uh} 2>/dev/null && echo UH_ALIVE || echo UH_DEAD", timeout=10)
        expect(alive, "UH_ALIVE", "usb-handler still alive after stale svcmgr pid")
    print(f"OK: svcmgr replaced stale pid with {sm}", flush=True)

    print("== svcmgr restart; USB Handler must survive ==", flush=True)
    sm = pid_of(g, SM_PID)
    if not sm:
        fail("svcmgr pidfile missing")
    uh = pid_of(g, UH_PID)
    g.cmd(f"kill -9 '{sm}' || true", timeout=10)
    wait_restarted(g, SM_PID, sm, "svcmgr")
    uh2 = ""
    for _ in range(20):
        uh2 = pid_of(g, UH_PID)
        if pid_alive_guest(g, uh2):
            break
        time.sleep(1)
    if not pid_alive_guest(g, uh2):
        fail("usb-handler died with svcmgr and did not return", f"old={uh} now={uh2}")
    print(f"OK: usb-handler alive after svcmgr restart (pid {uh2})", flush=True)

    print("== watchdog restart; stack must remain ==", flush=True)
    wd = pid_of(g, WD_PID)
    sm = pid_of(g, SM_PID)
    uh = pid_of(g, UH_PID)
    if not wd:
        fail("watchdog pidfile missing")
    g.cmd(f"kill -9 '{wd}' || true", timeout=10)
    wait_restarted(g, WD_PID, wd, "watchdog")
    sm2 = pid_of(g, SM_PID)
    if not pid_alive_guest(g, sm2):
        sm2 = wait_restarted(g, SM_PID, sm or "0", "svcmgr")
    uh2 = uh if pid_alive_guest(g, uh) else ""
    if not uh2:
        for _ in range(20):
            uh2 = pid_of(g, UH_PID)
            if pid_alive_guest(g, uh2):
                break
            time.sleep(1)
    if not pid_alive_guest(g, uh2):
        fail("usb-handler not alive after watchdog restart", f"old={uh} now={uh2}")
    print(f"OK: svcmgr+usb-handler alive after watchdog restart (sm={sm2} uh={uh2})", flush=True)


def run_wipe_and_safety(g: Guest) -> None:
    print("== wipe ==", flush=True)
    out = g.cmd("/system/xbin/nemesis wipe --confirm", timeout=40)
    expect(out, "ok wiped", "wipe --confirm")
    gone = g.cmd("ls /system/xbin/nemesis 2>/dev/null || echo GONE", timeout=10)
    print(gone, flush=True)
    expect(gone, "GONE", "CLI removed by wipe")
    out = g.cmd("ls /system/app/NEMESIS.apk 2>/dev/null || echo APK_GONE", timeout=10)
    expect(out, "APK_GONE", "Manager APK removed by wipe")
    out = g.cmd("ls /system/etc/install-recovery.sh 2>/dev/null || echo REC_GONE", timeout=10)
    expect(out, "REC_GONE", "install-recovery.sh removed by wipe")
    out = g.cmd("grep NEMESIS_QB_HOOK /system/etc/qb_runtime.sh || echo HOOK_GONE", timeout=10)
    expect(out, "HOOK_GONE", "qb hook stripped")
    out = g.cmd("cmp /system/etc/qb_runtime.sh /cache/qb.orig && echo QB_RESTORED", timeout=15)
    expect(out, "QB_RESTORED", "qb_runtime.sh matches pre-hook snapshot")
    out = g.cmd("echo WIPE_DONE", timeout=10)
    expect(out, "WIPE_DONE", "guest responded after wipe")
    assert_qemu("after wipe")
    print("\nALL NEMESIS EMULATOR TESTS PASSED", flush=True)


def main() -> None:
    sys.stdout.reconfigure(line_buffering=True)
    native = ROOT / "native/nemesis"
    hello = ROOT / "out/hello.tool.zip"
    if not native.is_file() or not hello.is_file():
        fail("build native + packages first (make -C /home/xwtk/nemesis)")
    if not (EMU / "images/system.qcow2").is_file():
        fail(f"run {EMU}/prepare_images.sh first")

    make_fixture_zips()
    host_verify_tar()

    g = None
    try:
        print("stopping any existing QEMU so cache.img can be restaged", flush=True)
        stop_all_qemu()
        STARTED_reset()
        start_qemu()

        print("waiting for hvc shell (up to 8 min)...", flush=True)
        g = wait_shell(time.time() + 480)
        print("guest shell is up", flush=True)
        serial = EMU / "logs/serial.log"
        if serial.is_file() and "shell@wp_daudioplus_ecokmc_us" in serial.read_text(errors="replace"):
            print("OK: serial reached shell@wp_daudioplus_ecokmc_us", flush=True)
        else:
            print("NOTE: serial prompt not seen yet (hvc is enough for CLI tests)", flush=True)

        listing = g.cmd("ls /cache", timeout=15)
        print("ls /cache =>", listing, flush=True)
        if "hello.tool.zip" not in listing:
            print("cache payload missing; restarting QEMU with staged cache.img", flush=True)
            g.close()
            g = None
            stop_all_qemu()
            STARTED_reset()
            start_qemu()
            print("waiting for hvc shell after restart...", flush=True)
            g = wait_shell(time.time() + 480)
            listing = g.cmd("ls /cache", timeout=15)
            print("ls /cache =>", listing, flush=True)
            if "hello.tool.zip" not in listing:
                fail("cache still missing after QEMU restart", listing)

        install_cli(g, native)
        run_cli_tests(g)
        run_corner_tests(g)
        run_usb_tests(g)
        run_wipe_and_safety(g)
    finally:
        if g:
            g.close()
        cleanup()


def STARTED_reset() -> None:
    global STARTED, QEMU_PID
    STARTED = False
    QEMU_PID = None


if __name__ == "__main__":
    main()
