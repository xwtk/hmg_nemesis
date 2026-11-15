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

/** Rows with a checkbox on the right. `checkable` names the rows that have one; null means all. */
final class CheckAdapter extends BaseAdapter {
    private final LayoutInflater inf;
    private final ArrayList<TwoLineAdapter.Row> rows;
    private final List<String> checked;
    private final List<String> checkable;

    CheckAdapter(Context ctx, ArrayList<TwoLineAdapter.Row> rows, List<String> checked, List<String> checkable) {
        this.inf = LayoutInflater.from(ctx);
        this.rows = rows;
        this.checked = checked;
        this.checkable = checkable;
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
        ImageView box = (ImageView) v.findViewById(R.id.row_check);
        box.setVisibility(checkable == null || checkable.contains(r.key) ? View.VISIBLE : View.GONE);
        box.setImageResource(checked.contains(r.key) ? R.drawable.ic_checkbox_on : R.drawable.ic_checkbox_off);
        return v;
    }
}
