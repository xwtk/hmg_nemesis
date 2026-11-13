# Troubleshooting: Engineering Mode will not open

NEMESIS installs through Engineering Mode (**USB Copy → Update DSP → Update OTA**). If the mode will not open, there is nothing to install yet — a recurring class of install reports ("nothing happens") reduces to this page (#76 is a recent example).

This page stays model-agnostic on purpose. Entry sequences, tap counts and codes are family-, firmware- and market-specific; the community threads that maintain them per family are where those live, and the README's "use the web to search" stands. What generalizes — and what this page carries — is the diagnostic method.

## 1. Which failure shape?

| Shape | Meaning |
|-------|---------|
| No password prompt appears at all | Entry mechanics: wrong route, missing prerequisites, or a count that does not match the firmware |
| Prompt appears, OK does nothing | Wrong code — or a dead entry surface; see section 3 |

## 2. Shape 1: no prompt

- Try every entry route documented for your unit's family, not just the first one you find. Routes differ — tap sequences on the SW Info/update screen, keyword entry through the Setup search on newer firmware — and prerequisites differ (radio source, volume level, which screen is open).
- Tap-based routes have firmware-dependent counts and tap targets; a count that works on one build can do nothing on another. Sweep the documented range methodically, prerequisites included, before concluding a route is dead.
- Search/keyword routes, where the firmware provides them, bypass tap-count drift entirely. Prefer them when one is documented for your family.

## 3. Shape 2: OK does nothing

- Feedback on a wrong code is often silent on HMG units: no error, no dialog change. Never conclude from one or two failed codes that the code list is wrong or the dialog is broken — sweep the full list for your family and market before diagnosing further.
- A dead entry surface is a distinct failure. Field case: one unit's entry dialog silently ignored every code, while a different entry route accepted the correct code on the first try. If the full list no-ops on one route, switch routes before grinding more codes.
- Code lists are family-specific: a list published for one firmware family or market is rejected on others. Use the list for your unit's family, not the first list a search returns.
- Units with NEMESIS history: leftover state from a failed package uninstall can produce dead-entry symptoms. Clear it (document 03; `wipe --force`, proposed in #78, exists for exactly this) rather than retrying codes.

## 4. Does OTA status matter?

No. NEMESIS needs OTA update **support**, not an OTA **subscription** (README, Compatibility Check). A telematics-subscription message on the OTA row does not remove the OTA update logic from the firmware, and that is what the install path uses.

## 5. No Engineering Mode, no install

There is no supported install path without Engineering Mode on stock firmware: USB `type=execute` packages and `NEMESIS.sh` require NEMESIS to already be installed (document 05).
