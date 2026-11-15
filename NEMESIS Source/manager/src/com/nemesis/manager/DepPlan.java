package com.nemesis.manager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What must happen before a package with this depends= can install. No Android. */
final class DepPlan {
    interface Source {
        /** Installed version, "" when it has none; null when not installed. */
        String installed(String id);

        /** A row that can be installed and satisfies spec, or null. */
        StoreList.Entry find(String id, String spec);
    }

    /** To install or update, dependencies before what needs them. */
    final List<StoreList.Entry> steps = new ArrayList<StoreList.Entry>();
    /** Bullet text of dependencies no Store has. */
    final List<String> missing = new ArrayList<String>();
    /** Ids of installed packages named with a ! in depends=. */
    final List<String> conflicts = new ArrayList<String>();

    private final Source source;
    private final Set<String> seen = new HashSet<String>();

    private DepPlan(Source source) {
        this.source = source;
    }

    static DepPlan of(String self, String depends, Source source) {
        DepPlan p = new DepPlan(source);
        p.seen.add(self);
        p.walk(depends);
        return p;
    }

    private void walk(String depends) {
        String[] items = depends == null ? new String[0] : depends.split(",");
        for (int i = 0; i < items.length; i++) {
            String item = items[i].trim();
            if (item.length() == 0) continue;
            boolean bang = item.charAt(0) == '!';
            if (bang) item = item.substring(1).trim();
            int at = item.indexOf('@');
            String id = (at < 0 ? item : item.substring(0, at)).trim();
            String spec = at < 0 ? "any" : item.substring(at + 1).trim();
            String have = source.installed(id);
            if (bang) {
                if (have != null && UpdateRule.specMatch(spec, have) && !conflicts.contains(id)) conflicts.add(id);
                continue;
            }
            if (have != null && UpdateRule.specMatch(spec, have)) continue;
            if (!seen.add(id)) continue;
            StoreList.Entry e = source.find(id, spec);
            if (e == null) {
                missing.add(describe(id, spec, have));
                continue;
            }
            walk(e.depends);
            steps.add(e);
        }
    }

    private static String describe(String id, String spec, String have) {
        if (have != null) return id + " (have " + (have.length() > 0 ? have : "no version") + ", need " + spec + ")";
        return "any".equals(spec) ? id : id + " (need " + spec + ")";
    }

    /** One bullet text per dep-missing or dep-version line of native output. */
    static List<String> unmet(String out) {
        List<String> l = new ArrayList<String>();
        String[] lines = out == null ? new String[0] : out.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String[] f = lines[i].trim().split(" ");
            if (f.length >= 2 && "dep-missing".equals(f[0])) {
                l.add(f[1]);
            } else if (f.length >= 6 && "dep-version".equals(f[0])) {
                l.add(describe(f[1], f[5], "unknown".equals(f[3]) ? "" : f[3]));
            }
        }
        return l;
    }
}
