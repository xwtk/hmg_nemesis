# NEMESIS run scripts (`NEMESIS.sh`)

NEMESIS is the head unit’s **Debugging Execution Interface**. A **run script** is a one-shot shell file on USB, started with **root rights**, that is **not** installed as a package. Use it for a diagnostic command, a file copy, or a short procedure. Lasting daemons belong in a [package](04-Packages.md).

This document assumes NEMESIS is already on the unit.

---

## 1. Two different USB files

| File | Role |
|------|------|
| **`NEMESIS.data`** | INI descriptor. The USB Handler **only** reacts to this file. |
| **`NEMESIS.sh`** (or any relative path you name in `path=`) | The script. Ignored by the Handler unless `type=execute` points at it. |

A stick with only `NEMESIS.sh` does **nothing** on insert. Manager can still run `/storage/usb0/NEMESIS.sh` from **Run USB NEMESIS.sh** once Manager is open.

---

## 2. How the USB Handler runs a script

The Handler is the locked `usb-handler` service. It loops:

1. Wait until a USB volume is mounted (`/storage/usb0`, `usb1`, `usb2`, or `/mnt/media_rw/usb*`).
2. Look for **`NEMESIS.data`** on that volume.
3. Read `[nemesis] type=` and `[nemesis] path=`.
4. Handle that descriptor once. Re-arm when the stick is unmounted, when `NEMESIS.data` disappears, or when the file’s identity (inode/size/mtime) changes — so a new package on the same stick, or a reinsert after a stale mount, is seen without restarting the service.

`type` values:

| `type` | `path` | Android up | Android down |
|--------|--------|------------|----------------|
| `execute` | required, relative | Yes/No, then run the script as root, cwd = USB root | **Runs without a dialog** (recovery) |
| `install` | required, relative zip | Yes/No, then `nemesis install` | **Installs without a dialog** |
| `open` | optional | Starts Manager | Skipped (no UI) |

Anything else → “Invalid package descriptor”.

`path` rules (rejected as invalid):

- must not be empty for execute/install
- must not start with `/`
- must not contain `..`
- must not contain `; | & \` $ ' " newlines ` ( ) < >`

The Handler waits up to ~3 seconds if `type=` is still empty (incomplete copy), then treats the descriptor as invalid.

Timeout for a Yes/No dialog: **60 seconds**. Unplugging the stick cancels the wait.

---

## 3. Manual run from Manager

Menu **Run USB NEMESIS.sh** always executes:

```text
/system/bin/sh /storage/usb0/NEMESIS.sh
```

through the privileged CLI (`nemesis root -c …`). That path is **usb0** only. It does not read `NEMESIS.data`. Use this when you already have Manager open and a script named exactly `NEMESIS.sh` on the stick.

---

## 4. `type=execute` descriptor

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

When Android is up, the dialog asks whether to execute the compatible item on the USB drive. **Yes** runs the script; **No** skips. When Android is down, nobody can answer the dialog, so the Handler holds the stick and prompts once Android is up. Unattended runs (recovering a unit that never shows UI) happen only if the owner has created the root-owned flag `/data/nemesis/usb-recovery`, for example from a prompted `type=execute` script: `touch /data/nemesis/usb-recovery`. Delete it again when done.

The script is started as:

```sh
/system/bin/sh <full-path-to-script>
```

with **cwd = USB root** and **uid root**. Exit code 0 → success dialog (if Android is up); non-zero → failure dialog. The Handler waits until the script **exits** (or the USB is gone).

---

## 5. How to write `NEMESIS.sh`

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

## 6. Creating a stick — checklist

**Execute (automatic on insert):**

1. Format the stick so the HU mounts it as usb0 (usual USB layout for this unit).
2. Write `NEMESIS.data` with `type=execute` and `path=NEMESIS.sh`.
3. Write the script at that relative path; `chmod` is not required on VFAT.
4. Insert. Confirm Yes if Android is up, or wait for the script if Android is down.
5. To run the same job again, unplug, or replace/remove `NEMESIS.data` (the Handler re-arms on a new file identity).

**Open Manager only:** `type=open`, no script required ([02](02-Debugging-Execution-Interface.md)).

**Install a zip:** `type=install` and `path=packages/foo.zip` ([04](04-Packages.md)).

**Manager-only script:** file named `/storage/usb0/NEMESIS.sh`, open Manager with `type=open` (or `am start`), then **Run USB NEMESIS.sh**.

---

## 7. Logs

USB Handler lines go to `/data/nemesis/nemesis.log` and `/data/nemesis/logs/usb-session.log` (`handled type=execute path=... reply=yes|recovery|timeout|unmount`). Copy from Manager **Logs → Copy to USB** (`/storage/usb0/NEMESIS-logs`).
