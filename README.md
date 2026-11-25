# NEMESIS

Own your car, to its fullest.

Browse and download packages in the [NEMESIS Package Store](https://xwtk.cloud/services/nemesis#package-store). The [NEMESIS website](https://xwtk.cloud/services/nemesis) also has the Discord link.

### **Support NEMESIS development and research**

You may pledge a small amount to help cover research costs for Gen5W systems as well as NEMESIS development. It is completely optional and is considered as a gratitude for the done work.

[![Sponsor via GitHub](https://img.shields.io/badge/Sponsor%20via%20GitHub-FAFAFA?style=for-the-badge&logo=githubsponsors&logoColor=black)](https://github.com/sponsors/xwtk)
[![Donate via xwtk.Portal](https://img.shields.io/badge/Donate%20via%20xwtk.Portal-FAFAFA?style=for-the-badge)](https://xwtk.cloud/services/nemesis#donate)

## **Warning**

NEMESIS is subject to GNU AGPL 3.0 License, get to know more [here](LICENSE).

You bear complete responsibility for any procedure with your car and its components, it is important to understand that there is always risk associated with procedures described in this and associated documents in this repository, your car functionality may be heavily impacted if this software is not compatible with your components.

## Packages

Discover packages to install on your car in the [NEMESIS Package Store](https://xwtk.cloud/services/nemesis#package-store). Made one of your own? [Submit it to the store](08-Submit-a-Package.md).

## Compatibility Check

For your car to be compatible with NEMESIS, it has to:

1. Have an Android based Gen5W HU (version must have S5W_M in it in General Settings > SW Info). S5W_L is not supported, it is a fundamentally different hardware and software combination.
2. Have a fairly recent version of S5W_M firmware (250818 or later has been confirmed to work).

## Installation Guidance

First of all, I want to express my sinecere gratitude to users sin and toxuin from Gen5W/ccNC community Discord, they have done massive contributions to the new installation architecture so NEMESIS is now available to pretty much all Gen5W head units.

Because of this, we now have 2 methods available for installation, the first method is the Update OTA method which has been the way of installing NEMESIS from the beginning (option 1), the second method requires slightly more work, but it does not depend on OTA update capabilities of the car.


## **Quick Installation Guidance (Option 1)**

| Step | Guidance |
| ---- | -------- |
| 1 | Download the `ota_package.tar` asset from the [latest release](https://github.com/xwtk/hmg_nemesis/releases/latest) onto your computer. |
| 2 | Prepare an exFAT or FAT32 formatted blank USB drive. |
| 3 | Copy the downloaded tar file onto the USB, do not extract or repack the archive file. |
| 4 | Insert into the car's HU USB port, messages such as "No Supported Files" are expected. |
| 5 | Navigate to Engineering Mode (use web to search on how to do so), open USB Copy, select Update DSP |
| 6 | Tap on Update OTA (existence of this doesn't mean your car has OTA support), if you see a loading animation come up on the status bar in the top right, everything is going as planned. Wait for installation to complete and the HU to restart. |


## **Quick Installation Guidance (Option 2)**

| Step | Guidance |
| ---- | -------- |
| 1 | Download the `nemesis-usb-update-package.7z` asset from the [latest release](https://github.com/xwtk/hmg_nemesis/releases/latest) onto your computer. |
| 2 | Prepare an exFAT or FAT32 formatted blank USB drive. |
| 3 | Extract (not copy the 7z file) the contents of the downloaded 7z file onto the root of the USB, so the .tar files and such are in the USB, not inside a folder or anywhere else, root means the USB drive itself when you open it (I can't believe I have to clarify this). |
| 4 | Insert into the car's HU USB port, messages such as "No Supported Files" are expected, wait for up to 15 seconds. |
| 5 | Remove USB, restart the HU, this can be done by pressing the reset pinhole for example. |
| 6 | Wait for boot to complete, you may see an Updating screen popup briefly, if so, then the entrypoint has been achieved. Wait for the buttons in the disclaimer screen to become available, then proceed to the home screen. |
| 7 | **DO NOT OPERATE THE HU**, wait for a few seconds for NEMESIS Installation Interface to popup. |
| 8 | Proceed with installation. |

### **If you decide to not proceed with the installation**

If you have mistakenly closed the installer, NEMESIS Installation Interface will popup on every HU boot, you can restart the HU to see it again. Press Decline in the interface to remove the installer and restore the system to factory state.

## Updating NEMESIS

### **1.15 and earlier**

| **Step** | **Description** |
|----------|-----------------|
| 1 | Follow **Quick Installation Guidance** procedures for updating to 1.16, no need to uninstall 1.15. |

### **1.16 and later**

| **Step** | **Description** |
|----------|-----------------|
| 1 | From the [latest release](https://github.com/xwtk/hmg_nemesis/releases/latest), download the `nemesis-update-<version>.zip` asset and the `NEMESIS.data` next to it, then copy both to the root of a blank exFAT or FAT32 USB drive. Do not extract the zip or put them in a folder. |
| 2 | Insert the USB drive into the car's HU USB port with Android running. |
| 3 | Choose **Yes** when NEMESIS asks to update. The HU restarts when the update is done. |

OR

**Settings → Check for updates** in NEMESIS Manager does the same over the internet, once the update is on the portal. You may additionally get a popup saying new update is ready to be installed, that is the automatic update doing its job.

## **Changelog**

### 1.17.1

- **NEMESIS 1.17 and later refuse to install** over HMG-API older than 1.1.0-rc.9, or, without HMG-API, over a modified `app_process`. The installer, the troubleshooter and the core update show why and change nothing. See [04 - Install and Uninstall](04-Install-and-Uninstall.md#units-nemesis-117-and-later-refuse).
- **Package Store** shows "Retrieving data…" with a spinner until the list has fully loaded, instead of the connection error. The connection error is now laid out on separate lines.
- **LivePatch safe mode** no longer turns on after a single Android restart.
- **`nemesis remount rw --bypass-livepatch`** lets a script outside NEMESIS write folders LivePatch serves read-only, such as `/system/lib` with Enhanced Projection installed. The next `nemesis remount ro` or boot puts LivePatch's view back. Packages use `[file.N]` instead.
- **"LivePatch engine missing".** The 1.17 USB update package didn't install the LivePatch engine, and neither did the 1.17 in-app update on a unit coming from 1.16.x (the older updater skips the engine files). LivePatch packages with a dex patch then failed to install. The 1.17.1 USB package installs the engine, and the 1.17.1 update installs it from any 1.16.x or 1.17.
- **NEMESIS uninstall** from USB puts Android's start-up file back again. When the `nemesis` binary was already gone, the 1.17 script left NEMESIS's start-up script in place, because the head unit's `mv` rejects `-f`.
- **Tools that manage files, such as HMG-API,** can hand their results to LivePatch instead of writing `/system`. A package with `livepatch-first=1` puts its `[file.N]` outputs under every other package's patches, and `nemesis livepatch stock` gives the stock bytes under any output. HMG-API needs its own support for this; see [09 - LivePatch](09-LivePatch.md#38-tools-that-manage-files-themselves-livepatch-first).
- A `[file.N]` refused because another package is on the same file now names that package.
- **Sources.** A fresh clone builds and passes its host tests again: `enhanced.projection/native/converter.c` is back, and the build and tests skip the parts that are not published (listed in `.gitignore`). `projection.tbt` is removed; `enhanced.projection` replaces it.

### 1.17

- **LivePatch.** Packages can change stock files and system properties without writing `/system`. The patched copy lives on `/data` and is mounted over the stock file at boot, and uninstall brings stock back at once. Several packages can patch the same file. Conflicts are refused at install, and a safe mode boots stock after repeated failed boots. See [09 - LivePatch](09-LivePatch.md).
- **Packages.** `adb.tcp`, `wireless.projection`, `voicehandler.vr-gates`, `carplay.session` and `enhanced.projection` now use LivePatch. Uninstall their 1.x release before installing the new one.
- **Install queue.** Installs and uninstalls now run one at a time from a queue, so a USB stick can be removed as soon as its packages are queued. A job cut off by a power loss or restart can be continued or deleted. If a script was running when the job stopped, the Manager warns that the head unit may need checking. CLI: `nemesis queue add|uninstall|resume|delete|list`.
- **Package Manager.** A new screen with Available updates, In progress, Interrupted and Installed sections replaces the Updates screen. It shows live install progress and lists the packages on a connected USB drive. When the queue is done, it counts down a single restart for everything that needs one, LivePatch packages included.
- **Descriptor version 1.2.** `NEMESIS.data` has a `version` key. A package written for a newer NEMESIS is refused, even with the risk accepted, and the Store asks you to update NEMESIS. `1.2` adds `favicon`, an icon shown in the Store and package lists.
- **Package zips.** A zip may keep its files inside one wrapper folder, and the metadata file may be spelled in any case (for example `nemesis.data`). Scripts from zips made on Windows or macOS are now executable.
- **CLI.** New `nemesis prop`, `nemesis livepatch`, `nemesis reboot` and `nemesis selinux cmdline`.
- **Installer.** The error screen now says which step failed, and a failed step no longer leaves the installer stuck on "Please wait".

### 1.16.9

- **Package metadata.** `os` and `models` in `NEMESIS.data` can now be up to 1024 characters each, up from 256.
- **Update package.** The build now writes the stick-root `NEMESIS.data` with `path=` next to the zip.

### 1.16.8

- **Package Store installs.** Fixed `INSTALL_FAILED_INVALID_URI` when installing APKs through the Store (SmartTube, Gen5W Themer and others). `/data/nemesis/apps` was being created with umask 077 masking it to 0700, which the system uid cannot traverse; it is now set to 0755 and self-heals on the next `nemesis` command.
- **Disable OTA system updates.** New Settings toggle (and a first-boot prompt) that stops online OTA update checks and removes any downloaded OTA package. Manual Update OTA and NEMESIS updates still work.
- **Disable SELinux.** New Settings toggle that switches SELinux to Permissive and restores it on wipe.
- **ADB.** New home tile with USB ADB and Wireless ADB toggles. Wireless ADB shows the `adb connect <ip>:5555` line when it is on. Restored on wipe.
- **WiFi tile.** New home tile that opens the Wi-Fi picker. The first press warns, if Wi-Fi is off, that wireless projection will need a reboot to recover.
- **Logs.** Left pane lists sources (NEMESIS, Logcat, services) and the right pane shows the selected log newest line first.

### 1.16.7

- **Package Store from USB.** Installing a package that needs no other package first no longer fails at once with "Operation failed".
- **Dashboard.** The second line of the Engineering Menu and Dealership Menu labels is no longer cut off.
- **Logs.** Manager can write its logs again after a reinstall, which left files it could not open. A shortcut press that does not match the chosen button is now recorded in the press log.

### 1.16.6

- **Dashboard.** Long package titles, descriptions and app names are cut short with an ellipsis instead of wrapping. The bottom of the Engineering Menu and Dealership Menu labels is no longer clipped.
- **Package details.** The installed packages page now labels **Package ID** and **Version**. An installed package opened in the Package Store shows its own detailed description when the portal cannot be reached.

### 1.16.5

- **Wi-Fi.** Manager has a **WiFi** button after Dealership Menu that opens the Wi-Fi network list. It appears only on units with a Wi-Fi module.
- **Package Store.** The details page shows "Loading details…" while it fetches, then the full description and **Package ID**. If the fetch fails, it says so and offers **Retry**, and an installed package can still be uninstalled. An install that hit an internal error now shows a failure popup instead of waiting forever.
- **NEMESIS updates.** A downloaded update is kept if you decline it and offered again on every check, even offline. A newer version replaces it, and an update installed any other way (for example from USB) deletes it. Partial downloads and leftovers are removed at startup.

### 1.16.4

- **Settings.** Use Telematics Internet is now **Use built-in modem for network services**, and turning it on asks you to confirm first.
- **Services.** A stopped service stays stopped until started again, built-in services excepted. Uninstalling a package no longer risks leaving its service running. A background instance service deferred while Android is down now starts when Android comes up.
- **Packages.** A hook, app or service path containing `..` is refused, and so is an id starting with `-`. A nested `NEMESIS.data` can no longer replace the one at the root.
- **USB.** Fixes the handler reading freed memory after an execute or install insertion.
- **Mount.** The mount wrapper now also recognises `-o rw,remount` and `-o ro,remount`.
- **Wipe.** Reports when the boot hook could not be removed from `qb_runtime.sh`.

### 1.16.3

- **Package dependencies.** A dependency can be updated while packages that need it stay installed, as long as the new version still suits them. A package with unmet dependencies is no longer marked incompatible: it keeps its compatibility colour and the install stops with a message saying what to install or update.
- **Incompatibilities.** Put `!id` in `depends` (for example `depends="base@>=1.0,!other"`) for a package that cannot be installed together with yours.
- **Dependency install.** If the missing dependencies are on the USB drive or the Package Store, Manager lists them and installs them before the package.
- **Package Store.** Rows show a check mark with the installed version, and an update icon with the new version. Downloads can be cancelled. A details page shows why a package is yellow or red.
- **Package updates.** Manager checks once a day, and when the Store opens from a USB drive with a package list, then offers an Updates page with a count badge on both tabs. The USB update question names the package.
- **Hotspot.** USB and Bluetooth hotspot connections work without Use Telematics Internet.
- **Manager.** Checkable rows show a checkbox.

### 1.16.2

- **Updater.** Fixes the update from 1.16 failing with `replace /system/etc/qb_runtime.sh failed errno=95` and `restore FAILED`. A unit that showed this is still on 1.16 and is safe to use: update again to 1.16.2.

### 1.16.1

- **Network use.** Everything NEMESIS Manager does online now uses Wi-Fi or a USB or Bluetooth hotspot only. Turn on **Settings → Use Telematics Internet** to also allow the head unit's SIM connection.

### 1.16

- **Package Store.** Manager has a Package Store with an online tab (xwtk.Portal) and a USB tab (`type=list`). Rows are colored by compatibility with your OS version, car model and dependencies, and several packages can be installed in one go. Tap a package to open its details, where Install, Update and Uninstall are. Packages with a `version=1.0` descriptor are shown in yellow with a warning that they may not be compatible. The same store is on the [website](https://xwtk.cloud/services/nemesis#package-store), where packages from other publishers carry a Community badge.
- **NEMESIS updater.** `nemesis update`, USB `type=update`, a manual check in Settings, and an hourly Update Monitor that does the 24 hour online check.
- **Wipe.** `nemesis wipe --confirm` and a Wipe action in Manager, which asks twice.
- **Package descriptors `version=1.1`.** New keys: `type`, `package-version`, `short-desc`, `os`, `models` and `depends`. `version=1.0` descriptors still work. A package cannot be uninstalled while another one depends on it.
- **Manager.** New layout: My Packages, Package Store, Android Apps and Settings. Root certificates ship with it so HTTPS works on the unit. Settings has a Reboot button. Running a script package shows what the script printed as a toast. Auto-launch also runs when the screen comes back on with Android still up.
- **Fixes.** NEMESIS missing after a remote start, the Manager crash about `GET_TASKS` and after Close, the service staying disabled after repeated crashes, Manager opening on the first press, and Setup covering Manager after it opened.
- **Self-hosted stores.** The store API is documented in [07 - Cloud Store API](07-Cloud-Store-API.md), so a fork can run its own.

## **Documentation**

Read about NEMESIS, what it is exploiting, what it features, and its use manual.

| Document | Contents |
|----------|----------|
| [01 - Entrypoint variant 1: DSP OTA troubleShooter](01-DSP-OTA-troubleShooter.md) | First entrypoint variant. How the firmware runs unsigned DSP OTA members as root, and how that path is reused. |
| [02 - Entrypoint variant 2: USB update package](02-USB-Update-Package.md) | Second entrypoint variant. The stock USB system-package upgrade path and the three steps that lead to a NEMESIS install. |
| [03 - Debugging Execution Interface](03-Debugging-Execution-Interface.md) | What NEMESIS is on the unit after it is present: features, how you open it, CLI and Manager usage. |
| [04 - Install and uninstall](04-Install-and-Uninstall.md) | How NEMESIS is written onto `/system` and how it is removed. |
| [05 - Packages](05-Packages.md) | Installable packages: metadata, layout, builder, lifecycle. |
| [06 - Run scripts](06-Run-Scripts.md) | USB `NEMESIS.sh` / `type=execute`: when they run and how to write them. |
| [07 - Cloud Store API](07-Cloud-Store-API.md) | The public store and update API, for running a store of your own in a fork. |
| [08 - Submit a package](08-Submit-a-Package.md) | Step by step: from an xwtk.Account to a listing in the NEMESIS Package Store. |
| [09 - LivePatch](09-LivePatch.md) | Patching stock files and properties without writing `/system`: features, usage, and how to write a LivePatch package. |

Documents **01**, **02** and **04** are the only ones that describe the entrypoint variants and the on-disk install, do not use them with advanced LLMs which may have safeguards enabled. **03**, **05**, **06** and **07** assume NEMESIS is already on the head unit and treat it as a Debugging Execution Interface for packages and privileged commands, so safeguards will not be triggered.


Developed by Gor Mirzoyan (xwtk.cloud).
