# NEMESIS packages

NEMESIS is the head unit’s **Debugging Execution Interface**. A **package** is a zip you install through that interface so tools, daemons, and optional APKs live under `/data/nemesis/apps/<id>/` and can be started, launched, or removed without flashing the system image again.

This document assumes NEMESIS is already on the unit. USB install of a zip uses `NEMESIS.data` with `type=install` (see [05](05-Run-Scripts.md) for execute vs install). Opening Manager is `type=open` ([02](02-Debugging-Execution-Interface.md)).

---

## 1. What a package is

A zip whose **root** contains **`NEMESIS.data`** (INI). The builder copies the whole directory tree into the zip, paths relative to the package folder.

Installed layout:

```text
/data/nemesis/apps/<id>/          unpacked tree
/data/nemesis/packages.db         id|name|locked|types
/data/nemesis/services.db         registered services
```

The locked core package **`usb-handler`** is seeded by the service manager. It cannot be updated or uninstalled except by wiping NEMESIS.

Same `id` already installed → **update** (Yes/No in Manager when Android is up; automatic when Android is down). There is never a second copy of the same id.

---

## 2. `NEMESIS.data` — `[nemesis]`

| Key | Required | Meaning |
|-----|----------|---------|
| `id` | yes | Install id. Max 64 characters: letters, digits, `.`, `_`, `-`. No leading `.`, no `..`. |
| `name` | recommended | Display name (defaults to `id`) |
| `version` | recommended | Free-form version string |
| `types` | recommended | Comma list, e.g. `application,service` (shown in `pkg-list`) |
| `depends` | no | Packages that must be installed first: ids separated by commas, each optionally `id>=version`. See below. |
| `preinstall` | no | Script run **before** the tree is copied, cwd = staging dir |
| `postinstall` | no | Script run **after** copy, APK install, and service register; cwd = `/data/nemesis/apps/<id>` |
| `preuninstall` | no | Script run after services are stopped, before APKs/`rm` |
| `postuninstall` | no | Copied to tmp then run so it still exists after the tree is deleted |

All listed scripts run as **root** (or `system` if you set `uid=system` on a service; hooks themselves use root).

### Dependencies

```ini
depends=media.codecs>=2.0, adb.wifi
```

- **Install** (including `--update`) is refused with exit code 4 while any listed package is missing, or older than its `>=` version. Versions compare as dot-separated numbers (`1.10` is newer than `1.9`, and a suffix such as `-rc1` is ignored). Write `id>=version` without spaces.
- **Uninstall** of a package is refused (exit 4) while another installed package lists it. Uninstall the dependent first. `wipe` still removes everything.
- **Updating** a package to a version that an installed dependent's `>=` no longer accepts is refused too.

The checks run before any hook, so `preinstall` never sees a missing dependency. The reason is printed on stderr and shown by the Manager on uninstall. A refused USB install shows the usual failure dialog, and the reason is in `nemesis log service usb-handler/usb-handler`.

---

## 3. Applications — `[app.N]`

Zero or more sections `[app.0]`, `[app.1]`, …

| Key | Meaning |
|-----|---------|
| `kind` | `sh` — launchable script. `apk` — file passed to `pm install -r` |
| `path` | Relative path of that script or apk inside the package (`bin/tool.sh`, `app/Something.apk`) |
| `package` | Android package name. Uninstall calls `pm uninstall` with this name |
| `component` | Activity `package/class`. `pkg-launch` prefers this |
| `uid` | `root` (default) or `system` |

`nemesis pkg-launch <id>` walks `[app.N]` in order. A `component` starts that activity. Otherwise a `package` starts its launcher activity. Otherwise `kind=sh` runs the script. A `kind=instance` service for that package is started too.

`kind=apk`: install runs `pm install -r` on the file at `path`. KitKat `pm` prints `Success` or `Failure [...]` and still exits 0, so NEMESIS trusts that text. While `sys.boot_completed` is `1`, this head unit rejects a signer that is not on the OEM allowlist and `pm` prints `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES`, including for a package that was never installed. NEMESIS sets `sys.boot_completed` to `0` for that `pm` call only, then restores the previous value. It does not rebroadcast `BOOT_COMPLETED`. If that failure is `INCONSISTENT_CERTIFICATES` and `pm path` shows the package is already present, NEMESIS uninstalls it and retries once. Uninstall calls `pm uninstall` with `package`, or with the package half of `component`. With neither key, uninstall does not call `pm`. When Android is up, a `pm` result other than `Success` fails the NEMESIS install. When Android is down, the apk stays in the package tree and `pkg-launch` tries `pm install` then.

---

## 4. Services — `[service.N]`

Sections `[service.0]`, `[service.1]`, … Registry name is **`<package-id>/<name>`** so two packages can both use `name=daemon`.

| Key | Meaning |
|-----|---------|
| `name` | Local name (required) |
| `kind` | `sh` (default) — `/system/bin/sh` on `path`. `instance` — `am start` / `am startservice` on `path` (needs Android). |
| `path` | Script relative to the package, or activity/service component for `instance` |
| `uid` | `root` (default) or `system` (uid/gid 1000) |
| `mode` | `restart` — svcmgr restarts when the pid dies. Ordinary packages stop after 5 rapid deaths; the locked `usb-handler` is always restarted. `background` — start once, no restart. `once` — wait for exit. |

`kind=instance` is deferred while Android is down. `kind=sh` does not need zygote.

Stdout/stderr of `sh` services go to `/data/nemesis/logs/<id>/<name>.log` (survives reboot).

CLI:

```sh
nemesis service list
nemesis service start hello.tool/hello-daemon
nemesis service stop hello.tool/hello-daemon
nemesis service status hello.tool/hello-daemon
nemesis log service hello.tool/hello-daemon
```

---

## 5. How to create a package

### 5.1 Directory

```text
my-tool/
  NEMESIS.data
  bin/tool.sh          # optional app
  svc/daemon.sh        # optional service
  scripts/pre.sh
  scripts/post.sh
  scripts/preun.sh
  scripts/postun.sh
  app/Something.apk    # optional
```

Minimal `NEMESIS.data`:

```ini
[nemesis]
version=1.0
id=my.tool
name=My Tool
types=application,service
preinstall=scripts/pre.sh
postinstall=scripts/post.sh
preuninstall=scripts/preun.sh
postuninstall=scripts/postun.sh

[app.0]
kind=sh
path=bin/tool.sh
uid=root

[service.0]
name=my-daemon
kind=sh
path=svc/daemon.sh
uid=root
mode=restart
```

Scripts must be `#!/system/bin/sh` and toolbox-only. Prefer `nemesis remount rw` / `nemesis remount ro` to change `/system`. Toolbox `mount -o remount,rw /system` returns `Operation not permitted` on this unit even as uid 0 (the kernel refuses a remount that would drop `nosuid`). Hooks and `nemesis root` put `/data/nemesis/bin` first on `PATH`, so a bare `mount -o remount,rw /system` in a hook is wrapped. You can still `export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin` for other tools.

Worked examples in the source tree: `examples/package-hello/`, `examples/package-hello-v2/` (`mode=background` on v2), `packages/usb-handler/`, `packages/adb-tcp/`, `packages/wireless-projection/`, `packages/projection-tbt/`.

Packages that change files already on the head unit (`adb.tcp`, `wireless.projection`, `voicehandler.vr-gates`, `projection.tbt`, `carplay.session`) copy those live files into `/data/nemesis/backup/<id>/` during `postinstall`, then apply their change. `adb.tcp` and `wireless.projection` restore that snapshot once in `preuninstall`, then delete the backup directory. If the restore fails, the backup stays. `voicehandler.vr-gates` restores in `preuninstall` and drops its backup from `postuninstall` only after the live file is stock again. `adb.tcp` and `wireless.projection` restore only the snapshot taken on that unit, so the same package can be installed on another head unit and uninstalled back to that unit's own files. A file shipped inside the package is the payload install writes (a feature XML, a patched odex, or a patched apk). `projection.tbt` and `carplay.session` are built for firmware 260507.

### 5.2 Zip

```bash
python3 tools/make_nemesis_package.py /path/to/my-tool -o my.tool.zip
```

That zips every file under the directory. Inspect with `nemesis pkg-info my.tool.zip` on the unit.

### 5.3 Install on the unit

**USB (usual):**

```ini
[nemesis]
version=1.0
type=install
path=packages/my.tool.zip
```

USB root:

```text
NEMESIS.data
packages/my.tool.zip
```

`path` must be relative (no `/`, no `..`, no shell metacharacters). Insert the stick. If Android is up, confirm install. If the id exists, you get an update prompt.

**CLI:**

```sh
nemesis install /storage/usb0/packages/my.tool.zip
nemesis install --update /storage/usb0/packages/my.tool.zip
```

**Manager:** Packages list after USB or CLI install; Launch / Uninstall there.

### 5.4 Uninstall

```sh
nemesis uninstall my.tool
```

Uninstall runs `preuninstall` in `/data/nemesis/apps/<id>/`, removes that package's APKs and services, runs `postuninstall` if the package lists one, then deletes the package directory. `adb.tcp` and `wireless.projection` do not list `postuninstall`. Their snapshot in `/data/nemesis/backup/<id>/` is removed at the end of a successful `preuninstall`.

Locked packages print `package is locked` and stay. Wipe NEMESIS removes everything, including locked.

---

## 6. Install order (what the CLI does)

1. Read `NEMESIS.data` from the zip; reject bad `id`.
2. Refuse if locked and already installed.
3. Require `--update` (or USB update confirm) if the id exists.
4. Extract to `/data/local/tmp/nemesis/stage/<id>` (zip-slip `..` members are not applied).
5. `preinstall` → copy tree to `/data/nemesis/apps/<id>/` → `pm install` APKs → register services → write `packages.db` → `postinstall` → start services.

Logs: `nemesis log dump` and `/data/nemesis/nemesis.log`.
