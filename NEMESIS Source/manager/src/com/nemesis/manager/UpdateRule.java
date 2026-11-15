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

    /**
     * May Manager use this connection? activeType is ConnectivityManager's, -1 for none.
     * Traffic follows the default route on KitKat, so its name decides and activeType
     * only settles a name not known here; the unit can report a hotspot as mobile.
     * shortcut: interface names are not confirmed on a real unit, and a telematics modem
     * named usb* or eth* would pass; adjust once network.log from a unit shows them.
     */
    static boolean networkAllowed(boolean telematics, int activeType, String routeIface) {
        if (telematics) return true;
        if (nameIn(routeIface, SIM_IFACES)) return false;
        if (nameIn(routeIface, HOTSPOT_IFACES)) return true;
        return activeType == 1 || activeType == 7 || activeType == 9; // Wi-Fi, Bluetooth, Ethernet
    }

    private static final String[] SIM_IFACES = {
        "rmnet", "ccmni", "pdp", "ppp", "wwan", "qmi", "cdma", "uwbr"
    };
    private static final String[] HOTSPOT_IFACES = { "wlan", "bnep", "usb", "rndis", "ncm", "eth", "en" };

    private static boolean nameIn(String iface, String[] prefixes) {
        if (iface == null) return false;
        for (int i = 0; i < prefixes.length; i++) {
            if (iface.startsWith(prefixes[i])) return true;
        }
        return false;
    }

    /** The lowest metric default route's interface in /proc/net/route text, or null. */
    static String defaultRouteIface(String procNetRoute) {
        if (procNetRoute == null) return null;
        String[] lines = procNetRoute.split("\n");
        String best = null;
        int bestMetric = 0;
        for (int i = 0; i < lines.length; i++) {
            String[] f = lines[i].trim().split("\\s+");
            if (f.length < 2 || !"00000000".equals(f[1])) continue;
            int metric = f.length > 6 ? number(f[6]) : 0;
            if (best == null || metric < bestMetric) {
                best = f[0];
                bestMetric = metric;
            }
        }
        return best;
    }

    /** Java form of native dep_spec_match: any, >=v, <=v, a-b or an exact version. */
    static boolean specMatch(String spec, String have) {
        if (spec == null || spec.length() == 0 || "any".equals(spec)) return true;
        if (have == null || !have.matches("[0-9]+(\\.[0-9]+)*")) return false;
        if (spec.startsWith(">=")) return compare(have, spec.substring(2)) >= 0;
        if (spec.startsWith("<=")) return compare(have, spec.substring(2)) <= 0;
        int dash = spec.indexOf('-');
        if (dash >= 0) {
            return compare(have, spec.substring(0, dash)) >= 0
                    && compare(have, spec.substring(dash + 1)) <= 0;
        }
        return compare(have, spec) == 0;
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
