package porter;
import java.util.*;
public class RenameTest {
    static String cls(String body) {
        return ".class public Lx/Foo;\n.super Ljava/lang/Object;\n.source \"Foo.java\"\n\n" + body;
    }
    static final String STOCK = ".method public m0()V\n    .locals 0\n    return-void\n.end method\n";
    static String added(String who) {
        return "\n.field public x:I\n\n.method public foo()V\n    .locals 1\n    const-string v0, \"" + who + "\"\n    return-void\n.end method\n";
    }
    static final String BCALL = "\n.method public bar()V\n    .locals 1\n    invoke-virtual {p0}, Lx/Foo;->foo()V\n    iget v0, p0, Lx/Foo;->x:I\n    return-void\n.end method\n";
    public static void main(String[] a) {
        Map<String,String> st = new HashMap<String,String>(); st.put("x/Foo.smali", cls(STOCK));
        Map<String,String> pk = new HashMap<String,String>(); pk.put("x/Foo.smali", cls(STOCK + added("B") + BCALL));
        Map<String,String> tg = new HashMap<String,String>(); tg.put("x/Foo.smali", cls(STOCK + added("A")));
        for (int mode = 0; mode < 2; mode++) {
            Map<String,String> mem = new LinkedHashMap<String,String>();
            if (mode == 1) { mem.put("Lx/Foo;->foo()V", "foo$nms1"); mem.put("Lx/Foo;->x:I", "x$nms1"); }
            Names n = new Names(new LinkedHashMap<String,String>(), mem, new LinkedHashMap<String,String>());
            Port.Result r = Port.port(Smali.fromTexts(st), Smali.fromTexts(pk), Smali.fromTexts(tg), new HashMap<String,String>(), n);
            System.out.println("=== mode " + mode + (mode == 1 ? " (renamed)" : " (no rename)") + " conflicts=" + r.conflicts + " report=" + r.report);
            for (Map.Entry<String,String> e : r.files.entrySet()) System.out.println(e.getValue());
        }
    }
}
