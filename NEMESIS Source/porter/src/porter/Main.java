package porter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * usage: porter regress <universal dir> <tag> <out dir>   build.py's app half from tgt/<tag>/smali, into <out dir>
 *        porter deodex <odex> <out dir> <class path dir>...   fetch.py's smali tree for one odex
 *        porter batch <pack dir> <jobs file> <class path dir>...   device for each "app odex apk dz report" line, one VM
 *        porter selftest
 *        porter pack <universal dir> <out dir> <ref apk dir> <app>...   the pack tree for a package (ref dexes, res ids, patched files)
 *        porter serve <class path dir>...   LivePatch: one "port\t..." request per stdin line, protocol on stdout
 *        porter device <pack dir> <app> <odex> <apk> <out.dz> <report.json> <class path dir>...
 *               on the unit: port one stock odex; exit 1 (report kept) on conflicts or unresolved refs
 */
public final class Main {
    static final String[] APPS = {"HKMC_DiPO", "Iap2ServerApp", "Bluetooth", "PhoneProjection", "HKMC_GPM", "HKMC_Navi_ExSL"};

    public static void main(String[] a) throws Exception {
        if (a.length == 1 && a[0].equals("selftest")) {
            Merge.selfCheck();
            System.out.println("selftest ok");
        } else if (a.length == 4 && a[0].equals("regress")) {
            regress(new File(a[1]), a[2], new File(a[3]));
        } else if (a.length >= 5 && a[0].equals("pack")) {
            pack(new File(a[1]), new File(a[2]), new File(a[3]), Arrays.asList(a).subList(4, a.length));
        } else if (a.length >= 2 && a[0].equals("serve")) {
            serve(Arrays.asList(a).subList(1, a.length));
        } else if (a.length >= 8 && a[0].equals("device")) {
            System.exit(device(new File(a[1]), a[2], new File(a[3]), new File(a[4]), new File(a[5]), new File(a[6]),
                    Arrays.asList(a).subList(7, a.length)) ? 0 : 1);
        } else if (a.length >= 4 && a[0].equals("batch")) {
            System.exit(batch(new File(a[1]), new File(a[2]), Arrays.asList(a).subList(3, a.length)) ? 0 : 1);
        } else if (a.length >= 4 && a[0].equals("deodex")) {
            writeTree(new File(a[2]), Dex.target(new File(a[1]), Arrays.asList(a).subList(3, a.length)));
        } else {
            System.err.println("usage: porter regress <universal> <tag> <out> | deodex <odex> <out> <dir>... | selftest");
            System.exit(2);
        }
    }

    static Map<String, String> resMap(File refApk, File tgtApk) throws IOException {
        Map<String, String> r = Arsc.resIds(refApk), t = Arsc.resIds(tgtApk), out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : r.entrySet())
            if (t.containsKey(e.getKey()))
                out.put(e.getValue(), t.get(e.getKey()));
        return out;
    }

    static void regress(File u, String tag, File out) throws Exception {
        Map<String, Object> rep = new LinkedHashMap<String, Object>();
        for (String app : APPS) {
            long t0 = System.currentTimeMillis();
            Map<String, Smali.Cls> A = Smali.loadTree(new File(u, "ref/stock/" + app + "_smali"));
            Map<String, Smali.Cls> P = Smali.loadTree(new File(u, "ref/patched/" + app + "_smali"));
            File tdir = new File(u, "tgt/" + tag + "/smali/" + app);
            Learn m = new Learn(A, Smali.loadTree(tdir));
            m.run();
            Py.writeText(new File(out, app + ".names.json"), Py.json(m.names(), 0));
            Map<String, String> res = resMap(new File(u, "tgt/ev6_usa/system/app/" + app + ".apk"),
                    new File(u, "tgt/" + tag + "/system/app/" + app + ".apk"));
            Map<String, String> cls = m.clsMap(), mem = m.memMap();
            Port.Result r = Port.port(A, P, Smali.loadTree(tdir), Port.loadOverrides(new File(u, "overrides/" + app)),
                    new Names(cls, mem, res));
            List<String> bad = RefCheck.check(A, P, Smali.loadTree(tdir), new Names(cls, mem, res));
            r.report.put("refs_differ", bad);
            File sm = new File(out, app + "_smali");
            writeTree(sm, r.files);
            if (r.conflicts.isEmpty())
                Py.writeBytes(new File(out, app + ".dex"), Dex.assemble(r.files, 18));
            rep.put(app, r.report);
            System.out.println(tag + " " + app + " conflicts " + r.conflicts.size() + " refs_differ " + bad.size() + " "
                    + (System.currentTimeMillis() - t0) + " ms");
        }
        Py.writeText(new File(out, "report.json"), Py.json(rep, 1));
    }

    /** stock ref trees as api-17 dexes (only if they disassemble back byte-identical), ref res ids, patched delta */
    static void pack(File u, File out, File apkDir, List<String> apps) throws IOException {
        for (String app : apps) {
            Map<String, String> A = readTree(new File(u, "ref/stock/" + app + "_smali"));
            Map<String, String> rt = Dex.disassemble(Dex.assemble(A, 17), 17);
            if (!rt.equals(A))
                throw new IOException(app + ": ref/stock does not survive smali a/baksmali d -a 17");
            byte[] zip = zipTree(rt); // the order the unit used to get from baksmali d
            Py.writeBytes(new File(out, "ref/" + app + ".zip"), zip);
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, String> e : Arsc.resIds(new File(apkDir, app + ".apk")).entrySet())
                b.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
            Py.writeText(new File(out, "ref/" + app + ".res"), b.toString());
            Map<String, String> P = readTree(new File(u, "ref/patched/" + app + "_smali")), delta = new LinkedHashMap<String, String>();
            if (!P.keySet().containsAll(A.keySet()))
                throw new IOException(app + ": patched tree drops stock classes");
            for (Map.Entry<String, String> e : P.entrySet())
                if (!e.getValue().equals(A.get(e.getKey())))
                    delta.put(e.getKey(), e.getValue());
            writeTree(new File(out, "patched/" + app), delta);
            writeTree(new File(out, "overrides/" + app), readTree(new File(u, "overrides/" + app)));
            System.out.println(app + " ref " + zip.length + " bytes, " + delta.size() + " patched files");
        }
    }

    /** regress for one app on the unit, from the pack dir and the live odex/apk; the dz is written only on success */
    /**
     * PORTER_CACHE=<dir>: Learn's answer for one (ref text, target text) pair. Keyed by content, so
     * packages with the same ref smali share it; entries are only deleted by LivePatch's cache trim.
     */
    static File cacheFile(final String app, Map<String, String> ref, Map<String, String> tgt) throws IOException {
        final String dir = System.getenv("PORTER_CACHE");
        if (dir == null)
            return null;
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-1");
            d.update("learn2".getBytes("UTF-8")); // bump when Learn's answer can change
            List<Map<String, String>> sides = new ArrayList<Map<String, String>>();
            sides.add(new java.util.TreeMap<String, String>(ref)); // zip order must not change the key
            sides.add(new java.util.TreeMap<String, String>(tgt));
            for (Map<String, String> side : sides)
                for (Map.Entry<String, String> e : side.entrySet()) {
                    d.update((e.getKey() + "\0").getBytes("UTF-8"));
                    d.update(e.getValue().getBytes("UTF-8"));
                    d.update((byte) 0);
                }
            StringBuilder h = new StringBuilder();
            for (byte b : d.digest())
                h.append(String.format("%02x", b & 255));
            return new File(dir, app + "." + h + ".names");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static boolean loadNames(File f, Map<String, String> cls, Map<String, String> mem) throws IOException {
        if (!f.isFile())
            return false;
        Map<String, String> c = new LinkedHashMap<String, String>(), m = new LinkedHashMap<String, String>();
        for (String l : Py.readText(f).split("\n")) {
            String[] x = l.split("\t", 3);
            if (x.length == 3 && x[0].equals("C"))
                c.put(x[1], x[2]);
            else if (x.length == 3 && x[0].equals("M"))
                m.put(x[1], x[2]);
            else if (!l.equals("END"))
                return false; // truncated or foreign: relearn
        }
        if (!Py.readText(f).endsWith("END\n"))
            return false;
        cls.putAll(c);
        mem.putAll(m);
        return true;
    }

    static void saveNames(File f, Map<String, String> cls, Map<String, String> mem) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : cls.entrySet())
            b.append("C\t").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        for (Map.Entry<String, String> e : mem.entrySet())
            b.append("M\t").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        b.append("END\n");
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        Py.writeBytes(tmp, b.toString().getBytes("UTF-8"));
        tmp.renameTo(f);
    }

    /** baksmali's "# <double>" note on const lines comes out differently on Dalvik than on the JDK */
    static final java.util.regex.Pattern CONST_NOTE = java.util.regex.Pattern.compile("(?m)^(    const[^\"\n]*?)    # [^\n]*$");

    static boolean sameText(String a, String b) {
        if (b == null)
            return false;
        if (a.equals(b))
            return true;
        return CONST_NOTE.matcher(a).replaceAll("$1").equals(CONST_NOTE.matcher(b).replaceAll("$1"));
    }

    /** The Manager's running row: $PORTER_UI is NEMESIS's session dir (ui.status, ui.progress percent). */
    static void ui(String app, String step, int pct) {
        String d = System.getenv("PORTER_UI");
        if (d == null)
            return;
        try {
            if (!"0".equals(System.getenv("PORTER_UI_STATUS")))
                Py.writeText(new File(d, "ui.status"), app + ": " + step + "\n");
            Py.writeText(new File(d, "ui.progress"), "0 100 " + pct + "\n");
        } catch (IOException ignored) {
        }
    }

    static boolean device(File pk, String app, File odex, File apk, File dz, File report, List<String> dirs)
            throws IOException {
        // shortcut: the bar moves at step boundaries only (shares measured for Iap2 on TCG), add ticks inside Port/assemble if users read it as hung
        ui(app, "reading stock", 0);
        long t0 = System.currentTimeMillis();
        org.jf.dexlib2.iface.DexFile deo = Dex.deodexCached(odex, dirs);
        long ta = System.currentTimeMillis(), tb = ta;
        ui(app, "reading classes", 33);
        Map<String, String> tgt = Dex.text(deo); // what fetch.py's assemble/disassemble round trip gives, see Dex.target
        ui(app, "matching names", 42);
        long tc = System.currentTimeMillis();
        Map<String, String> stock = unzipTree(new File(pk, "ref/" + app + ".zip"));
        Map<String, String> patched = new LinkedHashMap<String, String>(stock);
        patched.putAll(readTree(new File(pk, "patched/" + app)));
        Map<String, String> refRes = new LinkedHashMap<String, String>(), t = Arsc.resIds(apk), res = new LinkedHashMap<String, String>();
        for (String l : Py.readText(new File(pk, "ref/" + app + ".res")).split("\n")) {
            int sp = l.indexOf(' ');
            if (sp > 0)
                refRes.put(l.substring(0, sp), l.substring(sp + 1));
        }
        for (Map.Entry<String, String> e : refRes.entrySet())
            if (t.containsKey(e.getKey()))
                res.put(e.getValue(), t.get(e.getKey()));
        long t1 = System.currentTimeMillis();
        Map<String, Smali.Cls> A = Smali.fromTexts(stock), P = Smali.fromTexts(patched);
        Set<String> same = new HashSet<String>();
        if (System.getenv("PORTER_NOSEED") == null)
            for (Map.Entry<String, String> e : stock.entrySet())
                if (sameText(e.getValue(), tgt.get(e.getKey())))
                    same.add(e.getKey());
        String firstDiff = null;
        for (Map.Entry<String, String> e : stock.entrySet())
            if (firstDiff == null && !same.contains(e.getKey())) {
                String x = e.getValue(), y = tgt.get(e.getKey());
                int i = 0;
                while (y != null && i < x.length() && i < y.length() && x.charAt(i) == y.charAt(i))
                    i++;
                firstDiff = e.getKey() + (y == null ? " absent" : " @" + i + " ref[" + x.substring(Math.max(0, i - 40), Math.min(x.length(), i + 40))
                        + "] tgt[" + y.substring(Math.max(0, i - 40), Math.min(y.length(), i + 40)) + "]").replace("\n", "\\n");
            }
        System.out.println("  identical " + same.size() + "/" + stock.size() + (firstDiff == null ? "" : ", first differs: " + firstDiff));
        File cache = cacheFile(app, stock, tgt);
        stock = patched = null; // the unit's heap: drop the texts and Learn's caches before port
        long tp = System.currentTimeMillis();
        Map<String, String> cls = new LinkedHashMap<String, String>(), mem = new LinkedHashMap<String, String>();
        // every ref class byte-identical in the target: Learn's answer is the identity (13 builds x 6 apps agree)
        boolean identity = same.size() == A.size();
        if (!identity && (cache == null || !loadNames(cache, cls, mem))) {
            Learn m = new Learn(A, Smali.fromTexts(tgt));
            if (System.getenv("PORTER_SEED") != null) // shortcut: not exact (neev_can_v14 PhoneProjection differs), off until proven
                m.seed(same);
            m.run();
            cls = m.clsMap();
            mem = m.memMap();
            m = null;
            if (cache != null)
                saveNames(cache, cls, mem);
        }
        long tl = System.currentTimeMillis();
        ui(app, "porting", 43);
        Port.Result r = Port.port(A, P, Smali.fromTexts(tgt), Port.loadOverrides(new File(pk, "overrides/" + app)),
                new Names(cls, mem, res));
        long tq = System.currentTimeMillis();
        ui(app, "checking", 54);
        List<String> bad = RefCheck.check(A, P, Smali.fromTexts(tgt), new Names(cls, mem, res));
        r.report.put("refs_differ", bad);
        long t2 = System.currentTimeMillis();
        System.out.println("  parse " + (tp - t1) + " learn " + (tl - tp) + " port " + (tq - tl) + " refcheck " + (t2 - tq));
        boolean ok = r.conflicts.isEmpty() && bad.isEmpty();
        if (ok) {
            ui(app, "building", 65);
            File tmp = new File(dz.getPath() + ".tmp");
            Py.writeBytes(tmp, Dz.make(Dex.assembleOver(deo, tgt, r.files, 18)));
            if (!tmp.renameTo(dz))
                throw new IOException("rename " + tmp);
        }
        long t3 = System.currentTimeMillis();
        r.report.put("ms", list("deodex " + (ta - t0), "tgt text " + (tc - tb),
                "ref " + (t1 - tc), "port " + (t2 - t1), "assemble " + (t3 - t2), "jobs " + Dex.JOBS));
        Py.writeText(report, Py.json(r.report, 1));
        System.out.println(app + (ok ? " ok" : " FAIL") + " conflicts " + r.conflicts.size() + " refs_differ " + bad.size()
                + " deodex " + (ta - t0) + " tgt-text " + (tc - tb) + " ref " + (t1 - tc)
                + " port " + (t2 - t1) + " assemble " + (t3 - t2) + " ms, " + Dex.JOBS + " jobs");
        return ok;
    }

    /**
     * device for several apps in one VM (the unit pays the VM start, the dexopt of porter.jar and the warm-up
     * once). jobs: one "app odex apk dz report" per line. A failed app does not stop the others.
     */
    static boolean batch(File pk, File jobs, List<String> dirs) throws IOException {
        boolean all = true;
        for (String l : Py.readText(jobs).split("\n")) {
            String[] j = l.trim().split(" +");
            if (j.length != 5)
                continue;
            try {
                all &= device(pk, j[0], new File(j[1]), new File(j[2]), new File(j[3]), new File(j[4]), dirs);
            } catch (Exception e) {
                System.out.println(j[0] + " FAIL " + e);
                all = false;
            }
            System.gc();
        }
        return all;
    }

    // ---- LivePatch serve mode (one JVM per NEMESIS job) ----

    /** the protocol stream (the real stdout); everything printed with System.out goes to the job log */
    static java.io.PrintStream PROTO;

    static synchronized void proto(String s) {
        if (PROTO == null)
            return;
        PROTO.print(s + "\n");
        PROTO.flush();
    }

    static String uiApp;
    static double uiBase, uiSpan, uiLo, uiHi;
    static int uiP;

    /** step != null: new status text "<app>: <step>" (app cut to fit 40 chars). lo..hi: this step's share of 0..99.5 */
    static void phase(String step, double lo, double hi) {
        uiLo = lo;
        uiHi = hi;
        String d = System.getenv("PORTER_UI");
        // PORTER_UI_STATUS=0: an uninstall re-port, NEMESIS keeps "Re-porting <app>" on the row
        if (step != null && d != null && PROTO != null && !"0".equals(System.getenv("PORTER_UI_STATUS"))) {
            String post = ": " + step, n = uiApp;
            int room = Math.max(4, 40 - post.length());
            if (n.length() > room)
                n = n.substring(0, room - 1) + "…";
            try {
                Py.writeText(new File(d, "ui.status"), n + post + "\n");
            } catch (IOException ignored) {
            }
        }
        tick(0);
    }

    /** frac of the current step done; the bar only moves forward, one write per whole percent */
    static synchronized void tick(double frac) {
        String d = System.getenv("PORTER_UI");
        if (d == null || PROTO == null)
            return;
        int p = (int) (uiBase + uiSpan * (uiLo + (uiHi - uiLo) * Math.min(1, frac)) / 100);
        if (p <= uiP)
            return;
        uiP = p;
        try {
            Py.writeText(new File(d, "ui.progress"), "0 100 " + p + "\n");
        } catch (IOException ignored) {
        }
    }

    static void serve(List<String> dirs) throws IOException {
        PROTO = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, "UTF-8");
        System.setOut(System.err);
        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, "UTF-8"));
        for (String l; (l = in.readLine()) != null; ) {
            String[] q = l.split("\t", -1);
            String r;
            if (q.length != 13 || !q[0].equals("port"))
                r = "fail other bad request";
            else
                try {
                    r = serveOne(q, dirs);
                } catch (Throwable e) {
                    e.printStackTrace();
                    r = "fail other " + e;
                }
            proto("R " + r.replace('\n', ' '));
            System.gc(); // drop the trees between targets
        }
    }

    /** "Lcom/a/B;" (+ member key) -> "com.a.B" / "com.a.B.name" */
    static String human(String c, String k) {
        String s = c.length() > 2 ? c.substring(1, c.length() - 1).replace('/', '.') : c;
        return k == null || k.isEmpty() ? s : s + "." + Smali.name0(k);
    }

    static String pathOf(String desc) {
        return desc.substring(1, desc.length() - 1) + ".smali";
    }

    /** request: port app pack stockOdex inputOdex apk outDz pos belowName belowDir base span report */
    static String serveOne(String[] q, List<String> dirs) throws IOException {
        String app = q[1], below = q[8];
        File pk = new File(q[2]), apk = new File(q[5]), dz = new File(q[6]), bdir = new File(q[9]), report = new File(q[12]);
        int pos = Integer.parseInt(q[7]);
        boolean chain = !q[3].equals(q[4]);
        uiApp = app;
        uiBase = Double.parseDouble(q[10]);
        uiSpan = Double.parseDouble(q[11]);
        uiP = (int) uiBase;
        long t0 = System.currentTimeMillis();
        phase("reading stock", 0, 33);
        org.jf.dexlib2.iface.DexFile sdeo = Dex.deodexCached(new File(q[3]), dirs);
        String rd = Dex.lastHit ? "stock cached" : null;
        phase(rd, 33, 42);
        Map<String, String> S = Dex.text(sdeo), tgt = S;
        org.jf.dexlib2.iface.DexFile deo = sdeo;
        if (chain) { // shortcut: the input (the output below us) is deodexed uncached and holds the bar at 42; cache it if chains get common
            sdeo = null;
            deo = Dex.deodexDex(new File(q[4]), dirs);
            tgt = Dex.text(deo);
        }
        long ta = System.currentTimeMillis();
        phase(null, 42, 42.5);
        Map<String, String> stock = unzipTree(new File(pk, "ref/" + app + ".zip"));
        Map<String, String> patched = new LinkedHashMap<String, String>(stock);
        patched.putAll(readTree(new File(pk, "patched/" + app)));
        Map<String, String> refRes = new LinkedHashMap<String, String>(), t = Arsc.resIds(apk), res = new LinkedHashMap<String, String>();
        for (String l : Py.readText(new File(pk, "ref/" + app + ".res")).split("\n")) {
            int sp = l.indexOf(' ');
            if (sp > 0)
                refRes.put(l.substring(0, sp), l.substring(sp + 1));
        }
        for (Map.Entry<String, String> e : refRes.entrySet())
            if (t.containsKey(e.getKey()))
                res.put(e.getValue(), t.get(e.getKey()));
        Map<String, Smali.Cls> A = Smali.fromTexts(stock), P = Smali.fromTexts(patched);
        int same = 0;
        for (Map.Entry<String, String> e : stock.entrySet())
            if (sameText(e.getValue(), S.get(e.getKey())))
                same++;
        File cache = cacheFile(app, stock, S);
        stock = null;
        Map<String, String> cls = new LinkedHashMap<String, String>(), mem = new LinkedHashMap<String, String>();
        String how;
        if (same == A.size()) { // every ref class byte-identical in the stock target: the names are the identity
            how = "identical";
            phase("same build", 42.5, 42.5);
        } else if (cache != null && loadNames(cache, cls, mem)) {
            how = "names hit";
            proto("H names " + cache.getName());
            phase("names cached", 42.5, 42.5);
        } else {
            how = "learned";
            phase("matching names", 42.5, 42.5); // Learn has no ticks: the bar holds here
            Learn m = new Learn(A, Smali.fromTexts(S));
            m.run();
            cls = m.clsMap();
            mem = m.memMap();
            m = null;
            String nw = System.getenv("PORTER_NEW");
            if (cache != null) {
                File f = nw != null ? new File(nw, cache.getName()) : cache;
                saveNames(f, cls, mem);
                if (nw != null)
                    proto("C names " + f.getPath());
            }
        }
        long tl = System.currentTimeMillis();
        Map<String, String> overrides = Port.loadOverrides(new File(pk, "overrides/" + app));
        // renames of added names that clash with what the packages below added: ref class -> new class, "Lc;->key" -> new name
        Map<String, String> rcls = new LinkedHashMap<String, String>(), rmem = new LinkedHashMap<String, String>();
        List<String> renamed = new ArrayList<String>();
        Map<String, Smali.Cls> I0 = null;
        Port.Result r = null;
        Names nm = null;
        for (int round = 0; round < 4; round++) {
            Map<String, String> c2 = new LinkedHashMap<String, String>(cls), m2 = new LinkedHashMap<String, String>(mem);
            c2.putAll(rcls);
            m2.putAll(rmem);
            nm = new Names(c2, m2, res);
            phase("porting", 42.5, 53.5);
            r = Port.port(A, P, Smali.fromTexts(tgt), overrides, nm);
            if (pos < 2)
                break;
            boolean more = false;
            for (String[] is : r.issues) {
                if (!is[0].equals("addm") && !is[0].equals("addf") && !is[0].equals("newcls"))
                    continue;
                if (I0 == null)
                    I0 = Smali.fromTexts(tgt);
                String nn = renameOk(is, P, I0, S, nm, patched, tgt, bdir, apk, pos);
                if (nn == null)
                    continue;
                if (is[0].equals("newcls"))
                    rcls.put(is[1], nn);
                else
                    rmem.put(is[1] + "->" + is[2], nn);
                renamed.add(human(is[1], is[2]) + " -> " + (is[0].equals("newcls") ? human(nn, null) : nn));
                more = true;
            }
            if (!more)
                break;
        }
        long tq = System.currentTimeMillis();
        phase("checking", 53.5, 64.5);
        List<String> bad = RefCheck.check(A, P, chain ? Smali.fromTexts(S) : Smali.fromTexts(tgt), new Names(nm.cls, mem, res));
        r.report.put("refs_differ", bad);
        r.report.put("renamed", renamed);
        r.report.put("names", how);
        String fail = null;
        for (String[] is : r.issues) { // the first issue of the worst kind decides the reply
            String f;
            if (is[0].equals("body")) {
                f = pos > 1 && segChanged(S, tgt, is[3], is[4]) ? "chain Conflicts with " + below + ": " + human(is[1], is[2])
                        : "override " + app + ": no override for " + human(is[1], is[2]);
            } else if (pos > 1 && !is[0].equals("other") && !inStock(S, is, nm, P)) {
                f = "clash Name clash with " + below + ": " + human(is[1], is[2]);
            } else
                f = "other " + r.conflicts.get(r.issues.indexOf(is));
            if (fail == null || rank(f) < rank(fail))
                fail = f;
        }
        if (fail == null && !bad.isEmpty())
            fail = "other refs differ: " + bad.get(0);
        boolean ok = fail == null;
        if (ok) {
            phase("building", 64.5, 99.5);
            File tmp = new File(dz.getPath() + ".tmp");
            Py.writeBytes(tmp, Dz.make(Dex.assembleOver(deo, tgt, r.files, 18)));
            if (!tmp.renameTo(dz))
                throw new IOException("rename " + tmp);
            for (String x : renamed)
                proto("N " + x);
        }
        long t3 = System.currentTimeMillis();
        r.report.put("ms", list("deodex+text " + (ta - t0), "names " + (tl - ta), "port " + (tq - tl), "check+build " + (t3 - tq)));
        Py.writeText(report, Py.json(r.report, 1));
        System.out.println(app + (ok ? " ok " + how : " FAIL " + fail) + " conflicts " + r.conflicts.size() + " refs_differ " + bad.size()
                + " deodex " + (ta - t0) + " names " + (tl - ta) + " port " + (tq - tl) + " build " + (t3 - tq) + " ms");
        return ok ? "ok " + how : "fail " + fail;
    }

    static int rank(String f) {
        return f.startsWith("chain") ? 0 : f.startsWith("clash") ? 1 : f.startsWith("override") ? 2 : 3;
    }

    /** the target's def line tm: its body in the input differs from (or is absent in) the stock target */
    static boolean segChanged(Map<String, String> S, Map<String, String> I, String tcls, String tm) {
        String it = I.get(pathOf(tcls)), st = S.get(pathOf(tcls));
        if (it == null || tm.isEmpty())
            return false;
        if (st == null)
            return true;
        List<String> a = null, b = null;
        for (Smali.Seg s : new Smali.Cls(pathOf(tcls), it).segs)
            if (!s.kind.equals("raw") && s.lines.get(0).equals(tm))
                a = s.lines;
        for (Smali.Seg s : new Smali.Cls(pathOf(tcls), st).segs)
            if (!s.kind.equals("raw") && s.lines.get(0).equals(tm))
                b = s.lines;
        return a != null && (b == null || !Smali.canon(a).equals(Smali.canon(b)));
    }

    /** target name of an addm/addf/newcls issue: "Lc;" for classes, "name+sig" for members */
    static String tgtKey(String[] is, Names nm, Map<String, Smali.Cls> P) {
        if (is[0].equals("newcls"))
            return is[3];
        Smali.Cls pc = P.get(is[1]);
        String def = (is[0].equals("addm") ? pc.methods() : pc.fields()).get(is[2]).get(0);
        String d = nm.defToTgt(is[1], def);
        return is[0].equals("addm") ? Smali.msig(d) : Smali.fsig(d);
    }

    /** the clashing name already exists in the stock target (not something a package below added) */
    static boolean inStock(Map<String, String> S, String[] is, Names nm, Map<String, Smali.Cls> P) {
        String st = S.get(pathOf(is[3]));
        if (is[0].equals("newcls") || st == null)
            return st != null;
        String k = tgtKey(is, nm, P);
        for (Smali.Seg s : new Smali.Cls(pathOf(is[3]), st).segs)
            if (k.equals(s.key))
                return true;
        return false;
    }

    /**
     * L435-477: the new name for an added name that collides with one a package below added, or null when
     * renaming could change behaviour (override, native, reflection, component, JNI from the .so below).
     */
    static String renameOk(String[] is, Map<String, Smali.Cls> P, Map<String, Smali.Cls> I, Map<String, String> S, Names nm,
            Map<String, String> pTexts, Map<String, String> iTexts, File bdir, File apk, int pos) throws IOException {
        if (inStock(S, is, nm, P))
            return null;
        String tk = tgtKey(is, nm, P);
        if (is[0].equals("newcls")) {
            String dotted = human(tk, null), slashed = tk.substring(1, tk.length() - 1);
            if (anyText(pTexts, "\"" + dotted + "\"") || anyText(iTexts, "\"" + dotted + "\"") || inSo(bdir, slashed)
                    || inManifest(apk, dotted))
                return null;
            return tk.substring(0, tk.length() - 1) + "$nms" + pos + ";";
        }
        String n = Smali.name0(tk);
        Smali.Cls pc = P.get(is[1]);
        String def = (is[0].equals("addm") ? pc.methods() : pc.fields()).get(is[2]).get(0);
        if (n.startsWith("<") || def.contains(" native "))
            return null;
        if (is[0].equals("addm") && !def.contains(" private ") && !def.contains(" static ")) {
            Smali.Cls tc = I.get(is[3]);
            Set<String> seen = new HashSet<String>();
            if (tc == null || inherited(I, tc.sup, n, seen))
                return null;
            for (String x : ifaces(tc))
                if (inherited(I, x, n, seen))
                    return null;
        }
        if (subDeclares(P, is[1], Smali.name0(is[2])) || subDeclares(I, is[3], n))
            return null;
        if (anyText(pTexts, "\"" + n + "\"") || anyText(iTexts, "\"" + n + "\"") || inSo(bdir, n))
            return null;
        return n + "$nms" + pos;
    }

    static List<String> ifaces(Smali.Cls c) {
        List<String> out = new ArrayList<String>();
        for (Smali.Seg s : c.segs)
            if (s.kind.equals("raw"))
                for (String l : s.lines)
                    if (l.startsWith(".implements "))
                        out.add(Py.split(l).get(1));
        return out;
    }

    /** c or a class/interface above it declares a method named n; a class we cannot see counts as yes */
    static boolean inherited(Map<String, Smali.Cls> tree, String c, String n, Set<String> seen) {
        if (c == null || !seen.add(c))
            return false;
        Smali.Cls k = tree.get(c);
        if (k == null) {
            try {
                return declares(Class.forName(human(c, null), false, null), n);
            } catch (Throwable e) {
                return true;
            }
        }
        for (Smali.Seg s : k.segs)
            if (s.kind.equals("method") && Smali.name0(s.key).equals(n))
                return true;
        for (String x : ifaces(k))
            if (inherited(tree, x, n, seen))
                return true;
        return inherited(tree, k.sup, n, seen);
    }

    static boolean declares(Class<?> k, String n) {
        for (; k != null; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods())
                if (m.getName().equals(n))
                    return true;
            for (Class<?> i : k.getInterfaces())
                if (declares(i, n))
                    return true;
        }
        return false;
    }

    /** a class below c in tree declares a member named n */
    static boolean subDeclares(Map<String, Smali.Cls> tree, String c, String n) {
        for (Smali.Cls x : tree.values()) {
            if (x.name.equals(c))
                continue;
            boolean sub = false;
            for (String s = x.sup; s != null && !sub; s = tree.containsKey(s) ? tree.get(s).sup : null)
                sub = s.equals(c);
            if (sub)
                for (Smali.Seg s : x.segs)
                    if (!s.kind.equals("raw") && Smali.name0(s.key).equals(n))
                        return true;
        }
        return false;
    }

    static boolean anyText(Map<String, String> texts, String needle) {
        for (String s : texts.values())
            if (s.contains(needle))
                return true;
        return false;
    }

    static boolean has(byte[] h, byte[] n) {
        outer:
        for (int i = 0; i + n.length <= h.length; i++) {
            for (int j = 0; j < n.length; j++)
                if (h[i + j] != n[j])
                    continue outer;
            return true;
        }
        return false;
    }

    /** n in any .so of the package below (JNI names, RegisterNatives tables) */
    static boolean inSo(File dir, String n) throws IOException {
        String[] names = dir.list();
        if (names == null)
            return false;
        for (String x : names) {
            File f = new File(dir, x);
            if (f.isDirectory() ? inSo(f, n) : x.endsWith(".so") && has(Py.readBytes(f), n.getBytes(Py.UTF8)))
                return true;
        }
        return false;
    }

    /** the dotted class name (or its ".Simple" short form) in the apk's binary AndroidManifest.xml */
    static boolean inManifest(File apk, String dotted) throws IOException {
        java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk);
        try {
            java.util.zip.ZipEntry e = z.getEntry("AndroidManifest.xml");
            if (e == null)
                return false;
            byte[] m = Py.readBytes(z.getInputStream(e));
            String simple = dotted.substring(dotted.lastIndexOf('.'));
            for (String s : new String[] {dotted, simple})
                if (has(m, s.getBytes(Py.UTF8)) || has(m, s.getBytes("UTF-16LE")))
                    return true;
            return false;
        } finally {
            z.close();
        }
    }

    /** relative path -> text of every .smali under root (empty if root is missing) */
    static Map<String, String> readTree(File root) throws IOException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        readTree(root, "", out);
        return out;
    }

    private static void readTree(File dir, String rel, Map<String, String> out) throws IOException {
        String[] names = dir.list();
        if (names == null)
            return;
        Arrays.sort(names);
        for (String n : names) {
            File f = new File(dir, n);
            if (f.isDirectory())
                readTree(f, rel + n + "/", out);
            else if (n.endsWith(".smali"))
                out.put(rel + n, Py.readText(f));
        }
    }

    static byte[] zipTree(Map<String, String> files) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(b);
        z.setLevel(9);
        for (Map.Entry<String, String> f : files.entrySet()) {
            z.putNextEntry(new java.util.zip.ZipEntry(f.getKey()));
            z.write(f.getValue().getBytes(Py.UTF8));
        }
        z.close();
        return b.toByteArray();
    }

    /** path -> text. ZipFile, not ZipInputStream: the ref zips use data descriptors,
     *  and Dalvik's ZipInputStream throws EOFException on those. */
    static Map<String, String> unzipTree(File zip) throws IOException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                java.io.InputStream in = z.getInputStream(e);
                try {
                    out.put(e.getName(), new String(Py.readBytes(in), Py.UTF8));
                } finally {
                    in.close();
                }
            }
        } finally {
            z.close();
        }
        return out;
    }

    static void writeTree(File root, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> f : files.entrySet())
            Py.writeText(new File(root, f.getKey()), f.getValue());
    }

    static List<String> list(String... s) {
        return new ArrayList<String>(Arrays.asList(s));
    }
}
