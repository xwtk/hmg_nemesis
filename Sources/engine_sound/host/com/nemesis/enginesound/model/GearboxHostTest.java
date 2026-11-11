package com.nemesis.enginesound.model;

public final class GearboxHostTest {
    public static void main(String[] args) {
        idleInPark();
        revInParkToRedline();
        revInDriveOnBrake();
        idleInDriveWithoutPedal();
        launchInDrive();
        cruiseHundred();
        pullHoldsSecond();
        steadyTopIsEighth();
        steadyFiftyInEco();
        kickdownDropsAGear();
        movingWhileParkStuck();
        reverseCapped();
        coastDownShifts();
        pedalScales();
        pedalGlitchDropped();
        startFlaresThenSettles();
        driveLeavesTheFlare();
        shutdownGlides();
        liftOffCoastsDown();
        pullShiftsAreSmooth();
        coastHoldsGear();
        System.out.println("gearbox ok");
    }

    private static void idleInPark() {
        Gearbox g = run(2f, new Inputs(true, 0f, 0f, false, Shifter.P));
        check(g.rpm > 700f && g.rpm < 900f, "park idle rpm " + g.rpm);
        check(g.gear == 0, "park gear");
        check(!g.freeRev, "park not revving");
    }

    private static void revInParkToRedline() {
        Gearbox g = run(26f, new Inputs(true, 0f, 1f, false, Shifter.P));
        check(g.rpm > 6800f, "park WOT rpm " + g.rpm);
        check(g.rpm <= Gearbox.REDLINE, "park under redline " + g.rpm);
        check(g.freeRev, "park free rev");
        check(Gearbox.REDLINE == 7500, "redline");
    }

    private static void revInDriveOnBrake() {
        Gearbox g = run(26f, new Inputs(true, 0f, 1f, true, Shifter.D));
        check(g.freeRev, "brake-hold rev");
        check(g.rpm > 6500f, "brake-hold rpm " + g.rpm);
        check(g.rpm <= Gearbox.REDLINE, "brake-hold cap");
    }

    private static void idleInDriveWithoutPedal() {
        Gearbox g = run(1.5f, new Inputs(true, 0f, 0f, false, Shifter.D));
        check(g.gear == 1, "drive idle gear " + g.gear);
        check(g.rpm < 1000f, "drive idle rpm " + g.rpm);
        check(!g.freeRev, "no free rev");
    }

    private static void launchInDrive() {
        Gearbox g = run(8f, new Inputs(true, 0f, 0.6f, false, Shifter.D));
        check(!g.freeRev, "launch is in gear");
        // Held against the converter: stall speed, not redline.
        check(g.rpm > 1600f && g.rpm < 2800f, "launch rpm " + g.rpm);
    }

    private static void cruiseHundred() {
        Gearbox g = run(8f, new Inputs(true, 100f, 0.3f, false, Shifter.D));
        check(g.gear == 8, "100 km/h gear " + g.gear);
        check(g.rpm > 1400f && g.rpm < 2200f, "100 km/h rpm " + g.rpm);
        check(g.rpm <= Gearbox.REDLINE, "cruise cap");
    }

    /** A full-throttle pull through 80 km/h stays in 2nd. Speed is still rising. */
    private static void pullHoldsSecond() {
        Gearbox g = new Gearbox();
        Inputs in = new Inputs(true, 0f, 1f, false, Shifter.D);
        boolean seen = false;
        for (int i = 0; i < 90; i++) {
            in.kmh = i * 1.0f;
            g.update(0.05f, in);
            if (in.kmh > 78f && in.kmh < 86f) {
                seen = true;
                check(g.gear == 2, "pull 80 gear " + g.gear + " rpm " + g.rpm);
                check(g.rpm > 4500f && g.rpm < 7200f, "pull 80 rpm " + g.rpm);
            }
        }
        check(seen, "missed the 80 km/h sample");
    }

    /** Held speed, pedal still down, walks out to 8th. */
    private static void steadyTopIsEighth() {
        Gearbox g = run(8f, new Inputs(true, 184f, 1f, false, Shifter.D));
        check(g.gear == 8, "184 gear " + g.gear + " rpm " + g.rpm);
        check(g.rpm > 2800f && g.rpm < 3400f, "184 rpm " + g.rpm);
    }

    /** 50 km/h has a gear in the 1100-1400 cruise band, so it uses that. */
    private static void steadyFiftyInEco() {
        Gearbox g = run(8f, new Inputs(true, 50f, 0.3f, false, Shifter.D));
        check(g.gear == 6, "50 km/h gear " + g.gear + " rpm " + g.rpm);
        check(g.rpm > 1050f && g.rpm < 1400f, "50 km/h rpm " + g.rpm);
    }

    private static void kickdownDropsAGear() {
        Gearbox g = run(8f, new Inputs(true, 100f, 0.15f, false, Shifter.D));
        int cruise = g.gear;
        check(cruise >= 5, "cruise before kick " + cruise + " rpm " + g.rpm);
        Inputs kick = new Inputs(true, 100f, 1f, false, Shifter.D);
        float step = 0f;
        for (int i = 0; i < 40; i++) {
            float was = g.rpm;
            g.update(0.05f, kick);
            step = Math.max(step, g.rpm - was);
        }
        check(g.gear < cruise && g.gear <= 3, "kick gear " + g.gear);
        check(g.rpm > 4000f, "kick rpm " + g.rpm);
        // 50 ms steps. More than this is a snap, not a blended shift.
        check(step < 600f, "kick step " + step);
    }

    private static void movingWhileParkStuck() {
        Gearbox g = run(4f, new Inputs(true, 40f, 0.2f, false, Shifter.P));
        check(g.gear >= 1, "stale park still in gear " + g.gear);
        check(g.rpm > 1000f, "stale park rpm " + g.rpm);
    }

    private static void reverseCapped() {
        Gearbox g = run(2f, new Inputs(true, 20f, 0.5f, false, Shifter.R));
        check(g.gear < 0, "reverse gear");
        check(g.rpm <= 5200f, "reverse cap " + g.rpm);
        check(g.rpm > Gearbox.IDLE, "reverse above idle");
    }

    private static void coastDownShifts() {
        Gearbox g = new Gearbox();
        Inputs up = new Inputs(true, 0f, 0.4f, false, Shifter.D);
        for (int i = 0; i < 160; i++) {
            up.kmh = i * 0.8f;
            g.update(0.05f, up);
            check(g.rpm <= Gearbox.REDLINE, "ramp rpm " + g.rpm + " at " + up.kmh);
        }
        int top = g.gear;
        Inputs down = new Inputs(true, 120f, 0f, false, Shifter.D);
        for (int i = 0; i < 200; i++) {
            down.kmh = 120f - i * 0.6f;
            if (down.kmh < 0f) down.kmh = 0f;
            g.update(0.05f, down);
            check(g.rpm <= Gearbox.REDLINE, "coast rpm");
        }
        check(g.gear == 1, "stopped back in 1st, gear " + g.gear + " from " + top);
    }

    private static void pedalScales() {
        check(Math.abs(PedalScale.unit(0f)) < 0.001f, "0");
        check(Math.abs(PedalScale.unit(40f) - 0.40f) < 0.001f, "percent");
        check(Math.abs(PedalScale.unit(254f) - 1f) < 0.02f, "254");
        check(Math.abs(PedalScale.unit(0.5f) - 0.5f) < 0.001f, "fraction");
        check(Math.abs(PedalScale.percent(0.57f) - 0.0057f) < 0.0002f, "0.57 percent");
        check(Math.abs(PedalScale.percent(1f) - 0.01f) < 0.0002f, "1 percent");
        check(Math.abs(PedalScale.percent(40f) - 0.40f) < 0.001f, "40 percent");
    }

    /** One bad CAN frame must not show up as 100% or 57%. */
    private static void pedalGlitchDropped() {
        PedalFilter f = new PedalFilter();
        float shown = f.sample(0.08f, 0.05f);
        shown = f.sample(1f, 0.05f);
        check(shown < 0.2f, "spike showed " + shown);
        shown = f.sample(0.57f, 0.05f);
        check(shown < 0.2f, "second spike showed " + shown);
        shown = f.sample(0.09f, 0.05f);
        check(Math.abs(shown - 0.09f) < 0.02f, "back to pedal " + shown);
    }

    private static void startFlaresThenSettles() {
        Gearbox g = new Gearbox();
        Inputs off = new Inputs(false, 0f, 0f, false, Shifter.P);
        for (int i = 0; i < 120; i++) g.update(0.05f, off);
        check(g.rpm < 40f, "died down " + g.rpm);
        Inputs on = new Inputs(true, 0f, 0f, false, Shifter.P);
        float peak = 0f;
        for (int i = 0; i < 240; i++) {
            g.update(0.05f, on);
            if (g.rpm > peak) peak = g.rpm;
        }
        check(peak > 1000f && peak < 1250f, "start peak " + peak);
        check(g.rpm > 740f && g.rpm < 860f, "settled " + g.rpm);
    }

    /** Pulling away during the flare follows the road, and does not finish the script. */
    private static void driveLeavesTheFlare() {
        Gearbox g = new Gearbox();
        Inputs off = new Inputs(false, 0f, 0f, false, Shifter.P);
        for (int i = 0; i < 120; i++) g.update(0.05f, off);
        Inputs on = new Inputs(true, 0f, 0f, false, Shifter.D);
        for (int i = 0; i < 80; i++) g.update(0.05f, on);
        check(g.rpm > 900f, "still in the flare " + g.rpm);
        Inputs go = new Inputs(true, 30f, 0.4f, false, Shifter.D);
        for (int i = 0; i < 30; i++) g.update(0.05f, go);
        check(g.rpm > 1400f, "driving rpm " + g.rpm);
        float driving = g.rpm;
        Inputs stop = new Inputs(true, 0f, 0f, false, Shifter.D);
        for (int i = 0; i < 20; i++) g.update(0.05f, stop);
        check(g.rpm > driving - 700f, "stop dumped the revs " + g.rpm + " from " + driving);
    }

    private static void shutdownGlides() {
        Gearbox g = run(0.4f, new Inputs(true, 0f, 0f, false, Shifter.P));
        float before = g.rpm;
        Inputs off = new Inputs(false, 0f, 0f, false, Shifter.P);
        g.update(0.05f, off);
        float drop = before - g.rpm;
        check(drop > 5f && drop < 20f, "shutdown step " + drop);
        for (int i = 0; i < 110; i++) g.update(0.05f, off);
        check(g.rpm < 80f, "shutdown done " + g.rpm);
    }

    /** Releasing the pedal must not dump the revs to idle in one step. */
    private static void liftOffCoastsDown() {
        Gearbox g = run(8f, new Inputs(true, 0f, 1f, false, Shifter.P));
        float high = g.rpm;
        check(high > 2500f, "revved " + high);
        Inputs off = new Inputs(true, 0f, 0f, false, Shifter.P);
        g.update(0.05f, off);
        check(g.rpm > high - 30f, "lift first step " + g.rpm + " from " + high);
        for (int i = 0; i < 19; i++) g.update(0.05f, off);
        check(g.rpm > high - 1200f, "lift dumped " + g.rpm + " from " + high);
        for (int i = 0; i < 260; i++) g.update(0.05f, off);
        check(g.rpm < Gearbox.IDLE + 30f, "lift never settled " + g.rpm);
    }

    /** A floored pull from rest: shifts are blended, never a jump. */
    private static void pullShiftsAreSmooth() {
        Gearbox g = new Gearbox();
        Inputs in = new Inputs(true, 0f, 1f, false, Shifter.D);
        float worst = 0f;
        int shifts = 0;
        int last = 1;
        for (int i = 0; i < 180; i++) {
            in.kmh = Math.min(185f, i * 0.05f * 22f);
            float was = g.rpm;
            g.update(0.05f, in);
            if (i > 0) worst = Math.max(worst, Math.abs(g.rpm - was));
            if (g.gear > last) shifts++;
            last = g.gear;
            check(g.rpm <= Gearbox.REDLINE, "pull over redline " + g.rpm);
        }
        check(shifts >= 2, "pull shifts " + shifts);
        check(worst < 420f, "pull jumped " + worst);
    }

    /** Coasting from speed holds a gear and steps down. It never hunts back up. */
    private static void coastHoldsGear() {
        Gearbox g = run(8f, new Inputs(true, 80f, 0.15f, false, Shifter.D));
        Inputs in = new Inputs(true, 80f, 0f, false, Shifter.D);
        int last = g.gear;
        int ups = 0;
        for (int i = 0; i < 200; i++) {
            in.kmh = Math.max(0f, 80f - i * 0.05f * 6f);
            g.update(0.05f, in);
            if (g.gear > last) ups++;
            last = g.gear;
        }
        check(ups == 0, "coast upshifted " + ups);
        check(g.gear <= 3, "coast gear " + g.gear + " at " + in.kmh);
    }

    private static Gearbox run(float seconds, Inputs in) {
        Gearbox g = new Gearbox();
        int steps = (int) (seconds / 0.05f);
        for (int i = 0; i < steps; i++) g.update(0.05f, in);
        return g;
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
}
