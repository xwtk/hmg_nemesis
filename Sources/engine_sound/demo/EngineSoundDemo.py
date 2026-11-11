#!/usr/bin/env python3
"""Windows preview of the recorded loops.

Double-click EngineSoundDemo.bat. A pedal slider drives speed: floored, the
car covers 0-100 km/h in 4.5 s. RPM and gear come from the same gearbox the
head unit uses, so an upshift drops the revs and a downshift brings them back.
"""
import math
import os
import struct
import subprocess
import sys
import threading
import time
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
LOOP_DIR = os.path.join(HERE, "loops")
SR = 44100
# EV6 AWD: traction limit, then ~240 kW, then drag. Floored is 0-100 in 4.5 s.
A_GRIP = 7.0
POWER_PER_KG = 124.0
DRAG_K = 2.24e-4
ROLL_A = 0.118
# Lift-off regen, like an ICE engine-braking in gear. Full brake stops 100 km/h in 3 s.
REGEN_A = 0.6
TOP_KMH = 185.0
BRAKE_A = (100.0 / 3.6) / 3.0


def load_bank():
    cars = {}
    order = []
    with open(os.path.join(LOOP_DIR, "bank.txt"), "r") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            parts = line.split("|")
            role = None
            if len(parts) == 5:
                car, label, name, rpm, role = parts
            else:
                car, label, name, rpm = parts
            path = os.path.join(LOOP_DIR, name)
            with wave.open(path, "rb") as wav:
                raw = wav.readframes(wav.getnframes())
                frames = wav.getnframes()
            pcm = struct.unpack("<%dh" % (len(raw) // 2), raw)
            loop = {"pcm": pcm, "frames": frames, "rpm": float(rpm), "pos": 0.0, "role": role}
            if car not in cars:
                cars[car] = {"id": car, "label": label, "loops": [], "slots": {}}
                order.append(car)
            cars[car]["loops"].append(loop)
            if role:
                cars[car]["slots"][role] = loop
    return [cars[i] for i in order]


def read(pcm, frames, pos, channel):
    while pos >= frames:
        pos -= frames
    while pos < 0:
        pos += frames
    i0 = int(pos)
    i1 = i0 + 1
    if i1 >= frames:
        i1 = 0
    frac = pos - i0
    a = i0 * 2 + channel
    b = i1 * 2 + channel
    return pcm[a] + (pcm[b] - pcm[a]) * frac


def render(car, rpm, pedal, volume, frames, limiter):
    if rpm < 40:
        rpm = 40
    if car.get("slots"):
        return render_throttle(car, rpm, pedal, volume, frames, limiter)
    loops = car["loops"]
    upper = 0
    while upper < len(loops) and loops[upper]["rpm"] < rpm:
        upper += 1
    if len(loops) == 1 or upper <= 0:
        pair = [(loops[0], 1.0)]
    elif upper >= len(loops):
        pair = [(loops[-1], 1.0)]
    else:
        lo = loops[upper - 1]
        hi = loops[upper]
        span = hi["rpm"] - lo["rpm"]
        blend = (rpm - lo["rpm"]) / span if span > 1 else 0.0
        pair = [(lo, 1.0 - blend), (hi, blend)]
    gain = volume * (0.42 + 0.58 * pedal)
    out = [0.0] * (frames * 2)
    active = pair[0][0]["rpm"]
    for loop, weight in pair:
        if weight <= 0.0001:
            continue
        rate = rpm / loop["rpm"]
        if rate < 0.32:
            rate = 0.32
        if rate > 2.4:
            rate = 2.4
        pos = loop["pos"]
        n = loop["frames"]
        pcm = loop["pcm"]
        if pos < 0 or pos >= n:
            pos = 0
        g = gain * weight
        for i in range(frames):
            left = read(pcm, n, pos, 0)
            right = read(pcm, n, pos, 1)
            out[i * 2] += left * g
            out[i * 2 + 1] += right * g
            pos += rate
            if pos >= n:
                pos -= n
        loop["pos"] = pos
        if weight >= 0.5:
            active = loop["rpm"]
    data = bytearray(frames * 4)
    for i, sample in enumerate(out):
        if sample > 32767:
            sample = 32767
        elif sample < -32768:
            sample = -32768
        struct.pack_into("<h", data, i * 2, int(sample))
    return bytes(data), active


def render_throttle(car, rpm, pedal, volume, frames, limiter):
    """Throttle against overrun, and the low recording against the high one."""
    slots = car["slots"]
    low_rpm = slots["on_low"]["rpm"]
    high_rpm = slots["on_high"]["rpm"]
    span = high_rpm - low_rpm
    u = (rpm - low_rpm) / span if span > 1 else 0.0
    if u < 0:
        u = 0.0
    elif u > 1:
        u = 1.0
    low = math.cos(u * math.pi * 0.5)
    high = math.sin(u * math.pi * 0.5)
    on = math.sin(max(0.0, min(1.0, pedal)) * math.pi * 0.5)
    off = math.cos(max(0.0, min(1.0, pedal)) * math.pi * 0.5)
    layers = [
        (slots.get("on_low"), on * low),
        (slots.get("on_high"), on * high),
        (slots.get("off_low"), off * low),
        (slots.get("off_high"), off * high),
    ]
    if limiter and slots.get("limiter"):
        layers.append((slots["limiter"], 1.0))
    out = [0.0] * (frames * 2)
    for loop, weight in layers:
        if loop is None or weight <= 0.0001:
            continue
        rate = rpm / loop["rpm"]
        if rate < 0.25:
            rate = 0.25
        if rate > 4.0:
            rate = 4.0
        pos = loop["pos"]
        n = loop["frames"]
        pcm = loop["pcm"]
        if pos < 0 or pos >= n:
            pos = 0
        g = volume * weight
        for i in range(frames):
            out[i * 2] += read(pcm, n, pos, 0) * g
            out[i * 2 + 1] += read(pcm, n, pos, 1) * g
            pos += rate
            if pos >= n:
                pos -= n
        loop["pos"] = pos
    data = bytearray(frames * 4)
    for i, sample in enumerate(out):
        if sample > 32767:
            sample = 32767
        elif sample < -32768:
            sample = -32768
        struct.pack_into("<h", data, i * 2, int(sample))
    return bytes(data), rpm


class PcmOut(object):
    def write(self, data):
        raise NotImplementedError

    def close(self):
        pass


class AplayOut(PcmOut):
    def __init__(self):
        cmd = ["aplay", "-q", "-f", "S16_LE", "-c", "2", "-r", str(SR), "-t", "raw"]
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)

    def write(self, data):
        self.proc.stdin.write(data)
        self.proc.stdin.flush()

    def close(self):
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        self.proc.wait()


class WaveOut(PcmOut):
    """Windows waveOut. Stdlib only, so the bat file needs no extra install.

    Only two buffers stay queued. waveOutWrite returns immediately, so writing
    as fast as render can run would play audio that was mixed before the
    slider moved. Waiting for a buffer to finish keeps the sound on the
    current RPM, pedal, and volume.
    """

    QUEUED = 2

    def __init__(self, running):
        import ctypes
        from ctypes import wintypes
        self.ctypes = ctypes
        self.running = running
        self.winmm = ctypes.windll.winmm
        self.free = threading.Semaphore(self.QUEUED)
        self.pending = []

        class WAVEFORMATEX(ctypes.Structure):
            _fields_ = [
                ("wFormatTag", wintypes.WORD),
                ("nChannels", wintypes.WORD),
                ("nSamplesPerSec", wintypes.DWORD),
                ("nAvgBytesPerSec", wintypes.DWORD),
                ("nBlockAlign", wintypes.WORD),
                ("wBitsPerSample", wintypes.WORD),
                ("cbSize", wintypes.WORD),
            ]

        class WAVEHDR(ctypes.Structure):
            _fields_ = [
                ("lpData", ctypes.c_void_p),
                ("dwBufferLength", wintypes.DWORD),
                ("dwBytesRecorded", wintypes.DWORD),
                ("dwUser", ctypes.c_void_p),
                ("dwFlags", wintypes.DWORD),
                ("dwLoops", wintypes.DWORD),
                ("lpNext", ctypes.c_void_p),
                ("reserved", ctypes.c_void_p),
            ]

        self.WAVEHDR = WAVEHDR
        done = ctypes.WINFUNCTYPE(
            None, ctypes.c_void_p, ctypes.c_uint,
            ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)

        def on_wave(_hwo, msg, _inst, _p1, _p2):
            if msg == 0x3BD:  # WOM_DONE
                self.free.release()

        self._cb = done(on_wave)
        fmt = WAVEFORMATEX()
        fmt.wFormatTag = 1
        fmt.nChannels = 2
        fmt.nSamplesPerSec = SR
        fmt.wBitsPerSample = 16
        fmt.nBlockAlign = 4
        fmt.nAvgBytesPerSec = SR * 4
        self.hwo = ctypes.c_void_p()
        self.winmm.waveOutOpen.argtypes = [
            ctypes.POINTER(ctypes.c_void_p), ctypes.c_uint,
            ctypes.POINTER(WAVEFORMATEX), ctypes.c_void_p,
            ctypes.c_void_p, ctypes.c_uint]
        self.winmm.waveOutOpen.restype = ctypes.c_uint
        rc = self.winmm.waveOutOpen(
            ctypes.byref(self.hwo), ctypes.c_uint(-1), ctypes.byref(fmt),
            self._cb, None, 0x00030000)
        if rc != 0:
            raise RuntimeError("waveOutOpen %s" % rc)

    def write(self, data):
        while not self.free.acquire(timeout=0.02):
            if not self.running():
                return
        if not self.running():
            self.free.release()
            return
        self._reap()
        buf = self.ctypes.create_string_buffer(data)
        hdr = self.WAVEHDR()
        hdr.lpData = self.ctypes.cast(buf, self.ctypes.c_void_p)
        hdr.dwBufferLength = len(data)
        self.winmm.waveOutPrepareHeader(self.hwo, self.ctypes.byref(hdr), self.ctypes.sizeof(hdr))
        self.winmm.waveOutWrite(self.hwo, self.ctypes.byref(hdr), self.ctypes.sizeof(hdr))
        self.pending.append((buf, hdr))

    def _reap(self):
        still = []
        for old_buf, old_hdr in self.pending:
            if old_hdr.dwFlags & 1:
                self.winmm.waveOutUnprepareHeader(
                    self.hwo, self.ctypes.byref(old_hdr), self.ctypes.sizeof(old_hdr))
            else:
                still.append((old_buf, old_hdr))
        self.pending = still

    def close(self):
        self.winmm.waveOutReset(self.hwo)
        self._reap()
        self.winmm.waveOutClose(self.hwo)


def open_out(running):
    if sys.platform == "win32":
        return WaveOut(running)
    return AplayOut()


def clamp01(v):
    if v < 0.0:
        return 0.0
    if v > 1.0:
        return 1.0
    return v


def gear_name(gear):
    if gear < 0:
        return "R"
    if gear == 0:
        return "N"
    return str(gear)


P, B, ECO, D, N, R = 0, 1, 2, 5, 6, 7


def _blend(dt, tau):
    return 1.0 - math.exp(-dt / tau)


def _smooth(x):
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    return x * x * (3.0 - 2.0 * x)


class Gearbox(object):
    """Line-for-line copy of model/Gearbox.java. make test compares the traces."""

    IDLE = 780.0
    REDLINE = 7500.0
    ECO_LOAD = 0.05
    REV_UP_TAU = 0.9
    REV_DOWN_TAU = 0.9
    STALL_EXTRA = 1700.0
    RATIO = (4.714, 3.143, 2.106, 1.667, 1.285, 1.000, 0.839, 0.667)
    FINAL = 3.08
    WHEEL_M = 2.07
    REVERSE = 3.30

    def __init__(self):
        self.gear = 0
        self.rpm = self.IDLE
        self.limiter = False
        self.free_rev = False
        self.throttle = 0.0
        self.drive_gear = 1
        self.last_mode = 0
        self.from_ratio = 0.0
        self.shift_t = 0.0
        self.shift_dur = 0.0
        self.shift_up = False
        self.shift_lock = 0.0
        self.load = 0.0
        self.tip_t = 0.0
        self.steady_for = 0.0
        self.pedal_trail = -1.0
        self.intake = 0.0
        self.slip = 0.0
        self.lim_phase = 0.0
        self.saw_off = False
        self.crank = False
        self.crank_t = 0.0
        self.crank_from = 0.0
        self.last_mps = -1.0
        self.accel = 0.0
        self.accel_slow = 0.0

    @staticmethod
    def rpm_for_ratio(kmh, ratio):
        mps = max(0.0, kmh) / 3.6
        return (mps / Gearbox.WHEEL_M) * ratio * 60.0

    @staticmethod
    def rpm_for_speed(kmh, gear):
        if gear < 0:
            return Gearbox.rpm_for_ratio(kmh, Gearbox.REVERSE * Gearbox.FINAL)
        if 1 <= gear <= len(Gearbox.RATIO):
            return Gearbox.rpm_for_ratio(kmh, Gearbox.RATIO[gear - 1] * Gearbox.FINAL)
        return Gearbox.IDLE

    def update(self, dt, kmh, pedal, brake, running, shifter=D):
        if dt < 0.001:
            dt = 0.001
        elif dt > 0.25:
            dt = 0.25
        self.limiter = False
        self.free_rev = False
        if not running:
            self.gear = 0
            self.drive_gear = 1
            self.last_mode = 0
            self.shift_t = self.shift_dur = 0.0
            self.last_mps = -1.0
            self.accel = 0.0
            self.accel_slow = 0.0
            self.load = 0.0
            self.steady_for = 0.0
            self.pedal_trail = -1.0
            self.intake = 0.0
            self.throttle = 0.0
            self.slip = 0.0
            self.saw_off = True
            self.crank = False
            self.rpm -= (100.0 + self.rpm * 0.25) * dt
            if self.rpm < 0.0:
                self.rpm = 0.0
            return

        pedal = clamp01(pedal)
        kmh = max(0.0, kmh)
        if self.saw_off:
            self.saw_off = False
            if kmh < 2.0 and self.rpm < self.IDLE + 40.0:
                self.crank = True
                self.crank_t = 0.0
                self.crank_from = self.rpm
        self.intake += (pedal - self.intake) * _blend(dt, 1.0 if pedal >= self.intake else 0.7)
        self._track_accel(kmh, dt)
        mode = self.mode_of(shifter, kmh)
        free = mode == 0 or (kmh < 2.0 and pedal > 0.02 and brake)

        if free:
            self.free_rev = pedal > 0.02
            self.gear = -1 if mode < 0 else (0 if mode == 0 else max(1, self.drive_gear))
            self.shift_t = self.shift_dur = 0.0
            self.slip = 0.0
            target = self.IDLE + self.intake ** 1.15 * (self.REDLINE - 100.0 - self.IDLE)
            if self.crank:
                if self.free_rev:
                    self.crank = False
                else:
                    target = self._crank_target(dt)
            d = target - self.rpm
            v = d / 0.15 if self.crank else self._flywheel(d)
            self.rpm += v * dt
            if self.intake > 0.9 and self.rpm >= self.REDLINE - 160.0:
                self.limiter = True
                self.lim_phase += dt * 9.0
                self.lim_phase -= math.floor(self.lim_phase)
                self.rpm = self.REDLINE - 100.0 - 260.0 * self.lim_phase
            self.throttle = self.intake * 0.6 if self.limiter else self.intake
        else:
            if mode < 0:
                self.gear = -1
                self.shift_t = self.shift_dur = 0.0
                locked = self.rpm_for_speed(kmh, -1)
            else:
                if self.last_mode != 1:
                    self._settle_gear(kmh, pedal)
                self._select_drive_gear(dt, kmh, pedal, brake)
                self.gear = self.drive_gear
                locked = self.rpm_for_ratio(kmh, self._ratio_now())
            fade = 1.0 - kmh / 20.0
            if fade < 0.0:
                fade = 0.0
            self.slip += (self.intake * self.STALL_EXTRA * fade - self.slip) * _blend(dt, 0.35)
            target = max(locked, self.IDLE + self.slip)
            if target > self.REDLINE - 100.0:
                self.limiter = True
                target = self.REDLINE - 100.0
            if mode < 0 and target > 5200.0:
                target = 5200.0
            if self.crank and (kmh >= 2.0 or target > self.IDLE + 150.0):
                self.crank = False
            if self.crank:
                target = self._crank_target(dt)
            d = target - self.rpm
            v = d / 0.12
            if d < 0.0 and fade > 0.0 and self.shift_t >= self.shift_dur:
                v = self._flywheel(d)
            elif self.shift_t >= self.shift_dur:
                v = min(3500.0, max(-1800.0, v))
            self.rpm += v * dt
            self.throttle = self._shift_throttle(dt)
        self.last_mode = mode
        if self.rpm > self.REDLINE:
            self.rpm = self.REDLINE
        if self.rpm < 0.0:
            self.rpm = 0.0

    def _flywheel(self, d):
        if d > 0.0:
            return min(d / self.REV_UP_TAU, 1300.0 - self.rpm * 0.06)
        return max(d / self.REV_DOWN_TAU, -(250.0 + self.rpm * 0.12))

    def _crank_target(self, dt):
        self.crank_t += dt
        if self.crank_t < 2.5:
            s = _smooth(self.crank_t / 2.5)
            return self.crank_from + (1100.0 - self.crank_from) * s
        settle = math.exp(-(self.crank_t - 2.5) / 1.8)
        if settle < 0.02:
            self.crank = False
        return self.IDLE + (1100.0 - self.IDLE) * settle

    def _shift_throttle(self, dt):
        if self.shift_t >= self.shift_dur:
            return self.intake
        self.shift_t += dt
        p = min(1.0, self.shift_t / self.shift_dur)
        bump = math.sin(p * math.pi)
        if self.shift_up:
            return self.intake * (1.0 - 0.45 * bump)
        return max(self.intake, 0.25 * bump)

    def _ratio_now(self):
        to = self.RATIO[self.drive_gear - 1] * self.FINAL
        if self.shift_t >= self.shift_dur:
            return to
        return self.from_ratio + (to - self.from_ratio) * _smooth(self.shift_t / self.shift_dur)

    def _start_shift(self, to, kmh):
        self.from_ratio = self._ratio_now()
        self.shift_up = to > self.drive_gear
        self.drive_gear = to
        self.shift_t = 0.0
        if self.shift_up:
            self.shift_dur = 0.75 - 0.3 * self.load
        else:
            self.shift_dur = 0.45 if self.load > 0.6 else 0.6
        step = abs(self.rpm_for_ratio(kmh, self.RATIO[to - 1] * self.FINAL)
                   - self.rpm_for_ratio(kmh, self.from_ratio))
        if step / 4500.0 > self.shift_dur:
            self.shift_dur = step / 4500.0
        self.shift_lock = self.shift_dur + (0.9 if self.accel < -0.35 else 0.35)

    def _settle_gear(self, kmh, pedal):
        self.load = pedal
        g = len(self.RATIO)
        while g > 1 and self.rpm_for_speed(kmh, g) < self.down_at(self.load):
            g -= 1
        self.drive_gear = g
        self.shift_t = self.shift_dur = 0.0

    @staticmethod
    def up_at(load):
        return 1500.0 + 5700.0 * load ** 1.5

    @staticmethod
    def down_at(load):
        return 1000.0 + 3200.0 * load ** 1.5

    def _track_load(self, dt, pedal, brake):
        rise = 0.0 if self.pedal_trail < 0.0 else pedal - self.pedal_trail
        if self.pedal_trail < 0.0:
            self.pedal_trail = pedal
        self.pedal_trail += (pedal - self.pedal_trail) * _blend(dt, 0.5)
        steady = abs(self.accel_slow) < 0.3 and rise < 0.05 and not brake
        self.steady_for = self.steady_for + dt if steady else 0.0
        want = min(pedal, self.ECO_LOAD) if self.steady_for > 2.0 else pedal
        if want > self.load:
            tau = 0.15
        else:
            tau = 1.5 if self.steady_for > 2.0 else 0.5
        self.load += (want - self.load) * _blend(dt, tau)
        self.tip_t = self.tip_t + dt if pedal - self.load > 0.12 else 0.0

    def _select_drive_gear(self, dt, kmh, pedal, brake):
        if kmh < 1.2:
            self.drive_gear = 1
            self.shift_t = self.shift_dur = 0.0
            self.shift_lock = 0.0
            self.steady_for = 0.0
            self._track_load(dt, pedal, brake)
            return
        self._track_load(dt, pedal, brake)
        if self.shift_lock > 0.0:
            self.shift_lock -= dt
        cur = self.rpm_for_speed(kmh, self.drive_gear)
        top = len(self.RATIO)
        if cur > self.REDLINE - 200.0 and self.drive_gear < top:
            self._start_shift(self.drive_gear + 1, kmh)
            return
        if self.shift_lock > 0.001:
            return
        decel = -self.accel if self.accel < 0.0 else 0.0
        urgency = clamp01((decel - 0.6) / 2.5)
        extra = urgency * 900.0 + (300.0 if brake else 0.0)
        down = self.down_at(self.load) + extra
        up = self.up_at(self.load)
        if self.accel < -0.35 and up < 3000.0:
            up = 3000.0
        if (self.drive_gear < top and cur > up
                and self.rpm_for_speed(kmh, self.drive_gear + 1) >= down + 150.0):
            self._start_shift(self.drive_gear + 1, kmh)
            return
        if 0.0 < self.tip_t < 0.3:
            return
        demand = self.load if self.steady_for > 2.0 else max(self.load, pedal)
        kick = self.down_at(demand) + extra
        g = self.drive_gear
        while g > 1 and self.rpm_for_speed(kmh, g) < kick:
            if self.rpm_for_speed(kmh, g - 1) > self.REDLINE - 900.0:
                break
            if g == 2 and kmh >= 14.0:
                break
            g -= 1
        if g != self.drive_gear:
            self._start_shift(g, kmh)

    def _track_accel(self, kmh, dt):
        mps = kmh / 3.6
        if self.last_mps < 0.0:
            self.last_mps = mps
            return
        inst = (mps - self.last_mps) / dt
        self.last_mps = mps
        inst = min(12.0, max(-12.0, inst))
        self.accel += (inst - self.accel) * _blend(dt, 0.35)
        self.accel_slow += (inst - self.accel_slow) * _blend(dt, 1.2)

    @staticmethod
    def mode_of(shifter, kmh):
        if shifter == R:
            return -1
        if shifter == N:
            return 0
        if shifter in (D, B, ECO):
            return 1
        if kmh > 2.0:
            return 1
        return 0


# Same drive as host/.../GearboxTrace.java: seconds, pedal, brake, shifter, running.
TRACE = (
    (1.0, 0.0, 0.0, P, 0.0),
    (10.0, 0.0, 0.0, P, 1.0),
    (4.0, 1.0, 0.0, P, 1.0),
    (6.0, 0.0, 0.0, P, 1.0),
    (2.0, 0.0, 1.0, D, 1.0),
    (9.0, 1.0, 0.0, D, 1.0),
    (8.0, 0.35, 0.0, D, 1.0),
    (3.0, 1.0, 0.0, D, 1.0),
    (10.0, 0.0, 0.0, D, 1.0),
    (6.0, 0.0, 0.6, D, 1.0),
    (3.0, 0.0, 0.0, P, 0.0),
)


def trace():
    box = Gearbox()
    kmh = 0.0
    dt = 0.05
    step = 0
    for seconds, pedal, brake, shifter, running in TRACE:
        for _ in range(int(round(seconds / dt))):
            a = pedal * 22.0 - 2.5 - brake * 30.0 - kmh * 0.02
            if shifter == P:
                a = -30.0
            kmh = min(185.0, max(0.0, kmh + a * dt))
            box.update(dt, kmh, pedal, brake > 0.5, running > 0.5, shifter)
            step += 1
            if (step - 1) % 4 == 0:
                print("%.2f %.2f %d %.1f %.3f" % (step * dt, kmh, box.gear, box.rpm, box.throttle))


class Drive(object):
    """Pedal is torque demand, not a speed. Holding a speed takes a light pedal."""

    def __init__(self):
        self.box = Gearbox()
        self.kmh = 0.0

    def reset(self):
        self.box = Gearbox()
        self.kmh = 0.0

    def _accel(self, pedal, brake):
        v = self.kmh / 3.6
        drive = pedal ** 1.5 * min(A_GRIP, POWER_PER_KG / max(v, 0.1))
        resist = DRAG_K * v * v + (ROLL_A if v > 0.05 else 0.0)
        # Regen fades in as the pedal comes up, and out below walking pace.
        resist += REGEN_A * max(0.0, 1.0 - pedal / 0.08) * min(1.0, v / 3.0)
        resist += brake * BRAKE_A
        return drive - resist

    def step(self, dt, pedal, brake):
        pedal = clamp01(pedal)
        brake = clamp01(brake)
        self.kmh += self._accel(pedal, brake) * 3.6 * dt
        self.kmh = min(TOP_KMH, max(0.0, self.kmh))
        self.box.update(dt, self.kmh, pedal, brake > 0.02, True)
        return self.kmh, self.box.rpm, self.box.gear, self.box.limiter


def drive_check():
    drive = Drive()
    dt = 0.01
    t = 0.0
    shifted = False
    fall = 0.0
    while t < 4.5 - 1e-9:
        before = drive.box.rpm
        gear_before = drive.box.gear
        drive.step(dt, 1.0, 0.0)
        if (not shifted) and drive.box.gear > gear_before and gear_before >= 1:
            shifted = True
            trail = [before, drive.box.rpm]
            for _ in range(70):
                drive.step(dt, 1.0, 0.0)
                t += dt
                trail.append(drive.box.rpm)
            peak = max(trail)
            fall = peak - min(trail[trail.index(peak):])
            early = max(trail[i] - trail[i + 8] for i in range(len(trail) - 8))
            if early > 450.0:
                raise SystemExit("shift snapped %.0f rpm in 0.08 s" % early)
        t += dt
    if abs(drive.kmh - 100.0) > 1.5:
        raise SystemExit("0-100 was %.2f km/h" % drive.kmh)
    if not shifted or fall < 1000.0:
        raise SystemExit("upshift fall was %.0f rpm" % fall)
    at_100 = drive.kmh
    gear_100 = drive.box.gear
    for _ in range(200):
        drive.step(dt, 0.0, 0.0)
    if drive.kmh > at_100 - 5.0:
        raise SystemExit("lift-off did not slow the car (%.1f)" % drive.kmh)
    stopped = Drive()
    for _ in range(150):
        stopped.step(dt, 1.0, 1.0)
    # Brake-held free rev climbs at flywheel pace, not instantly.
    if stopped.kmh > 0.5 or stopped.box.rpm < 1800.0 or stopped.box.rpm > 3200.0:
        raise SystemExit("brake free-rev failed kmh=%.2f rpm=%.0f" % (stopped.kmh, stopped.box.rpm))
    # Hold half pedal. Once speed stops climbing it should walk to 8th.
    top = Drive()
    for _ in range(int(90.0 / dt)):
        top.step(dt, 0.5, 0.0)
    if top.box.gear != 8 or top.kmh < 175.0 or not (2800.0 < top.box.rpm < 3400.0):
        raise SystemExit("cruise gear %s rpm %.0f at %.1f km/h" % (
            gear_name(top.box.gear), top.box.rpm, top.kmh))
    print("drive ok 4.5s=%.1f km/h gear %s fall %.0f rpm cruise %s @ %.0f" % (
        at_100, gear_name(gear_100), fall, gear_name(top.box.gear), top.box.rpm))


def check():
    cars = load_bank()
    honda = cars[0]
    data, active = render(honda, 680, 1.0, 1.0, SR, False)
    samples = struct.unpack("<%dh" % (len(data) // 2), data)
    acc = 0.0
    for i in range(0, len(samples), 2):
        v = samples[i] / 32768.0
        acc += v * v
    rms = (acc / (len(samples) // 2)) ** 0.5
    if rms < 0.02:
        raise SystemExit("silent render rms %s" % rms)
    print("demo render ok rms=%.3f cars=%s" % (rms, ",".join(c["label"] for c in cars)))
    drive_check()


def queue_check():
    """Writing ahead of the device must take about as long as playback."""
    if sys.platform != "win32":
        print("queue check skipped")
        return
    running = {"on": True}
    out = WaveOut(lambda: running["on"])
    chunk = b"\x00" * (2048 * 4)
    started = time.time()
    try:
        for _ in range(20):
            out.write(chunk)
    finally:
        running["on"] = False
        out.close()
    elapsed = time.time() - started
    # 18 of 20 buffers have to finish before write returns. ~0.8 s.
    if elapsed < 0.5:
        raise SystemExit("wave queue did not wait, elapsed %.3f" % elapsed)
    print("queue check ok elapsed=%.2f" % elapsed)


def main():
    if "--check" in sys.argv:
        check()
        return
    if "--trace" in sys.argv:
        trace()
        return
    if "--queue-check" in sys.argv:
        queue_check()
        return
    import tkinter as tk
    cars = load_bank()
    state = {
        "car": 0,
        "rpm": 780.0,
        "pedal": 0.0,
        "brake": 0.0,
        "volume": 80.0,
        "kmh": 0.0,
        "gear": 1,
        "limiter": False,
        "run": False,
        "active": 0,
    }
    root = tk.Tk()
    root.title("Engine Sound demo")
    root.geometry("560x340")
    names = [c["label"] for c in cars]
    chosen = tk.StringVar(value=names[0])
    tk.Label(root, text="Floored pedal: 0-100 km/h in 4.5 s. Revs follow the gearbox.").pack(anchor="w", padx=12, pady=(10, 0))
    tk.OptionMenu(root, chosen, *names).pack(anchor="w", padx=12)

    def slider(label, frm, to, initial, key):
        row = tk.Frame(root)
        row.pack(fill="x", padx=12, pady=4)
        text = tk.Label(row, text=label, width=10, anchor="w")
        text.pack(side="left")
        scale = tk.Scale(row, from_=frm, to=to, orient="horizontal", showvalue=True, length=380)
        scale.set(initial)
        scale.pack(side="left")

        def on(_v):
            state[key] = float(scale.get())
        scale.configure(command=on)
        return scale

    slider("Pedal", 0, 100, 0, "pedal")
    slider("Brake", 0, 100, 0, "brake")
    slider("Volume", 0, 100, 80, "volume")
    readout = tk.Label(root, text="0 km/h    gear 1    780 rpm", anchor="w", font=("TkDefaultFont", 12))
    readout.pack(fill="x", padx=12, pady=(8, 0))
    status = tk.Label(root, text="Stopped. Raise the pedal to pull away.", anchor="w")
    status.pack(fill="x", padx=12, pady=6)

    def on_car(*_args):
        state["car"] = names.index(chosen.get())

    chosen.trace_add("write", on_car)

    drive = Drive()

    def loop():
        drive.reset()
        play = {"rpm": drive.box.rpm, "pedal": 0.0}
        out = open_out(lambda: state["run"])
        frames = 2048
        dt = frames / float(SR)
        try:
            while state["run"]:
                car = cars[state["car"]]
                pedal = state["pedal"] / 100.0
                brake = state["brake"] / 100.0
                volume = state["volume"] / 100.0
                kmh, rpm, gear, limiter = drive.step(dt, pedal, brake)
                state["kmh"] = kmh
                state["rpm"] = rpm
                state["gear"] = gear
                state["limiter"] = limiter
                # Glide pitch in 256-frame slices so it never steps per buffer (tau ~30 ms).
                parts = []
                for _ in range(frames // 256):
                    play["rpm"] += (rpm - play["rpm"]) * 0.17
                    play["pedal"] += (drive.box.throttle - play["pedal"]) * 0.17
                    data, active = render(car, play["rpm"], play["pedal"], volume, 256, limiter)
                    parts.append(data)
                state["active"] = active
                out.write(b"".join(parts))
        finally:
            out.close()

    worker = {"t": None}

    def play():
        if state["run"]:
            return
        state["run"] = True
        worker["t"] = threading.Thread(target=loop)
        worker["t"].daemon = True
        worker["t"].start()
        status.configure(text="Playing")

    def stop():
        state["run"] = False
        state["kmh"] = 0.0
        state["rpm"] = 780.0
        state["gear"] = 1
        state["limiter"] = False
        status.configure(text="Stopped. Raise the pedal to pull away.")

    def tick():
        lim = "    limiter" if state["limiter"] else ""
        readout.configure(text="%.0f km/h    gear %s    %.0f rpm%s" % (
            state["kmh"], gear_name(state["gear"]), state["rpm"], lim))
        if state["run"] and state["active"]:
            rate = state["rpm"] / state["active"]
            status.configure(text="Playing    layer %d rpm    rate %.2f" % (int(state["active"]), rate))
        root.after(100, tick)

    buttons = tk.Frame(root)
    buttons.pack(anchor="w", padx=12)
    tk.Button(buttons, text="Play", command=play, width=10).pack(side="left")
    tk.Button(buttons, text="Stop", command=stop, width=10).pack(side="left", padx=8)
    root.protocol("WM_DELETE_WINDOW", lambda: (stop(), root.destroy()))
    tick()
    root.mainloop()


if __name__ == "__main__":
    main()
