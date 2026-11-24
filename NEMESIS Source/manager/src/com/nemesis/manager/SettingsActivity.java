package com.nemesis.manager;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import java.util.ArrayList;

/** Settings list, and the Quick-open button picker opened from it. */
public class SettingsActivity extends Activity {
    private boolean picking;
    /** Host-state checks loaded from native in the background so the Settings list opens at once. */
    private volatile boolean otaBlocked;
    private volatile boolean selinuxOff;
    /** Only show the SELinux toggle when the unit actually boots enforcing. */
    private volatile boolean selinuxShow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        picking = getIntent().getBooleanExtra("pick_trigger", false);
        if (!Ui.setLayout(this, R.layout.activity_list)) {
            Ui.fallbackShell(this, true, false, false);
        }
        Ui.bindTitle(this, picking ? R.string.trigger_button : R.string.settings);
        View bar = findViewById(R.id.btn_bar);
        if (bar != null) bar.setVisibility(View.GONE);
        View empty = findViewById(R.id.empty);
        if (empty != null) empty.setVisibility(View.GONE);
        bindList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (picking) return;
        bindList();
        refreshHostStatus();
    }

    private void refreshHostStatus() {
        new Thread(new Runnable() {
            public void run() {
                final boolean ota = "on".equals(Cli.run("ota-block", "status").trim());
                String se = Cli.run("selinux", "status").trim();
                final boolean se_off = "permissive".equals(se) || "disabled".equals(se);
                final boolean se_show = "enforcing".equals(Cli.run("selinux", "cmdline").trim());
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        otaBlocked = ota;
                        selinuxOff = se_off;
                        selinuxShow = se_show;
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
        if (!picking) {
            rows.add(new TwoLineAdapter.Row(getString(R.string.trigger_button),
                    Trigger.label(this) + ". " + getString(R.string.trigger_hint), "trigger", false));
            rows.add(new TwoLineAdapter.Row(getString(R.string.auto_launch),
                    getString(R.string.auto_launch_sum), "auto", false));
            rows.add(new TwoLineAdapter.Row(getString(R.string.use_telematics),
                    getString(R.string.use_telematics_sum), "telematics", false));
            rows.add(new TwoLineAdapter.Row(getString(R.string.block_ota),
                    getString(R.string.block_ota_sum), "ota", false));
            if (selinuxShow) {
                rows.add(new TwoLineAdapter.Row(getString(R.string.disable_selinux),
                        getString(R.string.disable_selinux_sum), "selinux", false));
            }
            rows.add(new TwoLineAdapter.Row(getString(R.string.check_updates),
                    getString(R.string.title) + " " + Ui.versionName(this), "update", false));
            rows.add(new TwoLineAdapter.Row(getString(R.string.reboot),
                    getString(R.string.reboot_sum), "reboot", false));
            rows.add(new TwoLineAdapter.Row(getString(R.string.wipe),
                    getString(R.string.wipe_sum), "wipe", false));
        } else {
            String current = Trigger.suffix(this);
            for (int i = 0; i < Trigger.ALL.length; i++) {
                Trigger.Item item = Trigger.ALL[i];
                String sum = item.suffix.equals(current) ? getString(R.string.selected) : "";
                rows.add(new TwoLineAdapter.Row(item.label, sum, item.suffix, false));
            }
        }
        if (picking) {
            lv.setAdapter(new TwoLineAdapter(this, rows));
        } else {
            ArrayList<String> checked = new ArrayList<String>();
            if (Portal.useTelematics(this)) checked.add("telematics");
            if (otaBlocked) checked.add("ota");
            if (selinuxOff) checked.add("selinux");
            lv.setAdapter(new CheckAdapter(this, rows, checked,
                    java.util.Arrays.asList("telematics", "ota", "selinux")));
        }
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= rows.size()) return;
                onRow(rows.get(pos).key);
            }
        });
    }

    private void onRow(String key) {
        if (picking) {
            try {
                Trigger.save(this, key);
            } catch (Throwable ignored) {
            }
            Ui.close(this);
            return;
        }
        if ("trigger".equals(key)) {
            Intent i = new Intent(this, SettingsActivity.class);
            i.putExtra("pick_trigger", true);
            startActivity(i);
        } else if ("auto".equals(key)) {
            startActivity(new Intent(this, AppsActivity.class));
        } else if ("telematics".equals(key)) {
            if (Portal.useTelematics(this)) {
                Portal.setUseTelematics(this, false);
                bindList();
            } else {
                // Turning it on risks the vehicle's modem; DialogActivity sets it on Yes.
                Intent i = new Intent(this, DialogActivity.class);
                i.putExtra("nemesis_mode", "confirm_modem");
                startActivity(i);
            }
        } else if ("ota".equals(key)) {
            if (otaBlocked) runToggle("ota-block", "off");
            else startConfirm("confirm_ota_block");
        } else if ("selinux".equals(key)) {
            if (selinuxOff) runToggle("selinux", "on");
            else startConfirm("confirm_selinux_off");
        } else if ("update".equals(key)) {
            Intent i = new Intent(this, DialogActivity.class);
            i.putExtra("nemesis_mode", "update_check");
            startActivity(i);
        } else if ("reboot".equals(key)) {
            Intent i = new Intent(this, DialogActivity.class);
            i.putExtra("nemesis_mode", "confirm_reboot");
            startActivity(i);
        } else if ("wipe".equals(key)) {
            Intent i = new Intent(this, DialogActivity.class);
            i.putExtra("nemesis_mode", "wipe");
            startActivity(i);
        }
    }

    private void startConfirm(String mode) {
        Intent i = new Intent(this, DialogActivity.class);
        i.putExtra("nemesis_mode", mode);
        startActivity(i);
    }

    /** Runs `nemesis <args>` off the UI thread, then reloads status so the box reflects the change. */
    private void runToggle(final String... args) {
        new Thread(new Runnable() {
            public void run() {
                Cli.runRc(args);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!isFinishing()) refreshHostStatus();
                    }
                });
            }
        }).start();
    }
}
