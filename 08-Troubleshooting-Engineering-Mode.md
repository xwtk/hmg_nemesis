# Troubleshooting: Engineering Mode will not open

NEMESIS installs through Engineering Mode (**USB Copy → Update DSP → Update OTA**). If the mode will not open, there is nothing to install yet — and most install-time "NEMESIS does nothing" reports reduce to this page. Entry sequences are community knowledge and vary by firmware and market; the code table below originates in kiaevforums thread 8018.

## 1. Which failure shape?

| Shape | Meaning |
|-------|---------|
| No password prompt appears at all | Entry mechanics: wrong route, wrong tap target, or too few taps |
| Prompt appears, OK does nothing (no error, no lockout) | Wrong code — or a dead entry dialog, see section 3 |

## 2. Shape 1: no prompt

The tap route, with the details that matter:

1. FM radio on, volume at 2.
2. Setup → General → SW Info/Update.
3. Tap 5–10× on the **blank area to the LEFT of the Update button** — not on any text row. (The Firmware Version row is the MICOM body controller, not the head unit; tapping it does nothing.) Then 1–2× to the RIGHT of that spot.
4. Counts vary by firmware: 6+1, 8+1 and 10+1 are all documented on US units. Sweep them before concluding the route is dead.

The search route, on recent firmware: Setup → Search (magnifier, upper-left) → type `go2engmodenow` (lowercase) → the password prompt opens directly. Try this before diagnosing anything from failed taps: it bypasses tap-count drift entirely.

## 3. Shape 2: OK does nothing

On US S5W_M units a wrong code silently does nothing — the dialog stays open, no error, no lockout message. Sweep the table in section 4 before diagnosing the dialog.

A dead dialog is a real failure shape: one US EV6 on `V015.012.260507` had a tap-route prompt that silently ignored every code — surviving a stock reflash and a settings reset — while the search-route prompt accepted the correct year code on the first try. If the full table no-ops on one route, switch entry routes before grinding more codes. On units with NEMESIS history, leftover state from a failed package uninstall can produce this shape; clear it (document 03, section 5.4) rather than retrying codes.

## 4. The code table (US S5W_M)

The code is year-keyed from GPS time, not firmware version. With no GPS fix (e.g. a garage car) the unit falls back to its default code.

| GPS year ends in | Code |
|-----------------|------|
| no GPS fix (default) | `20230411` |
| 0 | `20231009` |
| 1 | `20240101` |
| 2 | `20240210` |
| 3 | `19190301` |
| 4 | `19190413` |
| 5 | `19610505` |
| 6 | `19500625` |
| 7 | `19480717` |
| 8 | `19450815` |
| 9 | `20230928` |

Dealer Mode (`2400`, knob sequence) and Full Engineer Mode (two-knob, 8-digit) are different menus without USB Copy.

The widely-published per-firmware year-rotation lists (`2023=26031236`, `2024=25041045`, …) are a different firmware family and are rejected on US units. Do not use them here.

## 5. Does OTA status matter?

No. NEMESIS needs OTA update **support**, not an OTA **subscription** (README, Compatibility Check). An OTA row reading "Not Enabled (Kia Connect Extra Services Required)" is the telematics subscription display; the OTA update logic is still in the firmware, and that is what the install path uses.

## 6. No Engineering Mode, no install

There is no supported install path without Engineering Mode on stock firmware: USB `type=execute` packages and `NEMESIS.sh` require NEMESIS to already be installed (document 05).
