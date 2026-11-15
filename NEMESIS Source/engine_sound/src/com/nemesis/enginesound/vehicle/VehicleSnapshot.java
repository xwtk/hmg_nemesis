package com.nemesis.enginesound.vehicle;

import com.nemesis.enginesound.model.Shifter;

/** Latest car signals. Unknown numbers stay at the documented defaults. */
public final class VehicleSnapshot {
    public boolean running;
    public float kmh;
    public float pedal;
    public boolean brake;
    public int shifter = Shifter.UNKNOWN;
    public boolean speedOk;
    public boolean pedalOk;
    public boolean gearOk;
    public boolean powerOk;
    public boolean brakeOk;
    /** Human-readable source line for the screen. */
    public String sources = "";

    public boolean anySignal() {
        return speedOk || pedalOk || gearOk || powerOk || brakeOk;
    }
}
