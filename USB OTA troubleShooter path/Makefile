# Entrypoint variant 1 - DSP OTA troubleShooter
# Builds the self-extracting OTA tar from the shared NEMESIS Source tree.
HERE := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
SRC := $(HERE)/../NEMESIS Source

.PHONY: all clean verify

all:
	$(MAKE) -C "$(SRC)" native manager
	mkdir -p "$(HERE)/out"
	cd "$(HERE)" && python3 make_troubleshooter_tar.py
	sh "$(HERE)/verify_tars.sh"

verify:
	sh "$(HERE)/verify_tars.sh"

clean:
	rm -rf "$(HERE)/out"
