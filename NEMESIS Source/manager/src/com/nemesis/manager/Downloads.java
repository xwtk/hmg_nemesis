package com.nemesis.manager;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Store downloads, three at a time. A group is one user request: its dependencies, then
 *  the package. Each finished zip goes to the native queue in group order, so the queue
 *  always installs dependencies first. A journal in filesDir outlives a reboot. */
final class Downloads {
    static final int DL = 0;
    static final int FAILED = 1;
    static final int INTERRUPTED = 2;
    private static final String JOURNAL = "downloads.txt";
    private static final String PREFIX = "store-package-";

    static final class Item {
        long group;
        int idx;
        boolean last;
        boolean risk;
        String id = "";
        String name = "";
        String url = "";
        String sha256 = "";
        String local = "";
        String favicon = "";
        volatile int state = DL;
        volatile boolean started;
        volatile boolean done;
        volatile String error = "";
        final long[] prog = {0, -1};
        AtomicBoolean cancel = new AtomicBoolean();
        File file;
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final List<Item> ITEMS = new CopyOnWriteArrayList<Item>();
    /** Hand-off to the queue runs one at a time, so groups do not interleave. */
    private static final Object HANDOFF = new Object();
    private static boolean loaded;

    private Downloads() {
    }

    static List<Item> items(Context c) {
        load(c);
        return new ArrayList<Item>(ITEMS);
    }

    static boolean hasInterrupted(Context c) {
        List<Item> all = items(c);
        for (int i = 0; i < all.size(); i++) if (all.get(i).state == INTERRUPTED) return true;
        return false;
    }

    /** steps in install order; the last is what the user asked for, and only it takes risk. */
    static void start(Context c, List<StoreList.Entry> steps, boolean risk) {
        load(c);
        long g = System.currentTimeMillis();
        List<Item> mine = new ArrayList<Item>();
        for (int k = 0; k < steps.size(); k++) {
            StoreList.Entry e = steps.get(k);
            Item it = new Item();
            it.group = g;
            it.idx = k;
            it.last = k == steps.size() - 1;
            it.risk = risk && it.last;
            it.id = e.id;
            it.name = e.name.length() > 0 ? e.name : e.id;
            it.url = e.url == null ? "" : e.url;
            it.sha256 = e.sha256 == null ? "" : e.sha256;
            it.local = e.localZip == null ? "" : e.localZip;
            it.favicon = e.favicon;
            mine.add(it);
        }
        ITEMS.addAll(mine);
        save(c);
        for (int k = 0; k < mine.size(); k++) begin(c, mine.get(k));
    }

    /** Continue an interrupted or failed group from its first item still here. */
    static void resume(Context c, long group) {
        for (Item it : items(c)) {
            if (it.group != group || it.state == DL) continue;
            it.state = DL;
            it.error = "";
            it.cancel = new AtomicBoolean();
            begin(c, it);
        }
        save(c);
    }

    static void resumeAll(Context c) {
        for (Item it : items(c)) if (it.state == INTERRUPTED) resume(c, it.group);
    }

    /** Cancel or delete: the rest of the group cannot install without it. */
    static void drop(Context c, long group) {
        for (Item it : ITEMS) {
            if (it.group != group) continue;
            it.cancel.set(true);
            if (it.file != null) it.file.delete();
            ITEMS.remove(it);
        }
        save(c);
    }

    private static void begin(final Context c, final Item it) {
        it.started = false;
        it.done = false;
        it.prog[0] = 0;
        it.prog[1] = -1;
        if (it.local.length() > 0) {
            it.started = true;
            it.done = true;
            flushAsync(c, it.group);
            return;
        }
        final AtomicBoolean cancel = it.cancel;
        POOL.execute(new Runnable() {
            public void run() {
                if (cancel.get()) return;
                it.started = true;
                File f = null;
                String err = null;
                try {
                    f = Portal.download(c, it.url, it.sha256, PREFIX + it.group + "-" + it.idx + ".zip",
                            cancel, it.prog);
                    if (f == null) err = c.getString(R.string.download_bad);
                } catch (Portal.Cancelled x) {
                    return;
                } catch (Throwable t) {
                    CrashLog.note("update.log", "store download " + it.id + ": " + t);
                    err = c.getString(R.string.download_fail);
                }
                if (cancel.get() || !ITEMS.contains(it)) {
                    if (f != null) f.delete();
                    return;
                }
                if (err != null) {
                    fail(c, it, err);
                    return;
                }
                it.file = f;
                it.done = true;
                flush(c, it.group);
            }
        });
    }

    private static void flushAsync(final Context c, final long group) {
        new Thread(new Runnable() {
            public void run() {
                flush(c, group);
            }
        }).start();
    }

    /** Hand finished items to the queue in order, up to the first one not finished. */
    private static void flush(Context c, long group) {
        synchronized (HANDOFF) {
            for (Item it : ITEMS) {
                if (it.group != group) continue;
                if (it.state != DL || !it.done || it.cancel.get()) return;
                List<String> args = new ArrayList<String>();
                args.add("queue");
                args.add("add");
                if (it.risk) args.add("--accept-risk");
                // A download is ours to move; a stick's zip is copied so the stick may come out.
                if (it.file != null) args.add("--move");
                args.add(it.file != null ? it.file.getPath() : it.local);
                Cli.Result r = Cli.exec(args.toArray(new String[args.size()]));
                if (r.rc != 0) {
                    CrashLog.note("update.log", "queue add " + it.id + " rc=" + r.rc + " " + r.out);
                    String why = Queue.lastLine(r.out);
                    fail(c, it, why.length() > 0 ? why : c.getString(R.string.result_fail));
                    return;
                }
                if (it.file != null) it.file.delete();
                ITEMS.remove(it);
                save(c);
            }
        }
    }

    /** This item and everything after it in the group stop; the user sees why on the first. */
    private static void fail(Context c, Item bad, String why) {
        for (Item it : ITEMS) {
            if (it.group != bad.group || it.idx < bad.idx) continue;
            it.cancel.set(true);
            if (it.file != null) it.file.delete();
            it.file = null;
            it.state = FAILED;
            it.error = it == bad ? why : c.getString(R.string.dep_failed).replace("%s", bad.name);
        }
        save(c);
    }

    private static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        // A zip from before this process cannot be trusted to be whole: download again.
        File[] old = c.getCacheDir().listFiles();
        for (int k = 0; old != null && k < old.length; k++) {
            if (old[k].getName().startsWith(PREFIX)) old[k].delete();
        }
        String[] lines = Queue.read(new File(c.getFilesDir(), JOURNAL), 1 << 20).split("\n");
        for (int k = 0; k < lines.length; k++) {
            String[] p = lines[k].split("\t", -1);
            if (p.length < 10) continue;
            Item it = new Item();
            try {
                it.group = Long.parseLong(p[0]);
                it.idx = Integer.parseInt(p[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            it.last = "1".equals(p[2]);
            it.risk = "1".equals(p[3]);
            it.id = p[4];
            it.name = p[5];
            it.url = p[6];
            it.sha256 = p[7];
            it.local = p[8];
            it.favicon = p[9];
            it.state = INTERRUPTED;
            ITEMS.add(it);
        }
    }

    private static synchronized void save(Context c) {
        StringBuilder sb = new StringBuilder();
        for (Item it : ITEMS) {
            sb.append(it.group).append('\t').append(it.idx).append('\t').append(it.last ? 1 : 0)
                    .append('\t').append(it.risk ? 1 : 0).append('\t').append(clean(it.id))
                    .append('\t').append(clean(it.name)).append('\t').append(clean(it.url))
                    .append('\t').append(clean(it.sha256)).append('\t').append(clean(it.local))
                    .append('\t').append(clean(it.favicon)).append('\n');
        }
        File f = new File(c.getFilesDir(), JOURNAL);
        File tmp = new File(c.getFilesDir(), JOURNAL + ".tmp");
        try {
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(sb.toString().getBytes("UTF-8"));
                os.getFD().sync();
            } finally {
                os.close();
            }
            tmp.renameTo(f);
        } catch (Throwable t) {
            CrashLog.note("update.log", "download journal: " + t);
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }
}
