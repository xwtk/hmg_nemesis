package com.nemesis.manager;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;

/** Auto-launch checkmarks: every launchable app, system apps and Manager included.
 *  User apps are started from the home grid instead. */
public class AppsActivity extends Activity {
    private ArrayList<TwoLineAdapter.Row> rows = new ArrayList<TwoLineAdapter.Row>();
    private java.util.List<String> checked;
    private CheckAdapter checks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Ui.setLayout(this, R.layout.activity_list)) {
            Ui.fallbackShell(this, true, false, false);
        }
        Ui.bindTitle(this, R.string.auto_launch);
        View bar = findViewById(R.id.btn_bar);
        if (bar != null) bar.setVisibility(View.GONE);
        TextView empty = (TextView) findViewById(R.id.empty);
        if (empty != null) empty.setText(R.string.empty_list);
        final ListView lv = (ListView) findViewById(R.id.list);
        if (lv == null) return;
        lv.setEmptyView(empty);
        checked = AutoLaunch.load(this);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < rows.size()) onRow(rows.get(pos).key);
            }
        });
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<TwoLineAdapter.Row> next = AppList.load(AppsActivity.this, true, false);
                runOnUiThread(new Runnable() {
                    public void run() {
                        rows = next;
                        checks = new CheckAdapter(AppsActivity.this, rows, checked);
                        lv.setAdapter(checks);
                    }
                });
            }
        }).start();
    }

    private void onRow(String pkg) {
        if (!checked.remove(pkg)) checked.add(pkg);
        AutoLaunch.save(this, checked);
        if (checks != null) checks.notifyDataSetChanged();
    }
}
