#!/usr/bin/env python3
"""Cut loopable stereo clips from the Honda 3.2 and Mercedes C43 recordings.

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

# Measured pulse rate in the C43 take. 1760 is the early passage (also used
# down at 1100). 3100 and 5000 are separate louder moments; 5000 is several
# of them joined, so the 5k slider is not one short clip played faster.
C43_FILE = "772801__jerryberumen__mercedes-benz-c43-amg-exhaust-rev-slow-rear.wav"
C43_LAYERS = [
    (1760, [(0.25, 86.3, 1.35)]),
    (3100, [(9.15, 159.8, 1.05), (20.35, 154.7, 1.05)]),
    (5000, [(13.55, 165.2, 1.15), (19.55, 167.0, 1.15)]),
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
    c43 = os.path.join(ROOT, C43_FILE)
    if c43 not in cache:
        cache[c43] = load_stereo(c43)
    for rpm, slices in C43_LAYERS:
        seg, jump = assemble([cut_slice(cache[c43], t, hz, dur) for t, hz, dur in slices])
        name = "c43_%d" % rpm
        write_pcm(os.path.join(OUT_PCM, name + ".pcm"), seg)
        write_wav(os.path.join(OUT_WAV, name + ".wav"), seg)
        with open(bank, "a") as f:
            f.write("c43|Mercedes C43 V6|%s.wav|%d\n" % (name, rpm))
        catalog.append(("c43", "Mercedes C43 V6", name, rpm, len(seg) / SR, jump))
        print("%s rpm %d  %.2fs  %d slices  jump %.4f" % (name, rpm, len(seg) / SR, len(slices), jump))
    for stale_name in ("c43_960", "c43_5280"):
        for folder in (OUT_PCM, OUT_WAV):
            ext = ".pcm" if folder == OUT_PCM else ".wav"
            stale = os.path.join(folder, stale_name + ext)
            if os.path.exists(stale):
                os.remove(stale)
    print("wrote", len(catalog), "loops")


# Infiniti G37S coupe only. Low layers are the first-gear pull from the
# listener's map. 2600 at 1.9s sits on a knock, so that layer is the clean
# stretch just after it (about 3000). 4400 and 4800 stay in that pull, longer
# than before. 5700-7300 are the slower second-gear run (4.2s=5400 … 6.1s=7400),
# half a second instead of a tenth, and they stop before the 6.3s shift.
G37_LAYERS = [
    (1500, [("S", 1.40, 0.20)]),
    (2000, [("S", 1.58, 0.16)]),
    (3000, [("S", 2.00, 0.18)]),
    (3300, [("S", 2.16, 0.16)]),
    (3800, [("S", 2.36, 0.18)]),
    (4400, [("S", 2.58, 0.28)]),
    (4800, [("S", 2.82, 0.28)]),
    (5700, [("S", 4.30, 0.48)]),
    (6300, [("S", 4.88, 0.42)]),
    (6900, [("S", 5.34, 0.40)]),
    (7300, [("S", 5.80, 0.30)]),
]


def g37_paths():
    found = {}
    for path in glob.glob(os.path.join(ROOT, "*.mp3")):
        base = os.path.basename(path)
        if "G37x" in base:
            found["x"] = path
        elif "G37S" in base or "Coup" in base:
            found["S"] = path
        elif "G37" in base:
            found["100"] = path
    return found


def build_g37():
    paths = g37_paths()
    if "S" not in paths:
        raise SystemExit("missing G37S recording")
    os.makedirs(OUT_PCM, exist_ok=True)
    os.makedirs(OUT_WAV, exist_ok=True)
    cache = {}
    lines = []
    for rpm, slices in G37_LAYERS:
        chunks = []
        for key, start, length in slices:
            if key not in cache:
                cache[key] = load_stereo(paths[key])
            chunks.append(cut_slice(cache[key], start, rpm / 20.0, length))
        seg, jump = assemble(chunks)
        name = "g37_%d" % rpm
        write_pcm(os.path.join(OUT_PCM, name + ".pcm"), seg)
        write_wav(os.path.join(OUT_WAV, name + ".wav"), seg)
        lines.append("g37|Infiniti G37|%s.wav|%d\n" % (name, rpm))
        print("%s  t %.2f  %.2fs  jump %.4f" % (name, slices[0][1], len(seg) / SR, jump))
    bank = os.path.join(OUT_WAV, "bank.txt")
    kept = []
    if os.path.exists(bank):
        with open(bank) as handle:
            for line in handle:
                if line.startswith("g37|") or not line.strip():
                    continue
                kept.append(line if line.endswith("\n") else line + "\n")
    with open(bank, "w") as handle:
        handle.writelines(lines)
        handle.writelines(kept)
    keep = set("g37_%d" % rpm for rpm, _slices in G37_LAYERS)
    for folder, ext in ((OUT_PCM, ".pcm"), (OUT_WAV, ".wav")):
        for path in glob.glob(os.path.join(folder, "g37_*" + ext)):
            stem = os.path.splitext(os.path.basename(path))[0]
            if stem not in keep:
                os.remove(path)
    print("wrote", len(lines), "g37 loops")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "g37":
        build_g37()
    else:
        main()
