package porter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** difflib.SequenceMatcher(None, a, b, autojunk=False), line for line: no junk, no popular purge. */
final class Diff {
    private final int[] a, b;
    private final Map<Integer, int[]> b2j = new HashMap<Integer, int[]>();

    Diff(List<String> sa, List<String> sb) {
        Map<String, Integer> ids = new HashMap<String, Integer>();
        a = ids(sa, ids);
        b = ids(sb, ids);
        Map<Integer, List<Integer>> tmp = new HashMap<Integer, List<Integer>>();
        for (int j = 0; j < b.length; j++) {
            List<Integer> l = tmp.get(b[j]);
            if (l == null)
                tmp.put(b[j], l = new ArrayList<Integer>());
            l.add(j);
        }
        for (Map.Entry<Integer, List<Integer>> e : tmp.entrySet()) {
            int[] v = new int[e.getValue().size()];
            for (int i = 0; i < v.length; i++)
                v[i] = e.getValue().get(i);
            b2j.put(e.getKey(), v);
        }
    }

    private static int[] ids(List<String> s, Map<String, Integer> ids) {
        int[] out = new int[s.size()];
        for (int i = 0; i < out.length; i++) {
            Integer id = ids.get(s.get(i));
            if (id == null)
                ids.put(s.get(i), id = ids.size());
            out[i] = id;
        }
        return out;
    }

    /** find_longest_match; returns {i, j, k} */
    int[] longest(int alo, int ahi, int blo, int bhi) {
        int besti = alo, bestj = blo, bestsize = 0;
        Map<Integer, Integer> j2len = new HashMap<Integer, Integer>();
        int[] nothing = new int[0];
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newj2len = new HashMap<Integer, Integer>();
            int[] js = b2j.get(a[i]);
            for (int j : js == null ? nothing : js) {
                if (j < blo)
                    continue;
                if (j >= bhi)
                    break;
                Integer prev = j2len.get(j - 1);
                int k = (prev == null ? 0 : prev) + 1;
                newj2len.put(j, k);
                if (k > bestsize) {
                    besti = i - k + 1;
                    bestj = j - k + 1;
                    bestsize = k;
                }
            }
            j2len = newj2len;
        }
        // no junk: only the non-junk extension loops can move anything
        while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
            besti--;
            bestj--;
            bestsize++;
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi && a[besti + bestsize] == b[bestj + bestsize])
            bestsize++;
        return new int[] {besti, bestj, bestsize};
    }

    /** get_matching_blocks(), including the (la, lb, 0) sentinel */
    List<int[]> blocks() {
        int la = a.length, lb = b.length;
        List<int[]> queue = new ArrayList<int[]>();
        queue.add(new int[] {0, la, 0, lb});
        List<int[]> mb = new ArrayList<int[]>();
        while (!queue.isEmpty()) {
            int[] q = queue.remove(queue.size() - 1);
            int alo = q[0], ahi = q[1], blo = q[2], bhi = q[3];
            int[] x = longest(alo, ahi, blo, bhi);
            int i = x[0], j = x[1], k = x[2];
            if (k != 0) {
                mb.add(x);
                if (alo < i && blo < j)
                    queue.add(new int[] {alo, i, blo, j});
                if (i + k < ahi && j + k < bhi)
                    queue.add(new int[] {i + k, ahi, j + k, bhi});
            }
        }
        Collections.sort(mb, new Comparator<int[]>() {
            public int compare(int[] x, int[] y) {
                for (int t = 0; t < 3; t++)
                    if (x[t] != y[t])
                        return x[t] < y[t] ? -1 : 1;
                return 0;
            }
        });
        int i1 = 0, j1 = 0, k1 = 0;
        List<int[]> out = new ArrayList<int[]>();
        for (int[] m : mb) {
            if (i1 + k1 == m[0] && j1 + k1 == m[1])
                k1 += m[2];
            else {
                if (k1 != 0)
                    out.add(new int[] {i1, j1, k1});
                i1 = m[0];
                j1 = m[1];
                k1 = m[2];
            }
        }
        if (k1 != 0)
            out.add(new int[] {i1, j1, k1});
        out.add(new int[] {la, lb, 0});
        return out;
    }

    static final class Op {
        final String tag;
        final int i1, i2, j1, j2;

        Op(String tag, int i1, int i2, int j1, int j2) {
            this.tag = tag;
            this.i1 = i1;
            this.i2 = i2;
            this.j1 = j1;
            this.j2 = j2;
        }
    }

    List<Op> opcodes() {
        int i = 0, j = 0;
        List<Op> out = new ArrayList<Op>();
        for (int[] m : blocks()) {
            int ai = m[0], bj = m[1], size = m[2];
            String tag = "";
            if (i < ai && j < bj)
                tag = "replace";
            else if (i < ai)
                tag = "delete";
            else if (j < bj)
                tag = "insert";
            if (!tag.isEmpty())
                out.add(new Op(tag, i, ai, j, bj));
            i = ai + size;
            j = bj + size;
            if (size != 0)
                out.add(new Op("equal", ai, i, bj, j));
        }
        return out;
    }
}
