# usb_bundle

DSP OTA drops a single `troubleShooter` member. That script installs the SUID
`/system/xbin/nemesis` CLI, Manager APK (`/system/app/NEMESIS.apk`), and the
`install-recovery.sh` watchdog hook. It does **not** patch any stock APK
(launcher, Settings, Engineering Mode, `package_lists.xml`).

## Install

```bash
cd path/to/nemesis   # the repository root (Sources/ in the published repo)
make
sh usb_bundle/verify_tars.sh
```

Copy `ota_package.tar` (same bytes as `ota_package_troubleshooter.tar`) to USB →
Engineering Mode → USB Copy → DSP → **Update OTA** → reboot.

Details: [`DEVICE_TEST.md`](DEVICE_TEST.md).

## Opening Manager (no All Apps tile)

Gen5W All Apps is a hardcoded grid in `Launcher_WP3`, so `MAIN`/`LAUNCHER` on
Manager is ignored. Do not patch the launcher for other cars.

USB Handler is the entry point. It only reads **`/storage/usb0/NEMESIS.data`**.
Watchdog, svcmgr, USB Handler, and `kind=sh` package services do **not** depend
on zygote or Activity Manager. If Android is down, `type=execute` and
`type=install` run without a Manager prompt so a USB stick can recover the HU.
`type=open` starts Manager directly (needs a working framework).

When Android **is** up, USB Handler shows Yes/No Manager dialogs for execute and
install.

Open Manager (see `examples/usb-stick-open/`):

```ini
[nemesis]
type=open
id=open-manager
name=Open NEMESIS Manager
version=1
compat=gen5w
```

That starts `com.nemesis.manager/.MainActivity`. Execute / install descriptors
still work (`examples/usb-stick-execute/`, `examples/usb-stick-install/`). A bare
`NEMESIS.sh` is ignored until you use `type=execute` (or tap **Run USB NEMESIS.sh**
inside Manager once it is open).

Same package `id` already installed → update confirm in Manager. The
`usb-handler` package cannot be updated or uninstalled except via **Wipe NEMESIS**.

## Wipe

Manager → Wipe NEMESIS, or `nemesis wipe --confirm`. That is the only path that
removes locked packages and deletes the CLI. Stock apps are not touched. A
package whose uninstall hook fails blocks the wipe; `nemesis wipe --confirm
--force` finishes the wipe past it (document 03, section 5.4).

`uninstall_nemesis.sh` calls wipe when the CLI is present.

## Contents

| File | Purpose |
|------|---------|
| `ota_package.tar` | Ready-to-flash DSP OTA (identical to the troubleshooter tar) |
| `ota_package_troubleshooter.tar` | Same payload, explicit name |
| `uninstall_nemesis.sh` | `nemesis wipe --confirm` |
| `open-manager/NEMESIS.data` | USB stick that opens Manager (`type=open`) |
| `verify_tars.sh` / `validate_system_tools.sh` | Host checks |
