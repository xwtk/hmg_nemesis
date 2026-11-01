# Android NDK r21e: make NDK=/path/to/android-ndk-r21e
NDK ?= $(HOME)/android-ndk-r21e
CC  := $(NDK)/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi19-clang
STRIP := $(NDK)/toolchains/llvm/prebuilt/linux-x86_64/bin/arm-linux-androideabi-strip

CFLAGS := -O2 -fPIE -pie -Wall -Wextra -D_FILE_OFFSET_BITS=64
LDFLAGS := -lz -pie

SRC := main.c util.c ini.c meta.c update.c zip.c pkg.c svc.c usb.c su.c
OBJ := $(SRC:.c=.o)
OUT := nemesis

.PHONY: all clean

all: $(OUT)

$(OUT): $(SRC) nemesis.h
	$(CC) $(CFLAGS) -o $@ $(SRC) $(LDFLAGS)
	-$(STRIP) $@

clean:
	rm -f $(OUT) $(OBJ)
