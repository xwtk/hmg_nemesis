package com.nemesis.enginesound.vehicle;

/**
 * Latest gear from {@code VehicleInfoListener.onGearStatus}. The micom
 * pushes the current value as soon as the listener is registered.
 * {@code 0xFF} is the unset value.
 */
public final class GearSink {
    public static volatile int code = -1;
    public static volatile boolean seen;

    private GearSink() {}

    public static void onGear(int value) {
        if (value < 0 || value == 0xff) return;
        code = value;
        seen = true;
    }
}
