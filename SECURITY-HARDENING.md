# Security hardening (branch `security-hardening`)

Upstream NEMESIS gives root to any process that asks. This branch narrows root to the
callers that need it, without changing how packages, services, or the Manager work.

## Changes

| Hole (upstream) | Fix | Where |
|---|---|---|
| Root socket accepts **any uid**. `peer_uid` was only logged | The daemon accepts only uid 0, uid 1000 (system), and the Manager. The Manager's uid is read from `packages.xml`, and only while its `codePath` is `/system/app/NEMESIS.apk`. Everything else gets `caller not allowed` | `su.c` `peer_allowed`, `util.c` `manager_uid` |
| `/system/xbin/nemesis` is **SUID root** (any non-zygote process, e.g. `adb shell`, gets root without the socket) | Installed `0755`. Boot hooks already run as root via init | `util.c` `ensure_dirs`, `boot/*.sh`, `make_troubleshooter_tar.py` |
| USB `execute`/`install` **auto-confirmed with no prompt** whenever `sys.boot_completed!=1` (a stick inserted at boot, or plugged in during an APK install, ran as root) | The handler holds the stick and prompts once Android is up. Unattended runs need the root-owned flag file `/data/nemesis/usb-recovery` | `usb.c` |
| USB prompt answer read from a **0777** dir (any app could forge "yes") | `usb-session` is `0700`, owned by the Manager uid | `util.c` `secure_session_dir` |
| `DialogActivity` exported with no permission (any app could start `wipe_run`) | Guarded by a `signature` permission. `am start` from usb-handler runs as root and still passes | `manager/AndroidManifest.xml` |
| Staging dir `/data/local/tmp/nemesis` is 0777, and its parent is shell-writable. Uninstall hooks run from there as root | Moved to `/data/nemesis/tmp`, `0711` | `nemesis.h`, `pkg.c` wipe also removes the legacy dir |
| Log file `0666`, logs dir `0777` | `0644` / `0755`. Service logs are opened by root before the uid drop | `util.c` |
| `/data/nemesis/apps` left **0777**: the installer `mkdir -p`s it as root with umask 0, and package hooks in it run as root | `ensure_dirs` resets it to `0755`, and the root CLI runs with `umask(022)` | `util.c`, `main.c` |
| `/data/nemesis/bin` not reset if it already exists. Root package hooks (Leafia) put it first in `PATH` | `ensure_dirs` resets it to `0755`. The emulator test seeds it `0777` | `util.c` |

## Testing on a stock Android 4.4 emulator

`Sources/tests/api19/` boots a stock API 19 x86 emulator in Docker (needs `/dev/kvm`, e.g.
WSL2). It builds the CLI and Manager, installs them the way the troubleshooter does, starts
NEMESIS through the real `install-recovery.sh` boot hook, and runs 14 checks.

```sh
Sources/tests/api19/run.sh            # working tree
Sources/tests/api19/run.sh 167b71d    # upstream, as a baseline
```

| | upstream `167b71d` | this branch |
|---|---|---|
| Result | 6 passed, **8 failed** | **14 passed** |
| Other app / adb shell uid gets root | `uid=0(root)` | refused |
| CLI | `-rwsr-xr-x` | `-rwxr-xr-x` |
| Other uid starts DialogActivity | `Status: ok` | Permission Denial |
| Other app forges USB reply, writes log | yes | no |
| World-writable | `nemesis/`, `apps/`, `logs/`, `usb-session/`, `nemesis.log` | none |
| Stick inserted while Android down | ran as root, no prompt | held, prompted once Android is up |

Callers are simulated with `su <uid>`, which gives the daemon the same `SO_PEERCRED` as an
app with that uid. The emulator has none of HMG's firmware, so it does not cover the DSP OTA
install path, QuickBoot, the OEM signer allowlist, or the projection patches.
The upstream QEMU flow (`tests/emu_nemesis_flow.py`) needs the author's private Gen5W
emulator and was updated but not run.

## Not addressed (still true after this branch)

- **USB payloads are unsigned.** Anyone at the screen can tap "Yes" on a stick. Next step:
  only run payloads whose SHA-256 is listed in a root-owned allowlist, or that carry a
  signature from a key generated on the unit.
- **uid 1000 is trusted.** Any compromise of a system-uid OEM app is still root.
- **The firmware's DSP OTA hole stays open.** It is in HMG's firmware, not NEMESIS.
- **Prebuilt binaries** (`ota_package.tar`, `projection-tbt` APKs/.so, Leafia's patched dex)
  are not rebuilt by this branch. `ota_package.tar` still contains the **old SUID build**.
  You must rebuild it (NDK r21e, `make`) for any of this to take effect.
- **`adb.tcp`** still exposes unauthenticated adbd on all interfaces. Don't install it.
- `/system` is still remounted rw on boot.

## Upgrading an existing install

**Wipe first, then install the rebuilt troubleshooter.** An existing install keeps its old
`install-recovery.sh` and `qb_runtime.sh` hook, and both `chmod 4755` the CLI on every boot.
The QB hook is only appended when its marker is missing, so installing over the top does
not replace it. `wipe` strips both, and removes the old `/data/local/tmp/nemesis`. Back up
`/data/nemesis/backup` first, because wipe deletes it. If the Manager shows
`caller not allowed`, check that `/data/system/packages.xml` lists `com.nemesis.manager` with
`codePath="/system/app/NEMESIS.apk"`.
