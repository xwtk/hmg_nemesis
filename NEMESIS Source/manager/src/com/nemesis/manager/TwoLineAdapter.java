package com.nemesis.manager;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;

final class TwoLineAdapter extends BaseAdapter {
    static final class Row {
        String title;
        String summary;
        String key;
        boolean locked;

        Row(String title, String summary, String key, boolean locked) {
            this.title = title;
            this.summary = summary;
            this.key = key;
            this.locked = locked;
        }
    }

    private final LayoutInflater inf;
    private final ArrayList<Row> rows;
    private final boolean oneLine;
    private int selected = -1;

    TwoLineAdapter(Context ctx, ArrayList<Row> rows) {
        this(ctx, rows, false);
    }

    /** oneLine: title and summary each stay on one line and end in "…" when they do not fit. */
    TwoLineAdapter(Context ctx, ArrayList<Row> rows, boolean oneLine) {
        this.inf = LayoutInflater.from(ctx);
        this.rows = rows;
        this.oneLine = oneLine;
    }

    void setSelected(int pos) {
        selected = pos;
        notifyDataSetChanged();
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
        View v = convert;
        if (v == null) {
            try {
                v = inf.inflate(R.layout.list_row, parent, false);
            } catch (Throwable t) {
                TextView tv = new TextView(parent.getContext());
                tv.setTextColor(0xFFB6C1C8);
                tv.setPadding(24, 16, 24, 16);
                tv.setTextSize(26);
                v = tv;
            }
        }
        Row r = rows.get(pos);
        boolean on = pos == selected;
        v.setActivated(on);
        v.setSelected(on);
        v.setBackgroundResource(on ? R.drawable.co_btn_bg_list_sel : R.drawable.co_list_selector);
        TextView title = (TextView) v.findViewById(R.id.row_title);
        if (title != null) {
            title.setText(r.title);
            if (oneLine) clip(title);
            title.setTextColor(on ? 0xFFFFFFFF : 0xFF62797F);
        } else if (v instanceof TextView) {
            ((TextView) v).setText(r.title);
        }
        TextView s = (TextView) v.findViewById(R.id.row_summary);
        if (s != null) {
            if (r.summary == null || r.summary.length() == 0) {
                s.setVisibility(View.GONE);
            } else {
                s.setVisibility(View.VISIBLE);
                s.setText(r.summary);
                if (oneLine) clip(s);
                s.setTextColor(on ? 0xFFFFFFFF : 0xFF888888);
            }
        }
        return v;
    }

    private static void clip(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }
}
