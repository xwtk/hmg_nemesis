package com.nemesis.enginesound.model;

/**
 * Prints the gearbox over a fixed drive. EngineSoundDemo.py --trace runs the
 * same drive on its Python copy; make test compares the two.
 * Columns: time, km/h, gear, rpm, throttle.
 */
public final class GearboxTrace {
    /** seconds, pedal, brake, shifter, running. Same list as the demo's TRACE. */
    static final float[][] SCRIPT = {
            {1f, 0f, 0f, Shifter.P, 0f},
            {10f, 0f, 0f, Shifter.P, 1f},
            {4f, 1f, 0f, Shifter.P, 1f},
            {6f, 0f, 0f, Shifter.P, 1f},
            {2f, 0f, 1f, Shifter.D, 1f},
            {9f, 1f, 0f, Shifter.D, 1f},
            {8f, 0.35f, 0f, Shifter.D, 1f},
            {3f, 1f, 0f, Shifter.D, 1f},
            {10f, 0f, 0f, Shifter.D, 1f},
            {6f, 0f, 0.6f, Shifter.D, 1f},
            {3f, 0f, 0f, Shifter.P, 0f},
    };

    public static void main(String[] args) {
        Gearbox g = new Gearbox();
        Inputs in = new Inputs();
        float kmh = 0f;
        float dt = 0.05f;
        int step = 0;
        for (float[] seg : SCRIPT) {
            int n = Math.round(seg[0] / dt);
            for (int i = 0; i < n; i++) {
                // Same toy car as the trace in the demo.
                float a = seg[1] * 22f - 2.5f - seg[2] * 30f - kmh * 0.02f;
                if (seg[3] == Shifter.P) a = -30f;
                kmh += a * dt;
                if (kmh < 0f) kmh = 0f;
                if (kmh > 185f) kmh = 185f;
                in.running = seg[4] > 0.5f;
                in.kmh = kmh;
                in.pedal = seg[1];
                in.brake = seg[2] > 0.5f;
                in.shifter = (int) seg[3];
                g.update(dt, in);
                if (step++ % 4 == 0) {
                    System.out.println(String.format(java.util.Locale.ROOT, "%.2f %.2f %d %.1f %.3f",
                            step * dt, kmh, g.gear, g.rpm, g.throttle));
                }
            }
        }
    }
}
