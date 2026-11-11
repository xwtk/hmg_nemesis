package com.nemesis.enginesound.synth;

public final class SampleVoiceHostTest {
    public static void main(String[] args) {
        SampleVoice.Loop idle = tone(680f, 110f);
        SampleVoice.Loop high = tone(3200f, 110f * 3200f / 680f);
        SampleVoice voice = new SampleVoice(SampleVoice.HONDA, "Honda 3.2 V6",
                new SampleVoice.Loop[] {idle, high});
        SampleVoice one = new SampleVoice("t", "t", new SampleVoice.Loop[] {idle});
        short[] buf = new short[44100 * 2];
        one.render(buf, 44100, 680f, 1f, 1f, false);
        int period = dominantPeriod(buf);
        if (period < 370 || period > 430) {
            throw new AssertionError("idle period " + period);
        }
        int slow = risingCrossings(buf);
        one.render(buf, 44100, 1360f, 1f, 1f, false);
        int fast = risingCrossings(buf);
        if (fast < slow * 3 / 2) {
            throw new AssertionError("double-rpm crossings " + fast + " vs " + slow);
        }
        voice.render(buf, 44100, 1360f, 1f, 1f, false);
        double quiet = rms(buf);
        voice.render(buf, 44100, 1360f, 0f, 1f, false);
        if (!(rms(buf) < quiet)) {
            throw new AssertionError("lifted pedal should be quieter");
        }
        System.out.println("sample voice ok idlePeriod=" + period + " crossings " + slow + "->" + fast);
    }

    private static SampleVoice.Loop tone(float rpm, float hz) {
        int frames = 44100;
        short[] pcm = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            short s = (short) (Math.sin(2.0 * Math.PI * hz * i / 44100.0) * 12000.0);
            pcm[i * 2] = s;
            pcm[i * 2 + 1] = s;
        }
        return new SampleVoice.Loop(pcm, rpm);
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

    private static int risingCrossings(short[] pcm) {
        int n = 0;
        for (int i = 1; i < 4000; i++) {
            if (pcm[(i - 1) * 2] <= 0 && pcm[i * 2] > 0) n++;
        }
        return n;
    }

    private static int dominantPeriod(short[] pcm) {
        int best = 0;
        double bestScore = 0;
        for (int lag = 40; lag < 800; lag++) {
            double score = 0;
            for (int i = 0; i < 4000; i++) {
                score += pcm[i * 2] * pcm[(i + lag) * 2];
            }
            if (score > bestScore) {
                bestScore = score;
                best = lag;
            }
        }
        return best;
    }
}
