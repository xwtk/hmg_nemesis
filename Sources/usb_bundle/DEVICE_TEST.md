# NEMESIS — device test

## How it works

DSP OTA extracts `ota_package.tar` / `ota_package_troubleshooter.tar` → MAP_PREPARE
runs `run_bg_troubleShooter` → `/storage/upgrade/troubleShooter` (before `ota_info`
validation, which then fails **684** — expected).

Installs SUID `/system/xbin/nemesis`, Manager `/system/app/NEMESIS.apk`, and
`/system/etc/install-recovery.sh` which starts `nemesis watchdog`. Watchdog starts
`nemesis svcmgr`, which seeds the locked `usb-handler` package.

Stock apps are not modified. There is no All Apps tile.

## Build / verify (PC)

```bash
cd path/to/nemesis   # the repository root (Sources/ in the published repo)
make
sh usb_bundle/validate_system_tools.sh
sh usb_bundle/verify_tars.sh
```

## USB layout (root install)

| File | Purpose |
|------|---------|
| `ota_package.tar` | One-step framework install (flash this) |
| `examples/usb-stick-open/` | `NEMESIS.data` with `type=open` (opens Manager) |
| `examples/usb-stick-execute/` | `NEMESIS.data` + script |
| `examples/usb-stick-install/` | `NEMESIS.data` + `packages/hello.tool.zip` |
| `uninstall_nemesis.sh` | Calls `nemesis wipe --confirm` |

## Install

1. Copy `ota_package.tar` to USB root
2. Engineering Mode → USB Copy → DSP → **Update OTA**
3. Confirm OTAUA: **UNTAR OK** / `OTA_PKG_EXTRACT true` (not `UNTAR fail[651]`)
4. Reboot → install-recovery starts `nemesis watchdog`

## Verify

```sh
ls -l /system/xbin/nemesis          # -rwsr-xr-x
nemesis pkg-list                    # usb-handler locked
ls /system/app/NEMESIS.apk
ls /system/etc/install-recovery.sh
```

Open Manager with a USB stick that has only `NEMESIS.data` (`type=open`). USB
Handler only reacts to `/storage/usb0/NEMESIS.data`.

## Using USB

| `type` | `path` | Android up | Android down |
|--------|--------|------------|--------------|
| `open` | optional | starts Manager | skipped (no UI) |
| `execute` | required | Yes/No, then run script | **auto-run** (recovery) |
| `install` | required | Yes/No, then install zip | **auto-install** (recovery) |

Bare `NEMESIS.sh` is ignored by USB Handler; Manager can still run it when the
framework is up. Same package `id` already installed → update Yes/No when
Android is up, or auto-update when it is down. `usb-handler` cannot be updated
or uninstalled except via Wipe NEMESIS.

## Uninstall / wipe

Manager → Wipe NEMESIS, or `nemesis wipe --confirm`. Removes the CLI, Manager,
hooks, and `/data/nemesis`. Stock apps are not restored or rewritten.
