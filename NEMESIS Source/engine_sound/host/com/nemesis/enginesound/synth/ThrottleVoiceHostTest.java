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
                onLow, onHigh, offLow, offHigh, null, 3000f, 6500f);
        short[] buf = new short[64];
        voice.render(buf, 32, 3000f, 0f, 1f, false);
        check(Math.abs(buf[0]) < 20, "overrun silent on " + buf[0]);
        voice.render(buf, 32, 3000f, 1f, 1f, false);
        check(buf[0] > 3500 && buf[0] < 4500, "throttle level " + buf[0]);
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
