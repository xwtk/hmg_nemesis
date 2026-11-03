# Installing and uninstalling NEMESIS

This document is the on-disk install and removal procedure. The firmware path that first runs the installer as root is [01 — DSP OTA `troubleShooter`](01-DSP-OTA-troubleShooter.md). After install, day-to-day use is [02 — Debugging Execution Interface](02-Debugging-Execution-Interface.md).

---

## 1. Build the flash tar (PC)

From the Linux NEMESIS tree (ext4; do not build on NTFS/WSL mounts):

```bash
cd path/to/nemesis   # the Sources/ folder of the repository
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

- sends TERM/KILL to the pid at the start of `/data/nemesis/watchdog.pid`, `svcmgr.pid`, and `pids/usb-handler+usb-handler` (and the old `/data/local/tmp/nemesis/` copies if present), only if that process is NEMESIS
- slices ELF and APK from `$0` with `dd` into `/data/nemesis/.extract.$$`
- **`rm -f` destination, then `cp -f`** onto `/system/xbin/nemesis` and `/system/app/NEMESIS.apk`

The watchdog and `svcmgr` restart each other, so they can come back during the install. They run from the old binary's deleted file until the reboot at the end, which does no harm because the new files are placed with `rm` then `cp`.

Truncating a running `/system/xbin/nemesis` in place (`dd of=` that path) returns **ETXTBSY** and leaves the old binary. That is why a previous overlay could keep printing `not SUID root (euid=10208)`: the Manager was new, the CLI was not.

Dalvik odex for Manager is deleted **only during this OTA script**, not on every boot.

---

## 3. What is written

| Path | Mode / notes |
|------|----------------|
| `/system/xbin/nemesis` | `04755`, owner `root:root` |
| `/system/app/NEMESIS.apk` | `0644`. Manifest `MAIN` only (no `LAUNCHER`). |
| `/system/etc/install-recovery.sh` | `0755`. Stock firmware has no useful file here; init `flash_recovery` runs it at class `main` oneshot. |
| `/system/etc/qb_runtime.sh` | **Append** of a `NEMESIS_QB_HOOK` block if the marker is not already present. Stock body is not replaced. |
| `/system/etc/security/cacerts/<hash>.0` | `0644`. Added root CAs for HTTPS. A name that already exists is left alone. Added names are recorded in `/data/nemesis/owned-cacerts`. |
| `/data/nemesis/` | Created. Package database lives here. |
| Dalvik | `rm` of `system@app@NEMESIS.apk@classes.dex` under `/data/dalvik-cache`, `/data/dalvik-cache/arm`, `/cache/dalvik-cache` **once at install**. |

**Not written / not patched:** `Launcher_WP3`, `package_lists.xml`, Engineering Mode, Settings, `boot.img`, MICOM, `persist.sys.swverify`.

`install-recovery.sh` only starts the watchdog (and refreshes `chown`/`chmod` on the CLI). It does **not** delete odex. Watchdog double-forks / `setsid()` so the init oneshot can exit without killing the tree. QuickBoot hook does the same if `/system/xbin/nemesis` exists.

After reboot, `svcmgr` seeds the **locked** packages `usb-handler` and `nemesis.update-monitor`. They cannot be uninstalled or updated except via wipe, or by a `nemesis update` package that carries a newer copy.

---

## 4. Verify after reboot

```sh
ls -l /system/xbin/nemesis          # -rwsr-xr-x root root
ls /system/app/NEMESIS.apk
ls /system/etc/install-recovery.sh
grep NEMESIS_QB_HOOK /system/etc/qb_runtime.sh
/system/xbin/nemesis pkg-list       # includes usb-handler|...|1|
```

Open Manager with USB `NEMESIS.data` `type=open`. My Packages / Services / Logs should list without `not SUID root`. Manager is optimized (dexopt) **once** after this install, not on every boot.

---

## 5. Uninstall (wipe)

Wipe is the supported removal. It remounts `/system` with `nemesis remount rw` (`mount(2)`, `nosuid` kept). Toolbox `mount -o remount,rw /system` is not used.

### 5.1 From Manager

**Settings → Wipe NEMESIS** shows two dialogs. The first says what will be removed. The second reminds you to set back any head unit options you changed by hand, such as USB mode. Yes on both calls `nemesis wipe --confirm`.

### 5.2 From a shell

```sh
/system/xbin/nemesis wipe --confirm
/system/xbin/nemesis wipe --confirm --no-reboot
```

Without `--confirm` the CLI prints usage and does nothing. Wipe reboots the unit when it succeeds, unless `--no-reboot` is passed.

### 5.3 What wipe removes

1. Every installed package tree, including locked core packages such as `usb-handler`, with uninstall hooks, in install order. If an uninstall fails, wipe stops there, prints `wipe blocked <id>|<name>`, and exits 3 with NEMESIS still installed. Fix that package and wipe again.
2. Root CAs listed in `/data/nemesis/owned-cacerts`. Certificates NEMESIS did not add are not touched.
3. Manager dalvik cache entries.
4. `/system/app/NEMESIS.apk`
5. `/system/etc/install-recovery.sh` (deleted entirely; stock had nothing required here)
6. The `NEMESIS_QB_HOOK` block stripped from `/system/etc/qb_runtime.sh` (stock lines kept). The two blank lines an OTA install adds around the block stay, as they did in 1.15.
7. `/system/xbin/nemesis`
8. `/data/nemesis` and `/data/local/tmp/nemesis`

If `/system` stays read-only, wipe prints **`wipe incomplete`** and returns 1; CLI/APK may still be present.

Stock apps are not restored or rewritten. They were never patched.

### 5.4 `usb_bundle/uninstall_nemesis.sh`

If `/system/xbin/nemesis` exists, it runs `nemesis wipe --confirm` and reboots. If the CLI is already gone, it best-effort remounts, deletes the same `/system` files, strips the QuickBoot marker, and removes `/data/nemesis` and `/data/local/tmp/nemesis`. Prefer the CLI wipe when the binary is still there.

A factory **system image** flash also removes `/system/xbin/nemesis` and the APK.

---

## 6. Updating NEMESIS

An update replaces the core files without a DSP OTA. It comes from a stick (`type=update`), from **Settings → Check for updates**, or from a shell:

```sh
/system/xbin/nemesis update /storage/usb0/nemesis-update-1.17.zip
```

The `Update` folder in the repository is a ready stick: copy its two files to the root of the drive. `NEMESIS.data` there is only:

```ini
[nemesis]
version=1.1
type=update
path=nemesis-1.16.6.zip
```

Build the zip on the PC:

```bash
python3 tools/make_update_zip.py -o nemesis-update-1.17.zip --version 1.17 --no-qb-hook
```

`--no-qb-hook` leaves out `core/qb_hook.sh`. An update runs the binary already on the unit, and on real units 1.16 and 1.16.1 fail when they rewrite the hook, so a zip meant for them must not carry it.

The zip has `id=nemesis.core`, `type=update`, and a `package-version`. It holds `core/nemesis`, optionally `core/NEMESIS.apk`, `core/install-recovery.sh`, `core/qb_hook.sh`, `core/cacerts/`, `core-packages/*.zip`, and `scripts/migrate.sh`, plus an `MD5SUMS` that must list every file.

What it does:

1. Refuses a zip that is not marked compatible, older, or the same version. `--accept-risk` and `--force` override these.
2. Checks every file against `MD5SUMS` and runs the new `core/nemesis` once. A binary that cannot run on this unit is refused with exit code 8.
3. Copies the current core files to `/data/nemesis/update/backup`.
4. Stops the daemons, remounts `/system`, places the new files, and adds any new root CAs.
5. Installs `core-packages/` and runs `scripts/migrate.sh`.
6. Reboots, unless `--no-reboot` is passed.

`/system/etc/qb_runtime.sh` is replaced by a rename after the new copy is synced, so losing power leaves the old file or the new one. If a step after the backup fails, the backup is put back. The result is written to `/data/nemesis/update/last-result`.

| Exit code | Meaning |
|-----------|---------|
| 0 | Updated |
| 1 | Usage error or bad zip |
| 7 | Older than installed (pass `--force`) |
| 8 | `MD5SUMS` check failed or the new binary does not run, nothing changed |
| 9 | Already up to date |
| 10 | Failed, previous version restored |
| 11 | Failed, and the restore failed too |
| 20 | Not marked compatible (pass `--accept-risk`) |

---

## 7. Persistence summary

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
