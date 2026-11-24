# LivePatch

LivePatch is how the NEMESIS Debugging Execution Interface changes files and properties that ship with the head unit. Nothing under `/system` is rewritten. The patched copy lives on `/data` and is mounted over the stock file. Uninstalling a package unmounts it, and the stock file is in view again.

LivePatch is part of NEMESIS 1.17 and later. The packages `adb.tcp`, `wireless.projection`, `voicehandler.vr-gates`, `projection.tbt`, `carplay.session` and `enhanced.projection` use it.

---

## 1. Features

| Feature | What it means |
|---------|---------------|
| No `/system` writes | Stock files stay byte for byte as shipped. The patched copy is bind-mounted over the stock path. A new file (one the firmware does not have) is shown through a stock-directory overlay, and the directory itself is not changed. |
| Clean rollback | Uninstall unmounts the copy at the next reboot. No backup or restore script is involved. |
| Properties without `build.prop` | `[prop.N]` sections set system properties at every boot, before zygote starts. `build.prop` is not edited. The stock value comes back when the package is gone. |
| Boot safety net | The patches are applied by a small wrapper around zygote (`/system/bin/app_process`). If Android fails to come up several times in a row, the next boot runs stock. See [4. Safe mode](#4-safe-mode). |
| Integrity checks | At boot, each target is checked against the stock file it was built from and against the expected output. If the firmware changed the stock file, or the output on `/data` is damaged, that target stays stock and a repair job is queued. |
| Chains | Several packages may patch the same file. Each one is applied on top of the one installed before it. Real conflicts are refused at install with a clear reason. |
| Re-port on uninstall | Removing a package from the middle of a chain rebuilds the rest of the chain on stock. The old output stays mounted until the new one is ready. |
| One reboot for the queue | When the install queue finishes, the Manager counts down a single reboot for every package that changed a file. `[prop.N]` changes apply at once. |
| Cache | Results are cached on `/data` (100 MB ceiling), so installing the same package again, or the same patch on the same build, is fast. The cache is never written when `/data` is low on space. |
| Interrupted jobs | A job cut off by a power loss shows up again in the queue. You can resume it, which skips targets that are already done, or delete it. |

---

## 2. Usage

### 2.1 Install and uninstall

LivePatch packages install and uninstall like any other package, through the Store, the Manager, a USB stick, or `nemesis queue`. See [05 - Packages](05-Packages.md#53-install-on-the-unit). The LivePatch work runs inside the queue job, and the job row shows what it is doing:

| Status | Meaning |
|--------|---------|
| `<app>: reading stock` / `stock cached` | Reading the stock app, or found it in the cache |
| `<app>: matching names` / `names cached` / `same build` | Matching the package's code to this firmware's build |
| `<app>: porting` / `checking` / `building` / `optimizing` | Applying a dex patch, then producing the odex |
| `<app>: from cache` | The same result was built before |
| `<lib>: patching` | An `exec` tool is running on that file |
| `Adding <file>` | A `[file.N]` replacement or new file |
| `Re-porting <app>` | Uninstall is rebuilding the rest of a chain |

When the queue is empty, the Manager counts down a reboot: **Restart Now**, or **Later** to apply the change at the next boot. Until then the stock file stays in view.

### 2.2 Install errors

| Error | Cause |
|-------|-------|
| `Conflicts with <pkg>: <class>.<method>` | Both packages change the same method |
| `Conflicts with <pkg>: key=value` | Both packages set the same property to different values |
| `Name clash with <pkg>: <name>` | Both packages add a class or member with the same name, and it cannot be renamed |
| `<app>: no override for <method>` | The package's code does not fit this firmware's build of the app |
| `<file>: patching failed: <reason>` | An `exec` tool failed. The reason is the last line it printed |
| `Not enough space on /data` | Checked before any work starts |
| `[patch.N]: <target> matches no file` | A required target is missing on this firmware |

The full tool and porter output is in `livepatch-<id>.log` in the NEMESIS log folder.

### 2.3 CLI

```sh
nemesis livepatch status          # wrapper=N safe=N, then one line per target: mounted, farmed or unmounted
nemesis livepatch safe on|off     # force stock at the next boot, or leave safe mode
nemesis livepatch repair <id>     # rebuild a package's outputs
nemesis livepatch discard <id>    # drop a package's outputs and show stock
nemesis livepatch selftest
nemesis prop get <key>
nemesis prop set <key> <value>
nemesis prop del <key>
```

---

## 3. Writing a LivePatch package

A LivePatch package is a normal package (see [05 - Packages](05-Packages.md)) whose `NEMESIS.data` has one or more of the sections below. They can be mixed with `[app.N]`, `[service.N]` and hooks. Hooks no longer need to copy or restore live files.

`N` runs from 1 to 99. Steps run in section order.

### 3.1 `[patch.N]` — change an existing file

| Key | Required | Meaning |
|-----|----------|---------|
| `target` | yes | A `/system/...` path. One path component may contain `*` (for example `/system/onebin/*/featurexml.cfg`), and each match becomes its own target. |
| `engine` | yes | `exec` or `dex` |
| `tool` | `exec` | Path of the tool inside the package |
| `args` | no | `exec`: extra arguments put before the input and output paths |
| `pack` | `dex` | Directory of the dex pack inside the package |
| `app` | `dex` | App name in the pack, for example `Iap2ServerApp`. Letters, digits, `.`, `_`, `-`. |
| `stop` | no | Comma list of processes to stop when the patch is applied without a reboot (`nemesis install` from the CLI), for example `com.tcc.iap2server` |
| `restart` | no | Comma list of init services to restart when the patch is applied without a reboot (CLI) |
| `optional` | no | `1`: skip the section when the target does not exist on this firmware, instead of refusing the install |

### 3.2 `[file.N]` — replace a file or add a new one

| Key | Required | Meaning |
|-----|----------|---------|
| `target` | yes | A `/system/...` path. If it does not exist, the file is added. |
| `source` | yes | The file inside the package |
| `optional` | no | `1`: skip the section when the target cannot be handled on this firmware |

A replacement must be the first step on its target. A `[patch.N]` from another package may still be applied on top of it.

### 3.3 `[prop.N]` — set a system property

| Key | Required | Meaning |
|-----|----------|---------|
| `key` | yes | Property name, under 96 bytes |
| `value` | yes | Value, at most 91 bytes |
| `restart` | no | Comma list of init services that read the property, restarted when it is set or removed |

### 3.4 The `exec` engine

LivePatch runs the tool as:

```sh
<tool> <args> <in> <out>
```

- `<in>` is the file to patch: stock, or the output of the package below in the chain. Do not change it.
- Write the result to `<out>` and nothing else.
- Exit `0` when `<out>` is written. Exit `10` when `<in>` is already patched, and the file passes through. Any other exit fails the install.
- The last line the tool prints is shown as the reason.
- The tool runs as root from the package's own folder, so `args` paths such as `files/x.apk` are relative to the package root.
- The output must depend only on `<in>` and `<args>`. LivePatch rebuilds the same file again on uninstall re-ports and repairs, and caches it by content.
- It runs on the head unit: an ARM binary, or a `#!/system/bin/sh` script. That shell is mksh with toolbox. There is no `printf`, `awk`, `sed`, `head`, `tail`, `cut`, `tr`, `find`, `basename` or `busybox`. Use `print -r --`, `read`, `case`, `grep`, `cmp`, `md5`, `cp` and `dd`.

A tool that only accepts the stock file it was built for (from `projection.tbt`):

```sh
#!/system/bin/sh
# lp-pin <stock md5> <payload> <in> <out>
want="$1"; src="$2"; in="$3"; out="$4"
set -- $(md5 "$in" 2>/dev/null)
[ "$1" = "$want" ] || { echo "lp-pin: $in is ${1:-unreadable}, this package needs $want"; exit 1; }
[ -s "$src" ] || { echo "lp-pin: missing $src"; exit 1; }
rm -f "$out"
cp "$src" "$out" || exit 1
[ -s "$out" ]
```

### 3.5 The `dex` engine

A dex patch is ported onto the app as it is on that unit, so one package can work across firmware builds. Two packages may change different methods of the same app. The pack holds smali, not a finished odex:

```text
<pack>/
  ref/<app>.zip            the stock smali the patch was written against
  ref/<app>.res            resource ids of that stock apk, one "type/name 0x7f..." per line
  patched/<app>/...smali   only the classes the patch changes or adds
  overrides/<app>/*.smali  per-method bodies for builds the automatic match cannot handle
```

The pack is built on a computer with `porter pack`. Porter is the same dex engine NEMESIS runs on the unit, and its source is in `NEMESIS Source/porter/`. It needs a JDK, `d8`, and the Debian `smali`, `baksmali`, `antlr3` and `stringtemplate` jars in `/usr/share/java` (set `JAVA_LIBS` to use another folder). Build it once from `NEMESIS Source/`, then run it through `tools/porter.sh`:

```sh
make porter
tools/porter.sh pack <src> <out pack dir> <apk dir> <app>...
```

| Input | Meaning |
|-------|---------|
| `<src>/ref/stock/<app>_smali` | Stock app, baksmali'd at API 17. It must round-trip through smali and baksmali unchanged. |
| `<src>/ref/patched/<app>_smali` | The same tree with your changes. Every stock class must still be there. |
| `<src>/overrides/<app>/` | Optional hand-written method bodies |
| `<apk dir>/<app>.apk` | The stock apk, read for resource ids |

The packer keeps only the changed classes. On the unit, LivePatch matches the package's names to the build it finds, rewrites resource ids, and builds the odex with `dexopt`.

### 3.6 Example

```ini
[nemesis]
version=1.2
id=example.patch
name=Example patch
type=application
package-version=1.0.0
short-desc="Shows each LivePatch section."

[patch.1]
target=/system/app/Iap2ServerApp.odex
engine=dex
pack=files/porter
app=Iap2ServerApp
stop=com.tcc.iap2server

[patch.2]
target=/system/lib/libexample.so
engine=exec
tool=bin/lp-edit
args=files/edit.txt
optional=1

[file.1]
target=/system/etc/permissions/example_hide.xml
source=files/empty_permissions.xml
optional=1

[prop.1]
key=ro.adb.secure
value=0
restart=adbd
```

Build and install it the same way as any other package (see [05 - Packages](05-Packages.md#52-zip)).

### 3.7 Rules

- Targets must be under `/system/`. Paths with `..` are refused.
- Do not also write the target from a hook. LivePatch owns what is mounted there.
- A new class or member that collides with one from a package below it is renamed `<name>$nms<pos>`, and renamed back when that package is uninstalled. Do not depend on reflection by the name you added.
- Test the package on the unit with another package that patches the same target, installed in both orders. Each uninstall must leave the other package working.

---

## 4. Safe mode

The next boot runs fully stock (nothing mounted, no props set) when any of these is true:

- `nemesis livepatch safe on` was run.
- Android failed to finish booting more than twice in a row, or zygote restarted more than three times in one boot.
- A USB stick with `NEMESIS.emergency` was inserted. That creates the safe flag, so the following boot is stock.

The installed packages stay installed. Fix or uninstall the package at fault, then run `nemesis livepatch safe off`.
