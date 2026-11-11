package com.nemesis.enginesound.model;

/** One sample of car state for the fake gearbox. Pedal is 0..1. */
public final class Inputs {
    public boolean running;
    public float kmh;
    public float pedal;
    public boolean brake;
    public int shifter = Shifter.UNKNOWN;

    public Inputs() {}

    public Inputs(boolean running, float kmh, float pedal, boolean brake, int shifter) {
        this.running = running;
        this.kmh = kmh;
        this.pedal = pedal;
        this.brake = brake;
        this.shifter = shifter;
    }
}
