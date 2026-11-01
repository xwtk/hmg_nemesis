package com.nemesis.manager;

/** Counts releases of the chosen button. Three inside GAP_MS open Manager.
 *  A longer gap starts over. Two broadcasts for one physical press do not. */
final class SetupPress {
    static final long GAP_MS = 1500;
    /** ModeManager and PhoneWindowManager can both announce one press. */
    static final long DEBOUNCE_MS = 300;
    static final int NEED = 3;
    private int count;
    private long lastMs;
    /** Once a mobis.* broadcast has been seen, com.hkmc.* ones are echoes. */
    private boolean sawMobis;

    void restore(int count, long lastMs) {
        this.count = count;
        this.lastMs = lastMs;
    }

    void restoreFamily(boolean sawMobis) {
        this.sawMobis = sawMobis;
    }

    int count() {
        return count;
    }

    long lastMs() {
        return lastMs;
    }

    boolean sawMobis() {
        return sawMobis;
    }

    /** One action family per press: mobis.* when the unit sends it,
     *  com.hkmc.* only on units that never do. */
    boolean note(long now, String action) {
        boolean mobis = action != null && action.startsWith("mobis.");
        if (!mobis && sawMobis) return false;
        if (mobis) sawMobis = true;
        return note(now);
    }

    boolean note(long now) {
        if (lastMs != 0 && now >= lastMs && now - lastMs < DEBOUNCE_MS) return false;
        if (now - lastMs > GAP_MS) count = 0;
        lastMs = now;
        count++;
        if (count >= NEED) {
            count = 0;
            return true;
        }
        return false;
    }
}
