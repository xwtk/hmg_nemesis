package com.nemesis.manager;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes uncaught exceptions where the log collector picks them up.
 *  Installed from the activities: the manifest names no Application class. */
final class CrashLog {
    private static File own;
    private static boolean installed;

    static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        try {
            own = new File(c.getFilesDir(), "crash.log");
        } catch (Throwable ignored) {
        }
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                record(e);
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    static void record(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        e.printStackTrace(pw);
        pw.flush();
        byte[] data;
        try {
            data = sw.toString().getBytes("UTF-8");
        } catch (Exception x) {
            return;
        }
        append(new File("/data/nemesis/logs/manager/crash.log"), data);
        if (own != null) append(own, data);
    }

    /** One timestamped line in /data/nemesis/logs/manager/<name>. */
    static void note(String name, String line) {
        String t = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        try {
            append(new File("/data/nemesis/logs/manager/" + name),
                    (t + " " + line + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    private static void append(File f, byte[] data) {
        try {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            if (f.length() > 256 * 1024) f.delete();
            FileOutputStream fos;
            try {
                fos = new FileOutputStream(f, true);
            } catch (java.io.FileNotFoundException denied) {
                // A reinstall gets a new uid; the old one's file is read-only to us.
                // The directory is world-writable, so replace the file.
                f.delete();
                fos = new FileOutputStream(f, true);
            }
            fos.write(data);
            fos.close();
            f.setReadable(true, false);
        } catch (Exception ignored) {
        }
    }
}
