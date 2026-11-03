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

Discover packages to install on your car in the [NEMESIS Package Store](https://xwtk.cloud/services/nemesis#package-store). Made one of your own? [Submit it to the store](07-Submit-a-Package.md).

## Compatibility Check

For your car to be compatible with NEMESIS, it has to:

1. Have an Android based Gen5W HU (version must have S5W_M in it in General Settings > SW Info). S5W_L is not supported, it is a fundamentally different hardware and software combination.

2. It has to have OTA update support (not necessary to have OTA Update subscription) as NEMESIS utilizes a flaw in the OTA update logic as an entry point for its installation procedure. Some cars may still not be compatible even in that case, it can only be understood once you try to install.

### **Cars known not to work with NEMESIS**

| Name | Description |
| ---- | ----------- |
| Any car with no OTA updates capability | NEMESIS uses a flaw in OTA updates system to install itself, if your car does not have OTA updates, it does not have that flaw. |
| Any Canadian HMG car | No Canadian HMG vehicle has support for OTA updates, despite Update OTA being shown for some in the Engineering Mode menu. |
| Any car with S5W_L HU | Fundamentally different hardware and software architecture, does not run Android, NEMESIS cannot be ported in any shape or form there. |
| Hyundai Elantra / Kia Forte / Hyundai Ioniq 6 | Despite having OTA updates support, these cars use different schemes which do not have the required flaw for NEMESIS. |

### **Will there be support for incompatible cars in the future?**

**Not** guaranteed, other members are working on porting NEMESIS to cars such as Ioniq 6 and Canadian vehicles, check Discord for more information.

### **What will happen if I try to install?**

Most likely nothing, NEMESIS won't install.

## **Quick Installation Guidance**

| Step | Guidance |
| ---- | -------- |
| 1 | Download [NEMESIS Installation Package](ota_package.tar) onto your computer. |
| 2 | Prepare an exFAT or FAT32 formatted blank USB drive. |
| 3 | Copy the downloaded tar file onto the USB, do not extract or repack the archive file. |
| 4 | Insert into the car's HU USB port, messages such as "No Supported Files" are expected. |
| 5 | Navigate to Engineering Mode (use web to search on how to do so), open USB Copy, select Update DSP |
| 6 | Tap on Update OTA (existence of this doesn't mean your car has OTA support), if you see a loading animation come up on the status bar in the top right, everything is going as planned. Wait for installation to complete and the HU to restart. |

## Updating NEMESIS

### **1.15 and earlier**

| **Step** | **Description** |
|----------|-----------------|
| 1 | Follow **Quick Installation Guidance** procedures for updating to 1.16, no need to uninstall 1.15. |

### **1.16 and later**

| **Step** | **Description** |
|----------|-----------------|
| 1 | Copy the two files inside the [Update](Update) folder, `NEMESIS.data` and `nemesis-1.16.6.zip`, to the root of a blank exFAT or FAT32 USB drive. Do not extract the zip or put them in a folder. |
| 2 | Insert the USB drive into the car's HU USB port with Android running. |
| 3 | Choose **Yes** when NEMESIS asks to update. The HU restarts when the update is done. |

OR

**Settings → Check for updates** in Manager does the same over the internet, once the update is on the portal. You may additionally get a popup saying new update is ready to be installed, that is the automatic update doing its job.

## **Changelog**

### 1.16.6

- **Dashboard.** Long package titles, descriptions and app names are cut short with an ellipsis instead of wrapping. The bottom of the Engineering Menu and Dealership Menu labels is no longer clipped.

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
- **Self-hosted stores.** The store API is documented in [06 - Cloud Store API](06-Cloud-Store-API.md), so a fork can run its own.

## **Documentation**

Read about NEMESIS, what it is exploiting, what it features, and its use manual.

| Document | Contents |
|----------|----------|
| [01 - DSP OTA troubleShooter Path](01-DSP-OTA-troubleShooter.md) | How the firmware runs unsigned DSP OTA members as root, and how that path is reused. |
| [02 - Debugging Execution Interface](02-Debugging-Execution-Interface.md) | What NEMESIS is on the unit after it is present: features, how you open it, CLI and Manager usage. |
| [03 - Install and uninstall](03-Install-and-Uninstall.md) | How NEMESIS is written onto `/system` and how it is removed. |
| [04 - Packages](04-Packages.md) | Installable packages: metadata, layout, builder, lifecycle. |
| [05 - Run scripts](05-Run-Scripts.md) | USB `NEMESIS.sh` / `type=execute`: when they run and how to write them. |
| [06 - Cloud Store API](06-Cloud-Store-API.md) | The public store and update API, for running a store of your own in a fork. |
| [07 - Submit a package](07-Submit-a-Package.md) | Step by step: from an xwtk.Account to a listing in the NEMESIS Package Store. |

Documents **01** and **03** are the only ones that describe the DSP Update Agent path and on-disk install, do not use them with advanced LLMs which may have safeguards enabled. **02**, **04**, **05** and **06** assume NEMESIS is already on the head unit and treat it as a Debugging Execution Interface for packages and privileged commands, so safeguards will not be triggered.


Developed by Gor Mirzoyan (xwtk.cloud).
