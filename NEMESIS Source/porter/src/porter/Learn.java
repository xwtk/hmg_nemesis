package porter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of matcher.py: learn the ref -> target obfuscation map. Member keys are "class\0name+sig". */
final class Learn {
    static final Pattern TOK = Pattern.compile(Names.MREF_RE + "|(" + Names.DESC_RE + ")");
    static final Pattern RID = Pattern.compile("(?<![A-Za-z0-9_])0x7f[0-9a-f]{6}(?![A-Za-z0-9_])");

    static boolean obfCls(String d) {
        int end = d.length() - 1, i = end;
        while (i > 1 && d.charAt(i - 1) != '/' && d.charAt(i - 1) != '$')
            i--;
        // shortcut: ProGuard names here are 1-2 chars; widen if a build ever uses 3-char names
        return end - i <= 2;
    }

    static boolean obfMem(String n) {
        return n.length() <= 2;
    }

    final Map<String, Smali.Cls> A, T;
    final Map<String, String> cmap = new LinkedHashMap<String, String>();
    final Map<String, String> mmap = new LinkedHashMap<String, String>();
    int bad;
    final Set<String> done = new HashSet<String>();
    /** ref classes whose members are all done: round() has nothing left to do there (done and cmap only grow) */
    private final Set<String> finished = new HashSet<String>();
    Map<String, String> rc = new HashMap<String, String>();
    Set<String> rm = new HashSet<String>();

    Learn(Map<String, Smali.Cls> A, Map<String, Smali.Cls> T) {
        this.A = A;
        this.T = T;
        for (String c : A.keySet())
            if (!obfCls(c) && T.containsKey(c))
                cmap.put(c, c);
    }

    /**
     * Ref classes whose text is byte-identical in the target (same path): mapped to themselves up front, members
     * included, and their bodies unified once so the names they reference still propagate. On 13 builds x 6 apps
     * Learn never mapped such a class, or one of its members, anywhere else.
     */
    void seed(Set<String> samePaths) {
        for (Smali.Cls c : A.values()) {
            if (!samePaths.contains(c.path) || !T.containsKey(c.name))
                continue;
            cmap.put(c.name, c.name);
            for (Map.Entry<String, List<String>> e : c.members().entrySet()) {
                String k = mk(c.name, e.getKey());
                mmap.put(k, Smali.name0(e.getKey()));
                done.add(k);
                unify(tail(e.getValue()), tail(e.getValue()));
            }
        }
    }

    static String mk(String c, String s) {
        return c + "\0" + s;
    }

    // --- abstraction ---
    String cls(String d, boolean a) {
        if (a) {
            String v = cmap.get(d);
            if (v != null)
                return v;
            return A.containsKey(d) && (obfCls(d) || !T.containsKey(d)) ? "L#;" : d;
        }
        if (rc.containsKey(d))
            return d;
        return T.containsKey(d) && (obfCls(d) || !A.containsKey(d)) ? "L#;" : d; // one-side name: renamed (V016 b/i -> util/NaviConfig)
    }

    /** sig cut at its descriptors: even indexes literal, odd indexes descriptors */
    private final Map<String, String[]> sigCut = new HashMap<String, String[]>();

    String sig(String sig, boolean a) {
        if (sig.indexOf(';') < 0)
            return sig;
        String[] c = sigCut.get(sig);
        if (c == null) {
            List<String> l = new ArrayList<String>();
            Matcher m = Names.DESC.matcher(sig);
            int last = 0;
            while (m.find()) {
                l.add(sig.substring(last, m.start()));
                l.add(m.group());
                last = m.end();
            }
            l.add(sig.substring(last));
            sigCut.put(sig, c = l.toArray(new String[0]));
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < c.length; i++)
            b.append(i % 2 == 0 ? c[i] : cls(c[i], a));
        return b.toString();
    }

    String mem(String c, String n, String sig, boolean a) {
        if (a) {
            String t = mmap.get(mk(c, n + sig));
            if (t == null) {
                String d = decl(A, c, n + sig);
                t = d != null ? mmap.get(mk(d, n + sig)) : null;
            }
            return t != null ? t : obfMem(n) ? "#" : n;
        }
        return rm.contains(mk(c, n + sig)) || !obfMem(n) ? n : "#";
    }

    static String decl(Map<String, Smali.Cls> tree, String c, String key) {
        while (c != null && tree.containsKey(c)) {
            Smali.Cls cl = tree.get(c);
            if (cl.methods().containsKey(key) || cl.fields().containsKey(key))
                return c;
            c = cl.sup;
        }
        return null;
    }

    String abstractDef(String line, boolean a, String cdef) {
        String[] d = Names.defSplit(line);
        Matcher m = Smali.NAME.matcher(d[1]);
        m.matches();
        return d[0] + " " + mem(cdef, m.group(1), m.group(2), a) + sig(m.group(2), a);
    }

    /** a shaped line cut at its TOK matches: lit[0] tok[0] lit[1] ... ; tok = {desc} or {class, name, sig} */
    static final class Pre {
        final String[] lit;
        final String[][] tok;

        Pre(String[] lit, String[][] tok) {
            this.lit = lit;
            this.tok = tok;
        }
    }

    private final Map<String, Pre> pre = new HashMap<String, Pre>();

    private Pre pre(String line) {
        Pre p = pre.get(line);
        if (p != null)
            return p;
        String s = Smali.shape(line);
        List<String> lit = new ArrayList<String>();
        List<String[]> tok = new ArrayList<String[]>();
        int last = 0;
        if (s.indexOf(';') >= 0) { // both TOK alternatives end in ';'
            Matcher m = TOK.matcher(s);
            while (m.find()) {
                lit.add(s.substring(last, m.start()));
                tok.add(m.group(4) != null ? new String[] {m.group(4)} : new String[] {m.group(1), m.group(2), m.group(3)});
                last = m.end();
            }
        }
        lit.add(s.substring(last));
        p = new Pre(lit.toArray(new String[0]), tok.toArray(new String[0][]));
        pre.put(line, p);
        return p;
    }

    /** TOK.sub then RID.sub, as matcher.abstract() does, from the cached cut */
    String abstractLine(String line, boolean a) {
        Pre p = pre(line);
        String s;
        if (p.tok.length == 0)
            s = p.lit[0];
        else {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < p.tok.length; i++) {
                String[] t = p.tok[i];
                b.append(p.lit[i]);
                if (t.length == 1)
                    b.append(cls(t[0], a));
                else
                    b.append(cls(t[0], a)).append("->").append(mem(t[0], t[1], t[2], a)).append(sig(t[2], a));
            }
            s = b.append(p.lit[p.tok.length]).toString();
        }
        return s.indexOf("0x7f") < 0 ? s : RID.matcher(s).replaceAll("0x#R");
    }

    /** member bodies never change while matching, so canon() is computed once per body */
    private final Map<List<String>, List<String>> canonMemo = new java.util.IdentityHashMap<List<String>, List<String>>();

    List<String> canon(List<String> lines) {
        List<String> c = canonMemo.get(lines);
        if (c == null)
            canonMemo.put(lines, c = Smali.canon(lines));
        return c;
    }

    List<String> fp(Smali.Cls cl, List<String> lines, boolean a) {
        List<String> c = canon(lines);
        List<String> out = new ArrayList<String>(c.size());
        out.add(abstractDef(c.get(0), a, cl.name));
        for (String l : c.subList(1, c.size()))
            out.add(abstractLine(l, a));
        return out;
    }

    /** TOK.findall: 4 groups, "" when unmatched */
    static List<String[]> findall(String s) {
        List<String[]> out = new ArrayList<String[]>();
        Matcher m = TOK.matcher(s);
        while (m.find()) {
            String[] g = new String[4];
            for (int i = 0; i < 4; i++)
                g[i] = m.group(i + 1) == null ? "" : m.group(i + 1);
            out.add(g);
        }
        return out;
    }

    static List<String> descs(String s) {
        List<String> out = new ArrayList<String>();
        Matcher m = Names.DESC.matcher(s);
        while (m.find())
            out.add(m.group());
        return out;
    }

    // --- unification ---
    boolean unify(List<String> al, List<String> tl) {
        Map<String, String> newC = new LinkedHashMap<String, String>(), newM = new LinkedHashMap<String, String>();
        for (int li = 0; li < Math.min(al.size(), tl.size()); li++) {
            List<String[]> ta = findall(al.get(li)), tt = findall(tl.get(li));
            if (ta.size() != tt.size())
                return false;
            for (int q = 0; q < ta.size(); q++) {
                String[] x = ta.get(q), y = tt.get(q);
                List<String[]> pairs = new ArrayList<String[]>();
                if (!x[3].isEmpty())
                    pairs.add(new String[] {x[3], y[3]});
                else {
                    pairs.add(new String[] {x[0], y[0]});
                    List<String> dx = descs(x[2]), dy = descs(y[2]);
                    for (int i = 0; i < Math.min(dx.size(), dy.size()); i++)
                        pairs.add(new String[] {dx.get(i), dy.get(i)});
                    String k = mk(x[0], x[1] + x[2]);
                    String have = mmap.containsKey(k) ? mmap.get(k) : newM.containsKey(k) ? newM.get(k) : y[1];
                    if (!have.equals(y[1]))
                        return false;
                    if (A.containsKey(x[0]))
                        newM.put(k, y[1]);
                }
                for (String[] pr : pairs) {
                    String ca = pr[0], ct = pr[1];
                    if (A.containsKey(ca)) {
                        String have = cmap.containsKey(ca) ? cmap.get(ca) : newC.containsKey(ca) ? newC.get(ca) : ct;
                        if (!have.equals(ct))
                            return false;
                    }
                    if (A.containsKey(ca) && T.containsKey(ct))
                        newC.put(ca, ct);
                }
            }
        }
        for (Map.Entry<String, String> e : newC.entrySet())
            if (rc.containsKey(e.getValue()) && !rc.get(e.getValue()).equals(e.getKey()))
                return false;
        cmap.putAll(newC);
        mmap.putAll(newM);
        return true;
    }

    private void reverse() {
        rc = new HashMap<String, String>();
        for (Map.Entry<String, String> e : cmap.entrySet())
            rc.put(e.getValue(), e.getKey());
    }

    private String tkey(String s, String n) {
        String name = Smali.name0(s);
        return n + Names.sig(s.substring(name.length()), cmap);
    }

    int round() {
        reverse();
        rm = new HashSet<String>();
        for (Map.Entry<String, String> e : mmap.entrySet()) {
            String[] cs = e.getKey().split("\0", -1);
            rm.add(mk(Names.get(cmap, cs[0]), tkey(cs[1], e.getValue())));
        }
        int gained = 0;
        for (Map.Entry<String, String> ce : new ArrayList<Map.Entry<String, String>>(copy(cmap).entrySet())) {
            String ca = ce.getKey(), ct = ce.getValue();
            if (finished.contains(ca))
                continue;
            Smali.Cls ac = A.get(ca), tc = T.get(ct);
            Map<List<String>, List<String>> tfp = null;
            Map<List<String>, Integer> tfpN = null;
            Map<String, List<String>> tall = tc.members();
            boolean all = true;
            for (Map.Entry<String, List<String>> ae : ac.members().entrySet()) {
                String k = ae.getKey();
                List<String> v = ae.getValue();
                if (done.contains(mk(ca, k)))
                    continue;
                all = false;
                List<String> fa = fp(ac, v, true);
                if (mmap.containsKey(mk(ca, k))) { // named from a call site; its body still has names to give
                    String tk = tkey(k, mmap.get(mk(ca, k)));
                    if (tall.containsKey(tk) && fp(tc, tall.get(tk), false).equals(fa)) {
                        done.add(mk(ca, k));
                        if (unify(tail(v), tail(tall.get(tk))))
                            gained++;
                        else
                            bad++;
                    }
                    continue;
                }
                if (tfpN == null) { // the target table, built on first use: rm is fixed for the round
                    tfp = new HashMap<List<String>, List<String>>();
                    tfpN = new HashMap<List<String>, Integer>();
                    for (Map.Entry<String, List<String>> te : tall.entrySet()) {
                        if (rm.contains(mk(ct, te.getKey())))
                            continue;
                        List<String> f = fp(tc, te.getValue(), false);
                        Integer n = tfpN.get(f);
                        tfpN.put(f, n == null ? 1 : n + 1);
                        if (n == null)
                            tfp.put(f, java.util.Arrays.asList(te.getKey()));
                    }
                }
                Integer hits = tfpN.get(fa);
                if (hits != null && hits == 1) {
                    done.add(mk(ca, k));
                    String tk = tfp.get(fa).get(0);
                    mmap.put(mk(ca, k), Smali.name0(tk));
                    if (unify(tail(v), tail(tall.get(tk))))
                        gained++;
                    else {
                        mmap.remove(mk(ca, k));
                        bad++;
                    }
                }
            }
            if (all)
                finished.add(ca);
        }
        return gained;
    }

    private static Map<String, String> copy(Map<String, String> m) {
        return new LinkedHashMap<String, String>(m);
    }

    private List<String> tail(List<String> lines) {
        List<String> c = canon(lines);
        return c.subList(1, c.size());
    }

    private Map<List<String>, Integer> prof(Smali.Cls cl, boolean a) {
        Map<List<String>, Integer> m = new HashMap<List<String>, Integer>();
        for (List<String> v : cl.members().values()) {
            List<String> f = fp(cl, v, a);
            List<String> k = new ArrayList<String>(f.subList(1, f.size()));
            Integer n = m.get(k);
            m.put(k, n == null ? 1 : n + 1);
        }
        return m;
    }

    private static String pkg(String c) {
        int i = c.lastIndexOf('/');
        return i < 0 ? c : c.substring(0, i);
    }

    int bySimilarity() {
        Set<String> mappedT = new HashSet<String>(cmap.values());
        reverse();
        List<String> ua = new ArrayList<String>(), ut = new ArrayList<String>();
        for (String c : A.keySet())
            if (!cmap.containsKey(c))
                ua.add(c);
        for (String c : T.keySet())
            if (!mappedT.contains(c))
                ut.add(c);
        Map<String, Map<List<String>, Integer>> tp = new HashMap<String, Map<List<String>, Integer>>();
        Map<String, Integer> tsum = new HashMap<String, Integer>();
        for (String c : ut) {
            Map<List<String>, Integer> p = prof(T.get(c), false);
            tp.put(c, p);
            tsum.put(c, sum(p));
        }
        Map<String, String> best = new LinkedHashMap<String, String>();
        for (String ca : ua) {
            Map<List<String>, Integer> pa = prof(A.get(ca), true);
            int asum = sum(pa);
            String pk = pkg(ca);
            double top = -1, second = -1;
            String topC = null;
            int cands = 0;
            for (String ct : ut) {
                if (!pkg(ct).equals(pk))
                    continue;
                Map<List<String>, Integer> pt = tp.get(ct);
                int inter = 0;
                Map<List<String>, Integer> small = pa.size() <= pt.size() ? pa : pt, big = small == pa ? pt : pa;
                for (Map.Entry<List<String>, Integer> e : small.entrySet()) {
                    Integer o = big.get(e.getKey());
                    if (o != null)
                        inter += Math.min(o, e.getValue());
                }
                int union = asum + tsum.get(ct) - inter;
                double sc = (double) inter / (union == 0 ? 1 : union);
                cands++;
                // sc.sort(reverse=True) on (score, name): ties go to the larger name
                if (sc > top || (sc == top && ct.compareTo(topC) > 0)) {
                    second = top;
                    top = sc;
                    topC = ct;
                } else if (sc > second)
                    second = sc;
            }
            if (cands > 0 && top >= 0.5 && (cands == 1 || second < top))
                best.put(ca, topC);
        }
        Map<String, Integer> claimed = new HashMap<String, Integer>();
        for (String ct : best.values()) {
            Integer n = claimed.get(ct);
            claimed.put(ct, n == null ? 1 : n + 1);
        }
        int n = 0;
        for (Map.Entry<String, String> e : best.entrySet())
            if (claimed.get(e.getValue()) == 1) {
                cmap.put(e.getKey(), e.getValue());
                n++;
            }
        return n;
    }

    private static int sum(Map<List<String>, Integer> m) {
        int s = 0;
        for (int v : m.values())
            s += v;
        return s;
    }

    void run() {
        while (true) {
            while (round() != 0) {
            }
            if (bySimilarity() == 0)
                return;
        }
    }

    /** learn(): {"cls": {...}, "mem": {...}} with sorted keys, as names.json is written */
    Map<String, Object> names() {
        Map<String, String> c = new TreeMap<String, String>(), m = new TreeMap<String, String>();
        for (Map.Entry<String, String> e : cmap.entrySet())
            if (!e.getKey().equals(e.getValue()))
                c.put(e.getKey(), e.getValue());
        for (Map.Entry<String, String> e : mmap.entrySet()) {
            String[] cs = e.getKey().split("\0", -1);
            if (!Smali.name0(cs[1]).equals(e.getValue()))
                m.put(cs[0] + "->" + cs[1], e.getValue());
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("cls", c);
        out.put("mem", m);
        return out;
    }

    /** cls / mem in the matcher's own insertion order, which is what port() and refcheck see */
    Map<String, String> clsMap() {
        Map<String, String> c = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : cmap.entrySet())
            if (!e.getKey().equals(e.getValue()))
                c.put(e.getKey(), e.getValue());
        return c;
    }

    Map<String, String> memMap() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : mmap.entrySet()) {
            String[] cs = e.getKey().split("\0", -1);
            if (!Smali.name0(cs[1]).equals(e.getValue()))
                m.put(cs[0] + "->" + cs[1], e.getValue());
        }
        return m;
    }
}
