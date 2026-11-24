package porter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of port.py's Names: ref <-> target names. Member keys are "class\0name\0sig". */
final class Names {
    // ASCII spellings of Python's \w, \s and \b (Dalvik's ICU regex differs from both on the rest)
    static final String DESC_RE = "L[A-Za-z0-9_/$\\-]+;";
    static final String MREF_RE = "(" + DESC_RE + ")->([A-Za-z0-9_$\\-<>]+)([:(][^\\s\\x1c-\\x1f,}]*)";
    static final Pattern DESC = Pattern.compile(DESC_RE);
    static final Pattern MREF = Pattern.compile(MREF_RE);
    static final Pattern TOK = Pattern.compile(MREF_RE + "|(" + DESC_RE + ")|(?<![A-Za-z0-9_])(0x7f[0-9a-f]{6})(?![A-Za-z0-9_])");

    Map<String, String> cls, rcls;
    final Map<String, String> mem = new LinkedHashMap<String, String>(), rmem = new LinkedHashMap<String, String>();
    Map<String, String> asup = new HashMap<String, String>(), tsup = new HashMap<String, String>();
    final Map<String, String> res, rres = new LinkedHashMap<String, String>();
    final Set<String> missingRes = new HashSet<String>();

    static String key(String c, String n, String sig) {
        return c + "\0" + n + "\0" + sig;
    }

    /** Names(cls, mem, res=res). Like Python's `cls or {}`, a non-empty cls map is shared, not copied. */
    Names(Map<String, String> cls, Map<String, String> memIn, Map<String, String> res) {
        this.cls = cls != null && !cls.isEmpty() ? cls : new LinkedHashMap<String, String>();
        this.res = res != null && !res.isEmpty() ? res : new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : this.res.entrySet())
            rres.put(e.getValue(), e.getKey());
        if (memIn != null)
            for (Map.Entry<String, String> e : memIn.entrySet()) {
                String k = e.getKey();
                int at = k.indexOf("->");
                if (at < 0 || k.indexOf("->", at + 2) >= 0)
                    throw new IllegalArgumentException("bad member key " + k);
                String c = k.substring(0, at);
                Matcher m = Smali.NAME.matcher(k.substring(at + 2));
                if (!m.matches())
                    throw new IllegalArgumentException("bad member key " + k);
                mem.put(key(c, m.group(1), m.group(2)), e.getValue());
            }
        rcls = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : this.cls.entrySet())
            rcls.put(e.getValue(), e.getKey());
        for (Map.Entry<String, String> e : mem.entrySet()) {
            String[] p = e.getKey().split("\0", -1);
            rmem.put(key(get(this.cls, p[0]), e.getValue(), sig(p[2], this.cls)), p[1]);
        }
    }

    static String get(Map<String, String> m, String k) {
        String v = m.get(k);
        return v == null ? k : v;
    }

    static String sig(String sig, final Map<String, String> cmap) {
        if (sig.indexOf(';') < 0)
            return sig;
        return Py.sub(DESC, sig, new Py.Rep() {
            public String rep(Matcher m) {
                return get(cmap, m.group());
            }
        });
    }

    private static String look(Map<String, String> table, Map<String, String> sup, String c, String n, String sig) {
        while (c != null) {
            String v = table.get(key(c, n, sig));
            if (v != null)
                return v;
            c = sup.get(c);
        }
        return n;
    }

    private String tr(String line, final Map<String, String> cmap, final Map<String, String> table,
            final Map<String, String> sup, final Map<String, String> rs) {
        if (cls.isEmpty() && mem.isEmpty() && res.isEmpty())
            return line;
        return Py.sub(TOK, line, new Py.Rep() {
            public String rep(Matcher m) {
                if (m.group(5) != null) {
                    if (!rs.containsKey(m.group(5)) && rs == res)
                        missingRes.add(m.group(5));
                    return get(rs, m.group(5));
                }
                if (m.group(4) != null)
                    return get(cmap, m.group(4));
                String c = m.group(1), n = m.group(2), s = m.group(3);
                return get(cmap, c) + "->" + look(table, sup, c, n, s) + sig(s, cmap);
            }
        });
    }

    String toTgt(String line) {
        return tr(line, cls, mem, asup, res);
    }

    String toRef(String line) {
        return tr(line, rcls, rmem, tsup, rres);
    }

    /** {head, name+sig, rest} of a .method/.field definition line */
    static String[] defSplit(String line) {
        int eq = line.indexOf(" = ");
        String left = eq >= 0 ? line.substring(0, eq) : line;
        int sp = left.lastIndexOf(' ');
        if (sp < 0)
            throw new IllegalArgumentException("not a definition: " + line);
        String head = left.substring(0, sp), s = left.substring(sp + 1);
        return new String[] {head, s, line.substring(head.length() + 1 + s.length())};
    }

    String defToRef(String tcls, String line) {
        if (cls.isEmpty() && mem.isEmpty())
            return line;
        String[] d = defSplit(line);
        Matcher m = Smali.NAME.matcher(d[1]);
        m.matches();
        return d[0] + " " + get(rmem, key(tcls, m.group(1), m.group(2)), m.group(1)) + sig(m.group(2), rcls) + d[2];
    }

    String defToTgt(String rc, String line) {
        if (cls.isEmpty() && mem.isEmpty())
            return line;
        String[] d = defSplit(line);
        Matcher m = Smali.NAME.matcher(d[1]);
        m.matches();
        return d[0] + " " + get(mem, key(rc, m.group(1), m.group(2)), m.group(1)) + sig(m.group(2), cls) + d[2];
    }

    private static String get(Map<String, String> m, String k, String dflt) {
        String v = m.get(k);
        return v == null ? dflt : v;
    }

    /** seg_to: first line is a definition, the rest are code */
    List<String> segTo(String cdesc, List<String> lines, boolean ref) {
        List<String> out = new java.util.ArrayList<String>(lines.size());
        out.add(ref ? defToRef(cdesc, lines.get(0)) : defToTgt(cdesc, lines.get(0)));
        for (String l : lines.subList(1, lines.size()))
            out.add(ref ? toRef(l) : toTgt(l));
        return out;
    }
}
