package com.nemesis.enginesound.synth;

/**
 * Plays recorded exhaust loops. The loop whose recorded RPM is nearest the
 * target is resampled to that RPM. Between two loops the two results are
 * crossfaded. Honda layers are 680, 2300 and 3200. C43 layers are 1760
 * and 5280. G37 layers run from 1500 through a 7300 rpm layer.
 */
public final class SampleVoice implements EngineVoice {
    public static final String HONDA = "honda";
    public static final String C43 = "c43";
    public static final String G37 = "g37";

    public static final class Loop {
        public final short[] pcm;
        public final int frames;
        public final float rpm;
        double cursor;

        public Loop(short[] interleavedStereo, float rpm) {
            this.pcm = interleavedStereo;
            this.frames = interleavedStereo.length / 2;
            this.rpm = rpm;
        }
    }

    private final String id;
    private final String label;
    private final Loop[] loops;
    private float chop;

    public SampleVoice(String id, String label, Loop[] loops) {
        this.id = id;
        this.label = label;
        this.loops = loops;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public void render(short[] dst, int frames, float rpm, float pedal, float volume, boolean limiter) {
        if (rpm < 40f) rpm = 40f;
        if (pedal < 0f) pedal = 0f;
        else if (pedal > 1f) pedal = 1f;
        if (volume < 0f) volume = 0f;
        else if (volume > 1f) volume = 1f;
        int n = frames * 2;
        if (dst.length < n) n = dst.length - (dst.length & 1);
        for (int i = 0; i < n; i++) dst[i] = 0;

        int upper = 0;
        while (upper < loops.length && loops[upper].rpm < rpm) upper++;
        int lo;
        int hi;
        float blend;
        if (loops.length == 1 || upper <= 0) {
            lo = 0;
            hi = 0;
            blend = 0f;
        } else if (upper >= loops.length) {
            lo = loops.length - 1;
            hi = lo;
            blend = 0f;
        } else {
            lo = upper - 1;
            hi = upper;
            float span = loops[hi].rpm - loops[lo].rpm;
            blend = span > 1f ? (rpm - loops[lo].rpm) / span : 0f;
        }

        float gain = volume * (0.42f + 0.58f * pedal);
        accumulate(dst, frames, loops[lo], rpm, gain * (1f - blend), limiter);
        if (hi != lo) {
            accumulate(dst, frames, loops[hi], rpm, gain * blend, false);
        }
    }

    private void accumulate(short[] dst, int frames, Loop loop, float rpm, float gain, boolean cut) {
        if (gain <= 0.0001f || loop.frames < 2) return;
        double rate = rpm / loop.rpm;
        if (rate < 0.32) rate = 0.32;
        if (rate > 2.4) rate = 2.4;
        int n = loop.frames;
        short[] pcm = loop.pcm;
        double pos = loop.cursor;
        if (pos < 0 || pos >= n) pos = 0;
        for (int i = 0; i < frames; i++) {
            float left = read(pcm, n, pos, 0);
            float right = read(pcm, n, pos, 1);
            float g = gain;
            if (cut) {
                chop += 28f / 44100f;
                if (chop >= 1f) chop -= 1f;
                if (chop < 0.5f) g *= 0.25f;
            }
            int o = i * 2;
            dst[o] = clamp(dst[o] + left * g);
            dst[o + 1] = clamp(dst[o + 1] + right * g);
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
