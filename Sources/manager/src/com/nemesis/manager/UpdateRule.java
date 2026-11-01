package com.nemesis.manager;

/** When the 24 h update check is due, and how versions compare. No Android. */
final class UpdateRule {
    static final long PERIOD_MS = 24L * 60 * 60 * 1000;
    /** 2016-01-01. The unit reads 2015 until it has real time. */
    static final long CLOCK_SANE_MS = 1451606400000L;

    private UpdateRule() {
    }

    static boolean clockSane(long nowWall) {
        return nowWall >= CLOCK_SANE_MS;
    }

    /** lastWall and lastElapsed are from the last successful check; 0 means none. */
    static boolean due(long nowWall, long lastWall, long nowElapsed, long lastElapsed) {
        if (!clockSane(nowWall)) {
            // Only this boot's own clock can be trusted, and elapsed restarts at boot.
            return lastElapsed == 0 || nowElapsed < lastElapsed
                    || nowElapsed - lastElapsed >= PERIOD_MS;
        }
        return lastWall == 0 || nowWall < lastWall || nowWall - lastWall >= PERIOD_MS;
    }

    /** Dotted numeric compare, like native ver_cmp. */
    static int compare(String a, String b) {
        String[] x = (a == null ? "" : a.trim()).split("\\.");
        String[] y = (b == null ? "" : b.trim()).split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int p = i < x.length ? number(x[i]) : 0;
            int q = i < y.length ? number(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int number(String s) {
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
        }
        return v;
    }
}
