#!/usr/bin/env python3
"""Resample the ev-engine.com recorded loops into package PCM and demo WAV.

Sources live in engine_sound/sources/ev-engine/<id>/. Each car is five loops
the site already plays as a seamless loop: throttle low/high, overrun
low/high, and a limiter. Private use only, per the site.
"""
import os
import struct
import subprocess
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
SRC = os.path.join(ROOT, "sources", "ev-engine")
PCM = os.path.join(ROOT, "assets")
WAV = os.path.join(ROOT, "demo", "loops")
SR = 44100

# id, label, (role, filename stem, rpm)
CARS = [
    ("bac", "BAC Mono", [
        ("on_low", "bac_on_3000", 3000),
        ("on_high", "bac_on_6500", 6500),
        ("off_low", "bac_off_3000", 3000),
        ("off_high", "bac_off_6500", 6500),
        ("limiter", "bac_lim_8000", 8000),
    ], "bac_mono"),
    ("ferrari", "Ferrari 458", [
        ("on_low", "ferrari_on_5300", 5300),
        ("on_high", "ferrari_on_7700", 7700),
        ("off_low", "ferrari_off_6900", 6900),
        ("off_high", "ferrari_off_7900", 7900),
        ("limiter", "ferrari_lim_8800", 8800),
    ], "ferrari_458"),
    ("procar", "BMW M1 Procar", [
        ("on_low", "procar_on_3200", 3200),
        ("on_high", "procar_on_8000", 8000),
        ("off_low", "procar_off_3400", 3400),
        ("off_high", "procar_off_8430", 8430),
        ("limiter", "procar_lim_8000", 8000),
    ], "procar"),
]

ROLE_FILE = {
    "on_low": "on_low.wav",
    "on_high": "on_high.wav",
    "off_low": "off_low.wav",
    "off_high": "off_high.wav",
    "limiter": "limiter.wav",
}


def to_pcm(src, dst_pcm):
    raw = subprocess.check_output([
        "ffmpeg", "-v", "error", "-i", src,
        "-ac", "2", "-ar", str(SR), "-f", "s16le", "-",
    ])
    with open(dst_pcm, "wb") as handle:
        handle.write(raw)
    return raw


def to_wav(raw, path):
    with wave.open(path, "wb") as wav:
        wav.setnchannels(2)
        wav.setsampwidth(2)
        wav.setframerate(SR)
        wav.writeframes(raw)


def main():
    os.makedirs(PCM, exist_ok=True)
    os.makedirs(WAV, exist_ok=True)
    lines = []
    for car, label, layers, folder in CARS:
        for role, stem, rpm in layers:
            src = os.path.join(SRC, folder, ROLE_FILE[role])
            if not os.path.isfile(src):
                raise SystemExit("missing " + src)
            raw = to_pcm(src, os.path.join(PCM, stem + ".pcm"))
            to_wav(raw, os.path.join(WAV, stem + ".wav"))
            frames = len(raw) // 4
            lines.append("%s|%s|%s.wav|%d|%s\n" % (car, label, stem, rpm, role))
            print("%s  %.2fs  %s" % (stem, frames / float(SR), role))
    bank = os.path.join(WAV, "bank.txt")
    kept = []
    if os.path.exists(bank):
        with open(bank) as handle:
            for line in handle:
                car = line.split("|", 1)[0]
                if car in ("bac", "ferrari", "procar") or not line.strip():
                    continue
                kept.append(line if line.endswith("\n") else line + "\n")
    with open(bank, "w") as handle:
        handle.writelines(kept)
        handle.writelines(lines)
    print("wrote", len(lines), "ev-engine loops")


if __name__ == "__main__":
    main()
