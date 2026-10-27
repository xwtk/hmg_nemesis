# Stock Android 4.4 (API 19) x86 emulator + build tools for NEMESIS.
# Same Android release as the Gen5W head unit; none of the HMG firmware.
# Run with --device /dev/kvm (see run.sh).
FROM ubuntu:22.04

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        openjdk-17-jdk-headless unzip zip curl make python3 ca-certificates file \
        libpulse0 libgl1 libnss3 libxcomposite1 libxcursor1 libxdamage1 libxi6 \
        libxtst6 libasound2 libx11-6 libxkbfile1 libxrandr2 libdbus-1-3 \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/build-tools/30.0.3:$PATH

RUN curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/clt.zip \
    && mkdir -p $ANDROID_HOME/cmdline-tools \
    && unzip -q /tmp/clt.zip -d $ANDROID_HOME/cmdline-tools \
    && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm /tmp/clt.zip

# NDK r21e matches Sources/native/Makefile; android-30 jar matches Sources/manager/Makefile.
RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" "emulator" "platforms;android-30" \
        "build-tools;30.0.3" "ndk;21.4.7075529" "system-images;android-19;default;x86" > /dev/null

RUN echo no | avdmanager create avd -n api19 -k "system-images;android-19;default;x86" -d pixel

ENV NDK=$ANDROID_HOME/ndk/21.4.7075529
WORKDIR /src
