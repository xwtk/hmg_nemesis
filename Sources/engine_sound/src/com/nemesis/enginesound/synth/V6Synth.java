package com.nemesis.enginesound.synth;

/**
 * Real-time even-fire V6. Three exhaust pulses per revolution (6 cylinders,
 * four-stroke). The turbo voice adds a rising whistle and a whoosh; that is
 * the VR35DDTT character. The plain voice is the naturally aspirated VQ35
 * used in the G35. Neither is a recording.
 */
public final class V6Synth implements EngineVoice {
    public static final String ID_VR35 = "vr35";
    public static final String ID_VQ35 = "vq35";

    public final String id;
    public final String label;
    public final boolean turbo;

    private final int sampleRate;
    private double firePhase;
    private double harmPhase;
    private double intakePhase;
    private double whistlePhase;
    private float env;
    private float lp;
    private float bov;
    private float lastPedal;
    private int noise = 0x1234567;

    public V6Synth(String id, String label, boolean turbo, int sampleRate) {
        this.id = id;
        this.label = label;
        this.turbo = turbo;
        this.sampleRate = sampleRate;
    }

    public static V6Synth vr35(int sampleRate) {
        return new V6Synth(ID_VR35, "VR35 twin-turbo V6", true, sampleRate);
    }

    public static V6Synth vq35(int sampleRate) {
        return new V6Synth(ID_VQ35, "VQ35 V6 (G35)", false, sampleRate);
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static V6Synth byId(String id, int sampleRate) {
        if (ID_VQ35.equals(id)) return vq35(sampleRate);
        return vr35(sampleRate);
    }

    /**
     * Interleaved stereo PCM. {@code rpm} is engine speed, {@code pedal} is
     * 0..1, {@code volume} is 0..1 track gain.
     */
    public void render(short[] dst, int frames, float rpm, float pedal, float volume, boolean limiter) {
        if (rpm < 40f) rpm = 40f;
        if (pedal < 0f) pedal = 0f;
        else if (pedal > 1f) pedal = 1f;
        if (volume < 0f) volume = 0f;
        else if (volume > 1f) volume = 1f;

        double revHz = rpm / 60.0;
        double fireHz = revHz * 3.0;
        double fireStep = fireHz / sampleRate;
        double harmStep = (revHz * 1.5) / sampleRate;
        double intakeStep = (revHz * 2.0) / sampleRate;
        double whistleHz = 700.0 + rpm * 0.62 + pedal * 2200.0;
        double whistleStep = whistleHz / sampleRate;
        // Shorter pulses as RPM rises so they stay separate.
        float decay = (float) Math.exp(-fireHz / (sampleRate * 0.55));
        float twoPi = (float) (Math.PI * 2.0);
        float pedalDrop = lastPedal - pedal;
        if (turbo && pedalDrop > 0.08f) {
            bov = Math.min(1f, bov + pedalDrop * 2.5f);
        }
        lastPedal = pedal;

        int n = frames * 2;
        if (dst.length < n) n = dst.length - (dst.length & 1);
        float gain = volume * 0.72f;
        for (int i = 0, s = 0; s < n; i++, s += 2) {
            firePhase += fireStep;
            if (firePhase >= 1.0) {
                firePhase -= 1.0;
                env = 1f;
            }
            env *= decay;

            harmPhase += harmStep;
            if (harmPhase >= 1.0) harmPhase -= 1.0;
            intakePhase += intakeStep;
            if (intakePhase >= 1.0) intakePhase -= 1.0;
            whistlePhase += whistleStep;
            if (whistlePhase >= 1.0) whistlePhase -= 1.0;

            float nz = nextNoise();
            lp += (nz - lp) * (0.02f + pedal * 0.08f);

            float pulse = env * (0.55f * (float) Math.sin(firePhase * twoPi * 2.0)
                    + 0.45f * nz);
            float rumble = 0.22f * (float) Math.sin(harmPhase * twoPi);
            float intake = (0.08f + pedal * (turbo ? 0.10f : 0.28f))
                    * (float) Math.sin(intakePhase * twoPi);
            float body = 0.16f * (float) Math.sin(firePhase * twoPi);

            float turboPart = 0f;
            if (turbo) {
                float spool = pedal * Math.min(1f, rpm / 2800f);
                turboPart = spool * 0.20f * (float) Math.sin(whistlePhase * twoPi);
                turboPart += spool * 0.10f * lp;
                if (bov > 0.001f) {
                    turboPart += bov * 0.35f * lp;
                    bov *= 0.9992f;
                }
            }

            float sample = pulse * (0.35f + pedal * 0.45f) + rumble + intake + body + turboPart;
            if (limiter) {
                // Ignition cut: drop the level on half of a ~28 Hz chop.
                float chop = (float) Math.sin(whistlePhase * 0.04);
                if (chop > 0f) sample *= 0.25f;
            }
            // Mild idle lope.
            if (rpm < 1100f) {
                sample *= 0.82f + 0.18f * (float) Math.sin(harmPhase * twoPi * 0.5);
            }

            if (sample > 1f) sample = 1f;
            else if (sample < -1f) sample = -1f;
            int pcm = (int) (sample * gain * 32767f);
            if (pcm > 32767) pcm = 32767;
            else if (pcm < -32768) pcm = -32768;
            short v = (short) pcm;
            dst[s] = v;
            // A few samples of skew so it is not a hard mono point.
            dst[s + 1] = (short) ((v * 15 + (s >= 2 ? dst[s - 1] : v)) / 16);
        }
    }

    private float nextNoise() {
        noise ^= noise << 13;
        noise ^= noise >>> 17;
        noise ^= noise << 5;
        return (noise & 0xffff) / 32768f - 1f;
    }
}
