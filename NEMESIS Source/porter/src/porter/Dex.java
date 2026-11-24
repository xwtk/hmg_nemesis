package porter;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.tree.CommonTree;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.baksmali.formatter.BaksmaliWriter;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.VersionMap;
import org.jf.dexlib2.analysis.ClassPath;
import org.jf.dexlib2.analysis.ClassPathResolver;
import org.jf.dexlib2.analysis.InlineMethodResolver;
import org.jf.dexlib2.analysis.MethodAnalyzer;
import org.jf.dexlib2.analysis.UnresolvedOdexInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.dexbacked.DexBackedOdexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodImplementation;
import org.jf.dexlib2.util.SyntheticAccessorResolver;
import org.jf.dexlib2.writer.builder.DexBuilder;
import org.jf.dexlib2.writer.pool.DexPool;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.smali.smaliFlexLexer;
import org.jf.smali.smaliParser;
import org.jf.smali.smaliTreeWalker;

/** baksmali x / smali a / baksmali d with the command-line defaults, on in-memory trees (path -> text). */
final class Dex {
    private Dex() {}

    /** smali/baksmali -j: one class per task, shared builder/options, as the command-line tools do */
    static final int JOBS = Math.max(1, Runtime.getRuntime().availableProcessors());

    static <T> List<T> parallel(List<Callable<T>> in) throws IOException {
        ExecutorService ex = Executors.newFixedThreadPool(JOBS);
        final int total = in.size();
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        List<Callable<T>> tasks = new ArrayList<Callable<T>>(total);
        for (final Callable<T> t : in)
            tasks.add(new Callable<T>() { // the Manager's bar moves per class
                public T call() throws Exception {
                    T r = t.call();
                    Main.tick((double) done.incrementAndGet() / total);
                    return r;
                }
            });
        try {
            List<T> out = new ArrayList<T>(tasks.size());
            for (Future<T> f : ex.invokeAll(tasks))
                out.add(f.get());
            return out;
        } catch (InterruptedException e) {
            throw new IOException(e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException)
                throw (IOException) c;
            if (c instanceof RuntimeException)
                throw (RuntimeException) c;
            throw new IOException(c);
        } finally {
            ex.shutdown();
        }
    }

    /** tgt/<tag>/smali/<App> from an odex: what fetch.py does (deodex, assemble -a 17, disassemble -a 17) */
    static Map<String, String> target(File odex, List<String> dirs) throws IOException {
        return disassemble(assemble(deodex(odex, dirs), 17), 17);
    }

    /** the odex as dexlib2 sees it, plus the options baksmali deodexes it with */
    private static final class Opened {
        DexBackedDexFile dex;
        BaksmaliOptions o;
    }

    private static Opened open(File odex, List<String> dirs) throws IOException {
        Opcodes op = Opcodes.forApi(17);
        MultiDexContainer<? extends DexBackedDexFile> c = DexFileFactory.loadDexContainer(odex, op);
        if (c.getDexEntryNames().size() != 1)
            throw new IOException(odex + ": " + c.getDexEntryNames().size() + " dex entries");
        MultiDexContainer.DexEntry<? extends DexBackedDexFile> e = c.getEntry(c.getDexEntryNames().get(0));
        Opened x = new Opened();
        x.dex = e.getDexFile();
        if (!(x.dex instanceof DexBackedOdexFile))
            throw new IOException(odex + ": not an odex");
        x.o = options(x.dex);
        ClassPathResolver r = new ClassPathResolver(dirs, Collections.<String>emptyList(), e);
        x.o.classPath = new ClassPath(r.getResolvedClassProviders(), false, VersionMap.mapApiToArtVersion(op.api));
        x.o.deodex = true;
        x.o.inlineResolver = InlineMethodResolver.createInlineMethodResolver(((DexBackedOdexFile) x.dex).getOdexVersion());
        return x;
    }

    /** baksmali x -a 17 -d <dirs>; the odex's own dependency list is the boot class path */
    static Map<String, String> deodex(File odex, List<String> dirs) throws IOException {
        Opened x = open(odex, dirs);
        return write(x.dex, x.o);
    }

    /**
     * The same deodexing as deodex(), kept as classes: method bodies come from MethodAnalyzer, which is
     * what baksmali prints. Classes that the patch leaves alone go to the new dex from here, with no
     * smali text in between. A class with an instruction the analyzer cannot resolve (baksmali prints a
     * throw, which changes the code size) takes the text route, as before.
     */
    static DexFile deodexDex(File odex, List<String> dirs) throws IOException {
        final Opened x = open(odex, dirs);
        final List<ClassDef> in = new ArrayList<ClassDef>(x.dex.getClasses());
        List<Callable<ClassDef>> tasks = new ArrayList<Callable<ClassDef>>();
        for (final ClassDef cd : in)
            tasks.add(new Callable<ClassDef>() {
                public ClassDef call() throws IOException {
                    return deodexClass(cd, x.o);
                }
            });
        List<ClassDef> done = parallel(tasks), out = new ArrayList<ClassDef>(), tricky = new ArrayList<ClassDef>();
        for (int i = 0; i < in.size(); i++)
            if (done.get(i) != null)
                out.add(done.get(i));
            else
                tricky.add(in.get(i));
        if (!tricky.isEmpty())
            out.addAll(new DexBackedDexFile(Opcodes.forApi(17), assemble(write(dexFile(tricky), x.o), 17)).getClasses());
        return dexFile(out);
    }

    /**
     * deodexDex through PORTER_DEODEX=<dir>: <stock odex md5>.dex holds the deodexed classes, so a later
     * port of the same stock file skips the analyzer. Entries are only deleted by LivePatch's cache trim.
     */
    static boolean lastHit;

    static DexFile deodexCached(File odex, List<String> dirs) throws IOException {
        String dir = System.getenv("PORTER_DEODEX");
        if (dir == null)
            return deodexDex(odex, dirs);
        StringBuilder h = new StringBuilder();
        try {
            for (byte b : java.security.MessageDigest.getInstance("MD5").digest(Py.readBytes(odex)))
                h.append(String.format("%02x", b & 255));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        File f = new File(dir, h + ".dex");
        lastHit = f.isFile();
        if (lastHit) {
            Main.proto("H deodex " + f.getName()); // NEMESIS keeps the cache index (last hit, size)
            return new DexBackedDexFile(Opcodes.forApi(17), Py.readBytes(f));
        }
        String nw = System.getenv("PORTER_NEW"); // serve: written to tmp/new, NEMESIS moves it into the cache
        if (nw != null)
            f = new File(nw, h + ".dex");
        DexFile d = deodexDex(odex, dirs);
        DexPool pool = new DexPool(Opcodes.forApi(17));
        for (ClassDef cd : d.getClasses())
            pool.internClass(cd);
        MemoryDataStore m = new MemoryDataStore();
        pool.writeTo(m);
        new File(dir).mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        Py.writeBytes(tmp, m.getData());
        if (!tmp.renameTo(f))
            tmp.delete(); // a cache miss next time, nothing worse
        else if (nw != null)
            Main.proto("C deodex " + f.getPath());
        // the reloaded form, so a cold run and a hit feed the same classes to the rest of the port
        return new DexBackedDexFile(Opcodes.forApi(17), m.getData());
    }

    private static DexFile dexFile(final List<ClassDef> classes) {
        final java.util.Set<ClassDef> set = new java.util.LinkedHashSet<ClassDef>(classes);
        return new DexFile() {
            public java.util.Set<? extends ClassDef> getClasses() {
                return set;
            }

            public Opcodes getOpcodes() {
                return Opcodes.forApi(17);
            }
        };
    }

    /** null when a method of cd has an instruction the analyzer cannot resolve */
    private static ClassDef deodexClass(ClassDef cd, BaksmaliOptions o) throws IOException {
        List<Method> direct = new ArrayList<Method>(), virt = new ArrayList<Method>();
        for (Method m : cd.getDirectMethods()) {
            Method d = deodexMethod(m, o);
            if (d == null)
                return null;
            direct.add(d);
        }
        for (Method m : cd.getVirtualMethods()) {
            Method d = deodexMethod(m, o);
            if (d == null)
                return null;
            virt.add(d);
        }
        return new ImmutableClassDef(cd.getType(), cd.getAccessFlags(), cd.getSuperclass(), cd.getInterfaces(),
                cd.getSourceFile(), cd.getAnnotations(), cd.getStaticFields(), cd.getInstanceFields(), direct, virt);
    }

    /** null: see deodexClass */
    private static Method deodexMethod(Method m, BaksmaliOptions o) throws IOException {
        MethodImplementation impl = m.getImplementation();
        if (impl == null)
            return m;
        MethodAnalyzer a = new MethodAnalyzer(o.classPath, m, o.inlineResolver, false);
        if (a.getAnalysisException() != null)
            throw new IOException(m.getDefiningClass() + "->" + m.getName() + ": " + a.getAnalysisException());
        for (Instruction i : a.getInstructions())
            if (i instanceof UnresolvedOdexInstruction)
                return null;
        return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(),
                new ImmutableMethodImplementation(impl.getRegisterCount(), a.getInstructions(), impl.getTryBlocks(),
                        impl.getDebugItems()));
    }

    /** baksmali d of a dex file that is already in memory (classes, not bytes) */
    static Map<String, String> text(DexFile d) throws IOException {
        return write(d, options(d));
    }

    static Map<String, String> disassemble(byte[] dex, int api) throws IOException {
        DexBackedDexFile d = new DexBackedDexFile(Opcodes.forApi(api), dex);
        return write(d, options(d));
    }

    private static BaksmaliOptions options(DexFile d) {
        BaksmaliOptions o = new BaksmaliOptions();
        o.syntheticAccessorResolver = new SyntheticAccessorResolver(d.getOpcodes(), d.getClasses());
        return o;
    }

    private static Map<String, String> write(DexFile d, final BaksmaliOptions o) throws IOException {
        List<ClassDef> classes = new ArrayList<ClassDef>(d.getClasses());
        Collections.sort(classes, new java.util.Comparator<ClassDef>() {
            public int compare(ClassDef a, ClassDef b) { // Ordering.natural(): BaseTypeReference compares types
                return a.getType().compareTo(b.getType());
            }
        });
        List<String> paths = new ArrayList<String>();
        List<Callable<String>> tasks = new ArrayList<Callable<String>>();
        for (final ClassDef cd : classes) {
            String t = cd.getType();
            // shortcut: baksmali's ClassFileNameHandler renames only on a case-insensitive output dir (the
            // trees here are made on ext4, and a.smali/A.smali coexist) or for names over 255 bytes; port it if
            // a tree is ever made on a case-insensitive file system.
            String path = t.substring(1, t.length() - 1) + ".smali";
            for (String part : path.split("/"))
                if (part.getBytes(Py.UTF8).length > 245)
                    throw new IOException("class name too long for the simple path rule: " + t);
            paths.add(path);
            tasks.add(new Callable<String>() {
                public String call() throws IOException {
                    StringWriter w = new StringWriter();
                    BaksmaliWriter bw = new BaksmaliWriter(w, null);
                    new ClassDefinition(o, cd).writeTo(bw);
                    bw.close();
                    return w.toString();
                }
            });
        }
        List<String> texts = parallel(tasks);
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < paths.size(); i++)
            if (out.put(paths.get(i), texts.get(i)) != null)
                throw new IOException("duplicate class path " + paths.get(i));
        return out;
    }

    /**
     * smali a -a <api> over files, where classes whose text equals baseText's are copied from base (baseText
     * is base disassembled) instead of being reassembled. Same classes as assemble(files, api); layout may differ.
     */
    static byte[] assembleOver(DexFile base, Map<String, String> baseText, Map<String, String> files, int api)
            throws IOException {
        Map<String, String> changed = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> f : files.entrySet())
            if (!f.getValue().equals(baseText.get(f.getKey())))
                changed.put(f.getKey(), f.getValue());
        DexPool pool = new DexPool(Opcodes.forApi(api));
        for (ClassDef cd : base.getClasses()) {
            String p = cd.getType().substring(1, cd.getType().length() - 1) + ".smali";
            if (files.containsKey(p) && !changed.containsKey(p))
                pool.internClass(cd);
        }
        for (ClassDef cd : new DexBackedDexFile(Opcodes.forApi(api), assemble(changed, api)).getClasses())
            pool.internClass(cd);
        MemoryDataStore m = new MemoryDataStore();
        pool.writeTo(m);
        return m.getData();
    }

    /** smali a -a <api> over the tree; throws on any syntax error */
    static byte[] assemble(Map<String, String> files, final int api) throws IOException {
        final DexBuilder b = new DexBuilder(Opcodes.forApi(api));
        List<Callable<Void>> tasks = new ArrayList<Callable<Void>>();
        for (final Map.Entry<String, String> f : files.entrySet())
            tasks.add(new Callable<Void>() {
                public Void call() throws IOException {
                    assembleOne(b, f.getKey(), f.getValue(), api);
                    return null;
                }
            });
        parallel(tasks);
        MemoryDataStore m = new MemoryDataStore();
        b.writeTo(m);
        return m.getData();
    }

    private static void assembleOne(DexBuilder b, String path, String text, int api) throws IOException {
        try {
            smaliFlexLexer lexer = new smaliFlexLexer(new StringReader(text), api);
            lexer.setSourceFile(new File(path));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            smaliParser parser = new smaliParser(tokens);
            parser.setVerboseErrors(false);
            parser.setAllowOdex(false);
            parser.setApiLevel(api);
            smaliParser.smali_file_return res = parser.smali_file();
            if (parser.getNumberOfSyntaxErrors() > 0 || lexer.getNumberOfSyntaxErrors() > 0)
                throw new IOException(path + ": syntax error");
            CommonTreeNodeStream ts = new CommonTreeNodeStream((CommonTree) res.getTree());
            ts.setTokenStream(tokens);
            smaliTreeWalker g = new smaliTreeWalker(ts);
            g.setApiLevel(api);
            g.setVerboseErrors(false);
            g.setDexBuilder(b);
            g.smali_file();
            if (g.getNumberOfSyntaxErrors() != 0)
                throw new IOException(path + ": tree error");
        } catch (org.antlr.runtime.RecognitionException ex) {
            throw new IOException(path + ": " + ex, ex);
        }
    }
}
