ANDROID_JAR ?= /usr/lib/android-sdk/platforms/android-30/android.jar
AAPT ?= aapt
D8 ?= d8
JAVAC ?= javac
JARSIGNER ?= jarsigner
ZIPALIGN ?= zipalign
KEYSTORE := $(CURDIR)/nemesis.keystore
ALIAS := nemesis
PASS := nemesis

SRC := $(wildcard src/com/nemesis/installer/*.java)
OUT := NEMESIS-Installer.apk

.PHONY: all clean

all: $(OUT)

res.apk: AndroidManifest.xml $(shell find res -type f)
	mkdir -p gen
	$(AAPT) package -f -M AndroidManifest.xml -S res -I $(ANDROID_JAR) \
		-F res.apk -J gen --custom-package com.nemesis.installer

# javac and d8 run in one recipe so KitKat never loads a dex missing a manifest class.
classes.dex: res.apk $(SRC)
	mkdir -p classes
	$(JAVAC) -source 1.7 -target 1.7 -bootclasspath $(ANDROID_JAR) \
		-cp $(ANDROID_JAR) -d classes gen/R.java $(SRC)
	$(D8) --min-api 19 --lib $(ANDROID_JAR) --release --output . `find classes -name '*.class' | sort -u`

unsigned.apk: res.apk classes.dex
	cp res.apk unsigned.apk
	zip -u unsigned.apk classes.dex

$(OUT): unsigned.apk
	$(JARSIGNER) "-J-Djava.security.properties=$(CURDIR)/allow-sha1.security" \
		-sigalg SHA1withRSA -digestalg SHA1 -keystore "$(KEYSTORE)" \
		-storepass $(PASS) -keypass $(PASS) unsigned.apk $(ALIAS)
	$(ZIPALIGN) -f 4 unsigned.apk $(OUT)
	@echo wrote $(OUT)

clean:
	rm -rf classes gen classes.dex res.apk unsigned.apk $(OUT)
