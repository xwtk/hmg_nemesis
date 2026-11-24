package porter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of smali.py: a class file is a list of segments; methods and fields are addressable by signature. */
final class Smali {
    private Smali() {}

    // ASCII classes: every smali file in tgt/, ref/ and overrides/ is pure ASCII (baksmali escapes the rest)
    static final String W = "[A-Za-z0-9_]";
    static final String[] DEBUG = {".line ", ".local ", ".end local", ".restart local", ".prologue", ".param ", ".source "};
    static final Pattern AUTO_LABEL = Pattern.compile(
            ":(?:cond|goto|try_start|try_end|catch|catchall|pswitch|pswitch_data|sswitch|sswitch_data|array)_[0-9a-f]+(?![A-Za-z0-9_])");
    static final Pattern NAME = Pattern.compile("([A-Za-z0-9_$\\-<>]+)(.*)");
    static final Pattern NAME0 = Pattern.compile("[A-Za-z0-9_$\\-<>]+");

    static List<String> canon(List<String> lines) {
        List<String> out = new ArrayList<String>();
        for (String l : lines) {
            String s = Py.strip(l);
            if (s.isEmpty() || s.startsWith("#") || startsAny(s, DEBUG))
                continue;
            out.add(s);
        }
        return out;
    }

    static boolean startsAny(String s, String[] p) {
        for (String x : p)
            if (s.startsWith(x))
                return true;
        return false;
    }

    static String shape(String line) {
        return line.indexOf(':') < 0 ? line : AUTO_LABEL.matcher(line).replaceAll(":L");
    }

    static List<String> shapeAll(List<String> lines) {
        List<String> out = new ArrayList<String>(lines.size());
        for (String l : lines)
            out.add(shape(l));
        return out;
    }

    static String msig(String line) {
        List<String> p = Py.split(line);
        return p.get(p.size() - 1);
    }

    static String fsig(String line) {
        int eq = line.indexOf(" = ");
        List<String> p = Py.split(eq >= 0 ? line.substring(0, eq) : line);
        return p.get(p.size() - 1);
    }

    /** name part of a member key ("name(args)ret" or "name:type") */
    static String name0(String s) {
        Matcher m = NAME0.matcher(s);
        if (!m.lookingAt())
            throw new IllegalStateException("no member name in " + s);
        return m.group();
    }

    static final class Seg {
        String kind; // raw / field / method / x
        final String key;
        List<String> lines;

        Seg(String kind, String key, List<String> lines) {
            this.kind = kind;
            this.key = key;
            this.lines = lines;
        }
    }

    static final class Cls {
        final String path; // tree-relative, e.g. com/a/B.smali
        final List<Seg> segs = new ArrayList<Seg>();
        final String name, sup;
        private Map<String, List<String>> methods, fields, all;

        Cls(String path, String text) {
            this.path = path;
            List<String> lines = Arrays.asList(text.split("\n", -1));
            int i = 0;
            while (i < lines.size()) {
                String l = lines.get(i);
                if (l.startsWith(".method ")) {
                    int j = index(lines, ".end method", i);
                    segs.add(new Seg("method", msig(l), new ArrayList<String>(lines.subList(i, j + 1))));
                    i = j + 1;
                } else if (l.startsWith(".field ")) {
                    int j = i + 1;
                    if (j < lines.size() && lines.get(j).startsWith("    .annotation"))
                        j = index(lines, ".end field", i) + 1;
                    segs.add(new Seg("field", fsig(l), new ArrayList<String>(lines.subList(i, j))));
                    i = j;
                } else {
                    Seg last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
                    if (last != null && last.kind.equals("raw"))
                        last.lines.add(l);
                    else {
                        List<String> one = new ArrayList<String>();
                        one.add(l);
                        segs.add(new Seg("raw", null, one));
                    }
                    i++;
                }
            }
            List<String> hdr = Py.split(lines.get(0));
            name = hdr.get(hdr.size() - 1);
            String s = null;
            for (String l : lines)
                if (l.startsWith(".super ")) {
                    s = Py.split(l).get(1);
                    break;
                }
            sup = s;
        }

        private static int index(List<String> lines, String want, int from) {
            for (int k = from; k < lines.size(); k++)
                if (lines.get(k).equals(want))
                    return k;
            throw new IllegalStateException(want + " missing");
        }

        Map<String, List<String>> methods() {
            if (methods == null)
                methods = of("method");
            return methods;
        }

        Map<String, List<String>> fields() {
            if (fields == null)
                fields = of("field");
            return fields;
        }

        /** {**methods(), **fields()} */
        Map<String, List<String>> members() {
            if (all == null) {
                all = new LinkedHashMap<String, List<String>>(methods());
                all.putAll(fields());
            }
            return all;
        }

        private Map<String, List<String>> of(String kind) {
            Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
            for (Seg s : segs)
                if (s.kind.equals(kind))
                    m.put(s.key, s.lines);
            return m;
        }

        /** segments were edited: cached member maps are stale */
        void touched() {
            methods = fields = all = null;
        }

        String text() {
            StringBuilder b = new StringBuilder();
            boolean first = true;
            for (Seg s : segs)
                for (String l : s.lines) {
                    if (!first)
                        b.append('\n');
                    b.append(l);
                    first = false;
                }
            return b.toString();
        }
    }

    /** class name -> Cls, in os.walk order (each dir's files sorted, then its sorted subdirs) */
    static Map<String, Cls> loadTree(File root) throws IOException {
        Map<String, Cls> out = new LinkedHashMap<String, Cls>();
        walk(root, "", out);
        return out;
    }

    private static void walk(File dir, String rel, Map<String, Cls> out) throws IOException {
        String[] names = dir.list();
        if (names == null)
            return;
        Arrays.sort(names);
        List<String> dirs = new ArrayList<String>();
        for (String n : names) {
            File f = new File(dir, n);
            if (f.isDirectory())
                dirs.add(n);
            else if (n.endsWith(".smali")) {
                Cls c = new Cls(rel + n, Py.readText(f));
                out.put(c.name, c);
            }
        }
        for (String d : dirs)
            walk(new File(dir, d), rel + d + "/", out);
    }

    /** a tree from in-memory texts keyed by relative path; ordered as loadTree would read them from disk */
    static Map<String, Cls> fromTexts(Map<String, String> texts) {
        List<String> paths = new ArrayList<String>(texts.keySet());
        java.util.Collections.sort(paths, WALK_ORDER);
        Map<String, Cls> out = new LinkedHashMap<String, Cls>();
        for (String p : paths) {
            Cls c = new Cls(p, texts.get(p));
            out.put(c.name, c);
        }
        return out;
    }

    /** os.walk top-down order on relative paths: at each level, files (sorted) before subdirs (sorted) */
    static final java.util.Comparator<String> WALK_ORDER = new java.util.Comparator<String>() {
        public int compare(String a, String b) {
            String[] x = a.split("/", -1), y = b.split("/", -1);
            for (int i = 0;; i++) {
                boolean xf = i == x.length - 1, yf = i == y.length - 1;
                if (xf != yf)
                    return xf ? -1 : 1;
                int c = x[i].compareTo(y[i]);
                if (c != 0 || xf)
                    return c;
            }
        }
    };
}
