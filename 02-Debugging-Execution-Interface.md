# NEMESIS as a Debugging Execution Interface

NEMESIS is a **Debugging Execution Interface** for a Gen5W head unit. After it is present on the unit, it is used to **install packages** and **run commands with root rights** for engineering, diagnostics, and in-house development. This document does not describe how the interface was placed on the unit. For that, see [03 — Install and uninstall](03-Install-and-Uninstall.md).

NEMESIS Manager app can be opened from USB or from a shell.

---

## 1. What it is made of

| Piece | Path / identity | Role |
|-------|-----------------|------|
| CLI | `/system/xbin/nemesis` | Command entry for packages, services, logs, wipe, and a root shell |
| Manager | `/system/app/NEMESIS.apk` (`com.nemesis.manager/.MainActivity`) | On-screen lists: USB run, packages, services, logs, wipe |
| Watchdog | `nemesis watchdog` | Started at cold boot and QuickBoot; keeps the rest of the stack up |
| Service manager | `nemesis svcmgr` | Starts and restarts registered package services |
| USB Handler | Locked package `usb-handler` | Watches the USB mount and reads **`NEMESIS.data`** |
| Privileged channel | `/dev/nemesis.sock` and `/data/nemesis/root.sock` | Lets the Manager (and any other UID) run CLI operations with root rights while `/system` is `nosuid` |

On-disk data:

| Path | Survives reboot? | Use |
|------|------------------|-----|
| `/data/nemesis/` | Yes | Packages, `packages.db`, `services.db`, socket, logs, pidfiles, USB session reply |
| `/data/nemesis/apps/*` | Yes | Unpacked package trees |
| `/data/nemesis/tmp/` | Yes | Staging for zip extract / uninstall hooks only (`0711`, root). Moved out of shell-writable `/data/local/tmp`. |

Stock applications, the launcher, and Engineering Mode are not modified.

---

## 2. How you open it (after it is on the unit)

### 2.1 USB `type=open` (normal)

Place only this file at USB root as **`NEMESIS.data`**:

```ini
[nemesis]
type=open
id=open-manager
name=Open NEMESIS Manager
version=1
compat=gen5w
```

Insert the stick. The USB Handler starts `com.nemesis.manager/.MainActivity`. Example tree: `examples/usb-stick-open/` in the NEMESIS source.

The Handler looks for `NEMESIS.data` on `/storage/usb0` (and `usb1` / `usb2` plus `/mnt/media_rw/usb*`). A stick with only `NEMESIS.sh` and **no** `NEMESIS.data` is ignored by the Handler.

Android must be up for `type=open`. If the framework is not up, open is skipped.

### 2.2 Shell

When a shell is available:

```sh
am start -n com.nemesis.manager/.MainActivity
```

or:

```sh
/system/xbin/nemesis pkg-list
```

### 2.3 Faceplate or steering button

Three presses of the button chosen in **Options** open Manager. Presses more than 1.5 seconds apart start the count over. A second broadcast of the same physical press within 200 ms does not count. The button's normal action still runs.

**Options** lists **Setup** and **Navigation**. A new install uses Navigation. A saved Setup stays Setup. Any other saved value, including an older Custom choice, becomes Navigation.

### 2.4 Manager, “Run USB NEMESIS.sh”

Inside Manager, **Run USB NEMESIS.sh** runs `/storage/usb0/NEMESIS.sh` through the CLI. That is a manual path; the USB Handler still requires `type=execute` in `NEMESIS.data` for automatic insert handling. See [05 — Run scripts](05-Run-Scripts.md).

---

## 3. Manager screens

| Row | Action |
|-----|--------|
| Run USB NEMESIS.sh | Runs `/storage/usb0/NEMESIS.sh` as a privileged command |
| Packages | List, Launch, Uninstall (locked packages refuse uninstall) |
| Services | List, Start, Stop (`<package-id>/<local-name>`) |
| Logs | Last NEMESIS log; **Copy to USB** writes `/storage/usb0/NEMESIS-logs` |
| Options | Choose the button that opens Manager: Setup or Navigation |
| Open Engineering Mode | Opens Engineering Mode |
| Open Dealer Mode | Opens Dealer Mode |
| Wipe NEMESIS | Confirm, then remove the interface and all packages |

Close is on the title bar. Package and service screens add Launch / Uninstall or Start / Stop on the bottom bar.

NEMESIS was built to allow recovering the Android system in case it was corrupted by incorrect changes and services such as Zygote fail to run.
USB insert while Android is **up**: Yes/No dialogs for execute and install. While Android is **down**: execute and install run without a dialog so a stick can recover a unit that is not showing UI. `type=open` still needs Android.

---

## 4. CLI reference

All of these go through `/system/xbin/nemesis`. From the Manager they are invoked as the app user; the watchdog’s command channel runs them with root rights.

```text
nemesis install [--update] <zip>
nemesis uninstall <package-id>
nemesis pkg-list
nemesis pkg-info <zip>
nemesis pkg-launch <package-id>
nemesis root [uid] [-c command]
nemesis remount [rw|ro]
nemesis service start|status|stop|list <package-id>/<name>
nemesis log dump|logcat|dmesg|service <package-id>/<name>
nemesis wipe --confirm
nemesis watchdog | svcmgr | usb-handler
```

Notes:

- `pkg-list` lines: `id|name|locked|types` (`locked` is `1` or `0`).
- `service list` lines: `qname|mode|state|pid`.
- `nemesis root` with no `-c` is an interactive root shell. `nemesis root -c 'id'` runs one command. Any UID may use this channel.
- `nemesis remount rw` (or `ro`) remounts `/system` with `mount(2)` while keeping `nosuid`/`nodev`. Toolbox `mount -o remount,rw /system` returns `Operation not permitted` on this unit even as uid 0; do not use it. `nemesis root -c 'mount -o remount,rw /system'` is intercepted and uses the same path. Package hooks get `/data/nemesis/bin/mount` first on `PATH`.
- `watchdog` / `svcmgr` / `usb-handler` are the long-running pieces. They are started at boot; you do not start them from Manager.
- `wipe --confirm` is required; `nemesis wipe` alone prints usage.

PATH inside privileged commands:

```text
/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
```

Use toolbox (`sh`, `cp`, `mv`, `rm`, `mount`, `logcat`, `am`, `dd`). Do not assume busybox or `awk`.

---

## 5. Boot behaviour (why it is still there after reboot)

Cold boot: init `flash_recovery` runs `/system/etc/install-recovery.sh`, which starts `nemesis watchdog`. The shell waits until the watchdog has left that service’s process group and opened its command socket (`nemesis ping`), then returns so init does not kill the daemon with the oneshot.

QuickBoot: `on quickboot_fs` runs `qb_uid_check`, which executes `/system/etc/qb_runtime.sh`. The marked block at the end of that script starts the watchdog the same way.

The watchdog binds the command sockets, remounts `/system` read-write when it can, and polls for CLI requests. Pidfiles live under `/data/nemesis/` so they survive QuickBoot clearing `/data/local/tmp`. A pidfile is trusted only when that pid’s `/proc` start time still matches (older files: the command line must still be `nemesis watchdog`). A reused pid cannot block the next start, which is what left the USB Handler down after a reboot. The locked `usb-handler` service is restarted without a 5-strike limit. USB inserts are handled only while that service is running; `svcmgr` starts it, and the watchdog starts `svcmgr`.

---

## 6. Quick usage

1. Insert a USB stick whose root contains `NEMESIS.data` with `type=open`.
2. Confirm Manager. Use Packages / Services / Logs as needed.
3. To add software, use a stick with `type=install` and a zip — see [04 — Packages](04-Packages.md).
4. To run a one-off script, use `type=execute` and `NEMESIS.sh` — see [05 — Run scripts](05-Run-Scripts.md).
5. To work from a PC shell: `nemesis pkg-list`, `nemesis root`, `nemesis log dump`.
