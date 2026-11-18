package com.nemesis.manager;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/** USB and Wireless ADB toggles. State is read from `nemesis adb status`. */
public class AdbActivity extends Activity {
    private volatile boolean usbOn;
    private volatile boolean tcpOn;
    private volatile String ip = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Ui.setLayout(this, R.layout.activity_list)) {
            Ui.fallbackShell(this, true, false, false);
        }
        Ui.bindTitle(this, R.string.adb);
        View bar = findViewById(R.id.btn_bar);
        if (bar != null) bar.setVisibility(View.GONE);
        View empty = findViewById(R.id.empty);
        if (empty != null) empty.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindList();
        refreshStatus();
    }

    private void refreshStatus() {
        new Thread(new Runnable() {
            public void run() {
                Map<String, String> f = Cli.fields(Cli.run("adb", "status"));
                final boolean u = "on".equals(f.get("usb"));
                final boolean t = "on".equals(f.get("tcp"));
                final String a = f.get("ip") == null ? "" : f.get("ip");
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        usbOn = u;
                        tcpOn = t;
                        ip = a;
                        bindList();
                    }
                });
            }
        }).start();
    }

    private void bindList() {
        ListView lv = (ListView) findViewById(R.id.list);
        if (lv == null) return;
        final ArrayList<TwoLineAdapter.Row> rows = new ArrayList<TwoLineAdapter.Row>();
        rows.add(new TwoLineAdapter.Row(getString(R.string.adb_usb),
                getString(R.string.adb_usb_sum), "usb", false));
        String tcpSum;
        if (tcpOn) {
            tcpSum = ip.length() > 0
                    ? getString(R.string.adb_tcp_at).replace("%s", ip)
                    : getString(R.string.adb_tcp_no_ip);
        } else {
            tcpSum = getString(R.string.adb_tcp_sum);
        }
        rows.add(new TwoLineAdapter.Row(getString(R.string.adb_tcp), tcpSum, "tcp", false));
        ArrayList<String> checked = new ArrayList<String>();
        if (usbOn) checked.add("usb");
        if (tcpOn) checked.add("tcp");
        lv.setAdapter(new CheckAdapter(this, rows, checked, Arrays.asList("usb", "tcp")));
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= rows.size()) return;
                onRow(rows.get(pos).key);
            }
        });
    }

    private void onRow(String key) {
        if ("usb".equals(key)) {
            if (usbOn) runToggle("adb", "usb-off");
            else startConfirm("confirm_adb_usb");
        } else if ("tcp".equals(key)) {
            if (tcpOn) runToggle("adb", "tcp-off");
            else startConfirm("confirm_adb_tcp");
        }
    }

    private void startConfirm(String mode) {
        Intent i = new Intent(this, DialogActivity.class);
        i.putExtra("nemesis_mode", mode);
        startActivity(i);
    }

    private void runToggle(final String... args) {
        new Thread(new Runnable() {
            public void run() {
                Cli.runRc(args);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!isFinishing()) refreshStatus();
                    }
                });
            }
        }).start();
    }
}
