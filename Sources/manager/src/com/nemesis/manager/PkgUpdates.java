package com.nemesis.manager;

import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Where updates and dependencies come from: the stick's list, then the portal. */
final class PkgUpdates {
    static final class Found {
        List<StoreList.Entry> updates = new ArrayList<StoreList.Entry>();
        boolean onlineFailed;
    }

    private PkgUpdates() {
    }

    /** The stick's rows, resolved. Empty when it has no list. */
    static List<StoreList.Entry> usbRows() {
        Map<String, String> info = Cli.fields(Cli.run("usb-info"));
        if (!"list".equals(info.get("type"))) return new ArrayList<StoreList.Entry>();
        List<StoreList.Entry> rows = StoreList.parseList(Cli.run("usb-info", "--list"));
        for (int i = 0; i < rows.size(); i++) StoreActivity.resolveUsb(rows.get(i), info.get("root"));
        return rows;
    }

    /** Only packages the catalog shows as newer cost a descriptor request. */
    private static List<StoreList.Entry> onlineRows(Context c) throws Exception {
        Map<String, String> have = StoreList.parseInstalled(Cli.run("pkg-list"));
        List<StoreList.Entry> out = new ArrayList<StoreList.Entry>();
        List<StoreList.Entry> rows = Portal.catalog(c);
        for (int i = 0; i < rows.size(); i++) {
            StoreList.Entry e = rows.get(i);
            String v = have.get(e.id);
            if (v == null || UpdateRule.compare(e.version, v) <= 0) continue;
            StoreActivity.resolveOnline(c, e);
            out.add(e);
        }
        return out;
    }

    static Found find(Context c) {
        Found f = new Found();
        List<StoreList.Entry> rows = usbRows();
        try {
            rows.addAll(onlineRows(c));
        } catch (Throwable t) {
            f.onlineFailed = true;
            CrashLog.note("update.log", "package updates: " + t);
        }
        f.updates = StoreList.updates(rows);
        return f;
    }

    static void popup(Context c, int count) {
        Intent i = new Intent(c, DialogActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra("nemesis_mode", "pkg_updates");
        i.putExtra("nemesis_count", count);
        c.startActivity(i);
    }

    /** Dependencies, from the stick first. */
    static DepPlan.Source source(final Context c) {
        return new DepPlan.Source() {
            private List<StoreList.Entry> usb;

            public String installed(String id) {
                Map<String, String> m = Cli.fields(Cli.run("pkg-info", id));
                if (!"1".equals(m.get("installed"))) return null;
                String v = m.get("installed-version");
                return v == null ? "" : v;
            }

            public StoreList.Entry find(String id, String spec) {
                if (usb == null) usb = usbRows();
                StoreList.Entry best = null;
                for (int i = 0; i < usb.size(); i++) {
                    StoreList.Entry e = usb.get(i);
                    if (!id.equals(e.id) || !usable(e, spec)) continue;
                    if (best == null || UpdateRule.compare(e.version, best.version) > 0) best = e;
                }
                if (best != null) return best;
                try {
                    StoreList.Entry e = new StoreList.Entry();
                    e.id = id;
                    StoreActivity.resolveOnline(c, e);
                    return usable(e, spec) ? e : null;
                } catch (Throwable t) {
                    CrashLog.note("update.log", "dependency " + id + ": " + t);
                    return null;
                }
            }
        };
    }

    private static boolean usable(StoreList.Entry e, String spec) {
        return e.compat != StoreList.NONE && e.compat != StoreList.UNKNOWN
                && UpdateRule.specMatch(spec, e.version);
    }
}
