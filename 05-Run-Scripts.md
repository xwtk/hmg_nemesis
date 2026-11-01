# NEMESIS run scripts (`NEMESIS.sh`)

NEMESIS is the head unit’s **Debugging Execution Interface**. A **run script** is a one-shot shell file on USB, started with **root rights**, that is **not** installed as a package. Use it for a diagnostic command, a file copy, or a short procedure. Lasting daemons belong in a [package](04-Packages.md).

This document assumes NEMESIS is already on the unit.

---

## 1. Two different USB files

| File | Role |
|------|------|
| **`NEMESIS.data`** | INI descriptor. The USB Handler **only** reacts to this file. |
| **`NEMESIS.sh`** (or any relative path you name in `path=`) | The script. Ignored by the Handler unless `type=execute` points at it. |

A stick with only `NEMESIS.sh` does **nothing** on insert.

---

## 2. How the USB Handler runs a script

The Handler is the locked `usb-handler` service. It loops:

1. Wait until a USB volume is mounted (`/storage/usb0`, `usb1`, `usb2`, or `/mnt/media_rw/usb*`).
2. Look for **`NEMESIS.data`** on that volume.
3. Read `[nemesis] type=` and `[nemesis] path=`.
4. Handle that descriptor once. Re-arm when the stick is unmounted, when `NEMESIS.data` disappears, or when the file’s identity (inode/size/mtime) changes — so a new package on the same stick, or a reinsert after a stale mount, is seen without restarting the service.

`type` values:

| `type` | `path` | Android up | Android down, with `NEMESIS.emergency` |
|--------|--------|------------|----------------|
| `execute` | required, relative | Yes/No, then run the script as root, cwd = USB root | **Runs without a dialog** (recovery) |
| `install` | required, relative zip | Yes/No, then `nemesis install` | **Installs without a dialog** |
| `update` | required, relative zip | Yes/No, then `nemesis update`. Same or older than the running core: a message, no prompt. | **Updates without a dialog** |
| `open` | optional | Starts Manager | Skipped (no UI) |
| `list` | not used | Opens the Package Store on the stick's list | Waits for Android |

Anything else → “Invalid package descriptor”.

**Android down.** The Handler acts only if the stick root also has a file named `NEMESIS.emergency`. Its contents do not matter. Without it, the stick waits until Android comes up and is then prompted as usual, so a stick left plugged in does not run again on the next boot. A recovery stick made for 1.15 needs this file added, or it does nothing on a unit whose Android will not start. `examples/usb-stick-recover-buildprop/` has it.

`path` rules (rejected as invalid):

- must not be empty for execute/install/update
- must not start with `/`
- must not contain `..`
- must not contain `; | & \` $ ' " newlines ` ( ) < >`

The Handler waits up to ~3 seconds if `type=` is still empty (incomplete copy), then treats the descriptor as invalid.

Timeout for a Yes/No dialog: **60 seconds**. Unplugging the stick cancels the wait.

---

## 3. `type=execute` descriptor

USB root example (`examples/usb-stick-execute/`):

```text
NEMESIS.data
NEMESIS.sh
```

`NEMESIS.data`:

```ini
[nemesis]
version=1.0
type=execute
path=NEMESIS.sh
```

`path` can be another relative name (`scripts/diag.sh`) as long as the file exists on the stick.

When Android is up, the dialog asks whether to execute the compatible item on the USB drive. **Yes** runs the script; **No** skips. When Android is down and the stick has `NEMESIS.emergency`, the Handler logs `android down; auto-confirm` and runs it, so a stick can recover a unit that is not showing UI. Without that file, the script waits for Android and then gets the dialog.

The script is started as:

```sh
/system/bin/sh <full-path-to-script>
```

with **cwd = USB root** and **uid root**. Exit code 0 → success dialog (if Android is up); non-zero → failure dialog. The Handler waits until the script **exits** (or the USB is gone).

---

## 4. How to write `NEMESIS.sh`

KitKat toolbox only:

```sh
#!/system/bin/sh
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

# your commands
exit 0
```

Do not call `busybox`, `awk`, `inotifywait`, `unzip` as host tools unless you shipped them in a package. `nemesis` itself is available at `/system/xbin/nemesis` for `pkg-list`, `install`, `log dump`, and so on.

Keep the script on the USB (or copy what you need onto `/data/nemesis/`). `/data/local/tmp` is cleared on reboot.

Minimal example (source `examples/execute-hello/NEMESIS.sh`):

```sh
#!/system/bin/sh
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
mkdir -p /data/nemesis
echo "execute-hello ok" > /data/nemesis/execute-hello.ok
exit 0
```

---

## 5. Creating a stick — checklist

**Execute (automatic on insert):**

1. Format the stick so the HU mounts it as usb0 (usual USB layout for this unit).
2. Write `NEMESIS.data` with `type=execute` and `path=NEMESIS.sh`.
3. Write the script at that relative path; `chmod` is not required on VFAT.
4. To recover a unit whose Android does not come up, also put an empty file named `NEMESIS.emergency` at the stick root. Remove it afterward.
5. Insert. Confirm Yes if Android is up. If Android is down, the script runs only with `NEMESIS.emergency`.
6. To run the same job again, unplug, or replace/remove `NEMESIS.data` (the Handler re-arms on a new file identity).

**Open Manager only:** `type=open`, no script required ([02](02-Debugging-Execution-Interface.md)).

**Install a zip:** `type=install` and `path=packages/foo.zip` ([04](04-Packages.md)).

**Update NEMESIS:** `type=update` and `path=` the update zip ([03](03-Install-and-Uninstall.md)).

**Package list for the Store:** `type=list` ([04](04-Packages.md)).

---

## 6. Logs

USB Handler lines go to `/data/nemesis/nemesis.log` and `/data/nemesis/logs/usb-session.log` (`handled type=execute path=... reply=yes|recovery|timeout|unmount`). Copy from Manager **Logs → Copy to USB** (`/storage/usb0/NEMESIS-logs`).
