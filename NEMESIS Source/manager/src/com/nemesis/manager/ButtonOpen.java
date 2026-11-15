package com.nemesis.manager;

import android.os.SystemClock;

/** A button open (Setup or Navigation) also starts that button's own screen.
 *  A cold start of it can cover the Manager after the receiver's 1 s retry.
 *  MainActivity checks this in onPause and comes back to the front once. */
final class ButtonOpen {
    static final long WINDOW_MS = 3000;
    private static long at;
    private static boolean used;

    static synchronized void mark() {
        at = SystemClock.uptimeMillis();
        used = false;
    }

    /** A pause now falls inside the window of an unused button open. */
    static synchronized boolean pending() {
        return at != 0 && !used && SystemClock.uptimeMillis() - at <= WINDOW_MS;
    }

    /** True once per button open. */
    static synchronized boolean take() {
        if (at == 0 || used) return false;
        used = true;
        return true;
    }

    static synchronized void cancel() {
        used = true;
    }
}
