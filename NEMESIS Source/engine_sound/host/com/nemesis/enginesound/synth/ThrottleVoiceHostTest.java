package com.nemesis.enginesound.synth;

/** Pedal selects throttle vs overrun, and the two stay apart. */
public final class ThrottleVoiceHostTest {
    public static void main(String[] args) {
        SampleVoice.Loop onLow = constant(4000, 3000f);
        SampleVoice.Loop onHigh = constant(4000, 6500f);
        SampleVoice.Loop offLow = constant(0, 3000f);
        SampleVoice.Loop offHigh = constant(0, 6500f);
        ThrottleVoice voice = new ThrottleVoice(
                ThrottleVoice.BAC, "BAC Mono",
                new SampleVoice.Loop[] {onLow, onHigh}, new float[] {3000f, 6500f},
                new SampleVoice.Loop[] {offLow, offHigh}, new float[] {3000f, 6500f}, null);
        short[] buf = new short[64];
        voice.render(buf, 32, 3000f, 0f, 1f, false);
        check(Math.abs(buf[0]) < 20, "overrun silent on " + buf[0]);
        voice.render(buf, 32, 3000f, 1f, 1f, false);
        check(buf[0] > 3500 && buf[0] < 4500, "throttle level " + buf[0]);
        // Three bands: mid rpm plays the middle loop alone, and the ends hold.
        SampleVoice.Loop a = constant(1000, 2000f);
        SampleVoice.Loop b = constant(2000, 4000f);
        SampleVoice.Loop c = constant(4000, 6000f);
        ThrottleVoice tri = new ThrottleVoice(ThrottleVoice.GT, "GT",
                new SampleVoice.Loop[] {a, b, c}, new float[] {2000f, 4000f, 6000f},
                new SampleVoice.Loop[] {constant(0, 1f)}, new float[] {1f}, null);
        tri.render(buf, 32, 1000f, 1f, 1f, false);
        check(Math.abs(buf[0] - 1000) < 10, "below first band holds " + buf[0]);
        tri.render(buf, 32, 4000f, 1f, 1f, false);
        check(Math.abs(buf[0] - 2000) < 10, "middle band alone " + buf[0]);
        tri.render(buf, 32, 9000f, 1f, 1f, false);
        check(Math.abs(buf[0] - 4000) < 10, "above last band holds " + buf[0]);
        System.out.println("throttle voice ok");
    }

    private static SampleVoice.Loop constant(int value, float rpm) {
        short[] pcm = new short[64];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) value;
        return new SampleVoice.Loop(pcm, rpm);
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
}
