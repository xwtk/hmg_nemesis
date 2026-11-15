package com.nemesis.manager;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

final class Ui {
    private static final int BG = 0xFF1A1D1E;
    private static final int TITLE_FG = 0xFFB6C1C8;
    private static final int MUTED = 0xFF62797F;
    private static boolean watchdogStarted;

    /** HMG's finishActivity() throws SecurityException without GET_TASKS.
     *  The manifest asks for it; a build missing the grant still closes. */
    static void close(Activity a) {
        try {
            a.finish();
        } catch (SecurityException e) {
            CrashLog.record(e);
            try {
                a.moveTaskToBack(true);
            } catch (Throwable ignored) {
            }
        }
    }

    static boolean setLayout(Activity a, int layoutRes) {
        CrashLog.install(a);
        try {
            a.setContentView(layoutRes);
            return a.findViewById(android.R.id.content) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static void bindTitle(final Activity a, int titleRes) {
        TextView t = (TextView) a.findViewById(R.id.title_bar_text);
        if (t != null) t.setText(titleRes);
        View c = a.findViewById(R.id.btn_close);
        if (c != null) {
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    close(a);
                }
            });
        }
    }

    /** The author line next to the title. Home only; screens below it show the title alone. */
    static void showCredit(Activity a) {
        View v = a.findViewById(R.id.title_bar_credit);
        if (v != null) v.setVisibility(View.VISIBLE);
    }

    static String versionName(Context c) {
        try {
            String name = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).versionName;
            if (name != null && name.length() > 0) return name;
        } catch (Throwable ignored) {
        }
        return "";
    }

    static void ensureWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        new Thread(new Runnable() {
            public void run() {
                Cli.exec("watchdog");
            }
        }).start();
    }

    static void toast(Activity a, int res) {
        Toast.makeText(a, res, Toast.LENGTH_SHORT).show();
    }

    static void toast(Activity a, String msg) {
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
    }

    static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    static LinearLayout fallbackShell(Activity a, boolean withList, boolean withButtons,
                                      boolean withLog) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout title = new LinearLayout(a);
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setBackgroundColor(0xFF111314);
        title.setPadding(dp(a, 22), 0, dp(a, 4), 0);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 60));
        title.setLayoutParams(tp);

        TextView tv = new TextView(a);
        tv.setId(R.id.title_bar_text);
        tv.setText(R.string.title);
        tv.setTextColor(TITLE_FG);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setSingleLine(true);
        title.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(a, 60)));

        TextView credit = new TextView(a);
        credit.setId(R.id.title_bar_credit);
        credit.setText(R.string.credit);
        credit.setTextColor(MUTED);
        credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        credit.setGravity(Gravity.CENTER_VERTICAL);
        credit.setSingleLine(true);
        credit.setPadding(dp(a, 16), 0, 0, 0);
        credit.setVisibility(View.GONE);
        title.addView(credit, new LinearLayout.LayoutParams(0, dp(a, 60), 1));

        ImageButton close = new ImageButton(a);
        close.setId(R.id.btn_close);
        close.setImageResource(R.drawable.ic_close);
        close.setContentDescription(a.getString(R.string.close));
        close.setBackgroundColor(Color.TRANSPARENT);
        title.addView(close, new LinearLayout.LayoutParams(dp(a, 72), dp(a, 60)));
        root.addView(title);

        if (withLog) {
            ScrollView sv = new ScrollView(a);
            sv.setId(R.id.log_scroll);
            TextView log = new TextView(a);
            log.setId(R.id.log_text);
            log.setTextColor(MUTED);
            log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            log.setPadding(dp(a, 22), dp(a, 10), dp(a, 22), dp(a, 10));
            sv.addView(log, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            root.addView(sv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        } else if (withList) {
            TextView empty = new TextView(a);
            empty.setId(R.id.empty);
            empty.setTextColor(MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setVisibility(View.GONE);
            ListView lv = new ListView(a);
            lv.setId(R.id.list);
            lv.setCacheColorHint(0);
            LinearLayout frame = new LinearLayout(a);
            frame.setOrientation(LinearLayout.VERTICAL);
            frame.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            frame.addView(lv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            root.addView(frame, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            ListView lv = new ListView(a);
            lv.setId(R.id.list);
            lv.setCacheColorHint(0);
            root.addView(lv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        }

        if (withButtons) {
            LinearLayout bar = new LinearLayout(a);
            bar.setId(R.id.btn_bar);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 8));
            bar.setBackgroundColor(0xFF111314);
            Button aBtn = new Button(a);
            aBtn.setId(R.id.btn_a);
            Button bBtn = new Button(a);
            bBtn.setId(R.id.btn_b);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(a, 48), 1);
            bp.rightMargin = dp(a, 8);
            bar.addView(aBtn, bp);
            bar.addView(bBtn, new LinearLayout.LayoutParams(0, dp(a, 48), 1));
            root.addView(bar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 60)));
        }
        a.setContentView(root);
        return root;
    }

    static void fallbackDialog(Activity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF222628);
        root.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 16));
        TextView title = new TextView(a);
        title.setId(R.id.title_bar_text);
        title.setTextColor(TITLE_FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        TextView msg = new TextView(a);
        msg.setId(R.id.message);
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        msg.setPadding(0, dp(a, 16), 0, dp(a, 16));
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button yes = new Button(a);
        yes.setId(R.id.button1);
        Button no = new Button(a);
        no.setId(R.id.button2);
        bar.addView(yes, new LinearLayout.LayoutParams(0, dp(a, 48), 1));
        bar.addView(no, new LinearLayout.LayoutParams(0, dp(a, 48), 1));
        root.addView(title);
        root.addView(msg);
        root.addView(bar);
        a.setContentView(root);
    }
}
