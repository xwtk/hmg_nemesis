package com.nemesis.enginesound.synth;

/**
 * Recorded loops from one car: throttle ("on") and overrun ("off") sets,
 * each ordered by RPM. Pedal crossfades throttle against overrun. RPM
 * crossfades neighbouring loops of a set at its band points. Each loop is
 * pitch-shifted from the RPM it was recorded at. A short limiter loop is
 * added while the gearbox is on the rev limiter.
 */
public final class ThrottleVoice implements EngineVoice {
    public static final String BAC = "bac";
    public static final String FERRARI = "ferrari";
    public static final String PROCAR = "procar";
    public static final String GT = "gt";

    private final String id;
    private final String label;
    private final SampleVoice.Loop[] on;
    private final float[] onBand;
    private final SampleVoice.Loop[] off;
    private final float[] offBand;
    private final SampleVoice.Loop limiter;
    private final float[] onGain;
    private final float[] offGain;

    /** Each loop set pairs with an equally long, ascending band of RPM points. */
    public ThrottleVoice(String id, String label,
            SampleVoice.Loop[] on, float[] onBand,
            SampleVoice.Loop[] off, float[] offBand, SampleVoice.Loop limiter) {
        this.id = id;
        this.label = label;
        this.on = on;
        this.onBand = onBand;
        this.off = off;
        this.offBand = offBand;
        this.limiter = limiter;
        this.onGain = new float[on.length];
        this.offGain = new float[off.length];
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

        float onAmt = (float) Math.sin(pedal * Math.PI * 0.5);
        float offAmt = (float) Math.cos(pedal * Math.PI * 0.5);
        spread(rpm, onBand, onGain);
        spread(rpm, offBand, offGain);
        for (int i = 0; i < on.length; i++) mix(dst, frames, on[i], rpm, volume * onAmt * onGain[i]);
        for (int i = 0; i < off.length; i++) mix(dst, frames, off[i], rpm, volume * offAmt * offGain[i]);
        if (limit && limiter != null) {
            mix(dst, frames, limiter, rpm, volume);
        }
    }

    /** Equal-power crossfade between the two band points around rpm; the ends hold. */
    private static void spread(float rpm, float[] band, float[] gain) {
        int last = band.length - 1;
        for (int i = 0; i <= last; i++) gain[i] = 0f;
        int k = 0;
        while (k < last && rpm >= band[k + 1]) k++;
        float span = k < last ? band[k + 1] - band[k] : 0f;
        float u = span > 1f ? (rpm - band[k]) / span : 0f;
        if (u < 0f) u = 0f;
        if (k < last && span > 1f) {
            gain[k] = (float) Math.cos(u * Math.PI * 0.5);
            gain[k + 1] = (float) Math.sin(u * Math.PI * 0.5);
        } else {
            gain[k] = 1f;
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
