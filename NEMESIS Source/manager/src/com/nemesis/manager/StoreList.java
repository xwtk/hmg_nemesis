package com.nemesis.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Store rows: the USB list file, search, filter and sort. No Android. */
final class StoreList {
    static final int UNKNOWN = 0;
    static final int FULL = 1;
    static final int PARTIAL = 2;
    static final int NONE = 3;

    static final class Entry {
        String id = "";
        String path = "";
        String name = "";
        String shortDesc = "";
        String published = "";
        String version = "";
        String installed = "";
        boolean isInstalled;
        boolean locked;
        boolean legacy;
        String detailedDesc = "";
        int compat = UNKNOWN;
        String url = "";
        String sha256 = "";
        String depends = "";
        /** Set for a zip on the stick; such a row needs no download. */
        String localZip = "";
        /** The os and model lines of the verdict, one per line. */
        String reasons = "";
    }

    private StoreList() {
    }

    /** [section] key=value text, as the native INI reader takes it. */
    static Map<String, Map<String, String>> parseIni(String text) {
        Map<String, Map<String, String>> out = new HashMap<String, Map<String, String>>();
        Map<String, String> cur = null;
        if (text == null) return out;
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i].trim();
            if (ln.length() == 0 || ln.charAt(0) == '#' || ln.charAt(0) == ';') continue;
            if (ln.charAt(0) == '[' && ln.indexOf(']') > 0) {
                cur = new HashMap<String, String>();
                out.put(ln.substring(1, ln.indexOf(']')).trim(), cur);
                continue;
            }
            int eq = ln.indexOf('=');
            if (eq <= 0 || cur == null) continue;
            cur.put(ln.substring(0, eq).trim(), value(ln.substring(eq + 1).trim()));
        }
        return out;
    }

    private static String value(String v) {
        if (v.length() > 0 && v.charAt(0) == '"') {
            int end = v.indexOf('"', 1);
            return end > 0 ? v.substring(1, end) : v.substring(1);
        }
        int hash = v.indexOf(" #");
        return hash >= 0 ? v.substring(0, hash).trim() : v;
    }

    /** A path inside the stick: no leading slash and no "..". */
    static boolean relOk(String p) {
        if (p == null || p.length() == 0 || p.charAt(0) == '/') return false;
        String[] parts = p.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("..".equals(parts[i])) return false;
        }
        return true;
    }

    /** [package.N] sections of a type=list descriptor, in N order. Bad paths are dropped. */
    static List<Entry> parseList(String text) {
        Map<String, Map<String, String>> ini = parseIni(text);
        List<Entry> out = new ArrayList<Entry>();
        Map<String, String> head = ini.get("nemesis");
        if (head == null || !hasWord(head.get("type"), "list")) return out;
        for (int n = 0; ; n++) {
            Map<String, String> s = ini.get("package." + n);
            if (s == null) break;
            String path = s.get("path");
            if (!relOk(path)) continue;
            Entry e = new Entry();
            e.path = path;
            e.name = or(s.get("name"), path);
            e.shortDesc = or(s.get("short-desc"), "");
            e.published = or(s.get("published"), "");
            out.add(e);
        }
        return out;
    }

    private static boolean hasWord(String list, String word) {
        if (list == null) return false;
        String[] p = list.split("[,\\s]+");
        for (int i = 0; i < p.length; i++) {
            if (word.equals(p[i])) return true;
        }
        return false;
    }

    private static String or(String v, String d) {
        return v == null || v.length() == 0 ? d : v;
    }

    static int compatOf(String name) {
        if ("full".equals(name)) return FULL;
        if ("partial".equals(name)) return PARTIAL;
        if ("incompatible".equals(name)) return NONE;
        return UNKNOWN;
    }

    /** A 1.0 descriptor declares no OS or car, so its "full" says nothing: never show it green. */
    static boolean isLegacy(String descriptor) {
        return descriptor != null && descriptor.length() > 0 && UpdateRule.compare(descriptor, "1.1") < 0;
    }

    /** The colour a row gets. Stays listed by default, unlike a real PARTIAL. */
    static int shade(Entry e) {
        return e.legacy && e.compat == FULL ? PARTIAL : e.compat;
    }

    /** Why the unit did not match, readable, from pkg-info or compat-check output. */
    static String reasons(String out) {
        StringBuilder sb = new StringBuilder();
        String[] lines = out == null ? new String[0] : out.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i].trim();
            String text = ln.startsWith("os ") ? "Software " + ln.substring(3)
                    : ln.startsWith("model ") ? "Car model " + ln.substring(6) : null;
            if (text == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(text);
        }
        return sb.toString();
    }

    /** id to package-version from pkg-list: id|name|locked|types|package-version|short-desc. */
    static Map<String, String> parseInstalled(String text) {
        Map<String, String> out = new HashMap<String, String>();
        String[] lines = text == null ? new String[0] : text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String[] f = lines[i].split("\\|", -1);
            if (f.length >= 5 && f[0].trim().length() > 0) out.put(f[0].trim(), f[4].trim());
        }
        return out;
    }

    /** The row a package would be updated from: newest per id, the stick's on a tie. */
    static List<Entry> updates(List<Entry> rows) {
        Map<String, Entry> best = new java.util.LinkedHashMap<String, Entry>();
        for (int i = 0; i < rows.size(); i++) {
            Entry e = rows.get(i);
            if (!e.isInstalled || e.locked || e.compat == NONE || e.compat == UNKNOWN) continue;
            if (UpdateRule.compare(e.version, e.installed) <= 0) continue;
            Entry had = best.get(e.id);
            int c = had == null ? 1 : UpdateRule.compare(e.version, had.version);
            if (c > 0 || (c == 0 && had.localZip.length() == 0 && e.localZip.length() > 0)) best.put(e.id, e);
        }
        return new ArrayList<Entry>(best.values());
    }

    static boolean matches(Entry e, String query) {
        if (query == null || query.trim().length() == 0) return true;
        String q = query.trim().toLowerCase(Locale.US);
        return e.name.toLowerCase(Locale.US).contains(q)
                || e.shortDesc.toLowerCase(Locale.US).contains(q);
    }

    /** Rows hidden by the default filter: known and not fully compatible. */
    static int hiddenCount(List<Entry> all) {
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            int c = all.get(i).compat;
            if (c == PARTIAL || c == NONE) n++;
        }
        return n;
    }

    static List<Entry> view(List<Entry> all, String query, boolean showAll,
                            final boolean byDate, final boolean ascending) {
        List<Entry> out = new ArrayList<Entry>();
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            if (!matches(e, query)) continue;
            if (!showAll && (e.compat == PARTIAL || e.compat == NONE)) continue;
            out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                int r = byDate ? a.published.compareTo(b.published) : 0;
                if (r == 0) {
                    r = a.name.compareToIgnoreCase(b.name);
                    return byDate || ascending ? r : -r;
                }
                return ascending ? r : -r;
            }
        });
        return out;
    }
}
