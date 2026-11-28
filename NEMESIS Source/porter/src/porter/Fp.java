package porter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fingerprint packs (metadata 1.3): a dex patch without the stock app's code.
 *
 *   fp/<app>.zip      every stock class as a fingerprint: .class/.super/.implements, member definition lines
 *                     (field initial values hashed), and per member one hash of its literal text plus the names
 *                     it references, line by line. Learn and RefCheck compare exactly what they compare on smali.
 *   fp/<app>.ids      one hash per stock class text: the same-build check.
 *   delta/<app>.delta the patch: per changed stock method, Merge's hunks as edit ops on the stock body with a hash
 *                     of the body's shape and of each hunk's context; added members and new classes in full; the
 *                     names RefCheck must verify.
 *   ref/<app>.res, overrides/<app>/  as in the smali pack.
 *
 * On the unit the target body stands in for the stock one: Port only merges a body whose shape equals stock, and
 * then Merge's result is the ops applied to the target. tests/porter_fp_test.sh checks both packs give the same
 * ports on every build in the corpus.
 */
final class Fp {
    private Fp() {}

    static boolean isPack(File pk, String app) {
        return new File(pk, "fp/" + app + ".zip").isFile();
    }

    static String h(String s) {
        return Py.sha1hex(s).substring(0, 16);
    }

    // ---- fingerprint ----

    static String defLine(String def) {
        if (!def.startsWith(".field"))
            return def;
        String[] d = Names.defSplit(def);
        return d[2].isEmpty() ? def : d[0] + " " + d[1] + " = h" + h(d[2]);
    }

    /** member lines -> {def, "h<hash>", one line of names per source line that has any, end line} */
    static List<String> member(List<String> lines) {
        List<String> c = Smali.canon(lines), out = new ArrayList<String>();
        out.add(defLine(c.get(0)));
        if (c.size() == 1)
            return out;
        StringBuilder lit = new StringBuilder();
        List<String> toks = new ArrayList<String>();
        for (String l : c.subList(1, c.size())) {
            String s = Smali.shape(l);
            Matcher m = Names.TOK.matcher(s);
            StringBuilder tl = new StringBuilder();
            int last = 0;
            while (m.find()) {
                lit.append(s, last, m.start()).append('\u0001');
                if (tl.length() > 0)
                    tl.append(' ');
                tl.append(m.group());
                last = m.end();
            }
            lit.append(s, last, s.length()).append('\n');
            if (tl.length() > 0)
                toks.add(tl.toString());
        }
        boolean method = c.get(0).startsWith(".method");
        out.add((method ? "    h" : "    .annotation h") + h(lit.toString())); // Cls reads an annotated field up to .end field
        for (String t : toks)
            out.add("    " + t);
        out.add(method ? ".end method" : ".end field");
        return out;
    }

    static String cls(Smali.Cls c) {
        List<String> out = new ArrayList<String>();
        for (Smali.Seg s : c.segs)
            if (s.kind.equals("raw")) {
                for (String l : s.lines)
                    if (l.startsWith(".class ") || l.startsWith(".super ") || l.startsWith(".implements "))
                        out.add(l);
            } else
                out.addAll(member(s.lines));
        return Py.join("\n", out);
    }

    static Map<String, String> tree(Map<String, Smali.Cls> t) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Smali.Cls c : t.values())
            out.put(c.path, cls(c));
        return out;
    }

    /** .class/.super only: enough for supers() and containsKey where no member is compared */
    static String stub(Smali.Cls c) {
        List<String> out = new ArrayList<String>();
        for (Smali.Seg s : c.segs)
            if (s.kind.equals("raw"))
                for (String l : s.lines)
                    if (l.startsWith(".class ") || l.startsWith(".super "))
                        out.add(l);
        return Py.join("\n", out);
    }

    static String idHash(String text) {
        return h(Main.CONST_NOTE.matcher(text).replaceAll("$1"));
    }

    /** the ref classes (paths) whose text in S is the stock text, from fp/<app>.ids */
    static Set<String> same(File pk, String app, Map<String, String> S) throws IOException {
        Set<String> same = new HashSet<String>();
        for (String l : Py.readText(new File(pk, "fp/" + app + ".ids")).split("\n")) {
            int sp = l.indexOf(' ');
            String x = sp < 0 ? null : S.get(l.substring(0, sp));
            if (x != null && idHash(x).equals(l.substring(sp + 1)))
                same.add(l.substring(0, sp));
        }
        return same;
    }

    /** the target as Learn compares it to a fingerprint */
    static Map<String, Smali.Cls> learnTree(Map<String, String> S) {
        return Smali.fromTexts(tree(Smali.fromTexts(S)));
    }

    // ---- Merge's helpers ----

    static int regs(List<String> l) {
        for (int i = 0; i < l.size(); i++)
            if (l.get(i).startsWith(".registers "))
                return i;
        return -1;
    }

    static boolean label(String s) {
        return Smali.AUTO_LABEL.matcher(s).matches();
    }

    static List<String> without(List<String> l, int i) {
        List<String> o = new ArrayList<String>(l);
        o.remove(i);
        return o;
    }

    static String rename(String line, Map<String, String> m) {
        Matcher x = Smali.AUTO_LABEL.matcher(line);
        StringBuffer b = new StringBuffer();
        while (x.find()) {
            String r = m.get(x.group());
            x.appendReplacement(b, Matcher.quoteReplacement(r == null ? x.group() : r));
        }
        return x.appendTail(b).toString();
    }

    /** every label as the index of its definition: equal iff Merge's A line, renamed to T's labels, equals T's */
    static List<String> norm(List<String> l) {
        Map<String, String> idx = new HashMap<String, String>();
        for (int i = 0; i < l.size(); i++)
            if (label(l.get(i)))
                idx.put(l.get(i), "\u0002" + i + "\u0003");
        List<String> out = new ArrayList<String>(l.size());
        for (String s : l)
            out.add(rename(s, idx));
        return out;
    }

    static String shapeHash(List<String> lines) {
        return h(Py.join("\n", Smali.shapeAll(Smali.canon(lines))));
    }

    // ---- delta (PC) ----

    static final Pattern MARK = Pattern.compile("\u0002(\\d+)\u0003");

    /** Merge.merge(A, P, A)'s hunks: ops on canon(A) without .registers; A's labels as their definition index */
    static void methodOps(List<String> A, List<String> P, List<String> out) {
        List<String> a = Smali.canon(A), p = Smali.canon(P);
        int ra = regs(a), rp = regs(p);
        String rd = "-";
        if (ra >= 0 && rp >= 0) {
            rd = String.valueOf(Integer.parseInt(Py.split(p.get(rp)).get(1)) - Integer.parseInt(Py.split(a.get(ra)).get(1)));
            a = without(a, ra);
            p = without(p, rp);
        }
        List<String> sa = Smali.shapeAll(a), sp = Smali.shapeAll(p);
        Map<String, String> p2a = new LinkedHashMap<String, String>();
        for (int[] blk : new Diff(sa, sp).blocks())
            for (int k = 0; k < blk[2]; k++)
                if (label(a.get(blk[0] + k)))
                    p2a.put(p.get(blk[1] + k), a.get(blk[0] + k));
        for (String l : p)
            if (label(l) && !p2a.containsKey(l))
                p2a.put(l, ":np_" + l.substring(1));
        List<String> pa = new ArrayList<String>();
        for (String l : p)
            pa.add(rename(l, p2a));
        Map<String, String> adef = new HashMap<String, String>();
        for (int i = 0; i < a.size(); i++)
            if (label(a.get(i)))
                adef.put(a.get(i), "\u0002" + i + "\u0003");
        List<String> na = norm(a);
        List<Diff.Op> hunks = new ArrayList<Diff.Op>();
        for (Diff.Op o : new Diff(a, pa).opcodes())
            if (!o.tag.equals("equal"))
                hunks.add(o);
        out.add("M " + shapeHash(A) + " " + rd + " " + hunks.size());
        for (Diff.Op o : hunks) {
            int lo = Math.max(0, o.i1 - Merge.CTX), hi = Math.min(a.size(), o.i2 + Merge.CTX);
            out.add("H " + o.i1 + " " + o.i2 + " " + lo + " " + hi + " " + h(Py.join("\n", na.subList(lo, hi))) + " " + (o.j2 - o.j1));
            for (String l : pa.subList(o.j1, o.j2))
                out.add(rename(l, adef));
        }
    }

    static void block(List<String> out, String tag, List<String> lines) {
        out.add(tag + " " + lines.size());
        out.addAll(lines);
    }

    static List<String> lines(String text) {
        return Arrays.asList(text.split("\n", -1));
    }

    /** the delta for one app, in Port's order (P's classes, then each class's methods, then its fields) */
    static String delta(Map<String, Smali.Cls> A, Map<String, Smali.Cls> P) {
        List<String> out = new ArrayList<String>();
        TreeSet<String> refs = new TreeSet<String>();
        for (String l : RefCheck.patchLines(A, P)) {
            Matcher m = Names.MREF.matcher(l);
            while (m.find())
                refs.add("R " + m.group(1) + " " + m.group(2) + m.group(3));
            for (String x : Learn.descs(l))
                if (A.containsKey(x))
                    refs.add("D " + x);
        }
        for (Map.Entry<String, Smali.Cls> pe : P.entrySet()) {
            String cname = pe.getKey();
            Smali.Cls pc = pe.getValue(), ac = A.get(cname);
            if (ac == null) {
                List<String> t = lines(pc.text());
                out.add("N " + cname + " " + pc.path + " " + t.size());
                out.addAll(t);
                continue;
            }
            if (Smali.canon(lines(ac.text())).equals(Smali.canon(lines(pc.text()))))
                continue;
            out.add("C " + cname);
            Map<String, List<String>> am = ac.methods(), pm = pc.methods(), af = ac.fields(), pf = pc.fields();
            for (Map.Entry<String, List<String>> me : pm.entrySet()) {
                String k = me.getKey();
                if (am.containsKey(k)) {
                    if (Smali.canon(am.get(k)).equals(Smali.canon(me.getValue())))
                        continue;
                    out.add("K " + k);
                    methodOps(am.get(k), me.getValue(), out);
                } else
                    block(out, "A " + k, me.getValue());
            }
            for (Map.Entry<String, List<String>> fe : pf.entrySet()) {
                String k = fe.getKey();
                if (!af.containsKey(k))
                    block(out, "F " + k, fe.getValue());
                else if (!Smali.canon(af.get(k)).equals(Smali.canon(fe.getValue())))
                    block(out, "G " + k, fe.getValue());
            }
            out.add("E");
        }
        out.addAll(refs);
        return Py.join("\n", out) + "\n";
    }

    /** writes fp/<app>.zip, fp/<app>.ids and delta/<app>.delta from the stock and patched trees */
    static void write(File out, String app, Map<String, String> stock, Map<String, String> patched) throws IOException {
        Map<String, Smali.Cls> A = Smali.fromTexts(stock), P = Smali.fromTexts(patched);
        Py.writeBytes(new File(out, "fp/" + app + ".zip"), Main.zipTree(tree(A)));
        Py.writeText(new File(out, "delta/" + app + ".delta"), delta(A, P));
        StringBuilder ids = new StringBuilder();
        for (Map.Entry<String, String> e : stock.entrySet())
            ids.append(e.getKey()).append(' ').append(idHash(e.getValue())).append('\n');
        Py.writeText(new File(out, "fp/" + app + ".ids"), ids.toString());
    }

    /** porter convert: a smali pack -> a fingerprint pack, stock and patched read as the unit reads them */
    static void convert(File pk, File out, List<String> apps) throws IOException {
        for (String app : apps) {
            Map<String, String> stock = Main.unzipTree(new File(pk, "ref/" + app + ".zip"));
            Map<String, String> patched = new LinkedHashMap<String, String>(stock);
            patched.putAll(Main.readTree(new File(pk, "patched/" + app)));
            write(out, app, stock, patched);
            Py.writeText(new File(out, "ref/" + app + ".res"), Py.readText(new File(pk, "ref/" + app + ".res")));
            Main.writeTree(new File(out, "overrides/" + app), Main.readTree(new File(pk, "overrides/" + app)));
            System.out.println(app + " converted");
        }
    }

    // ---- delta (unit) ----

    static final class Mod {
        String shape, rd;
        final List<int[]> hunks = new ArrayList<int[]>(); // i1 i2 lo hi
        final List<String> ctx = new ArrayList<String>();
        final List<List<String>> repl = new ArrayList<List<String>>();
    }

    /** kind M (changed method), A (added method), F (added field), G (changed field) */
    static final class Step {
        final String kind, key;
        List<String> lines;
        Mod mod;

        Step(String kind, String key) {
            this.kind = kind;
            this.key = key;
        }
    }

    static final class DCls {
        final String name;
        String path; // new classes only
        List<String> text; // new classes only
        final List<Step> steps = new ArrayList<Step>();

        DCls(String name) {
            this.name = name;
        }
    }

    static final class Delta {
        final List<DCls> classes = new ArrayList<DCls>();
        final List<String> refM = new ArrayList<String>(), refD = new ArrayList<String>();
    }

    static Delta read(File f) throws IOException {
        String[] l = Py.readText(f).split("\n", -1);
        Delta d = new Delta();
        DCls cur = null;
        int i = 0;
        while (i < l.length) {
            String s = l[i++];
            if (s.isEmpty())
                continue;
            char t = s.charAt(0);
            String rest = s.length() > 2 ? s.substring(2) : "";
            if (t == 'N') {
                String[] x = rest.split(" ");
                DCls c = new DCls(x[0]);
                c.path = x[1];
                int n = Integer.parseInt(x[2]);
                c.text = new ArrayList<String>(Arrays.asList(l).subList(i, i + n));
                i += n;
                d.classes.add(c);
            } else if (t == 'C') {
                d.classes.add(cur = new DCls(rest));
            } else if (t == 'K') {
                Step st = new Step("M", rest);
                String[] x = l[i++].split(" ");
                Mod m = st.mod = new Mod();
                m.shape = x[1];
                m.rd = x[2];
                int nh = Integer.parseInt(x[3]);
                for (int k = 0; k < nh; k++) {
                    String[] y = l[i++].split(" ");
                    m.hunks.add(new int[] {Integer.parseInt(y[1]), Integer.parseInt(y[2]), Integer.parseInt(y[3]), Integer.parseInt(y[4])});
                    m.ctx.add(y[5]);
                    int n = Integer.parseInt(y[6]);
                    m.repl.add(new ArrayList<String>(Arrays.asList(l).subList(i, i + n)));
                    i += n;
                }
                cur.steps.add(st);
            } else if (t == 'A' || t == 'F' || t == 'G') {
                int sp = rest.lastIndexOf(' ');
                Step st = new Step(String.valueOf(t), rest.substring(0, sp));
                int n = Integer.parseInt(rest.substring(sp + 1));
                st.lines = new ArrayList<String>(Arrays.asList(l).subList(i, i + n));
                i += n;
                cur.steps.add(st);
            } else if (t == 'E') {
                cur = null;
            } else if (t == 'R') {
                d.refM.add(rest);
            } else if (t == 'D') {
                d.refD.add(rest);
            } else
                throw new IOException(f + ": bad line " + s);
        }
        return d;
    }

    /**
     * The patched tree as Main's rename checks read it (member keys, definition lines, supers): the fingerprint
     * plus the members and classes the delta adds. Stock string constants are hashed here; the input tree the
     * checks also search carries the unit's own.
     */
    static Map<String, String> patchedView(Map<String, String> fpTexts, Map<String, Smali.Cls> FA, Delta d) {
        Map<String, String> out = new LinkedHashMap<String, String>(fpTexts);
        for (DCls dc : d.classes) {
            if (dc.text != null) {
                out.put(dc.path, Py.join("\n", dc.text));
                continue;
            }
            Smali.Cls c = FA.get(dc.name);
            if (c == null)
                continue;
            StringBuilder b = new StringBuilder(out.get(c.path));
            for (Step st : dc.steps)
                if (st.kind.equals("A") || st.kind.equals("F"))
                    b.append('\n').append(Py.join("\n", st.lines));
            out.put(c.path, b.toString());
        }
        return out;
    }

    /** the target fingerprinted only where RefCheck compares members; every class keeps its .super */
    static Map<String, Smali.Cls> checkTree(Map<String, Smali.Cls> FA, Delta d, Map<String, Smali.Cls> T, Map<String, String> cls) {
        Set<String> need = new HashSet<String>();
        for (String x : d.refD)
            need.add(Names.get(cls, x));
        for (String x : d.refM) {
            int sp = x.indexOf(' ');
            String dc = Learn.decl(FA, x.substring(0, sp), x.substring(sp + 1));
            if (dc != null)
                need.add(Names.get(cls, dc));
        }
        Map<String, String> ft = new LinkedHashMap<String, String>();
        for (Smali.Cls c : T.values())
            ft.put(c.path, need.contains(c.name) ? cls(c) : stub(c));
        return Smali.fromTexts(ft);
    }

    private static List<String> indent(List<String> m) {
        List<String> out = new ArrayList<String>(m.size());
        for (String l : m)
            out.add(l.startsWith(".method") || l.startsWith(".end method") ? l : "    " + l);
        return out;
    }

    /** Merge.merge(A, P, tref) for a target body shaped like stock, from the ops alone */
    static List<String> apply(Mod m, List<String> tref) throws Merge.Conflict {
        List<String> t = Smali.canon(tref);
        int rt = regs(t);
        Integer nregs = null;
        if (!m.rd.equals("-") && rt >= 0) {
            nregs = Integer.parseInt(Py.split(t.get(rt)).get(1)) + Integer.parseInt(m.rd);
            t = without(t, rt);
        }
        List<String> nt = norm(t), out = new ArrayList<String>(t);
        for (int k = m.hunks.size() - 1; k >= 0; k--) {
            int[] hk = m.hunks.get(k);
            if (hk[3] > nt.size() || hk[2] >= hk[3] || !h(Py.join("\n", nt.subList(hk[2], hk[3]))).equals(m.ctx.get(k)))
                throw new Merge.Conflict("hunk at A[" + hk[0] + ":" + hk[1] + "] context differs in target");
            List<String> repl = new ArrayList<String>();
            for (String l : m.repl.get(k)) {
                Matcher x = MARK.matcher(l);
                StringBuffer b = new StringBuffer();
                while (x.find())
                    x.appendReplacement(b, Matcher.quoteReplacement(t.get(Integer.parseInt(x.group(1)))));
                repl.add(x.appendTail(b).toString());
            }
            List<String> tail = new ArrayList<String>(out.subList(hk[1], out.size()));
            out.subList(hk[0], out.size()).clear();
            out.addAll(repl);
            out.addAll(tail);
        }
        if (nregs != null)
            out.add(rt, ".registers " + nregs);
        return out;
    }

    /** Port.port with the stock side from the fingerprint and the delta; same results, issues and messages */
    static Port.Result port(Map<String, Smali.Cls> FA, Delta d, Map<String, Smali.Cls> T, Map<String, String> overrides,
            Names names) {
        Port.Result r = new Port.Result();
        for (String k : new String[] {"merged", "overridden", "taken", "added", "new_classes"})
            r.report.put(k, 0);
        r.report.put("conflicts", r.conflicts);
        for (Smali.Cls c : T.values())
            r.files.put(c.path, c.text());
        names.asup = Port.supers(FA);
        names.tsup = Port.supers(T);
        for (DCls dc : d.classes) { // a new inner class follows its outer class's target name
            String cname = dc.name, outer = cname.split("\\$", -1)[0] + ";";
            if (dc.text != null && cname.contains("$") && names.cls.containsKey(outer)) {
                String o = names.cls.get(outer);
                names.cls.put(cname, o.substring(0, o.length() - 1) + cname.substring(outer.length() - 1));
                names.rcls.put(names.cls.get(cname), cname);
            }
        }
        for (DCls dc : d.classes)
            if (dc.text != null && T.containsKey(Names.get(names.cls, dc.name)))
                r.bad("newcls", dc.name, "", Names.get(names.cls, dc.name), "", dc.name + ": new class name already taken in target");
        int done = 0;
        for (DCls dc : d.classes) {
            Main.tick((double) done++ / d.classes.size());
            String cname = dc.name, tname = Names.get(names.cls, cname);
            if (dc.text != null) {
                Smali.Cls pc = new Smali.Cls(dc.path, Py.join("\n", dc.text));
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
            Smali.Cls tc = T.get(tname);
            if (tc == null) {
                r.bad("other", cname, "", tname, "", cname + ": no target class " + tname);
                continue;
            }
            Map<String, Integer> tidx = new HashMap<String, Integer>();
            for (int i = 0; i < tc.segs.size(); i++) {
                Smali.Seg s = tc.segs.get(i);
                if (s.kind.equals("raw"))
                    continue;
                String dl = names.defToRef(tname, s.lines.get(0));
                tidx.put(s.kind + " " + (s.kind.equals("method") ? Smali.msig(dl) : Smali.fsig(dl)), i);
            }
            Set<String> ttaken = new HashSet<String>();
            for (Smali.Seg s : tc.segs)
                if (!s.kind.equals("raw"))
                    ttaken.add(s.kind + " " + (s.kind.equals("method") ? Smali.msig(s.lines.get(0)) : Smali.fsig(s.lines.get(0))));
            List<List<String>> extra = new ArrayList<List<String>>();
            for (Step st : dc.steps) {
                String k = st.key;
                if (st.kind.equals("M")) {
                    Integer i = tidx.get("method " + k);
                    if (i == null) {
                        r.bad("other", cname, k, tname, "", cname + "->" + k + ": method missing in target");
                        continue;
                    }
                    List<String> tref = names.segTo(tname, tc.segs.get(i).lines, true);
                    if (!st.mod.shape.equals(shapeHash(tref))) {
                        String hh = Port.bodyHash(tref);
                        String ov = overrides.get(hh);
                        if (ov == null) {
                            r.bad("body", cname, k, tname, tc.segs.get(i).lines.get(0),
                                    cname + "->" + k + ": target body differs from stock (no override " + hh + ".smali)");
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
                        m = apply(st.mod, tref);
                    } catch (Merge.Conflict e) {
                        r.bad("body", cname, k, tname, tc.segs.get(i).lines.get(0), cname + "->" + k + ": " + e.getMessage());
                        continue;
                    }
                    tc.segs.get(i).lines = indent(names.segTo(cname, m, false));
                    r.inc("merged");
                } else if (st.kind.equals("A")) {
                    if (ttaken.contains("method " + Smali.msig(names.defToTgt(cname, st.lines.get(0))))) {
                        r.bad("addm", cname, k, tname, "", cname + "->" + k + ": added method already exists in target");
                        continue;
                    }
                    extra.add(names.segTo(cname, st.lines, false));
                    r.inc("added");
                } else if (st.kind.equals("F")) {
                    if (ttaken.contains("field " + Smali.fsig(names.defToTgt(cname, st.lines.get(0))))) {
                        r.bad("addf", cname, k, tname, "", cname + "." + k + ": added field already exists in target");
                        continue;
                    }
                    extra.add(0, names.segTo(cname, st.lines, false));
                    r.inc("added");
                } else {
                    Integer i = tidx.get("field " + k);
                    if (i == null) {
                        r.bad("other", cname, k, tname, "", cname + "." + k + ": field missing in target");
                        continue;
                    }
                    tc.segs.get(i).lines = names.segTo(cname, st.lines, false);
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

    /** RefCheck.check with the patch's names from the delta; FT is the target fingerprinted (checkTree) */
    static List<String> refCheck(Map<String, Smali.Cls> FA, Delta d, Map<String, Smali.Cls> FT, Names names) {
        names.asup = Port.supers(FA);
        names.tsup = Port.supers(FT);
        TreeSet<String> classes = new TreeSet<String>(d.refD), mems = new TreeSet<String>();
        for (String x : d.refM) {
            int sp = x.indexOf(' ');
            String key = x.substring(sp + 1), dc = Learn.decl(FA, x.substring(0, sp), key);
            if (dc != null)
                mems.add(dc + "\0" + key);
        }
        List<String> bad = new ArrayList<String>();
        for (String c : classes)
            if (!FT.containsKey(Names.get(names.cls, c)))
                bad.add("class " + c + " missing");
        for (String ck : mems) {
            String c = ck.substring(0, ck.indexOf('\0')), k = ck.substring(ck.indexOf('\0') + 1);
            Smali.Cls tc = FT.get(Names.get(names.cls, c));
            List<String> mine = names.segTo(c, FA.get(c).members().get(k), false);
            String tk = mine.get(0).startsWith(".method") ? Smali.msig(mine.get(0)) : Smali.fsig(mine.get(0));
            Map<String, List<String>> tsegs = tc != null ? tc.members() : Collections.<String, List<String>>emptyMap();
            if (!tsegs.containsKey(tk))
                bad.add(c + "->" + k + " missing");
            else if (!Smali.shapeAll(Smali.canon(mine)).equals(Smali.shapeAll(Smali.canon(tsegs.get(tk)))))
                bad.add(c + "->" + k + " differs");
        }
        return bad;
    }
}
