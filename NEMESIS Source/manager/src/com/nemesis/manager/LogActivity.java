package com.nemesis.manager;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;

public class LogActivity extends Activity {
    private static final String NEMESIS = "nemesis";
    private static final String LOGCAT = "logcat";
    /** NEMESIS, LOGCAT, or "<pkg>/<svc>" of a per-service log. */
    private String current = NEMESIS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.ensureWatchdog();
        if (!Ui.setLayout(this, R.layout.activity_log)) {
            Ui.fallbackShell(this, false, true, true);
        }
        Ui.bindTitle(this, R.string.logs);
        Button a = (Button) findViewById(R.id.btn_a);
        if (a != null) {
            a.setText(R.string.copy_usb);
            a.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    new Thread(new Runnable() {
                        public void run() {
                            boolean ok = copyAll();
                            final int msg = ok ? R.string.copied : R.string.copy_fail;
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    Ui.toast(LogActivity.this, msg);
                                }
                            });
                        }
                    }).start();
                }
            });
        }
        bindSources();
        load();
    }

    private void load() {
        final String src = current;
        new Thread(new Runnable() {
            public void run() {
                String dump;
                if (LOGCAT.equals(src)) {
                    dump = Cli.run("log", "logcat", "-d", "-v", "time", "-t", "1500");
                } else if (NEMESIS.equals(src)) {
                    dump = Cli.run("log", "dump");
                    if (dump == null || dump.trim().length() == 0) {
                        dump = readFile(new File("/data/nemesis/nemesis.log"));
                    }
                    if (dump == null || dump.trim().length() == 0) {
                        dump = readFile(new File("/data/local/tmp/nemesis/nemesis.log"));
                    }
                } else {
                    dump = Cli.run("log", "service", src);
                }
                if (dump == null || dump.trim().length() == 0) dump = "(no log yet)";
                final String text = reverseLines(dump);
                runOnUiThread(new Runnable() {
                    public void run() {
                        TextView tv = (TextView) findViewById(R.id.log_text);
                        if (tv != null && src.equals(current)) tv.setText(text);
                    }
                });
            }
        }).start();
    }

    /** Newest line first so the latest entry is at the top. */
    private static String reverseLines(String s) {
        String[] lines = s.split("\n", -1);
        // Trim a trailing empty line so the output doesn't start with a blank.
        int n = lines.length;
        if (n > 0 && lines[n - 1].length() == 0) n--;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = n - 1; i >= 0; i--) {
            sb.append(lines[i]);
            if (i > 0) sb.append('\n');
        }
        return sb.toString();
    }

    private void bindSources() {
        ListView lv = (ListView) findViewById(R.id.list);
        if (lv == null) return;
        final ArrayList<String> keys = new ArrayList<String>();
        final ArrayList<TwoLineAdapter.Row> rows = new ArrayList<TwoLineAdapter.Row>();
        keys.add(NEMESIS);
        rows.add(new TwoLineAdapter.Row(getString(R.string.log_nemesis), "", "", false));
        keys.add(LOGCAT);
        rows.add(new TwoLineAdapter.Row(getString(R.string.log_logcat), "", "", false));
        ArrayList<String> ids = listServiceLogs();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            int slash = id.indexOf('/');
            keys.add(id);
            rows.add(new TwoLineAdapter.Row(id.substring(slash + 1), id.substring(0, slash), id, false));
        }
        final TwoLineAdapter ad = new TwoLineAdapter(this, rows, true);
        ad.setSelected(0);
        lv.setAdapter(ad);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= keys.size()) return;
                current = keys.get(pos);
                ad.setSelected(pos);
                load();
            }
        });
    }

    /** "<pkg>/<svc>" for each .log under /data/nemesis/logs/<pkg>/<svc>.log, sorted. */
    private ArrayList<String> listServiceLogs() {
        ArrayList<String> out = new ArrayList<String>();
        File root = new File("/data/nemesis/logs");
        File[] pkgs = root.listFiles();
        if (pkgs == null) return out;
        for (int i = 0; i < pkgs.length; i++) {
            if (!pkgs[i].isDirectory()) continue;
            File[] files = pkgs[i].listFiles();
            if (files == null) continue;
            for (int j = 0; j < files.length; j++) {
                String name = files[j].getName();
                if (!name.endsWith(".log")) continue;
                out.add(pkgs[i].getName() + "/" + name.substring(0, name.length() - 4));
            }
        }
        Collections.sort(out);
        return out;
    }

    private boolean copyAll() {
        File dst = new File("/storage/usb0/NEMESIS-logs");
        boolean any = false;
        copyTree(new File("/data/nemesis/logs"), dst);
        copyTree(new File("/data/local/tmp/nemesis/logs"), dst);
        if (copyFile(new File("/data/nemesis/nemesis.log"),
                new File(dst, "nemesis.log"))) any = true;
        if (copyFile(new File("/data/local/tmp/nemesis/nemesis.log"),
                new File(dst, "nemesis.log.tmp"))) any = true;
        if (dst.isDirectory() && dst.list() != null && dst.list().length > 0) any = true;
        return any;
    }

    private void copyTree(File src, File dst) {
        if (src == null || !src.exists()) return;
        dst.mkdirs();
        File[] files = src.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            File t = new File(dst, f.getName());
            if (f.isDirectory()) copyTree(f, t);
            else copyFile(f, t);
        }
    }

    private boolean copyFile(File src, File dst) {
        try {
            if (!src.isFile()) return false;
            dst.getParentFile().mkdirs();
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String readFile(File src) {
        try {
            if (!src.isFile()) return "";
            FileInputStream in = new FileInputStream(src);
            byte[] buf = new byte[(int) src.length()];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return "";
            return new String(buf, 0, n, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
