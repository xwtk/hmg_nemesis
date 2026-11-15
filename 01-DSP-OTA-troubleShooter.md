# Entrypoint variant 1 - DSP OTA `troubleShooter` (tested on firmware 260507)

This is the **first entrypoint variant** for NEMESIS: the path NEMESIS historically used to first run unsigned code as **uid 0** on the Kia EV6 Gen5W head unit during Engineer Mode **DSP Update OTA**. For the second entrypoint variant, see [02 - USB update package](02-USB-Update-Package.md).

This document is the full technical record of this entrypoint. It is independent of the NEMESIS product UI. Once you understand the slot, you can drop any `troubleShooter` script into an `ota_package.tar` and the Update Agent will execute it on the next DSP OTA, with the same privileges as the install we ship.

Target:

- Head unit: Gen5W (HKMC), Telechips **TCC803x**
- OS: Android **4.4.2** (KitKat)
- Firmware family exercised in development: **260507**
- Entry in the car: Engineering Mode → USB Copy → **DSP** → **Update OTA**
- Package file the agent looks for on USB: **`ota_package.tar`**

---

## 1. What was discovered

HKMC’s Update Agent (`otaagentd` / `uagentd`, started from `init.tcc803x.rc`) does **not** wait for a signed `ota_info` / `ota.info` RSA bundle before it runs a helper named **`troubleShooter`**.

Device logs (`OTAUA_FAIL_*.txt`) show MAP_PREPARE always does, in this order:

1. `bg_ota_pre_descrypt_files` — missing hash files are logged and **non-fatal**
2. **`run_bg_troubleShooter`** — forks, and the child `execl`s `/storage/upgrade/troubleShooter` if that file exists. The parent stays up and `waitpid`s
3. `_bg_validation_function` — looks for `ota_info` / `ota.info`; if they are missing, the session ends with **error 684**

Step 2 runs **before** step 3. An unsigned tar that only contains a `troubleShooter` member therefore:

- is extracted onto `/storage/upgrade/troubleShooter`
- runs as the agent's child (observed: **`uid=0(root) gid=1000(system)`**, `context=kernel`). The parent waits; it does not replace itself
- then fails validation with **684**, which is expected and does **not** roll back what the script already wrote

Stock log line when the member is absent:

```text
In OTA Package,there has no troubleShooter :)
```

When the member is present and executable:

```text
Extracting[/storage/upgrade/troubleShooter], Mode[100755], ...
UNTAR OK
give 0755 permission to [/storage/upgrade/troubleShooter]
Success to execute,troubleShooter
Child Proc is Terminated, Ret[0]
Can't find Path[/storage/upgrade/ota_info], ret[684]
```

`run_bg_rb_ua` (another helper under `/storage/upgrade/bg_rb_ua`) sits **after** signed `ota_info` validation. It never appears in unsigned-package logs. **`troubleShooter` is the unsigned MAP_PREPARE hook. `bg_rb_ua` is not.**

Init starts the agent as:

```text
service otaagentd /system/bin/otaagentd
    user system root
    group system log sdcard_r
    oneshot
```

`user system root` is not valid AOSP `user` syntax (one token). The line is ignored or parsed loosely; on the car the child that `execl`s `troubleShooter` is **uid 0**. That process can remount `/system` read-write (or finds it already rw in the OTA window) and write anywhere the OTA daemon can write.

`/system` on a normal boot is typically:

```text
/dev/block/platform/bdm/by-name/system /system ext4 ro,nosuid,nodev,...
```

During MAP_PREPARE the same `mount -o remount,rw /system` string that fails from an adb-spawned toolbox **succeeds** in this process (init-class root, often `/system` already rw). After NEMESIS is installed, use `nemesis remount rw` instead of toolbox `mount`; toolbox remount still returns `Operation not permitted` because it would drop `nosuid`.

---

## 2. How the tar is extracted (Legacy vs New Logic)

The DSP agent has two extract modes. They were mapped by flashing controlled tars and reading `OTAUA_FAIL` logs.

### Legacy mode (no `./map/new_one` in the tar)

Used for the working Engineer Mode “hook / restore” USB packages and for NEMESIS.

Rules that are **device-tested**:

| Rule | Result |
|------|--------|
| Single regular-file member named `troubleShooter` | Extracted to `/storage/upgrade/troubleShooter`, then MAP_PREPARE `execl`s it |
| One symlink + one regular file, unique member name (classic `./update.info` pair) | Writes **through** the symlink (Navi BIN hook / restore) |
| Several pairs that **reuse** the same member name `./update.info` | **Error 651** after the first pair (name collision) |
| Directory members (`DIRTYPE`) | **Error 651** — libtar does not mkdir parents |
| Multi-file tars without the New Logic marker | Unreliable / 651 unless each name is unique and parents already exist |

NEMESIS’s install tar is **one** regular file: `troubleShooter`. No symlink pair, no `./map/new_one`.

### New Logic (`./map/new_one` present)

The tar is treated as an official signed package. The agent then requires RSA metadata (`ota_info` / `ota.info`). Without vendor keys this ends in **684** (`Can't find RSA files`) and **does not** run custom pairs the way Legacy does. **Do not** add `./map/new_one` to a custom DSP tar unless you have the OEM signing material.

### Confirm on the car

After Update OTA, pull the Update Agent log. Success for this path is:

- `UNTAR OK` / `OTA_PKG_EXTRACT true`
- `Success to execute,troubleShooter`
- then **684**

`UNTAR fail[651]` means extract never produced a runnable `troubleShooter`.

---

## 3. How you trigger it on the car

1. Copy **`ota_package.tar`** to the **root** of a USB stick (the agent does not look in subfolders for this name).
2. On the HU: **Engineering Mode → USB Copy → DSP → Update OTA**.
3. MAP_PREPARE extracts and runs `/storage/upgrade/troubleShooter`.
4. Validation fails **684**. If the script `reboot`s (NEMESIS does, after a short sleep), the unit restarts with whatever the script wrote.

The script runs with:

```text
PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
LD_LIBRARY_PATH=/vendor/lib:/system/lib
```

Stock userland is **toolbox + mksh**. There is **no** `/system/bin/busybox`, no `awk`, no `inotifywait`. Anything you put in `troubleShooter` must be toolbox-safe.

A typical body (what NEMESIS does, and what any later use can copy) is:

1. `mount -o remount,rw /system` (fallback `mount -o rw,remount /` — `/` is ramdisk and often `EPERM`; `/system` is the real ext4).
2. Write files onto `/system` (NEMESIS stages blobs via `dd` of a byte range from `$0` into `/data/nemesis/.extract.$$`, then `rm` + `cp` onto the destination so a **running** `/system/xbin/nemesis` is not truncated in place — that is `ETXTBSY` and leaves the old inode).
3. Optional: toast via `am broadcast -a com.hkmc.intent.action.TOAST`.
4. `sync`, optional `rm -rf /storage/upgrade/*`, `reboot`.

`$0` during MAP_PREPARE is `/storage/upgrade/troubleShooter`. Concatenating a shell header and binary blobs in one file is how NEMESIS ships the ELF and Manager APK without extra tar members.

---

## 4. How to use the same path later

The firmware does not close the slot after NEMESIS is installed. **Every** later Engineer Mode DSP OTA that supplies an unsigned `ota_package.tar` with a `troubleShooter` member will run it again as root (fork, then the child `execl`s the script), then 684.

Uses:

| Goal | What to put in `troubleShooter` |
|------|----------------------------------|
| Refresh NEMESIS | Rebuild `make` → flash new `ota_package.tar` (overlay). Kill any running watchdog/svcmgr first, then `rm` + `cp` the ELF (do not `dd` onto the live path). |
| One-shot root script | A plain `#!/system/bin/sh` that remounts `/system` and does the work, then reboots or exits 0. |
| Write a single `/system` file | Same as above; parents must already exist (libtar will not `mkdir -p` for other tar layouts). |
| Avoid New Logic | Never add `./map/new_one` unless you intend the signed-package parser. |

Constraints that stay true on later flashes:

- **One** `troubleShooter` regular-file member is the reliable unsigned shape.
- Expected terminal error is **684**, not success 0.
- `bg_rb_ua` will not run without signed `ota_info`.
- `/data/local/tmp` is wiped on reboot; do not persist tools only there.
- `/system` is `nosuid` after a normal boot. A SUID bit on `/system/xbin/...` does **not** elevate an app UID. Persistent privileged work after reboot must be started from **init** (`flash_recovery` → `/system/etc/install-recovery.sh`) or another init-born task.

---

## 5. Related firmware paths (not used to install NEMESIS)

These were mapped on the same firmware. They are not the current install, but they remain in the ramdisk / agent.

### 5.1 `persist.sys.swverify=nemesis` (init)

`init.daudio.rc`:

```text
on property:persist.sys.swverify=nemesis
    start dbus
    start nemesis

service nemesis /data/local/tmp/nemesis/nemesis
    oneshot
    disabled
```

If that persist property is set, init starts **`/data/local/tmp/nemesis/nemesis` as root** (no `user` line). `/data/local/tmp` is cleared on reboot, so a binary only copied there does not survive. NEMESIS does **not** set this property.

### 5.2 Legacy `./update.info` symlink pair

A two-entry tar: symlink `/storage/upgrade/update.info` → some **existing** path, then a regular file with the same member name that writes through the symlink. This is how I was able to achieve Helloyunho's Navi `libExSLAndroidJNI.so` hook to be installed in the system. The process that extracts is the OTA agent (root). The **hooked library** later runs as the **Navi app UID** (`u0_a19`), which cannot execute the majority of commands, but can be used to retrieve encryption/decryption key.

---

## 6. Important

- Error **684** after a successful `troubleShooter` is normal. Treat **651** as extract failure.

That MAP_PREPARE child is the entire unsigned root window. Everything NEMESIS persists after reboot is written by the script in that window, then kept alive by `install-recovery.sh` / QuickBoot — see document 03.
