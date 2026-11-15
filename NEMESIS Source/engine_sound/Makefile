ANDROID_JAR ?= /usr/lib/android-sdk/platforms/android-30/android.jar
AAPT ?= aapt
D8 ?= d8
JAVAC ?= javac
JARSIGNER ?= jarsigner
ZIPALIGN ?= zipalign
KEYSTORE ?= $(CURDIR)/../manager/nemesis.keystore
ALIAS := nemesis
PASS := nemesis
PKG_PY ?= $(CURDIR)/../tools/make_nemesis_package.py

SRC := $(shell find src -name '*.java' | sort)
OUT := EngineSound.apk

.PHONY: all test clean package

all: $(OUT)

test:
	mkdir -p host-out
	$(JAVAC) -source 1.7 -target 1.7 -d host-out \
		$(shell find src/com/nemesis/enginesound/model src/com/nemesis/enginesound/synth host -name '*.java' | sort)
	java -cp host-out com.nemesis.enginesound.model.GearboxHostTest
	java -cp host-out com.nemesis.enginesound.synth.SynthHostTest
	java -cp host-out com.nemesis.enginesound.synth.SampleVoiceHostTest
	java -cp host-out com.nemesis.enginesound.synth.ThrottleVoiceHostTest
	java -cp host-out com.nemesis.enginesound.model.GearboxTrace > host-out/trace-java.txt
	python3 -I demo/EngineSoundDemo.py --trace > host-out/trace-py.txt
	python3 -I tools/trace_compare.py host-out/trace-java.txt host-out/trace-py.txt

res.apk: AndroidManifest.xml $(shell find res -type f)
	mkdir -p gen
	$(AAPT) package -f -M AndroidManifest.xml -S res -I $(ANDROID_JAR) \
		-F res.apk -J gen --custom-package com.nemesis.enginesound

classes.dex: res.apk $(SRC)
	rm -rf classes
	mkdir -p classes
	$(JAVAC) -source 1.7 -target 1.7 -bootclasspath $(ANDROID_JAR) \
		-cp $(ANDROID_JAR) -d classes gen/R.java $(SRC)
	rm -f classes.dex hook.dex
	smali assemble -o hook.dex smali
	$(D8) --min-api 19 --lib $(ANDROID_JAR) --release --output . hook.dex $$(find classes -name '*.class' | sort -u)

LOOPS := $(wildcard assets/*.pcm)

unsigned.apk: res.apk classes.dex $(LOOPS)
	cp res.apk unsigned.apk
	zip -u unsigned.apk classes.dex
	zip -u unsigned.apk $(LOOPS)

$(OUT): unsigned.apk
	$(JARSIGNER) -J-Djava.security.properties=$(CURDIR)/allow-sha1.security \
		-sigalg SHA1withRSA -digestalg SHA1 -keystore $(KEYSTORE) \
		-storepass $(PASS) -keypass $(PASS) unsigned.apk $(ALIAS)
	$(ZIPALIGN) -f 4 unsigned.apk $(OUT)
	$(AAPT) dump badging $(OUT) | head -n 20

package: $(OUT)
	mkdir -p pkg/app out
	cp $(OUT) pkg/app/EngineSound.apk
	chmod 755 pkg/scripts/post.sh pkg/scripts/preun.sh
	python3 $(PKG_PY) pkg -o out/engine.sound.zip

clean:
	rm -rf classes gen host-out classes.dex res.apk unsigned.apk $(OUT) pkg/app out
