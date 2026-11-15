package com.nemesis.enginesound.model;

/**
 * Cluster gear codes from 260507
 * {@code SystemProtocol.GearStatus} and {@code TM_HSCANDefine.GEAR_SEL_DISP}.
 */
public final class Shifter {
    public static final int UNKNOWN = -1;
    public static final int P = 0;
    public static final int B = 1;
    public static final int ECO = 2;
    public static final int D = 5;
    public static final int N = 6;
    public static final int R = 7;

    private Shifter() {}

    public static String codeLabel(int code) {
        if (code == P) return "P";
        if (code == R) return "R";
        if (code == N) return "N";
        if (code == D) return "D";
        if (code == B) return "B";
        if (code == ECO) return "ECO";
        if (code == UNKNOWN) return "?";
        return "0x" + Integer.toHexString(code);
    }
}
