package com.nemesis.manager;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** The native install queue, read straight from its 0644 job files. */
final class Queue {
    static final String DIR = "/data/nemesis/queue";
    static final String SESSION = "/data/nemesis/usb-session";

    static final class Job {
        int seq;
        String action = "";
        String id = "";
        String name = "";
        String state = "";
        int rc;
        boolean unclean;

        boolean active() {
            return "queued".equals(state) || "running".equals(state);
        }
    }

    private Queue() {
    }

    /** All jobs in seq order. */
    static List<Job> list() {
        List<Job> out = new ArrayList<Job>();
        String[] names = new File(DIR).list();
        if (names == null) return out;
        Arrays.sort(names);
        for (int i = 0; i < names.length; i++) {
            if (!names[i].endsWith(".job")) continue;
            Map<String, String> m = Cli.fields(read(new File(DIR, names[i]), 4096));
            Job j = new Job();
            try {
                j.seq = Integer.parseInt(names[i].substring(0, names[i].length() - 4));
                j.rc = Integer.parseInt(or(m.get("rc"), "0"));
            } catch (NumberFormatException e) {
                continue;
            }
            j.action = or(m.get("action"), "");
            j.id = or(m.get("id"), "");
            j.name = or(m.get("name"), j.id);
            j.state = or(m.get("state"), "");
            j.unclean = "1".equals(m.get("unclean"));
            out.add(j);
        }
        return out;
    }

    static int count(List<Job> jobs, boolean active) {
        int n = 0;
        for (int i = 0; i < jobs.size(); i++) {
            Job j = jobs.get(i);
            if (j.active() == active) n++;
        }
        return n;
    }

    /** The seq the runner is on now, or -1. ui.job is "<seq> <id>". */
    static int runningSeq() {
        String s = firstLine(new File(SESSION, "ui.job"));
        if (s == null) return -1;
        try {
            return Integer.parseInt(s.split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The last line the job printed: the reason a failed job gives. */
    static String lastLine(int seq) {
        return lastLine(read(new File(DIR, String.format("%08d.out", seq)), 65536));
    }

    static String lastLine(String text) {
        String[] lines = text.trim().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String ln = lines[i].trim();
            if (ln.length() > 0) return ln;
        }
        return "";
    }

    static String firstLine(File f) {
        String s = read(f, 1024);
        if (s.length() == 0) return null;
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).trim();
    }

    /** The end of the file when it is longer than cap. */
    static String read(File f, int cap) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                long skip = f.length() - cap;
                if (skip > 0) in.skip(skip);
                byte[] buf = new byte[cap];
                int n, len = 0;
                while (len < cap && (n = in.read(buf, len, cap - len)) > 0) len += n;
                return new String(buf, 0, len, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static String or(String v, String d) {
        return v == null || v.length() == 0 ? d : v;
    }
}
