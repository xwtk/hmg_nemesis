package com.nemesis.manager;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Installed packages the Package Store has a newer version of, from either tab. */
public class UpdatesActivity extends Activity {
    private List<StoreList.Entry> rows = new ArrayList<StoreList.Entry>();
    private int gen;
    private final BaseAdapter adapter = new BaseAdapter() {
        public int getCount() {
            return rows.size();
        }

        public Object getItem(int pos) {
            return rows.get(pos);
        }

        public long getItemId(int pos) {
            return pos;
        }

        public View getView(int pos, View convert, ViewGroup parent) {
            View v = convert != null ? convert
                    : LayoutInflater.from(UpdatesActivity.this).inflate(R.layout.update_row, parent, false);
            final StoreList.Entry e = rows.get(pos);
            ((TextView) v.findViewById(R.id.row_title)).setText(e.name);
            ((TextView) v.findViewById(R.id.row_summary)).setText(e.installed + " > " + e.version);
            v.findViewById(R.id.row_update).setOnClickListener(new View.OnClickListener() {
                public void onClick(View b) {
                    Intent i = DialogActivity.storeIntent(UpdatesActivity.this, e);
                    i.putExtra("nemesis_mode", "store_online");
                    startActivity(i);
                }
            });
            return v;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.ensureWatchdog();
        if (!Ui.setLayout(this, R.layout.activity_list)) {
            Ui.close(this);
            return;
        }
        Ui.bindTitle(this, R.string.updates);
        findViewById(R.id.btn_bar).setVisibility(View.GONE);
        TextView empty = (TextView) findViewById(R.id.empty);
        empty.setText(R.string.updates_empty);
        ListView lv = (ListView) findViewById(R.id.list);
        lv.setEmptyView(empty);
        lv.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (findViewById(R.id.list) == null) return;
        final int g = ++gen;
        new Thread(new Runnable() {
            public void run() {
                final List<StoreList.Entry> found = PkgUpdates.find(UpdatesActivity.this).updates;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (g != gen || isFinishing()) return;
                        rows = found;
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }
}
