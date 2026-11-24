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
import java.util.List;

public class MainActivity extends Activity {
    private static final int ACT_STORE = 0;
    private static final int ACT_SETTINGS = 1;
    private static final int ACT_SVC = 2;
    private static final int ACT_LOG = 3;
    private static final int ACT_ENG = 4;
    private static final int ACT_DEALER = 5;
    private static final int ACT_ADB = 6;
    private static final int ACT_WIFI = 7;
    private static final int[] LABELS = {
            R.string.store, R.string.settings, R.string.services,
            R.string.logs, R.string.engineering, R.string.dealer,
            R.string.adb, R.string.wifi,
    };
    private static final int[] ICONS = {
            R.drawable.ic_home_store, R.drawable.ic_home_settings,
            R.drawable.ic_home_services, R.drawable.ic_home_logs, R.drawable.ic_home_engineering,
            R.drawable.ic_home_dealer, R.drawable.ic_home_adb, R.drawable.ic_home_wifi,
    };
    private static final String FLAG_PREFS = "flags";
    private static final String KEY_OTA_ASKED = "ota_asked_version";
    private static final String KEY_WIFI_WARNED = "wifi_warned_boot";
    private static final int TILE_CHARS = 26;
    private static final int COLS = 4;
    private static final int ROWS = 2;
    private static final String ENG_COMPONENT =
            "com.hkmc.system.app.engineering/.EngineeringModeMainActivity";
    private static final String DEALER_COMPONENT =
            "com.hkmc.system.app.engineering/.DealerModeActivity";

    private static final String WIFI_SETTINGS = "-a android.net.wifi.PICK_WIFI_NETWORK";

    /** Built-in tiles shown: all of LABELS, minus WiFi on a unit without the module. */
    private int builtin = LABELS.length - 1;
    private final Handler handler = new Handler();
    private final ArrayList<TwoLineAdapter.Row> packages = new ArrayList<TwoLineAdapter.Row>();
    /** User-installed apps shown after the built-in tiles. */
    private ArrayList<TwoLineAdapter.Row> apps = new ArrayList<TwoLineAdapter.Row>();
    /** The plain list that stands in for the two columns if the layout fails. */
    private boolean fallback;
    private boolean resumed;
    private final Runnable statusPoll = new Runnable() {
        public void run() {
            if (!resumed) return;
            showStatus();
            handler.postDelayed(this, 1000);
        }
    };
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
        handler.removeCallbacks(statusPoll);
        handler.post(statusPoll);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refront);
        handler.removeCallbacks(statusPoll);
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.ensureWatchdog();
        if (hasWifi()) builtin = LABELS.length;
        try {
            if (!Ui.setLayout(this, R.layout.activity_main) || findViewById(R.id.pager) == null) {
                showFallback();
                return;
            }
            Ui.bindTitle(this, R.string.title);
            Ui.showCredit(this);
            bindPackages();
            bindIcons();
            maybeFirstBootOtaPrompt();
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
        for (int i = 0; i < builtin; i++) {
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
        findViewById(R.id.pm_status).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                DialogActivity.openPm(MainActivity.this, null);
            }
        });
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

    /** My Packages is Package Manager lite: updates, in progress and interrupted counts. */
    private void showStatus() {
        List<Queue.Job> jobs = Queue.list();
        int active = Queue.count(jobs, true);
        int stopped = Queue.count(jobs, false);
        List<Downloads.Item> dl = Downloads.items(this);
        for (int i = 0; i < dl.size(); i++) {
            Downloads.Item it = dl.get(i);
            if (it.state != Downloads.DL) stopped++;
            else if (!it.done) active++;
        }
        badge(R.id.pm_updates, PkgUpdates.lastCount);
        badge(R.id.pm_progress, active);
        badge(R.id.pm_interrupted, stopped);
    }

    private void badge(int id, int n) {
        TextView t = (TextView) findViewById(id);
        if (t == null) return;
        t.setText(String.valueOf(n));
        t.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
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
                        lv.setAdapter(new TwoLineAdapter(MainActivity.this, packages, true));
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
        int total = builtin + apps.size();
        int perPage = COLS * ROWS;
        int pages = (total + perPage - 1) / perPage;
        for (int pg = 0; pg < pages; pg++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setGravity(Gravity.CENTER_VERTICAL);
            for (int r = 0; r < ROWS; r++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < COLS; c++) {
                    int idx = pg * perPage + r * COLS + c;
                    View cell = idx < total ? iconCell(idx) : new View(this);
                    row.addView(cell, new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.MATCH_PARENT, 1));
                }
                // Tile height, not a share of the pane: stretched rows leave a wide gap.
                page.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 166)));
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
        if (idx < builtin) {
            icon.setImageResource(ICONS[idx]);
        } else {
            try {
                icon.setImageDrawable(getPackageManager()
                        .getApplicationIcon(apps.get(idx - builtin).key));
            } catch (Throwable t) {
                icon.setImageDrawable(getPackageManager().getDefaultActivityIcon());
            }
        }
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                Ui.dp(this, 66), Ui.dp(this, 66), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        ip.topMargin = Ui.dp(this, 34);
        tile.addView(icon, ip);
        TextView label = new TextView(this);
        if (idx < builtin) label.setText(LABELS[idx]);
        else label.setText(clipTitle(apps.get(idx - builtin).title));
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTextSize(25);
        label.setTextColor(0xFFFFFFFF);
        label.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        // Setup uses -10dp line spacing; at 25sp a wrapped "Engineering Menu" then overlaps.
        label.setMaxLines(2);
        // Font padding made two lines taller than the label box, which cut the descenders.
        label.setIncludeFontPadding(false);
        // The unit font's lines are taller than stock; tighten them so line two clears the tile edge.
        label.setLineSpacing(-Ui.dp(this, 3), 1);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 68), Gravity.TOP);
        lp.topMargin = Ui.dp(this, 98);
        tile.addView(label, lp);
        tile.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onIcon(idx);
            }
        });
        slot.addView(tile, new LinearLayout.LayoutParams(Ui.dp(this, 204), Ui.dp(this, 166)));
        return slot;
    }

    /** A tile fits two lines of about 14 characters; "Engineering Menu" is 16. */
    private static String clipTitle(String t) {
        return t.length() > TILE_CHARS ? t.substring(0, TILE_CHARS - 1) + "\u2026" : t;
    }

    private void onIcon(int idx) {
        if (idx >= builtin) openApp(apps.get(idx - builtin).key);
        else if (idx == ACT_STORE) startActivity(new Intent(this, StoreActivity.class));
        else if (idx == ACT_SETTINGS) startActivity(new Intent(this, SettingsActivity.class));
        else if (idx == ACT_SVC) startActivity(new Intent(this, ServiceActivity.class));
        else if (idx == ACT_LOG) startActivity(new Intent(this, LogActivity.class));
        else if (idx == ACT_ENG) openComponent("-n " + ENG_COMPONENT);
        else if (idx == ACT_DEALER) openComponent("-n " + DEALER_COMPONENT);
        else if (idx == ACT_ADB) startActivity(new Intent(this, AdbActivity.class));
        else if (idx == ACT_WIFI) openWifi();
    }

    /** First press on WiFi while it is off warns that wireless projection needs a reboot afterwards.
     * Shown once per boot: the warning is keyed on the boot token, not a permanent flag. */
    private void openWifi() {
        android.content.SharedPreferences prefs =
                getSharedPreferences(FLAG_PREFS, MODE_PRIVATE);
        String boot = bootToken();
        if (wifiOff() && !boot.equals(prefs.getString(KEY_WIFI_WARNED, ""))) {
            prefs.edit().putString(KEY_WIFI_WARNED, boot).commit();
            Intent i = new Intent(this, DialogActivity.class);
            i.putExtra("nemesis_mode", "wifi_warn");
            startActivity(i);
            return;
        }
        openComponent(WIFI_SETTINGS);
    }

    /** A value that stays fixed within a boot and changes across reboots: the kernel
     * boot_id, falling back to the boot wall-clock if that cannot be read. */
    private String bootToken() {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream("/proc/sys/kernel/random/boot_id");
            try {
                byte[] buf = new byte[64];
                int n = in.read(buf);
                if (n > 0) return new String(buf, 0, n, "UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
        }
        return Long.toString(System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime());
    }

    private boolean wifiOff() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
            return wm != null && !wm.isWifiEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Offer the OTA blocker once per installed NEMESIS version. */
    private void maybeFirstBootOtaPrompt() {
        android.content.SharedPreferences prefs =
                getSharedPreferences(FLAG_PREFS, MODE_PRIVATE);
        String version = Ui.versionName(this);
        if (version.length() == 0 || version.equals(prefs.getString(KEY_OTA_ASKED, ""))) return;
        prefs.edit().putString(KEY_OTA_ASKED, version).commit();
        handler.post(new Runnable() {
            public void run() {
                if (isFinishing()) return;
                Intent i = new Intent(MainActivity.this, DialogActivity.class);
                i.putExtra("nemesis_mode", "confirm_ota_block");
                startActivity(i);
            }
        });
    }

    /** The module's feature file (not on units without one) or its network interface. */
    private boolean hasWifi() {
        return getPackageManager().hasSystemFeature("android.hardware.wifi")
                || new java.io.File("/sys/class/net/wlan0").exists();
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
                final int rc = Cli.runRc("root", "-c", "am start " + component);
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
