# Entrypoint variant 2 - USB update package
# Builds the path-traversal tar set from the shared NEMESIS Source tree.
# Pass TEMPLATE=/path/to/new_system_package_*.tar to override the auto-detected template.
HERE := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
SRC := $(HERE)/../NEMESIS Source

TEMPLATE ?=
TEMPLATE_ARG := $(if $(TEMPLATE),--template "$(TEMPLATE)",)

.PHONY: all installer clean

all: installer
	$(MAKE) -C "$(SRC)" native manager porter
	mkdir -p "$(HERE)/out"
	cd "$(HERE)" && python3 make_nemesis_installer.py $(TEMPLATE_ARG) --out "$(HERE)/out"

installer:
	$(MAKE) -C "$(HERE)/installer"

clean:
	$(MAKE) -C "$(HERE)/installer" clean
	rm -rf "$(HERE)/out"
