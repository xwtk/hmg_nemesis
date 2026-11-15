package com.nemesis.manager;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class LogActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.ensureWatchdog();
        if (!Ui.setLayout(this, R.layout.activity_log)) {
            Ui.fallbackShell(this, false, true, true);
        }
        Ui.bindTitle(this, R.string.logs);
        Button a = (Button) findViewById(R.id.btn_a);
        View extraClose = findViewById(R.id.btn_b);
        if (extraClose != null) extraClose.setVisibility(View.GONE);
        if (a == null) return;
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
        new Thread(new Runnable() {
            public void run() {
                String dump = Cli.run("log", "dump");
                if (dump == null || dump.trim().length() == 0) {
                    dump = readFile(new File("/data/nemesis/nemesis.log"));
                }
                if (dump == null || dump.trim().length() == 0) {
                    dump = readFile(new File("/data/local/tmp/nemesis/nemesis.log"));
                }
                if (dump == null || dump.trim().length() == 0) dump = "(no log yet)";
                final String text = dump;
                runOnUiThread(new Runnable() {
                    public void run() {
                        TextView tv = (TextView) findViewById(R.id.log_text);
                        if (tv != null) tv.setText(text);
                    }
                });
            }
        }).start();
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
