package com.nemesis.manager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class Cli {
    public static class Result {
        public int rc;
        public String out;

        Result(int rc, String out) {
            this.rc = rc;
            this.out = out != null ? out : "";
        }
    }

    interface Lines {
        void line(String s);
    }

    public static Result exec(String... args) {
        return exec(null, args);
    }

    /** Same as exec, and each output line also goes to cb as it arrives. */
    public static Result exec(Lines cb, String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "/system/xbin/nemesis";
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (cb == null) continue;
                for (int i = 0; i < n; i++) {
                    if (buf[i] != '\n') {
                        line.write(buf[i]);
                        continue;
                    }
                    cb.line(line.toString("UTF-8"));
                    line.reset();
                }
            }
            in.close();
            int rc = p.waitFor();
            return new Result(rc, bos.toString("UTF-8"));
        } catch (Exception e) {
            return new Result(1, "ERR " + e.getMessage());
        }
    }

    public static String run(String... args) {
        return exec(args).out;
    }

    /** key=value lines from usb-info and pkg-info. */
    public static java.util.Map<String, String> fields(String out) {
        java.util.HashMap<String, String> m = new java.util.HashMap<String, String>();
        if (out == null) return m;
        String[] lines = out.split("\n");
        for (int i = 0; i < lines.length; i++) {
            int eq = lines[i].indexOf('=');
            if (eq > 0) m.put(lines[i].substring(0, eq).trim(), lines[i].substring(eq + 1).trim());
        }
        return m;
    }

    public static int runRc(String... args) {
        return exec(args).rc;
    }
}
