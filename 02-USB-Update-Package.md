# Entrypoint variant 2 - USB update package

This is the second entrypoint variant for NEMESIS. The first entrypoint variant is documented in [01 - DSP OTA troubleShooter](01-DSP-OTA-troubleShooter.md).

Unlike variant 1, this entrypoint does not need Engineer Mode's DSP OTA menu. It reuses the stock USB system-package upgrade flow already shipped on the unit. The build script is `USB package path/make_nemesis_installer.py`; the control script and Installer APK sources live in the same folder.

Target:

- Head unit: Gen5W (HKMC), Telechips **TCC803x**
- OS: Android **4.4.2** (KitKat)
- Firmware family exercised in development: **260507**
- Entry in the car: standard USB system-package upgrade

---

## 1. Preparing the `new_system_package` file

The stock extractor reads a tar whose name is `new_system_package_{BRAND}.{REGION}.{VARIANT}.{VERSION}.tar`. The build script writes one file per (brand × region × variant) pair so a single stick covers the full matrix; the real-world matrix is listed in `BRANDS`/`REGIONS`/`VARIANTS` near the top of `make_nemesis_installer.py`. `VERSION` is a placeholder high enough that the unit accepts it as an upgrade.

Each tar reuses the members of a genuine stock template (`--template path/to/new_system_package_*.tar`) verbatim, except for the single `./usb_news/../../../data/property/persist.sys.usb.config` entry, which is dropped. In its place the build script writes four new members whose names start with `./usb_news/../../.../` so the extractor, whose working directory is `/storage/upgrade/usb_news/`, writes them outside that directory:

| Tar member name                                                             | Lands at                                          |
|-----------------------------------------------------------------------------|---------------------------------------------------|
| `./usb_news/../../../data/local/tmp/nemesis/nemesis.bin`                    | `/data/local/tmp/nemesis/nemesis.bin` (ELF)       |
| `./usb_news/../../../data/local/tmp/nemesis/NEMESIS.apk`                    | `/data/local/tmp/nemesis/NEMESIS.apk` (Manager)   |
| `./usb_news/../../../data/app/NEMESIS-Installer.apk`                        | `/data/app/NEMESIS-Installer.apk` (Installer)     |
| `./usb_news/../../../data/local/tmp/nemesis/nemesis`                        | `/data/local/tmp/nemesis/nemesis` (control script)|

Nothing in the stock flow validates the member names. The extraction order is the order given above: binaries first, control script last, so the script never runs against a half-written payload.

Build:

```sh
cd "USB package path"
make TEMPLATE=/path/to/new_system_package_*.tar
```

Output lands in `USB package path/out/`. Copy its contents to the root of the USB stick alongside the stick-extras already placed there by the build (`splash.png`, the RSA-signed `NEW_TarList.txt_encrypted*`).

## 2. Installing the APK and files into the system

Plug the stick into the head unit. The stock Upgrade UI lists the tars; accept the upgrade. For every tar on the stick the stock extractor runs `tar -x` with `cwd = /storage/upgrade/usb_news/`, which writes:

- The three staged files into `/data/local/tmp/nemesis/` (owned `shell:shell`, extractor-written as root).
- The Installer APK into `/data/app/`, where the Package Manager scans it at the next boot and installs it as `com.nemesis.installer`.

The stick reboots as part of the normal upgrade flow. On the next boot, PM registers the Installer; the Installer's `BootReceiver` launches `InstallerActivity`, which shows the agreement UI. Nothing has been written to `/system` at this stage — if the user declines, the only on-device residue is the Installer APK itself and the three files under `/data/local/tmp/nemesis/`, all of which the control script removes if it runs the decline branch.

## 3. Initiating the NEMESIS installation

When the user accepts, the Installer writes `1` to `/storage/upgrade/NEMESISAGREEMENT` (writable as the app's uid because `/storage/upgrade/` is `0777`) and calls:

```
service call com.hkmc.misc 6 s16 NEMESISSVC
```

Transaction `6` of `com.hkmc.misc` reads an `int` equal to the `s16` argument's **length** and calls `setSystem(int)` with it. A 10-character argument therefore selects `setSystem(10)`, whose effect is to set `persist.sys.swverify=nemesis`. `init.daudio.rc`'s matching line

```
on property:persist.sys.swverify=nemesis
    start nemesis_sw
```

starts a oneshot root service whose `exec` is `/data/local/tmp/nemesis/nemesis` — the control script the extractor just staged there. It runs as `uid=0`.

The control script reads `/storage/upgrade/NEMESISAGREEMENT`:

- `1` — installs the ELF to `/system/xbin/nemesis` (mode `4755`), the Manager APK to `/system/app/NEMESIS.apk`, writes `install-recovery.sh` and the quickboot hook, installs root CAs, pokes the 11-character `NEMESISSVCX` to clear `swverify` and engineer mode, restores `persist.sys.QB.use.allow=true`, cleans up the Installer and the stage directory, and reboots via a finisher placed in `/storage/upgrade/` (the `0777` location, since `nemesis root` lacks `CAP_DAC_OVERRIDE` for `/data/local/tmp/`).
- `0` — skips the install, runs the same teardown (`NEMESISSVCX` + QB restore), removes the Installer and the stage, and exits without rebooting.
- anything else — no decision recorded yet; the service exits so a later retry can re-enter the flow.

After a successful install, nothing under `/data/local/tmp/nemesis/` remains, `swverify` is cleared, and the unit boots with NEMESIS present exactly as a variant-1 install leaves it. See [04 - Install and uninstall](04-Install-and-Uninstall.md) for the shared uninstall procedure.
