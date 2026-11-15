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
        if (!picking) bindList();
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
            lv.setAdapter(new CheckAdapter(this, rows, checked, java.util.Collections.singletonList("telematics")));
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
}
