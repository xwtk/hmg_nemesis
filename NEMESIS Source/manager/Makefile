ANDROID_JAR ?= /usr/lib/android-sdk/platforms/android-30/android.jar
AAPT ?= aapt
D8 ?= d8
JAVAC ?= javac
JARSIGNER ?= jarsigner
ZIPALIGN ?= zipalign
KEYSTORE := $(CURDIR)/nemesis.keystore
ALIAS := nemesis
PASS := nemesis

SRC := $(wildcard src/com/nemesis/manager/*.java)
OUT := NEMESIS.apk

.PHONY: all clean keystore

all: $(OUT)

keystore:
	test -f "$(KEYSTORE)" || keytool -genkeypair -keystore "$(KEYSTORE)" -alias $(ALIAS) \
		-keyalg RSA -keysize 2048 -validity 10000 \
		-storepass $(PASS) -keypass $(PASS) \
		-dname 'CN=NEMESIS,O=NEMESIS,C=US'

res.apk: AndroidManifest.xml $(shell find res -type f)
	mkdir -p gen
	$(AAPT) package -f -M AndroidManifest.xml -S res -I $(ANDROID_JAR) \
		-F res.apk -J gen --custom-package com.nemesis.manager

# Directory timestamps do not change when .class files are rewritten, so javac
# and d8 must run in one recipe. Otherwise KitKat loads a dex that is missing
# classes named in AndroidManifest (ClassNotFoundException).
classes.dex: res.apk $(SRC)
	mkdir -p classes
	$(JAVAC) -source 1.7 -target 1.7 -bootclasspath $(ANDROID_JAR) \
		-cp $(ANDROID_JAR) -d classes gen/R.java $(SRC)
	$(D8) --min-api 19 --lib $(ANDROID_JAR) --release --output . `find classes -name '*.class' | sort -u`

unsigned.apk: res.apk classes.dex
	cp res.apk unsigned.apk
	zip -u unsigned.apk classes.dex

$(OUT): keystore unsigned.apk
	$(JARSIGNER) "-J-Djava.security.properties=$(CURDIR)/allow-sha1.security" \
		-sigalg SHA1withRSA -digestalg SHA1 -keystore "$(KEYSTORE)" \
		-storepass $(PASS) -keypass $(PASS) unsigned.apk $(ALIAS)
	$(ZIPALIGN) -f 4 unsigned.apk $(OUT)
	python3 "$(CURDIR)/../tests/check_manager_apk.py"
	@echo wrote $(OUT)

clean:
	rm -rf classes gen classes.dex res.apk unsigned.apk $(OUT)
