package com.nemesis.manager;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int ACT_STORE = 0;
    private static final int ACT_SETTINGS = 1;
    private static final int ACT_SVC = 2;
    private static final int ACT_LOG = 3;
    private static final int ACT_ENG = 4;
    private static final int ACT_DEALER = 5;
    private static final int[] LABELS = {
            R.string.store, R.string.settings, R.string.services,
            R.string.logs, R.string.engineering, R.string.dealer,
    };
    private static final int[] ICONS = {
            R.drawable.ic_home_store, R.drawable.ic_home_settings,
            R.drawable.ic_home_services, R.drawable.ic_home_logs, R.drawable.ic_home_engineering,
            R.drawable.ic_home_dealer,
    };
    private static final int COLS = 4;
    private static final int ROWS = 2;
    private static final String ENG_COMPONENT =
            "com.hkmc.system.app.engineering/.EngineeringModeMainActivity";
    private static final String DEALER_COMPONENT =
            "com.hkmc.system.app.engineering/.DealerModeActivity";

    private final Handler handler = new Handler();
    private final ArrayList<TwoLineAdapter.Row> packages = new ArrayList<TwoLineAdapter.Row>();
    /** User-installed apps shown after the built-in tiles. */
    private ArrayList<TwoLineAdapter.Row> apps = new ArrayList<TwoLineAdapter.Row>();
    /** The plain list that stands in for the two columns if the layout fails. */
    private boolean fallback;
    private boolean resumed;
    private final Runnable refront = new Runnable() {
        public void run() {
            // A pause that ended in onResume was our own onNewIntent.
            if (resumed || isFinishing() || !ButtonOpen.take()) return;
            CrashLog.note("press.log", "refront MainActivity");
            Intent i = new Intent(MainActivity.this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try {
                startActivity(i);
            } catch (Throwable ignored) {
            }
        }
    };

    /** A tap means the user is here: a later pause is theirs, not Setup's. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        ButtonOpen.cancel();
    }

    /** Setup or Navigation covered us right after a button open: come back once. */
    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (!isFinishing() && ButtonOpen.pending()) handler.postDelayed(refront, 400);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (fallback) return;
        reloadPackages();
        reloadApps();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refront);
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.ensureWatchdog();
        try {
            if (!Ui.setLayout(this, R.layout.activity_main) || findViewById(R.id.pager) == null) {
                showFallback();
                return;
            }
            Ui.bindTitle(this, R.string.title);
            Ui.showCredit(this);
            bindPackages();
            bindIcons();
        } catch (Throwable t) {
            CrashLog.record(t);
            try {
                showFallback();
            } catch (Throwable ignored) {
            }
        }
    }

    private void showFallback() {
        fallback = true;
        Ui.fallbackShell(this, false, false, false);
        Ui.bindTitle(this, R.string.title);
        Ui.showCredit(this);
        ListView lv = (ListView) findViewById(R.id.list);
        if (lv == null) return;
        ArrayList<TwoLineAdapter.Row> rows = new ArrayList<TwoLineAdapter.Row>();
        for (int i = 0; i < LABELS.length; i++) {
            rows.add(new TwoLineAdapter.Row(getString(LABELS[i]), "", "", false));
        }
        lv.setAdapter(new TwoLineAdapter(this, rows));
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onIcon(pos);
            }
        });
    }

    private void bindPackages() {
        ListView lv = (ListView) findViewById(R.id.list);
        lv.setEmptyView(findViewById(R.id.empty));
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= packages.size()) return;
                String key = packages.get(pos).key;
                if (key.length() == 0) return;
                Intent i = new Intent(MainActivity.this, PackageActivity.class);
                i.putExtra("nemesis_id", key);
                startActivity(i);
            }
        });
    }

    private void reloadPackages() {
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<TwoLineAdapter.Row> next = PackageActivity.parseList(Cli.run("pkg-list"));
                runOnUiThread(new Runnable() {
                    public void run() {
                        ListView lv = (ListView) findViewById(R.id.list);
                        if (lv == null) return;
                        packages.clear();
                        packages.addAll(next);
                        lv.setAdapter(new TwoLineAdapter(MainActivity.this, packages));
                    }
                });
            }
        }).start();
    }

    /** Rebuild the grid only when an app was installed or removed. */
    private void reloadApps() {
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<TwoLineAdapter.Row> next = AppList.load(MainActivity.this, false, true);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (keys(next).equals(keys(apps)) || findViewById(R.id.pager) == null) return;
                        apps = next;
                        bindIcons();
                    }
                });
            }
        }).start();
    }

    private static ArrayList<String> keys(ArrayList<TwoLineAdapter.Row> rows) {
        ArrayList<String> k = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) k.add(rows.get(i).key);
        return k;
    }

    private void bindIcons() {
        final IconPager pager = (IconPager) findViewById(R.id.pager);
        final LinearLayout dots = (LinearLayout) findViewById(R.id.page_dots);
        pager.clear();
        dots.removeAllViews();
        dots.setVisibility(View.VISIBLE);
        int total = LABELS.length + apps.size();
        int perPage = COLS * ROWS;
        int pages = (total + perPage - 1) / perPage;
        for (int pg = 0; pg < pages; pg++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            for (int r = 0; r < ROWS; r++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < COLS; c++) {
                    int idx = pg * perPage + r * COLS + c;
                    View cell = idx < total ? iconCell(idx) : new View(this);
                    row.addView(cell, new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.MATCH_PARENT, 1));
                }
                page.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            }
            pager.addPage(page);
        }
        if (pages < 2) {
            dots.setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < pages; i++) {
            View d = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 12), Ui.dp(this, 12));
            lp.leftMargin = Ui.dp(this, 6);
            lp.rightMargin = Ui.dp(this, 6);
            dots.addView(d, lp);
        }
        showDot(dots, 0);
        pager.setPageListener(new IconPager.PageListener() {
            public void onPage(int page) {
                showDot(dots, page);
            }
        });
    }

    private void showDot(LinearLayout dots, int page) {
        for (int i = 0; i < dots.getChildCount(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == page ? 0xFFB6C1C8 : 0xFF3A4347);
            dots.getChildAt(i).setBackgroundDrawable(g);
        }
    }

    /** Stock Setup grid_item geometry: a 66dp icon 34dp from the top and a 25sp white
     *  label of up to two lines from 102dp. The tile is 204x166dp, the bitmap's own
     *  306x249px at hdpi; Setup's 186dp squeezes the squircle out of square. */
    private View iconCell(final int idx) {
        LinearLayout slot = new LinearLayout(this);
        slot.setGravity(Gravity.CENTER);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackgroundResource(R.drawable.home_tile);
        tile.setClickable(true);
        ImageView icon = new ImageView(this);
        if (idx < LABELS.length) {
            icon.setImageResource(ICONS[idx]);
        } else {
            try {
                icon.setImageDrawable(getPackageManager()
                        .getApplicationIcon(apps.get(idx - LABELS.length).key));
            } catch (Throwable t) {
                icon.setImageDrawable(getPackageManager().getDefaultActivityIcon());
            }
        }
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                Ui.dp(this, 66), Ui.dp(this, 66), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        ip.topMargin = Ui.dp(this, 34);
        tile.addView(icon, ip);
        TextView label = new TextView(this);
        if (idx < LABELS.length) label.setText(LABELS[idx]);
        else label.setText(apps.get(idx - LABELS.length).title);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTextSize(25);
        label.setTextColor(0xFFFFFFFF);
        label.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        // Setup uses -10dp line spacing; at 25sp a wrapped "Engineering Menu" then overlaps.
        label.setMaxLines(2);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 66), Gravity.TOP);
        lp.topMargin = Ui.dp(this, 102);
        tile.addView(label, lp);
        tile.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onIcon(idx);
            }
        });
        slot.addView(tile, new LinearLayout.LayoutParams(Ui.dp(this, 204), Ui.dp(this, 166)));
        return slot;
    }

    private void onIcon(int idx) {
        if (idx >= LABELS.length) openApp(apps.get(idx - LABELS.length).key);
        else if (idx == ACT_STORE) startActivity(new Intent(this, StoreActivity.class));
        else if (idx == ACT_SETTINGS) startActivity(new Intent(this, SettingsActivity.class));
        else if (idx == ACT_SVC) startActivity(new Intent(this, ServiceActivity.class));
        else if (idx == ACT_LOG) startActivity(new Intent(this, LogActivity.class));
        else if (idx == ACT_ENG) openComponent(ENG_COMPONENT);
        else if (idx == ACT_DEALER) openComponent(DEALER_COMPONENT);
    }

    private void openApp(String pkg) {
        try {
            Intent i = AutoLaunch.launchIntent(this, pkg);
            if (i == null) {
                Ui.toast(this, R.string.open_fail);
                return;
            }
            startActivity(i);
        } catch (Throwable t) {
            Ui.toast(this, R.string.open_fail);
        }
    }

    private void openComponent(final String component) {
        new Thread(new Runnable() {
            public void run() {
                final int rc = Cli.runRc("root", "-c", "am start -n " + component);
                if (rc == 0) return;
                runOnUiThread(new Runnable() {
                    public void run() {
                        Ui.toast(MainActivity.this, R.string.open_fail);
                    }
                });
            }
        }).start();
    }
}
