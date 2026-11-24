package porter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Port of port.py's port(): the ref patch onto one target tree, entirely in memory. */
final class Port {
    private Port() {}

    static String bodyHash(List<String> lines) {
        return Py.sha1hex(Py.join("\n", Smali.canon(lines))).substring(0, 16);
    }

    private static List<String> indent(List<String> m) {
        List<String> out = new ArrayList<String>(m.size());
        for (String l : m)
            out.add(l.startsWith(".method") || l.startsWith(".end method") ? l : "    " + l);
        return out;
    }

    private static List<String> lines(String text) {
        return new ArrayList<String>(java.util.Arrays.asList(text.split("\n", -1)));
    }

    static final class Result {
        final Map<String, Object> report = new LinkedHashMap<String, Object>();
        final List<String> conflicts = new ArrayList<String>();
        /** {kind, ref class, ref member key or "", target class, target member key or ""}; kind body|addm|addf|newcls|other */
        final List<String[]> issues = new ArrayList<String[]>();
        /** tree-relative path -> text, for every file of the output tree */
        final Map<String, String> files = new LinkedHashMap<String, String>();

        void bad(String kind, String c, String m, String tc, String tm, String text) {
            conflicts.add(text);
            issues.add(new String[] {kind, c, m, tc, tm});
        }

        int n(String k) {
            return (Integer) report.get(k);
        }

        void inc(String k) {
            report.put(k, n(k) + 1);
        }
    }

    /**
     * T is the target tree (consumed: its classes are edited in place). overrides: body hash -> file
     * text, for this app. Output tree = T's files with the edits, plus the new classes.
     */
    static Result port(Map<String, Smali.Cls> A, Map<String, Smali.Cls> P, Map<String, Smali.Cls> T,
            Map<String, String> overrides, Names names) {
        Result r = new Result();
        for (String k : new String[] {"merged", "overridden", "taken", "added", "new_classes"})
            r.report.put(k, 0);
        r.report.put("conflicts", r.conflicts);
        for (Smali.Cls c : T.values())
            r.files.put(c.path, c.text());
        names.asup = supers(A);
        names.tsup = supers(T);
        for (String cname : P.keySet()) { // a new inner class follows its outer class's target name
            String outer = cname.split("\\$", -1)[0] + ";";
            if (!A.containsKey(cname) && cname.contains("$") && names.cls.containsKey(outer)) {
                String o = names.cls.get(outer);
                names.cls.put(cname, o.substring(0, o.length() - 1) + cname.substring(outer.length() - 1));
                names.rcls.put(names.cls.get(cname), cname);
            }
        }
        for (String cname : P.keySet())
            if (!A.containsKey(cname) && T.containsKey(Names.get(names.cls, cname)))
                r.bad("newcls", cname, "", Names.get(names.cls, cname), "", cname + ": new class name already taken in target");
        int done = 0;
        for (Map.Entry<String, Smali.Cls> pe : P.entrySet()) {
            Main.tick((double) done++ / P.size());
            String cname = pe.getKey();
            Smali.Cls pc = pe.getValue();
            String tname = Names.get(names.cls, cname);
            if (!A.containsKey(cname)) { // whole new class
                List<String> out = new ArrayList<String>();
                for (Smali.Seg s : pc.segs)
                    if (!s.kind.equals("raw"))
                        out.addAll(names.segTo(cname, s.lines, false));
                    else
                        for (String x : s.lines)
                            out.add(names.toTgt(x));
                r.files.put(tname.substring(1, tname.length() - 1) + ".smali", Py.join("\n", out));
                r.inc("new_classes");
                continue;
            }
            Smali.Cls ac = A.get(cname);
            if (Smali.canon(lines(ac.text())).equals(Smali.canon(lines(pc.text()))))
                continue;
            Smali.Cls tc = T.get(tname);
            if (tc == null) {
                r.bad("other", cname, "", tname, "", cname + ": no target class " + tname);
                continue;
            }
            Map<String, List<String>> am = ac.methods(), pm = pc.methods(), af = ac.fields(), pf = pc.fields();
            Map<String, Integer> tidx = new HashMap<String, Integer>();
            for (int i = 0; i < tc.segs.size(); i++) {
                Smali.Seg s = tc.segs.get(i);
                if (s.kind.equals("raw"))
                    continue;
                String d = names.defToRef(tname, s.lines.get(0));
                tidx.put(s.kind + " " + (s.kind.equals("method") ? Smali.msig(d) : Smali.fsig(d)), i);
            }
            // an added member collides by its target name, so a name-map rename (LivePatch collisions) clears it
            Set<String> ttaken = new HashSet<String>();
            for (Smali.Seg s : tc.segs)
                if (!s.kind.equals("raw"))
                    ttaken.add(s.kind + " " + (s.kind.equals("method") ? Smali.msig(s.lines.get(0)) : Smali.fsig(s.lines.get(0))));
            List<List<String>> extra = new ArrayList<List<String>>();
            for (Map.Entry<String, List<String>> me : pm.entrySet()) {
                String k = me.getKey();
                List<String> plines = me.getValue();
                if (am.containsKey(k)) {
                    if (Smali.canon(am.get(k)).equals(Smali.canon(plines)))
                        continue;
                    Integer i = tidx.get("method " + k);
                    if (i == null) {
                        r.bad("other", cname, k, tname, "", cname + "->" + k + ": method missing in target");
                        continue;
                    }
                    List<String> tref = names.segTo(tname, tc.segs.get(i).lines, true);
                    // a hunk can fit its context yet write a register the target keeps live; only a body
                    // equal to stock proves the patch's register use holds
                    if (!Smali.shapeAll(Smali.canon(am.get(k))).equals(Smali.shapeAll(Smali.canon(tref)))) {
                        String h = bodyHash(tref);
                        String ov = overrides.get(h);
                        if (ov == null) {
                            r.bad("body", cname, k, tname, tc.segs.get(i).lines.get(0),
                                    cname + "->" + k + ": target body differs from stock (no override " + h + ".smali)");
                            continue;
                        }
                        List<String> m = new ArrayList<String>();
                        for (String l : ov.split("\n", -1))
                            if (!l.isEmpty() && !l.startsWith("#"))
                                m.add(l);
                        tc.segs.get(i).lines = indent(names.segTo(cname, m, false));
                        r.inc("overridden");
                        continue;
                    }
                    List<String> m;
                    try {
                        m = Merge.merge(am.get(k), plines, tref);
                    } catch (Merge.Conflict e) {
                        r.bad("body", cname, k, tname, tc.segs.get(i).lines.get(0), cname + "->" + k + ": " + e.getMessage());
                        continue;
                    }
                    tc.segs.get(i).lines = indent(names.segTo(cname, m, false));
                    r.inc("merged");
                } else {
                    if (ttaken.contains("method " + Smali.msig(names.defToTgt(cname, plines.get(0))))) {
                        r.bad("addm", cname, k, tname, "", cname + "->" + k + ": added method already exists in target");
                        continue;
                    }
                    extra.add(names.segTo(cname, plines, false));
                    r.inc("added");
                }
            }
            for (Map.Entry<String, List<String>> fe : pf.entrySet()) {
                String k = fe.getKey();
                List<String> flines = fe.getValue();
                if (!af.containsKey(k)) {
                    if (ttaken.contains("field " + Smali.fsig(names.defToTgt(cname, flines.get(0))))) {
                        r.bad("addf", cname, k, tname, "", cname + "." + k + ": added field already exists in target");
                        continue;
                    }
                    extra.add(0, names.segTo(cname, flines, false));
                    r.inc("added");
                } else if (!Smali.canon(af.get(k)).equals(Smali.canon(flines))) {
                    Integer i = tidx.get("field " + k);
                    if (i == null) {
                        r.bad("other", cname, k, tname, "", cname + "." + k + ": field missing in target");
                        continue;
                    }
                    tc.segs.get(i).lines = names.segTo(cname, flines, false);
                    r.inc("taken");
                }
            }
            for (List<String> e : extra) {
                List<String> blank = new ArrayList<String>();
                blank.add("");
                tc.segs.add(new Smali.Seg("raw", null, blank));
                tc.segs.add(new Smali.Seg("x", null, e));
            }
            tc.touched();
            r.files.put(tc.path, tc.text());
        }
        List<String> miss = new ArrayList<String>(names.missingRes);
        Collections.sort(miss);
        for (String x : miss)
            r.bad("other", "", x, "", "", "resource " + x + " has no counterpart in target APK");
        return r;
    }

    static Map<String, String> supers(Map<String, Smali.Cls> tree) {
        Map<String, String> m = new HashMap<String, String>();
        for (Map.Entry<String, Smali.Cls> e : tree.entrySet())
            m.put(e.getKey(), e.getValue().sup);
        return m;
    }

    /** overrides/<App>/*.smali as hash -> text */
    static Map<String, String> loadOverrides(File dir) throws IOException {
        Map<String, String> m = new HashMap<String, String>();
        String[] names = dir.list();
        if (names != null)
            for (String n : names)
                if (n.endsWith(".smali"))
                    m.put(n.substring(0, n.length() - 6), Py.readText(new File(dir, n)));
        return m;
    }
}
