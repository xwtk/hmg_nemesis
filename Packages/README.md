# Pre-made Packages
This directory contains packages which can be installed on your car via NEMESIS Manager.

**Installation Guidance**
Download a zip file and extract it to the root of an exFAT or FAT32 formatted USB drive. Only one NEMESIS package may exist on your USB drive at once.

## Catalog

| Name | Description | Compatibility |
| ---- | ----------- | ------------- |
| Turn-by-Turn Guidance for Phone Projection | Implements full Turn-by-Turn guidance for Android Auto and Apple CarPlay for Cluster and HUD | Package built for KIA EV6 running on 260507 firmware |
| Enable ADB | Removes hide flag which hides ADB options in Engineering Mode, after this package is installed, goto Engineering Mode > Module Info, Page 3 > Tap a few times on the left (or right) bottom corner of the screen, ADB menu will show up in the menu selector column. | Universal |
| Enable Wireless Features | Enables WiFi control and Wireless Phone Projection, note that Apple CarPlay is currently not verified to work properly. | Will properly operate only on Head Units with WiFi modules |
| Wireless CarPlay and Android Auto | All-in-one wireless projection: wireless Apple CarPlay (Bluetooth pairing bridge, Wi-Fi session support, music over AAC, Siri/calls/microphone over Opus) and wireless Android Auto, plus Turn-by-Turn guidance on Cluster and HUD. Uses the car's own hotspot only: no Wi-Fi client mode, no extra Wi-Fi menu, no wireless ADB. See notes below. | Package built for KIA EV6 running on 260507 firmware, with a WiFi module |

## Wireless CarPlay and Android Auto - notes

- **Replaces** *Enable Wireless Features* and *Turn-by-Turn Guidance for Phone Projection*. Uninstall those (and any other wireless/projection packages) first, then reboot. The installer refuses to run while they are present.
- Installs four parts in order with one reboot at the end. Each part checks the stock files it replaces by checksum and keeps its own backup under `/data/nemesis/backup/`; if any part fails, the parts already applied are undone.
- Uninstall puts every changed file and setting back to what it was before install.
- Does **not** include wireless ADB. USB ADB is unaffected.
- Does **not** include Android Auto car data (battery, range, outside temperature): that is EV-specific and stays a separate add-on. If you add it on top, uninstall it before uninstalling or updating this package.
- On firmware other than 260507 (or another model) the checksum checks fail and nothing is written.
- Android Auto phone calls still use Bluetooth (that is how Android Auto works); music, maps and Assistant go over Wi-Fi. CarPlay sends everything over Wi-Fi.
