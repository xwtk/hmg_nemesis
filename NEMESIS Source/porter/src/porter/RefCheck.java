package porter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;

/** Port of refcheck.py: every app member the patch references must mean the same in the target. */
final class RefCheck {
    private RefCheck() {}

    private static List<String> lines(String text) {
        return java.util.Arrays.asList(text.split("\n", -1));
    }

    static List<String> patchLines(Map<String, Smali.Cls> A, Map<String, Smali.Cls> P) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Smali.Cls> e : P.entrySet()) {
            Smali.Cls ac = A.get(e.getKey());
            if (ac == null) {
                out.addAll(lines(e.getValue().text()));
                continue;
            }
            Map<String, List<String>> am = ac.members();
            for (Map.Entry<String, List<String>> m : e.getValue().members().entrySet())
                if (!am.containsKey(m.getKey()) || !Smali.canon(am.get(m.getKey())).equals(Smali.canon(m.getValue())))
                    out.addAll(m.getValue());
        }
        return out;
    }

    static List<String> check(Map<String, Smali.Cls> A, Map<String, Smali.Cls> P, Map<String, Smali.Cls> T, Names names) {
        names.asup = Port.supers(A);
        names.tsup = Port.supers(T);
        TreeSet<String> classes = new TreeSet<String>();
        TreeSet<String> mems = new TreeSet<String>(); // "decl\0key": \0 sorts first, so this is tuple order
        for (String l : patchLines(A, P)) {
            Matcher m = Names.MREF.matcher(l);
            while (m.find()) {
                String key = m.group(2) + m.group(3);
                String d = Learn.decl(A, m.group(1), key);
                if (d != null)
                    mems.add(d + "\0" + key);
            }
            for (String x : Learn.descs(l))
                if (A.containsKey(x))
                    classes.add(x);
        }
        List<String> bad = new ArrayList<String>();
        for (String c : classes)
            if (!T.containsKey(Names.get(names.cls, c)))
                bad.add("class " + c + " missing");
        for (String ck : mems) {
            String c = ck.substring(0, ck.indexOf('\0')), k = ck.substring(ck.indexOf('\0') + 1);
            Smali.Cls tc = T.get(Names.get(names.cls, c));
            List<String> mine = names.segTo(c, A.get(c).members().get(k), false);
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
