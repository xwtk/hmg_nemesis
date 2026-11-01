# NEMESIS development framework (Linux ext4 tree)
NATIVE := native/nemesis
MANAGER := manager/NEMESIS.apk
TAR := usb_bundle/ota_package_troubleshooter.tar

.PHONY: all native manager packages troubleshooter examples test-emu test-hooks test-meta clean

all: native manager packages examples troubleshooter

native:
	$(MAKE) -C native

test-meta:
	mkdir -p out
	cc -Wall -Wextra -o out/meta_host_test tests/meta_host_test.c native/ini.c native/meta.c
	./out/meta_host_test

manager:
	$(MAKE) -C manager

packages: native
	mkdir -p out examples/usb-stick-install/packages
	python3 tools/make_nemesis_package.py packages/usb-handler -o out/usb-handler.zip
	python3 tools/make_nemesis_package.py examples/package-hello -o out/hello.tool.zip
	python3 tools/make_nemesis_package.py examples/package-hello-v2 -o out/hello.tool-v2.zip
	python3 tools/make_nemesis_package.py packages/voicehandler-vr-gates -o out/voicehandler.vr-gates.zip
	python3 tools/make_nemesis_package.py packages/adb-tcp -o out/adb.tcp.zip
	python3 tools/make_nemesis_package.py packages/wireless-projection -o out/wireless.projection.zip
	python3 tools/make_nemesis_package.py packages/busybox -o out/busybox.zip
	cp -f out/hello.tool.zip examples/usb-stick-install/packages/hello.tool.zip
	mkdir -p examples/usb-stick-voicehandler-vr-gates/packages
	cp -f out/voicehandler.vr-gates.zip examples/usb-stick-voicehandler-vr-gates/packages/voicehandler.vr-gates.zip
	mkdir -p examples/usb-stick-adb/packages examples/usb-stick-wireless/packages
	cp -f out/adb.tcp.zip examples/usb-stick-adb/packages/adb.tcp.zip
	cp -f out/wireless.projection.zip examples/usb-stick-wireless/packages/wireless.projection.zip

examples:
	mkdir -p examples/usb-stick-execute examples/usb-stick-install/packages examples/usb-stick-open
	cp -f examples/execute-hello/NEMESIS.data examples/usb-stick-execute/NEMESIS.data
	cp -f examples/execute-hello/NEMESIS.sh examples/usb-stick-execute/NEMESIS.sh
	cp -f examples/open-manager/NEMESIS.data examples/usb-stick-open/NEMESIS.data

troubleshooter: native manager
	python3 make_troubleshooter_tar.py
	sh usb_bundle/verify_tars.sh

# Needs the QEMU lab (system image + emulator), which is not part of the published sources.
test-emu: native packages
	@[ -d "$${NEMESIS_EMU_DIR:-}" ] || { echo "test-emu needs NEMESIS_EMU_DIR (the QEMU lab); not published"; exit 1; }
	python3 tests/emu_nemesis_flow.py

test-hooks:
	python3 tests/setup_press_test.py
	python3 tests/store_logic_test.py
	python3 tests/check_manager_apk.py
	bash tests/pkg_live_snapshot.sh

clean:
	$(MAKE) -C native clean
	$(MAKE) -C manager clean
	rm -f out/*.zip
	rm -f $(TAR) usb_bundle/ota_package.tar
