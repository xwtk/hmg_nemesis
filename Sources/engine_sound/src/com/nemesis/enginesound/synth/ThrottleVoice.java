package com.nemesis.enginesound.synth;

/**
 * Four recorded loops from one car: throttle low and high, overrun low and
 * high. Pedal crossfades throttle against overrun. RPM crossfades the low
 * recording against the high one. Each loop is pitch-shifted from the RPM
 * it was recorded at. A short limiter loop is added while the gearbox is
 * on the rev limiter.
 */
public final class ThrottleVoice implements EngineVoice {
    public static final String BAC = "bac";
    public static final String FERRARI = "ferrari";
    public static final String PROCAR = "procar";

    private final String id;
    private final String label;
    private final SampleVoice.Loop onLow;
    private final SampleVoice.Loop onHigh;
    private final SampleVoice.Loop offLow;
    private final SampleVoice.Loop offHigh;
    private final SampleVoice.Loop limiter;
    private final float bandLow;
    private final float bandHigh;

    public ThrottleVoice(String id, String label,
            SampleVoice.Loop onLow, SampleVoice.Loop onHigh,
            SampleVoice.Loop offLow, SampleVoice.Loop offHigh,
            SampleVoice.Loop limiter, float bandLow, float bandHigh) {
        this.id = id;
        this.label = label;
        this.onLow = onLow;
        this.onHigh = onHigh;
        this.offLow = offLow;
        this.offHigh = offHigh;
        this.limiter = limiter;
        this.bandLow = bandLow;
        this.bandHigh = bandHigh;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public void render(short[] dst, int frames, float rpm, float pedal, float volume, boolean limit) {
        if (rpm < 40f) rpm = 40f;
        if (pedal < 0f) pedal = 0f;
        else if (pedal > 1f) pedal = 1f;
        if (volume < 0f) volume = 0f;
        else if (volume > 1f) volume = 1f;
        int n = frames * 2;
        if (dst.length < n) n = dst.length - (dst.length & 1);
        for (int i = 0; i < n; i++) dst[i] = 0;

        float span = bandHigh - bandLow;
        float u = span > 1f ? (rpm - bandLow) / span : 0f;
        if (u < 0f) u = 0f;
        else if (u > 1f) u = 1f;
        float low = (float) Math.cos(u * Math.PI * 0.5);
        float high = (float) Math.sin(u * Math.PI * 0.5);
        float on = (float) Math.sin(pedal * Math.PI * 0.5);
        float off = (float) Math.cos(pedal * Math.PI * 0.5);

        mix(dst, frames, onLow, rpm, volume * on * low);
        mix(dst, frames, onHigh, rpm, volume * on * high);
        mix(dst, frames, offLow, rpm, volume * off * low);
        mix(dst, frames, offHigh, rpm, volume * off * high);
        if (limit && limiter != null) {
            mix(dst, frames, limiter, rpm, volume);
        }
    }

    private static void mix(short[] dst, int frames, SampleVoice.Loop loop, float rpm, float gain) {
        if (loop == null || gain <= 0.0001f || loop.frames < 2) return;
        double rate = rpm / loop.rpm;
        if (rate < 0.25) rate = 0.25;
        if (rate > 4.0) rate = 4.0;
        int n = loop.frames;
        short[] pcm = loop.pcm;
        double pos = loop.cursor;
        if (pos < 0 || pos >= n) pos = 0;
        for (int i = 0; i < frames; i++) {
            float left = read(pcm, n, pos, 0);
            float right = read(pcm, n, pos, 1);
            int o = i * 2;
            dst[o] = clamp(dst[o] + left * gain);
            dst[o + 1] = clamp(dst[o + 1] + right * gain);
            pos += rate;
            if (pos >= n) pos -= n;
        }
        loop.cursor = pos;
    }

    private static float read(short[] pcm, int frames, double pos, int channel) {
        while (pos >= frames) pos -= frames;
        while (pos < 0) pos += frames;
        int i0 = (int) pos;
        int i1 = i0 + 1;
        if (i1 >= frames) i1 = 0;
        float frac = (float) (pos - i0);
        int a = i0 * 2 + channel;
        int b = i1 * 2 + channel;
        return pcm[a] + (pcm[b] - pcm[a]) * frac;
    }

    private static short clamp(float sample) {
        if (sample > 32767f) return 32767;
        if (sample < -32768f) return -32768;
        return (short) sample;
    }
}
