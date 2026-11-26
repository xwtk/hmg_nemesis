#!/usr/bin/env python3
"""Cut loopable stereo clips from the Honda 3.2 recordings (``v8``: the AVATR 07 sweep).

Each clip is an integer number of exhaust pulses so it can repeat, with a
short crossfade at the join. Outputs raw PCM for the head unit and WAV for
the Windows demo.
"""
import glob
import os
import struct
import subprocess
import sys
import wave

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT_PCM = os.path.join(os.path.dirname(__file__), "..", "assets")
OUT_WAV = os.path.join(os.path.dirname(__file__), "..", "demo", "loops")
SR = 44100

# start seconds, firing Hz (3 pulses per rev on these V6s), length seconds
TAKES = [
    ("honda", "Honda 3.2 V6", "754777__evanboyerman__sports-car-idling-exhaust.wav",
     3.0, 33.8, 1.4, 680),
    ("honda", "Honda 3.2 V6", "754776__evanboyerman__sports-car-acceleratingspeeding-up-medium-exhaust.wav",
     6.0, 115.0, 1.1, 2300),
    ("honda", "Honda 3.2 V6", "754775__evanboyerman__sports-car-acceleratingspeeding-up-fast-exhaust.wav",
     17.6, 160.0, 1.0, 3200),
]

FADE_S = 0.016


def load_stereo(path):
    raw = subprocess.check_output([
        "ffmpeg", "-v", "error", "-i", path,
        "-ac", "2", "-ar", str(SR), "-f", "f32le", "-",
    ])
    x = np.frombuffer(raw, dtype=np.float32)
    return x.reshape((-1, 2))


def cut_slice(x, start, fire_hz, length):
    """Integer firing cycles, plus a few extra samples past the end for the join."""
    period = max(8, int(round(SR / fire_hz)))
    fade = int(FADE_S * SR)
    cycles = max(3, int(round(length * SR / period)))
    n = cycles * period
    s0 = int(start * SR)
    best = s0
    best_score = 1e9
    for off in range(-period, period, 2):
        a = s0 + off
        if a < 0 or a + n + fade >= len(x):
            continue
        score = float(np.mean(np.abs(x[a:a + fade] - x[a + n:a + n + fade])))
        if score < best_score:
            best_score = score
            best = a
    audio = np.array(x[best:best + n + fade], dtype=np.float32, copy=True)
    return audio, n, best_score


def _match(audio, target):
    rms = float(np.sqrt(np.mean(audio ** 2))) + 1e-8
    gain = target / rms
    if gain < 0.7:
        gain = 0.7
    if gain > 1.4:
        gain = 1.4
    return audio * np.float32(gain)


def assemble(chunks):
    """Join slices with a short linear fade.

    Equal-power overlap of two similar exhaust passages adds a burr at the
    seam. A linear fade of one firing cycle does not.
    """
    fade = int(FADE_S * SR)
    levels = [float(np.sqrt(np.mean(audio[:n] ** 2))) for audio, n, _score in chunks]
    target = float(np.median(levels))
    chunks = [(_match(audio, target), n) for audio, n, _score in chunks]
    w = np.linspace(0.0, 1.0, fade, dtype=np.float32)[:, None]
    pieces = []
    for i, (audio, n) in enumerate(chunks):
        body = audio[:n]
        if i == 0:
            pieces.append(body)
        else:
            prev, pn = chunks[i - 1]
            tail = prev[pn:pn + fade]
            pieces.append(tail * (1.0 - w) + body[:fade] * w)
            pieces.append(body[fade:])
    loop = np.concatenate(pieces).copy()
    tail = chunks[-1][0][chunks[-1][1]:chunks[-1][1] + fade]
    loop[:fade] = tail * (1.0 - w) + loop[:fade] * w
    peak = float(np.max(np.abs(loop)))
    if peak > 1e-6:
        loop *= np.float32(0.9 / peak)
    jump = float(np.mean(np.abs(loop[0] - loop[-1])))
    return loop, jump


def make_loop(x, start, fire_hz, length):
    period = max(8, int(round(SR / fire_hz)))
    cycles = max(2, int(round(length * SR / period)))
    n = cycles * period
    fade = int(0.09 * SR)
    s0 = int(start * SR)
    best = s0
    best_score = 1e9
    step = max(1, period // 24)
    for shift in range(-int(0.4 * SR), int(0.4 * SR), int(0.02 * SR)):
        base = s0 + shift
        for off in range(0, period, step):
            a = base + off
            if a < 0 or a + n + fade >= len(x):
                continue
            tail = x[a + n:a + n + fade]
            head = x[a:a + fade]
            score = float(np.mean(np.abs(tail - head)))
            if score < best_score:
                best_score = score
                best = a
    seg = np.array(x[best:best + n], dtype=np.float32, copy=True)
    tail = x[best + n:best + n + fade]
    w = np.linspace(0.0, 1.0, fade, dtype=np.float32)[:, None]
    # Equal-power blend so the wrap from the end back to the start is quiet.
    seg[:fade] = tail * np.sqrt(1.0 - w) + seg[:fade] * np.sqrt(w)
    peak = float(np.max(np.abs(seg)))
    if peak > 1e-6:
        seg *= 0.9 / peak
    return seg, best_score


def write_pcm(path, seg):
    pcm = np.clip(seg, -1.0, 1.0)
    ints = (pcm * 32767.0).astype(np.int16)
    ints.tofile(path)


def write_wav(path, seg):
    pcm = np.clip(seg, -1.0, 1.0)
    ints = (pcm * 32767.0).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(ints.tobytes())


def main():
    os.makedirs(OUT_PCM, exist_ok=True)
    os.makedirs(OUT_WAV, exist_ok=True)
    catalog = []
    cache = {}
    for car, label, src, start, fire, length, rpm in TAKES:
        path = os.path.join(ROOT, src)
        if path not in cache:
            cache[path] = load_stereo(path)
        seg, score = make_loop(cache[path], start, fire, length)
        name = "%s_%d" % (car, rpm)
        write_pcm(os.path.join(OUT_PCM, name + ".pcm"), seg)
        write_wav(os.path.join(OUT_WAV, name + ".wav"), seg)
        catalog.append((car, label, name, rpm, len(seg) / SR, score))
        print("%s rpm %d  %.2fs  join %.4f" % (name, rpm, len(seg) / SR, score))
    bank = os.path.join(OUT_WAV, "bank.txt")
    with open(bank, "w") as f:
        for car, label, name, rpm, dur, score in catalog:
            f.write("%s|%s|%s.wav|%d\n" % (car, label, name, rpm))
    print("wrote", len(catalog), "loops")


# AVATR 07 "V8" sound, a 4 s rising sweep pulled from the car (sources/avatr07).
# The firing tone is 4th order, so rpm = 15 x its frequency: 1650 at the start,
# 4550 at the end, with small shift dips at 0.85 s and 1.5 s. The first 0.3 s
# fades in and the last 0.4 s fades out, so neither is used.
V8_PATH = os.path.join(os.path.dirname(__file__), "..", "sources", "avatr07", "V8.wav")
V8_LAYERS = [
    (2000, [("V", 0.40, 0.18)]),
    (2250, [("V", 0.92, 0.16)]),
    (2600, [("V", 1.60, 0.16)]),
    (2950, [("V", 1.98, 0.12)]),
    (3300, [("V", 2.28, 0.12)]),
    (3650, [("V", 2.62, 0.12)]),
    (4000, [("V", 3.00, 0.14)]),
    (4300, [("V", 3.38, 0.14)]),
]


def build_v8():
    if not os.path.exists(V8_PATH):
        raise SystemExit("missing " + V8_PATH)
    # One engine cycle (two revs) per pulse, so the 4.5th-order tone also loops.
    build_sweep("v8", "V8", {"V": V8_PATH}, V8_LAYERS, 120.0)


def build_sweep(car, label, paths, layers, pulses_div):
    """Cut one loop per layer from a sweep. pulses_div: rpm / pulse Hz."""
    os.makedirs(OUT_PCM, exist_ok=True)
    os.makedirs(OUT_WAV, exist_ok=True)
    cache = {}
    lines = []
    for rpm, slices in layers:
        chunks = []
        for key, start, length in slices:
            if key not in cache:
                cache[key] = load_stereo(paths[key])
            chunks.append(cut_slice(cache[key], start, rpm / pulses_div, length))
        seg, jump = assemble(chunks)
        name = "%s_%d" % (car, rpm)
        write_pcm(os.path.join(OUT_PCM, name + ".pcm"), seg)
        write_wav(os.path.join(OUT_WAV, name + ".wav"), seg)
        lines.append("%s|%s|%s.wav|%d\n" % (car, label, name, rpm))
        print("%s  t %.2f  %.2fs  jump %.4f" % (name, slices[0][1], len(seg) / SR, jump))
    bank = os.path.join(OUT_WAV, "bank.txt")
    kept = []
    at = None
    if os.path.exists(bank):
        with open(bank) as handle:
            for line in handle:
                if line.startswith(car + "|"):
                    if at is None:
                        at = len(kept)
                    continue
                if line.strip():
                    kept.append(line if line.endswith("\n") else line + "\n")
    # The car keeps its place in the list; a new car goes last.
    if at is None:
        at = len(kept)
    with open(bank, "w") as handle:
        handle.writelines(kept[:at] + lines + kept[at:])
    keep = set("%s_%d" % (car, rpm) for rpm, _slices in layers)
    for folder, ext in ((OUT_PCM, ".pcm"), (OUT_WAV, ".wav")):
        for path in glob.glob(os.path.join(folder, car + "_*" + ext)):
            stem = os.path.splitext(os.path.basename(path))[0]
            if stem not in keep:
                os.remove(path)
    print("wrote", len(lines), car, "loops")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "v8":
        build_v8()
    else:
        main()
