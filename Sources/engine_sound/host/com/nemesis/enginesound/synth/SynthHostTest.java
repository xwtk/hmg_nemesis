package com.nemesis.enginesound.synth;

public final class SynthHostTest {
    public static void main(String[] args) {
        V6Synth vr = V6Synth.vr35(44100);
        V6Synth vq = V6Synth.vq35(44100);
        short[] a = new short[44100 * 2];
        short[] b = new short[44100 * 2];
        vr.render(a, 44100, 3000f, 0.7f, 0.8f, false);
        vq.render(b, 44100, 3000f, 0.7f, 0.8f, false);
        double ra = rms(a);
        double rb = rms(b);
        if (ra < 0.02 || ra > 0.6) throw new AssertionError("vr rms " + ra);
        if (rb < 0.02 || rb > 0.6) throw new AssertionError("vq rms " + rb);
        // 3000 rpm, 3 pulses/rev -> 150 Hz. Period ~294 samples.
        int period = dominantPeriod(a);
        if (period < 260 || period > 330) {
            throw new AssertionError("firing period " + period + " expected ~294");
        }
        vr.render(a, 44100, 780f, 0f, 0.8f, false);
        double idle = rms(a);
        vr.render(a, 8000, 7400f, 1f, 0.8f, true);
        double limit = rms(a);
        if (!(idle < ra)) throw new AssertionError("idle quieter than cruise " + idle + " " + ra);
        if (!(limit > 0.01)) throw new AssertionError("limiter silent");
        System.out.println("synth ok period=" + period + " rmsIdle=" + idle + " rms3k=" + ra);
    }

    private static double rms(short[] pcm) {
        double s = 0;
        int n = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            double v = pcm[i] / 32768.0;
            s += v * v;
            n++;
        }
        return Math.sqrt(s / n);
    }

    /** Autocorrelation peak of the left channel, ignoring the zero lag. */
    private static int dominantPeriod(short[] stereo) {
        int frames = stereo.length / 2;
        int best = 0;
        double bestC = 0;
        for (int lag = 200; lag <= 400; lag++) {
            double c = 0;
            int n = 2000;
            for (int i = 0; i < n; i++) {
                c += (stereo[i * 2] / 32768.0) * (stereo[(i + lag) * 2] / 32768.0);
            }
            if (c > bestC) {
                bestC = c;
                best = lag;
            }
        }
        return best;
    }
}
