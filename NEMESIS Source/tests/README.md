# Tests

Host tests run on a Linux PC with `make test-meta test-hooks`. They need `cc`, `python3` and, for the hook check, `unshare` and `bash` (`mksh` if installed).

| Test | What it checks |
|------|----------------|
| `meta_host_test.c` | `NEMESIS.data` parser (`make test-meta`) |
| `setup_press_test.py`, `store_logic_test.py` | Manager button-press and Package Store logic (needs a JDK) |
| `check_manager_apk.py` | Built Manager APK is well formed |
| `pkg_live_snapshot.sh` | `adb.tcp` and `wireless.projection` install/uninstall hooks, in a private namespace |

The emulator tests (the QEMU lab that boots a Gen5W system image and installs NEMESIS in it) are not published, because they depend on a firmware image that cannot be redistributed. `make test-emu` is kept for reference and stops unless `NEMESIS_EMU_DIR` points at such a lab. A host run does not prove a head-unit script: the unit's shell has no `printf`, `awk` or `sed`, so run changed scripts on a real unit or an emulator before relying on them.
