# NEMESIS packages

NEMESIS is the head unit’s **Debugging Execution Interface**. A **package** is a zip you install through that interface so tools, daemons, and optional APKs live under `/data/nemesis/apps/<id>/` and can be started, launched, or removed without flashing the system image again.

This document assumes NEMESIS is already on the unit. USB install of a zip uses `NEMESIS.data` with `type=install` (see [06](06-Run-Scripts.md) for execute vs install). Opening Manager is `type=open` ([03](03-Debugging-Execution-Interface.md)).

---

## 1. What a package is

A zip whose **root** contains **`NEMESIS.data`** (INI). The builder copies the whole directory tree into the zip, paths relative to the package folder.

Installed layout:

```text
/data/nemesis/apps/<id>/          unpacked tree
/data/nemesis/packages.db         id|name|locked|types|package-version|short-desc
/data/nemesis/services.db         registered services
```

The locked core packages **`usb-handler`** and **`nemesis.update-monitor`** are seeded by the service manager. They cannot be updated or uninstalled except by wiping NEMESIS or by a `nemesis update` package that carries a newer copy.

Same `id` already installed → **update** (Yes/No in Manager when Android is up). With Android down, a stick is handled only if it has `NEMESIS.emergency`, and then without a prompt. There is never a second copy of the same id.

---

## 2. `NEMESIS.data` — `[nemesis]`

| Key | Required | Meaning |
|-----|----------|---------|
| `version` | recommended | Descriptor format: `1.2`, `1.1` or `1.0`. Missing means `1.0`. Dotted numbers only. A newer version than this release reads (for example `1.3`) makes the package incompatible: the Store says to update NEMESIS, and install refuses it even with the risk accepted. NEMESIS update packages are exempt. |
| `favicon` | no | `1.2`: an image inside the package (PNG or JPEG, up to 256 KiB), shown in the Store, My Packages and Package Manager. It must be a relative path with no `..`. Missing, too large, or not an image shows the generic app icon. |
| `id` | yes | Install id. Max 64 characters: letters, digits, `.`, `_`, `-`. No leading `.` or `-`, no `..`. |
| `name` | recommended | Display name (defaults to `id`) |
| `type` | recommended | Comma list, e.g. `application,service` (shown in `pkg-list`). A `1.0` descriptor names this key `types`, and each format reads the other name as a fallback. |
| `package-version` | recommended | The package's own version. Dotted numbers only; anything else is refused. Used for updates and for `depends`. |
| `short-desc` | recommended | One line for lists. Over 64 characters is refused. |
| `detailed-desc` | no | Text for the details screen. Over 256 characters is refused. |
| `depends` | no | Comma list of `id` or `id@spec`, and `!id` or `!id@spec` for packages this one cannot live with. See below. |
| `os` | no | Comma list of OS version globs, or `any`. Missing means `any`. Over 1024 characters is refused. |
| `models` | no | Comma list of model globs, or `any`. Missing means `any`. Over 1024 characters is refused. |
| `preinstall` | no | Script run **before** the tree is copied, cwd = staging dir |
| `postinstall` | no | Script run **after** copy, APK install, and service register; cwd = `/data/nemesis/apps/<id>` |
| `preuninstall` | no | Script run after services are stopped, before APKs/`rm` |
| `postuninstall` | no | Copied to tmp then run so it still exists after the tree is deleted |

A package may also carry `[patch.N]`, `[file.N]` and `[prop.N]` sections to change stock files and properties without writing `/system`. See [09 - LivePatch](09-LivePatch.md).

All listed scripts run as **root** (or `system` if you set `uid=system` on a service; hooks themselves use root).

Hook, `[app.N]`, and `[service.N]` paths may be absolute, but a path with a `..` component is refused at install.

A `spec` in `depends` is one of:

| Spec | Installed version must be |
|------|---------------------------|
| `any`, or no `@spec` | Anything |
| `1.2` | Exactly 1.2 (`1.2.0` counts as the same) |
| `>=1.2` | 1.2 or newer |
| `<=1.2` | 1.2 or older |
| `1.2-1.5` | From 1.2 to 1.5 |

A `!` makes the item an incompatibility: `depends="base@>=1.0,!other,!legacy@<=2.0"`. The package will not install while `other`, or `legacy` at 2.0 or older, is installed, and those packages cannot install while it is. The user has to uninstall one of them first. A package cannot name itself, and cannot both need and refuse the same id.

`os` and `models` items may use `*` and `?`. `nemesis compat <zip|package-id>` prints the verdict and its reasons:

| Verdict | When |
|---------|------|
| `full` | `os` matches |
| `partial` | `os` does not match, and `models` matches |
| `incompatible` | Neither `os` nor `models` matches |

`incompatible` stops the install with exit code 20 until `--accept-risk` is passed. Manager asks first.

The verdict is about the head unit only. Dependencies are listed as reasons but do not change it. They are checked at install:

| Exit code | When | Output lines |
|-----------|------|--------------|
| 21 | The update no longer satisfies an installed package that depends on this one | `dependent <id>\|<name>` |
| 22 | A dependency is missing or the wrong version. `--accept-risk` does not override this. | `dep-missing <id>`, `dep-version ...` |
| 23 | This package and an installed one exclude each other with `!` | `conflict <id>\|<name>` |

Uninstalling a package that others depend on exits 4 and lists them. A dependency can be updated while its dependents stay installed, as long as the new version still satisfies them. `install --force` does not skip these checks: it only carries on when the old version's uninstall hook or `pm` fails during an update.

When a package needs something, Manager looks for it on the USB drive first and then online. If everything can be found, it lists what will be installed or updated and asks before continuing, dependencies first. If something cannot be found, the install stops and says what is missing.

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

A `mode=restart` service that dies 5 times is disabled and `service list` shows it as `failed`. Starting it by hand gives it one more run, and if it stays up for 10 seconds the count is cleared so restarts work again. If it dies during that run it stays disabled. Installing, updating, or uninstalling the package also clears the count.

Stdout/stderr of `sh` services go to `/data/nemesis/logs/<id>/<name>.log` (survives reboot).

CLI:

```sh
nemesis service list
nemesis service start hello.tool/hello-daemon
nemesis service stop hello.tool/hello-daemon
nemesis service status hello.tool/hello-daemon
nemesis log service hello.tool/hello-daemon
```

`service stop` holds until `service start`, a reinstall, or the next time the service manager starts (a reboot). Built-in services always restart.

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
version=1.1
id=my.tool
name=My Tool
type=application,service
package-version=1.0
short-desc="One line for the list"
os=any
models=any
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

Packages that change files or properties already on the head unit (`adb.tcp`, `wireless.projection`, `voicehandler.vr-gates`, `projection.tbt`, `carplay.session`, `enhanced.projection`) do it with LivePatch: `[patch.N]`, `[file.N]` and `[prop.N]` sections in `NEMESIS.data`. `/system` is not written, and no hook backs up or restores live files. See [09 - LivePatch](09-LivePatch.md). Their 1.x releases edited `/system` in place and kept a copy in `/data/nemesis/backup/<id>/`. The 2.x `preinstall` refuses to install while that backup exists, so uninstall the 1.x copy first. `projection.tbt` and `carplay.session` are built for firmware 260507.

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

`path` must be relative (no `/`, no `..`, no shell metacharacters). Insert the stick. If Android is up, confirm install. If the id exists, you get an update prompt. If Android is down, the stick waits for Android unless its root also has `NEMESIS.emergency`.

**USB package list:** `type=list` opens the Package Store on the stick's own list. Each row is a section:

```ini
[nemesis]
version=1.1
type=list

[package.0]
path=packages/my.tool.zip
name=My Tool
short-desc=One line for the list
published=2026-10-31
```

`path` is required and follows the same rules. A row with a bad `path` is dropped. `name` falls back to `path`. Use `YYYY-MM-DD` for `published`, because sorting by date compares the text.

**CLI:**

```sh
nemesis install /storage/usb0/packages/my.tool.zip
nemesis install --update /storage/usb0/packages/my.tool.zip
```

**Manager:** **Package Store** installs from the portal or a USB stick (a `type=list` stick, or a `type=install` stick, which shows its one package). Anything installed by any path appears in **My Packages**, which has Launch and Uninstall on the package's screen.

**Queue:** Store installs, USB installs and Manager uninstalls go through one queue in `/data/nemesis/queue`, one job at a time. No progress popup appears: each one opens **Package Manager** on that package. Package Manager lists Available updates, In progress, Interrupted and Installed. Its rows show the script's status line and a progress bar. The Store downloads up to 3 packages at once, and each download has an X to cancel it. A zip on a USB stick is copied to `/data` first, so pulling the stick does not break the install.

```sh
nemesis queue add [--accept-risk] [--move] <zip>   # prints "queued <seq> <id>"
nemesis queue uninstall <id>
nemesis queue resume <seq|all> [--accept-risk]
nemesis queue delete <seq|all>
nemesis queue list
```

A `reboot` from a hook while a job runs does not reboot. It is held until the queue is empty. Then NEMESIS shows a 15 s countdown: **Restart now**, or **Not now** to skip the reboot. Outside a job, `reboot` and `nemesis reboot` reboot right away. Jobs that a reboot or power loss cut off are **Interrupted**. On the next boot Package Manager asks whether to continue them. One that stopped inside a script is flagged, because the unit may be half-changed.

### 5.4 Uninstall

```sh
nemesis uninstall my.tool
```

Uninstall runs `preuninstall` in `/data/nemesis/apps/<id>/`, removes that package's APKs and services, runs `postuninstall` if the package lists one, then deletes the package directory. A LivePatch package's mounts and props are removed as part of the same queue job (see [09 - LivePatch](09-LivePatch.md)).

Locked packages print `package is locked` and stay. Wipe NEMESIS removes everything, including locked. If `preuninstall` or `pm uninstall` fails, the package stays, its services are started again, and the exit code is 5. `--force` carries on past that failure.

---

## 6. Install order (what the CLI does)

1. Read `NEMESIS.data` from the zip, the copy nearest the root if there are several; reject a bad `id` or a `..` path.
2. Refuse if locked and already installed.
3. Require `--update` (or USB update confirm) if the id exists.
4. Refuse an update that would break an installed dependent (exit 21).
5. Refuse unmet dependencies (exit 22) and packages excluded with `!` (exit 23).
6. Refuse an `incompatible` package unless `--accept-risk` is passed (exit 20).
7. Extract to `/data/local/tmp/nemesis/stage/<id>` (zip-slip `..` members are not applied).
8. `preinstall` → copy tree to `/data/nemesis/apps/<id>/` → `pm install` APKs → register services → write `packages.db` → `postinstall` → start services.

Logs: `nemesis log dump` and `/data/nemesis/nemesis.log`.
