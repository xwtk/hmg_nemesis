package com.nemesis.enginesound.model;

/**
 * Eight-speed automatic with a torque converter, for an EV that has none.
 *
 * Shifting follows a shift map, the way a real TCU does: upshift and
 * downshift rpm both rise with load. Load is the pedal, lagged a little.
 * Holding a steady speed for a while drops load to economy, even with the
 * pedal still down, so the box walks up to a 1100-1400 rpm cruise. Coasting
 * holds the gear for engine braking; braking downshifts earlier.
 *
 * A shift blends the ratio over about half a second on an S-curve, so the
 * revs fall or climb smoothly while still following road speed. Upshifts
 * cut torque briefly and downshifts blip, which the voice hears through
 * {@link #throttle}.
 *
 * Out of gear (P/N, or D/R stopped on the brake) the engine is a flywheel:
 * revs chase the throttle with lag and coast down slowly.
 * The Windows demo has a line-for-line Python copy; GearboxTrace checks
 * the two agree.
 */
public final class Gearbox {
    public static final int IDLE = 780;
    public static final int REDLINE = 7500;
    /** Load economy falls to after a steady speed. Lands 1100-1400 rpm. */
    static final float ECO_LOAD = 0.05f;
    /** Free revs: rise and fall time constants (s). Rates are capped in {@link #flywheel}. */
    static final float REV_UP_TAU = 0.9f;
    static final float REV_DOWN_TAU = 0.9f;
    /** Torque converter: extra revs at a standstill, full throttle. */
    static final float STALL_EXTRA = 1700f;

    /** ZF 8HP. 7th and 8th are the overdrives. */
    private static final float[] RATIO = {
            4.714f, 3.143f, 2.106f, 1.667f, 1.285f, 1.000f, 0.839f, 0.667f
    };
    private static final float FINAL = 3.08f;
    /** Rolling circumference, metres, ~18 inch wheel. */
    private static final float WHEEL_M = 2.07f;
    private static final float REVERSE = 3.30f;

    /** -1 reverse, 0 neutral, 1..8 forward. */
    public int gear;
    public float rpm = IDLE;
    public boolean limiter;
    public boolean freeRev;
    /** Throttle the voice hears: lagged pedal, with shift cut and blip. */
    public float throttle;

    private int driveGear = 1;
    private int lastMode;
    private float fromRatio;
    private float shiftT;
    private float shiftDur;
    private boolean shiftUp;
    private float shiftLock;
    /** TCU load, 0..1. */
    private float load;
    private float tipT;
    private float steadyFor;
    private float pedalTrail = -1f;
    /** Intake: the pedal, lagged. A stab only opens it part way. */
    private float intake;
    private float slip;
    private float limPhase;
    private boolean sawOff;
    private boolean crank;
    private float crankT;
    private float crankFrom;
    private float lastMps = -1f;
    /** Smoothed longitudinal accel, m/s^2. Positive is speeding up. */
    private float accel;
    /** Slower accel for the steady-speed test. 0.5 km/h speed steps read as noise. */
    private float accelSlow;

    public static float rpmForSpeed(float kmh, int gear) {
        if (gear < 0) return rpmForRatio(kmh, REVERSE * FINAL);
        if (gear >= 1 && gear <= RATIO.length) return rpmForRatio(kmh, RATIO[gear - 1] * FINAL);
        return IDLE;
    }

    private static float rpmForRatio(float kmh, float ratio) {
        float mps = Math.max(0f, kmh) / 3.6f;
        return (mps / WHEEL_M) * ratio * 60f;
    }

    public String gearLabel() {
        if (gear < 0) return "R";
        if (gear == 0) return "N";
        return Integer.toString(gear);
    }

    public void update(float dt, Inputs in) {
        if (dt < 0.001f) dt = 0.001f;
        else if (dt > 0.25f) dt = 0.25f;
        limiter = false;
        freeRev = false;
        if (in == null || !in.running) {
            gear = 0;
            driveGear = 1;
            lastMode = 0;
            shiftT = shiftDur = 0f;
            lastMps = -1f;
            accel = 0f;
            accelSlow = 0f;
            load = 0f;
            steadyFor = 0f;
            pedalTrail = -1f;
            intake = 0f;
            throttle = 0f;
            slip = 0f;
            sawOff = true;
            crank = false;
            // Winds down fast at first, then coasts the last few hundred rpm.
            rpm -= (100f + rpm * 0.25f) * dt;
            if (rpm < 0f) rpm = 0f;
            return;
        }

        float pedal = clamp01(in.pedal);
        float kmh = Math.max(0f, in.kmh);
        if (sawOff) {
            sawOff = false;
            if (kmh < 2f && rpm < IDLE + 40f) {
                crank = true;
                crankT = 0f;
                crankFrom = rpm;
            }
        }
        intake += (pedal - intake) * blend(dt, pedal >= intake ? 1.0f : 0.7f);
        trackAccel(kmh, dt);
        int mode = modeOf(in.shifter, kmh);
        boolean free = mode == 0 || (kmh < 2f && pedal > 0.02f && in.brake);

        if (free) {
            freeRev = pedal > 0.02f;
            gear = mode < 0 ? -1 : mode == 0 ? 0 : Math.max(1, driveGear);
            shiftT = shiftDur = 0f;
            slip = 0f;
            float target = IDLE + (float) Math.pow(intake, 1.15) * (REDLINE - 100 - IDLE);
            if (crank) {
                if (freeRev) crank = false;
                else target = crankTarget(dt);
            }
            float d = target - rpm;
            // The start script is already smooth; follow it closely.
            float v = crank ? d / 0.15f : flywheel(d);
            rpm += v * dt;
            if (intake > 0.9f && rpm >= REDLINE - 160f) {
                // Fuel cut bounces the revs about nine times a second.
                limiter = true;
                limPhase += dt * 9f;
                limPhase -= (float) Math.floor(limPhase);
                rpm = REDLINE - 100f - 260f * limPhase;
            }
            throttle = limiter ? intake * 0.6f : intake;
        } else {
            float locked;
            if (mode < 0) {
                gear = -1;
                shiftT = shiftDur = 0f;
                locked = rpmForSpeed(kmh, -1);
            } else {
                if (lastMode != 1) settleGear(kmh, pedal);
                selectDriveGear(dt, kmh, pedal, in.brake);
                gear = driveGear;
                locked = rpmForRatio(kmh, ratioNow());
            }
            // Converter slip at walking pace. Gone by 20 km/h, where it locks up.
            float fade = 1f - kmh / 20f;
            if (fade < 0f) fade = 0f;
            slip += (intake * STALL_EXTRA * fade - slip) * blend(dt, 0.35f);
            float target = Math.max(locked, IDLE + slip);
            if (target > REDLINE - 100f) {
                limiter = true;
                target = REDLINE - 100f;
            }
            if (mode < 0 && target > 5200f) target = 5200f;
            if (crank && (kmh >= 2f || target > IDLE + 150f)) crank = false;
            if (crank) target = crankTarget(dt);
            // In gear the revs are locked to the wheels. The lag only smooths
            // coarse speed steps, and drags revs down after a free rev.
            float d = target - rpm;
            float v = d / 0.12f;
            if (d < 0f && fade > 0f && shiftT >= shiftDur) {
                // Converter still slipping: the engine coasts down like a free rev.
                v = flywheel(d);
            } else if (shiftT >= shiftDur) {
                if (v < -1800f) v = -1800f;
                if (v > 3500f) v = 3500f;
            }
            rpm += v * dt;
            throttle = shiftThrottle(dt);
        }
        lastMode = mode;
        if (rpm > REDLINE) rpm = REDLINE;
        if (rpm < 0f) rpm = 0f;
    }

    /**
     * Rev speed, rpm/s, toward a target {@code d} away. Torque fades near the
     * top; friction grows with rpm, so it falls quicker high and eases into idle.
     */
    private float flywheel(float d) {
        if (d > 0f) return Math.min(d / REV_UP_TAU, 1300f - rpm * 0.06f);
        return Math.max(d / REV_DOWN_TAU, -(250f + rpm * 0.12f));
    }

    /** Start: rise to 1100 over about 2.5 s, then settle to idle over ~6 s. */
    private float crankTarget(float dt) {
        crankT += dt;
        if (crankT < 2.5f) {
            float s = smooth(crankT / 2.5f);
            return crankFrom + (1100f - crankFrom) * s;
        }
        float settle = (float) Math.exp(-(crankT - 2.5f) / 1.8f);
        if (settle < 0.02f) crank = false;
        return IDLE + (1100f - IDLE) * settle;
    }

    /** Lagged intake, dipped while an upshift cuts torque, blipped on a downshift. */
    private float shiftThrottle(float dt) {
        if (shiftT >= shiftDur) return intake;
        shiftT += dt;
        float p = shiftT / shiftDur;
        if (p > 1f) p = 1f;
        float bump = (float) Math.sin(p * Math.PI);
        if (shiftUp) return intake * (1f - 0.45f * bump);
        return Math.max(intake, 0.25f * bump);
    }

    private float ratioNow() {
        float to = RATIO[driveGear - 1] * FINAL;
        if (shiftT >= shiftDur) return to;
        return fromRatio + (to - fromRatio) * smooth(shiftT / shiftDur);
    }

    private void startShift(int to, float kmh) {
        fromRatio = ratioNow();
        shiftUp = to > driveGear;
        driveGear = to;
        shiftT = 0f;
        // Heavier load shifts quicker. A cruise shift is slow and soft.
        shiftDur = shiftUp ? 0.75f - 0.3f * load : (load > 0.6f ? 0.45f : 0.6f);
        // A big step, like a kickdown past three gears, averages 4500 rpm/s at most.
        float step = Math.abs(rpmForRatio(kmh, RATIO[to - 1] * FINAL) - rpmForRatio(kmh, fromRatio));
        if (step / 4500f > shiftDur) shiftDur = step / 4500f;
        shiftLock = shiftDur + (accel < -0.35f ? 0.9f : 0.35f);
    }

    /** Entering D while rolling: pick the gear the map wants, no shift. */
    private void settleGear(float kmh, float pedal) {
        load = pedal;
        int g = RATIO.length;
        while (g > 1 && rpmForSpeed(kmh, g) < downAt(load)) g--;
        driveGear = g;
        shiftT = shiftDur = 0f;
    }

    /** Upshift rpm in the current gear. 1500 light, 7200 floored. */
    static float upAt(float load) {
        return 1500f + 5700f * (float) Math.pow(load, 1.5);
    }

    /** Downshift rpm. 1000 coasting, 4200 floored (kickdown). */
    static float downAt(float load) {
        return 1000f + 3200f * (float) Math.pow(load, 1.5);
    }

    private void trackLoad(float dt, float pedal, boolean brake) {
        float rise = pedalTrail < 0f ? 0f : pedal - pedalTrail;
        if (pedalTrail < 0f) pedalTrail = pedal;
        pedalTrail += (pedal - pedalTrail) * blend(dt, 0.5f);
        boolean steady = Math.abs(accelSlow) < 0.3f && rise < 0.05f && !brake;
        steadyFor = steady ? steadyFor + dt : 0f;
        // Held speed for 2 s: economy, even with the pedal down.
        float want = steadyFor > 2f ? Math.min(pedal, ECO_LOAD) : pedal;
        float tau = want > load ? 0.15f : (steadyFor > 2f ? 1.5f : 0.5f);
        load += (want - load) * blend(dt, tau);
        tipT = pedal - load > 0.12f ? tipT + dt : 0f;
    }

    private void selectDriveGear(float dt, float kmh, float pedal, boolean brake) {
        if (kmh < 1.2f) {
            driveGear = 1;
            shiftT = shiftDur = 0f;
            shiftLock = 0f;
            steadyFor = 0f;
            trackLoad(dt, pedal, brake);
            return;
        }
        trackLoad(dt, pedal, brake);
        if (shiftLock > 0f) shiftLock -= dt;
        float cur = rpmForSpeed(kmh, driveGear);
        if (cur > REDLINE - 200f && driveGear < RATIO.length) {
            startShift(driveGear + 1, kmh);
            return;
        }
        // Epsilon: float and the demo's double must release on the same frame.
        if (shiftLock > 0.001f) return;

        float decel = accel < 0f ? -accel : 0f;
        float urgency = clamp01((decel - 0.6f) / 2.5f);
        float down = downAt(load) + urgency * 900f + (brake ? 300f : 0f);
        float up = upAt(load);
        // Slowing holds the gear for engine braking, unless it is revving high.
        if (accel < -0.35f && up < 3000f) up = 3000f;

        if (driveGear < RATIO.length && cur > up
                && rpmForSpeed(kmh, driveGear + 1) >= down + 150f) {
            startShift(driveGear + 1, kmh);
            return;
        }
        // A pedal still on its way down waits, then drops straight to the gear it wants.
        if (tipT > 0f && tipT < 0.3f) return;
        // Economy ignores a pedal that is resting down; a new press ends it.
        float kick = downAt(steadyFor > 2f ? load : Math.max(load, pedal)) + urgency * 900f + (brake ? 300f : 0f);
        int g = driveGear;
        while (g > 1 && rpmForSpeed(kmh, g) < kick) {
            if (rpmForSpeed(kmh, g - 1) > REDLINE - 900f) break;
            if (g == 2 && kmh >= 14f) break;
            g--;
        }
        if (g != driveGear) startShift(g, kmh);
    }

    private void trackAccel(float kmh, float dt) {
        float mps = kmh / 3.6f;
        if (lastMps < 0f) {
            lastMps = mps;
            return;
        }
        float inst = (mps - lastMps) / dt;
        lastMps = mps;
        if (inst > 12f) inst = 12f;
        if (inst < -12f) inst = -12f;
        accel += (inst - accel) * blend(dt, 0.35f);
        accelSlow += (inst - accelSlow) * blend(dt, 1.2f);
    }

    /** 0 neutral, 1 drive, -1 reverse. */
    static int modeOf(int shifter, float kmh) {
        if (shifter == Shifter.R) return -1;
        if (shifter == Shifter.N) return 0;
        if (shifter == Shifter.D || shifter == Shifter.B || shifter == Shifter.ECO) return 1;
        // Micom often stays at P on this EV after the lever leaves Park.
        // Once the car is moving, that reading is stale.
        if (kmh > 2f) return 1;
        return 0;
    }

    private static float blend(float dt, float tau) {
        return 1f - (float) Math.exp(-dt / tau);
    }

    private static float smooth(float x) {
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
        return x * x * (3f - 2f * x);
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
