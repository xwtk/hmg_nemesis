package com.nemesis.enginesound.model;

/** Accelerator pedal scales seen on this head unit: 0..1, 0..100, or 0..254. */
public final class PedalScale {
    private PedalScale() {}

    public static float unit(float raw) {
        if (raw <= 0f) return 0f;
        if (raw <= 1.5f) return raw > 1f ? 1f : raw;
        if (raw <= 100f) return raw / 100f;
        if (raw <= 255f) return raw / 254f;
        return 1f;
    }

    /**
     * {@code getAccelPdlVal} is already percent. 0.57 is just over half a
     * percent. Treating that as a 0..1 fraction made the first touch of
     * the pedal read 57% or 100%, then look normal once it passed 1.5.
     */
    public static float percent(float raw) {
        if (raw <= 0f) return 0f;
        if (raw >= 100f) return 1f;
        return raw / 100f;
    }
}
