package porter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/** Port of merge.py: apply the ref patch (A -> P) to the target body (T), all in ref names. */
final class Merge {
    private Merge() {}

    static final int CTX = 2;

    static final class Conflict extends Exception {
        Conflict(String m) {
            super(m);
        }
    }

    private static int regs(List<String> l) {
        for (int i = 0; i < l.size(); i++)
            if (l.get(i).startsWith(".registers "))
                return i;
        return -1;
    }

    private static boolean label(String s) {
        return Smali.AUTO_LABEL.matcher(s).matches();
    }

    private static String rename(String line, Map<String, String> m) {
        Matcher x = Smali.AUTO_LABEL.matcher(line);
        StringBuffer b = new StringBuffer();
        while (x.find()) {
            String r = m.get(x.group());
            x.appendReplacement(b, Matcher.quoteReplacement(r == null ? x.group() : r));
        }
        return x.appendTail(b).toString();
    }

    private static List<String> renameAll(List<String> lines, Map<String, String> m) {
        List<String> out = new ArrayList<String>();
        for (String l : lines)
            out.add(rename(l, m));
        return out;
    }

    private static String relabel(String line, Map<String, String> lmap) throws Conflict {
        Matcher x = Smali.AUTO_LABEL.matcher(line);
        StringBuffer b = new StringBuffer();
        while (x.find()) {
            String r = lmap.get(x.group());
            if (r == null)
                throw new Conflict("label " + x.group() + " has no counterpart in target");
            x.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        return x.appendTail(b).toString();
    }

    private static Map<Integer, Integer> align(List<String> x, List<String> y) {
        Map<Integer, Integer> m = new LinkedHashMap<Integer, Integer>();
        for (int[] blk : new Diff(x, y).blocks())
            for (int k = 0; k < blk[2]; k++)
                m.put(blk[0] + k, blk[1] + k);
        return m;
    }

    private static List<String> without(List<String> l, int i) {
        List<String> o = new ArrayList<String>(l);
        o.remove(i);
        return o;
    }

    static List<String> merge(List<String> A, List<String> P, List<String> T) throws Conflict {
        List<String> a = Smali.canon(A), p = Smali.canon(P), t = Smali.canon(T);
        int ra = regs(a), rp = regs(p), rt = regs(t);
        Integer nregs = null;
        if (ra >= 0 && rp >= 0 && rt >= 0) {
            nregs = Integer.parseInt(Py.split(t.get(rt)).get(1)) + Integer.parseInt(Py.split(p.get(rp)).get(1))
                    - Integer.parseInt(Py.split(a.get(ra)).get(1));
            a = without(a, ra);
            p = without(p, rp);
            t = without(t, rt);
        }
        List<String> sa = Smali.shapeAll(a), sp = Smali.shapeAll(p), st = Smali.shapeAll(t);

        Map<Integer, Integer> amap = align(sa, st);
        Map<String, String> a2t = new LinkedHashMap<String, String>();
        for (Map.Entry<Integer, Integer> e : amap.entrySet())
            if (label(a.get(e.getKey())))
                a2t.put(a.get(e.getKey()), t.get(e.getValue()));
        List<String> at = renameAll(a, a2t);
        Map<Integer, Integer> same = new LinkedHashMap<Integer, Integer>();
        for (Map.Entry<Integer, Integer> e : amap.entrySet())
            if (at.get(e.getKey()).equals(t.get(e.getValue())))
                same.put(e.getKey(), e.getValue());
        amap = same;
        Map<String, String> p2a = new LinkedHashMap<String, String>();
        for (Map.Entry<Integer, Integer> e : align(sa, sp).entrySet())
            if (label(a.get(e.getKey())))
                p2a.put(p.get(e.getValue()), a.get(e.getKey()));
        for (String l : p)
            if (label(l) && !p2a.containsKey(l))
                p2a.put(l, ":np_" + l.substring(1));
        List<String> pa = renameAll(p, p2a);
        List<String> out = new ArrayList<String>(t);
        List<Diff.Op> hunks = new ArrayList<Diff.Op>();
        for (Diff.Op h : new Diff(a, pa).opcodes())
            if (!h.tag.equals("equal"))
                hunks.add(h);
        for (int h = hunks.size() - 1; h >= 0; h--) {
            Diff.Op o = hunks.get(h);
            int lo = Math.max(0, o.i1 - CTX), hi = Math.min(a.size(), o.i2 + CTX);
            List<Integer> span = new ArrayList<Integer>();
            for (int i = lo; i < hi; i++)
                span.add(amap.get(i));
            boolean ok = !span.isEmpty() && !span.contains(null);
            for (int i = 0; ok && i < span.size(); i++)
                ok = span.get(i) == span.get(0) + i;
            if (!ok)
                throw new Conflict("hunk at A[" + o.i1 + ":" + o.i2 + "] context differs in target");
            int t1 = span.get(0) + (o.i1 - lo), t2 = t1 + (o.i2 - o.i1);
            List<String> repl = new ArrayList<String>();
            for (String l : pa.subList(o.j1, o.j2))
                repl.add(relabel(l, a2t));
            List<String> tail = new ArrayList<String>(out.subList(t2, out.size()));
            out.subList(t1, out.size()).clear();
            out.addAll(repl);
            out.addAll(tail);
        }
        if (nregs != null)
            out.add(rt, ".registers " + nregs);
        return out;
    }

    static void selfCheck() throws Conflict {
        List<String> A = l(".method f()V", ".registers 2", "const/4 v0, 0x0", "if-eqz v0, :cond_0", "nop", ":cond_0", "return-void", ".end method");
        List<String> P = l(".method f()V", ".registers 3", "const/4 v0, 0x0", "if-eqz v0, :cond_0", "nop", "goto :cond_0", ":cond_0", "return-void", ".end method");
        check(merge(A, P, A).equals(Smali.canon(P)), "identity");
        List<String> T = l(".method f()V", ".registers 4", "const/4 v1, 0x1", "const/4 v0, 0x0", "if-eqz v0, :cond_7", "nop", ":cond_7", "return-void", ".end method");
        List<String> r = merge(A, P, T);
        check(r.equals(l(".method f()V", ".registers 5", "const/4 v1, 0x1", "const/4 v0, 0x0", "if-eqz v0, :cond_7", "nop", "goto :cond_7", ":cond_7", "return-void", ".end method")), "relabel");
        List<String> P9 = new ArrayList<String>();
        for (String x : P)
            P9.add(x.replace("cond_0", "cond_9"));
        check(merge(A, P9, T).equals(r), "renumbered patch");
        try {
            merge(A, P, l(".method f()V", ".registers 2", "const/4 v0, 0x0", "if-eqz v0, :cond_0", "const/4 v0, 0x1", ":cond_0", "return-void", ".end method"));
            check(false, "expected conflict");
        } catch (Conflict e) {
            // expected
        }
    }

    private static List<String> l(String... s) {
        return new ArrayList<String>(java.util.Arrays.asList(s));
    }

    private static void check(boolean c, String what) {
        if (!c)
            throw new IllegalStateException("merge self-check: " + what);
    }
}
