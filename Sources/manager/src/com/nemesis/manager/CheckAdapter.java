package com.nemesis.manager;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Rows with a checkmark on the right, for Auto-launch. */
final class CheckAdapter extends BaseAdapter {
    private final LayoutInflater inf;
    private final ArrayList<TwoLineAdapter.Row> rows;
    private final List<String> checked;

    CheckAdapter(Context ctx, ArrayList<TwoLineAdapter.Row> rows, List<String> checked) {
        this.inf = LayoutInflater.from(ctx);
        this.rows = rows;
        this.checked = checked;
    }

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
        View v = convert != null ? convert : inf.inflate(R.layout.check_row, parent, false);
        TwoLineAdapter.Row r = rows.get(pos);
        ((TextView) v.findViewById(R.id.row_title)).setText(r.title);
        ((TextView) v.findViewById(R.id.row_summary)).setText(r.summary);
        ((ImageView) v.findViewById(R.id.row_check))
                .setVisibility(checked.contains(r.key) ? View.VISIBLE : View.INVISIBLE);
        return v;
    }
}
