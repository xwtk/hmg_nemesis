# Installing and uninstalling NEMESIS

This document is the on-disk install and removal procedure. The firmware path that first runs the installer as root is [01 — DSP OTA `troubleShooter`](01-DSP-OTA-troubleShooter.md). After install, day-to-day use is [02 — Debugging Execution Interface](02-Debugging-Execution-Interface.md).

---

## 1. Build the flash tar (PC)

From the Linux NEMESIS tree (ext4; do not build on NTFS/WSL mounts):

```bash
cd /home/xwtk/nemesis
make
sh usb_bundle/verify_tars.sh
```

`make` builds:

- native CLI (`native/nemesis`)
- Manager APK (`manager/NEMESIS.apk`)
- example / USB-handler zips
- **`usb_bundle/ota_package_troubleshooter.tar`**, copied as **`usb_bundle/ota_package.tar`** (identical bytes)

Host checks require a single tar member named `troubleShooter`, a SUID install (`chown root:root` **before** `chmod 4755`), QuickBoot hook marker, dalvik wipe **only in the OTA script**, no `persist.sys.swverify`, no busybox, and `dd` of blobs into a temp file then `cp` onto `/system` (never `dd` onto a live ELF).

Copy **`ota_package.tar`** to the **root** of the USB stick (the name the DSP agent uses).

---

## 2. Flash on the car

1. Engineering Mode → USB Copy → **DSP** → **Update OTA**.
2. Confirm extract: **UNTAR OK** / `OTA_PKG_EXTRACT true`, then `Success to execute,troubleShooter`.
3. **Error 684** afterward is expected (`ota_info` absent). **651** means the tar did not extract.
4. The script shows **Welcome to NEMESIS.**, then **Installing...**, writes `/system`, `sync`s, and shows **NEMESIS installation completed successfully, restarting.** It then runs `/data/nemesis/ota-finish.sh`, which sleeps 5 seconds, deletes `/storage/upgrade/*`, `sync`s, and **reboots**. The delete happens after the script has handed off, so removing `/storage/upgrade` does not cut off the toast.

MAP_PREPARE `execl`s `/storage/upgrade/troubleShooter` as **uid 0 / gid system** **before** RSA validation. See document 01.

### Overlay (NEMESIS already running)

The installer:

- sends TERM/KILL to pids in `/data/nemesis/watchdog.pid`, `svcmgr.pid`, and `pids/usb-handler+usb-handler` (and the old `/data/local/tmp/nemesis/` copies if present)
- slices ELF and APK from `$0` with `dd` into `/data/nemesis/.extract.$$`
- **`rm -f` destination, then `cp -f`** onto `/system/xbin/nemesis` and `/system/app/NEMESIS.apk`

Truncating a running `/system/xbin/nemesis` in place (`dd of=` that path) returns **ETXTBSY** and leaves the old binary. That is why a previous overlay could keep printing `not SUID root (euid=10208)`: the Manager was new, the CLI was not.

Dalvik odex for Manager is deleted **only during this OTA script**, not on every boot.

---

## 3. What is written

| Path | Mode / notes |
|------|----------------|
| `/system/xbin/nemesis` | `0755`, owner `root:root`. Not SUID: non-root callers go through the daemon, which only accepts root, system, and the Manager. `adb shell` (uid 2000) is accepted only if the owner creates the root-owned file `/data/nemesis/allow-adb-root`, e.g. from a prompted USB `execute` script: `touch /data/nemesis/allow-adb-root`. |
| `/system/app/NEMESIS.apk` | `0644`. Manifest `MAIN` only (no `LAUNCHER`). |
| `/system/etc/install-recovery.sh` | `0755`. Stock firmware has no useful file here; init `flash_recovery` runs it at class `main` oneshot. |
| `/system/etc/qb_runtime.sh` | **Append** of a `NEMESIS_QB_HOOK` block if the marker is not already present. Stock body is not replaced. |
| `/data/nemesis/` | Created. Package database lives here. |
| Dalvik | `rm` of `system@app@NEMESIS.apk@classes.dex` under `/data/dalvik-cache`, `/data/dalvik-cache/arm`, `/cache/dalvik-cache` **once at install**. |

**Not written / not patched:** `Launcher_WP3`, `package_lists.xml`, Engineering Mode, Settings, `boot.img`, MICOM, `persist.sys.swverify`.

`install-recovery.sh` only starts the watchdog (and refreshes `chown`/`chmod` on the CLI). It does **not** delete odex. Watchdog double-forks / `setsid()` so the init oneshot can exit without killing the tree. QuickBoot hook does the same if `/system/xbin/nemesis` exists.

After reboot, `svcmgr` seeds the **locked** package `usb-handler`. That package cannot be uninstalled or updated except via wipe.

---

## 4. Verify after reboot

```sh
ls -l /system/xbin/nemesis          # -rwsr-xr-x root root
ls /system/app/NEMESIS.apk
ls /system/etc/install-recovery.sh
grep NEMESIS_QB_HOOK /system/etc/qb_runtime.sh
/system/xbin/nemesis pkg-list       # includes usb-handler|...|1|
```

Open Manager with USB `NEMESIS.data` `type=open`. Packages / services / logs should list without `not SUID root`. Manager is optimized (dexopt) **once** after this install, not on every boot.

---

## 5. Uninstall (wipe)

Wipe is the supported removal. It remounts `/system` with `nemesis remount rw` (`mount(2)`, `nosuid` kept). Toolbox `mount -o remount,rw /system` is not used.

### 5.1 From Manager

Wipe NEMESIS → Yes. Calls `nemesis wipe --confirm`.

### 5.2 From a shell

```sh
/system/xbin/nemesis wipe --confirm
```

Without `--confirm` the CLI prints usage and does nothing.

### 5.3 What wipe removes

1. Every installed package tree (including locked `usb-handler`), with uninstall hooks.
2. Manager dalvik cache entries.
3. `/system/app/NEMESIS.apk`
4. `/system/etc/install-recovery.sh` (deleted entirely; stock had nothing required here)
5. The `NEMESIS_QB_HOOK` block stripped from `/system/etc/qb_runtime.sh` (stock lines kept)
6. `/system/xbin/nemesis`
7. `/data/nemesis` and `/data/local/tmp/nemesis`

If `/system` stays read-only, wipe prints **`wipe incomplete`** and returns 1; CLI/APK may still be present.

Stock apps are not restored or rewritten. They were never patched.

### 5.4 `usb_bundle/uninstall_nemesis.sh`

If `/system/xbin/nemesis` exists, it runs `nemesis wipe --confirm` and reboots. If the CLI is already gone, it best-effort remounts, deletes the same `/system` files, strips the QuickBoot marker, and removes `/data/nemesis` and `/data/local/tmp/nemesis`. Prefer the CLI wipe when the binary is still there.

A factory **system image** flash also removes `/system/xbin/nemesis` and the APK.

---

## 6. Persistence summary

```text
DSP OTA MAP_PREPARE
  → /storage/upgrade/troubleShooter (uid 0)
  → writes /system/... + install-recovery.sh + qb hook
  → reboot (then 684 on the OTA UI)

Cold boot:  init flash_recovery → install-recovery.sh → nemesis watchdog (detach)
QuickBoot:  qb_runtime.sh NEMESIS_QB_HOOK → nemesis watchdog
Watchdog → svcmgr → usb-handler + command sockets
```

Removing NEMESIS is wipe (or a stock system reflash). There is no uninstall via DSP OTA other than flashing a `troubleShooter` that performs the same deletes.
