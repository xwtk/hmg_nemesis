#!/usr/bin/env python3
"""Host checks for Manager APK before flashing. No guest / no GUI."""
from __future__ import annotations

import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "manager" / "NEMESIS.apk"
SRC = ROOT / "manager" / "src" / "com" / "nemesis" / "manager"
AAPT = "aapt"


def fail(msg: str) -> None:
    print("FAIL:", msg, file=sys.stderr)
    sys.exit(1)


def dex_blob() -> bytes:
    with zipfile.ZipFile(APK) as z:
        return z.read("classes.dex")


def main() -> None:
    if not APK.is_file():
        fail(f"missing {APK}")
    with zipfile.ZipFile(APK) as z:
        names = z.namelist()
    nine = [n for n in names if n.endswith(".9.png")]
    if nine:
        fail("APK still contains 9-patches (KitKat inflate NPE): " + ", ".join(nine))
    if "classes.dex" not in names:
        fail("APK missing classes.dex")
    dex = dex_blob()
    if dex[:8] != b"dex\n035\x00":
        fail(f"DEX version is {dex[:8]!r}, KitKat needs 035")
    latin = dex.decode("latin1", errors="ignore")
    java_names = sorted(p.stem for p in SRC.glob("*.java"))
    if "App" in java_names:
        fail("custom Application class App.java must not be shipped")
    for stem in java_names:
        needle = f"Lcom/nemesis/manager/{stem};"
        if needle not in latin:
            fail(f"classes.dex missing {needle} (Make/d8 skipped a class)")
    dump = subprocess.check_output(
        [AAPT, "dump", "xmltree", str(APK), "AndroidManifest.xml"],
        text=True,
        errors="replace",
    )
    if "(type 0x10)0x15" not in dump:
        fail("Manager versionCode is not 21:\n" + dump[:800])
    if 'versionName(0x0101021c)="1.16.4"' not in dump and '="1.16.4"' not in dump:
        fail("Manager versionName is not 1.16.4:\n" + dump[:800])
    if "SetupReceiver" not in dump or "mobis.intent.action.SHORTCUT_SETUP" not in dump:
        fail("AndroidManifest missing SETUP shortcut receiver")
    if "mobis.intent.action.SHORTCUT_MODE" not in dump:
        fail("AndroidManifest missing configurable shortcut actions")
    if "com.mobis.intent.action.CUSTOM_SHORTCUT" not in dump:
        fail("AndroidManifest missing faceplate custom shortcut")
    if "com.hkmc.intent.action.SETUP_SHORTCUT" not in dump:
        fail("AndroidManifest missing ModeManager SETUP shortcut")
    for needle in (
        "SHORTCUT_SETUP",
        "SHORTCUT_NAVI",
        "com.hkmc.intent.action.SETUP_SHORTCUT",
        "com.hkmc.system.app.engineering/.EngineeringModeMainActivity",
        "com.hkmc.system.app.engineering/.DealerModeActivity",
    ):
        if needle not in latin:
            fail(f"classes.dex missing {needle}")
    if "Seek up" in latin or "Blue Link" in latin or "Custom (Steering Wheel)" in latin:
        fail("trigger picker still lists buttons other than Setup and Navigation")
    if '=".App"' in dump or 'name=".App"' in dump:
        fail("AndroidManifest must not name a custom Application class")
    if "BootReceiver" not in dump:
        fail("AndroidManifest missing BootReceiver")
    if "AskActivity" in dump:
        fail("AskActivity is a second popup; questions use DialogActivity")
    if "com.hkmc.intent.action.REARQUIET_SHORTCUT" not in dump:
        fail("AndroidManifest missing rear-quiet custom-key action")
    receiver = (SRC / "SetupReceiver.java").read_text()
    if "Thread.sleep" in receiver:
        fail("SetupReceiver blocks the broadcast thread")
    if "sleep 1" not in receiver:
        fail("SetupReceiver does not retry the launch from a detached shell")
    tree = subprocess.check_output(
        [AAPT, "dump", "xmltree", str(APK), "res/layout/activity_main.xml"],
        text=True,
        errors="replace",
    )
    if "btn_usb" in tree:
        fail("activity_main still has btn_usb (old layout)")
    if "list" not in tree:
        fail("activity_main missing ListView id list")
    if "com.nemesis.manager.IconPager" not in tree:
        fail("activity_main missing the icon pager")
    for perm in ("android.permission.INTERNET", "android.permission.GET_TASKS"):
        if perm not in dump:
            fail(f"AndroidManifest missing {perm}")
    for comp in ("StoreActivity", "SettingsActivity", "AppsActivity", "UpdateService", "EventReceiver"):
        if comp not in dump:
            fail(f"AndroidManifest missing {comp}")
    if "OptionsActivity" in dump:
        fail("AndroidManifest still names OptionsActivity")
    strings = subprocess.check_output(
        [AAPT, "dump", "resources", str(APK)],
        text=True,
        errors="replace",
    )
    dlg_style = re.search(
        r"(0x7f[0-9a-f]+) com\.nemesis\.manager:style/Hkmc\.Dialog",
        strings,
    )
    if not dlg_style or ("@" + dlg_style.group(1)) not in dump:
        fail("DialogActivity is not the floating popup theme")
    for needle in ("close", "packages", "services", "logs",
                   "string/engineering", "string/dealer", "string/open_fail",
                   "string/settings", "string/trigger_button",
                   "string/store", "string/my_packages", "string/update_offline",
                   "string/installing", "string/uninstalling",
                   "string/confirm_install_named", "string/uninstall_first",
                   "string/confirm_uninstall", "string/confirm_stop"):
        if needle not in strings:
            fail(f"missing string {needle}")
    title = subprocess.check_output(
        [AAPT, "dump", "xmltree", str(APK), "res/layout/title_bar.xml"],
        text=True,
        errors="replace",
    )
    mid = re.search(
        r"(0x7f[0-9a-f]+) com\.nemesis\.manager:id/title_bar_credit",
        strings,
    )
    if not mid or ("@" + mid.group(1)) not in title:
        fail("title_bar missing title_bar_credit")
    list_xml = (ROOT / "manager" / "res" / "layout" / "activity_list.xml").read_text()
    if "co_bt_bottom" in list_xml:
        fail("activity_list still uses stretched co_bt_bottom")
    if "co_bt_title" not in list_xml:
        fail("activity_list must use co_bt_title (same as Close)")
    log_xml = (ROOT / "manager" / "res" / "layout" / "activity_log.xml").read_text()
    if "@string/close" in log_xml:
        fail("activity_log has a second Close; Close belongs on the title bar only")
    dlg = (ROOT / "manager" / "res" / "layout" / "dialog_alert.xml").read_text()
    if "pop_icon" in dlg or "pop_bg" in dlg:
        fail("dialog still uses the stock popup chrome")
    if "@layout/title_bar" not in dlg:
        fail("dialog missing Manager title bar")
    if "popup_card" not in dlg or 'layout_gravity="center"' not in dlg:
        fail("dialog is not a centered card")
    if 'layout_height="match_parent"' in dlg.split("popup_card", 1)[-1][:400]:
        fail("popup card is full height")
    if "@drawable/co_bg\"" in dlg:
        fail("popup card uses the full-screen background bitmap")
    print("OK: Manager APK DEX 035, all Java classes present, no Application, versionCode 21")


if __name__ == "__main__":
    main()
