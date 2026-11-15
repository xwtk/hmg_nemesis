package com.nemesis.enginesound.model;

/**
 * Drops a pedal sample that leaps and then disappears. The accelerator
 * CAN frame on this car occasionally reports 100%, 57%, and similar for
 * one poll while the pedal is barely down.
 */
public final class PedalFilter {
    private float held = -1f;
    private float candidate;
    private int streak;

    public float sample(float raw, float dt) {
        if (raw < 0f) raw = 0f;
        else if (raw > 1f) raw = 1f;
        if (held < 0f) {
            held = raw;
            return held;
        }
        if (dt < 0.02f) dt = 0.02f;
        else if (dt > 0.2f) dt = 0.2f;
        // A foot can cross the pedal in about a tenth of a second.
        float limit = 8.5f * dt;
        if (Math.abs(raw - held) <= limit) {
            held = raw;
            streak = 0;
            return held;
        }
        if (streak > 0 && Math.abs(raw - candidate) <= 0.12f) {
            streak++;
        } else {
            candidate = raw;
            streak = 1;
        }
        // A real step that is this abrupt has to still be there on the
        // next polls. One bad frame never gets through.
        if (streak >= 3) {
            held = raw;
            streak = 0;
        }
        return held;
    }
}
