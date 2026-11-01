package com.nemesis.manager;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Package Store. Started by a type=list stick (store_mode=usb) or from the main screen.
 *  Exported through the stick flow, so extras never name a path: the list is read
 *  from the mounted stick with usb-info. */
public class StoreActivity extends Activity {
    private static final int GREEN = 0xFF6FBF73;
    private static final int YELLOW = 0xFFE3C565;
    private static final int RED = 0xFFD9685F;
    private static final int PLAIN = 0xFFB6C1C8;

    private boolean usbTab;
    private boolean usbOk;
    private boolean onlineOk;
    private boolean offlineShown;
    private String usbRoot;
    private List<StoreList.Entry> usbRows = new ArrayList<StoreList.Entry>();
    private List<StoreList.Entry> onlineRows = new ArrayList<StoreList.Entry>();
    private List<StoreList.Entry> all = new ArrayList<StoreList.Entry>();
    private List<StoreList.Entry> shown = new ArrayList<StoreList.Entry>();
    private boolean showAll;
    private boolean byDate = true;
    private boolean ascending;
    private int loadGen;
    private int resolveGen;
    private final RowAdapter adapter = new RowAdapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.ensureWatchdog();
        if (!Ui.setLayout(this, R.layout.activity_store)) {
            Ui.close(this);
            return;
        }
        Ui.bindTitle(this, R.string.store);
        usbTab = "usb".equals(getIntent().getStringExtra("store_mode"));
        ListView lv = (ListView) findViewById(R.id.list);
        lv.setAdapter(adapter);
        lv.setEmptyView(findViewById(R.id.empty));
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < shown.size()) open(shown.get(pos));
            }
        });
        click(R.id.tab_usb, new Runnable() {
            public void run() {
                if (usbOk) switchTab(true);
            }
        });
        click(R.id.tab_online, new Runnable() {
            public void run() {
                if (onlineOk) switchTab(false);
            }
        });
        click(R.id.sort_field, new Runnable() {
            public void run() {
                byDate = !byDate;
                refresh();
            }
        });
        click(R.id.sort_dir, new Runnable() {
            public void run() {
                ascending = !ascending;
                refresh();
            }
        });
        click(R.id.show_all, new Runnable() {
            public void run() {
                showAll = !showAll;
                refresh();
            }
        });
        ((EditText) findViewById(R.id.search)).addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                refresh();
            }
        });
    }

    private void click(int id, final Runnable r) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                r.run();
            }
        });
    }

    /** Back from an install or uninstall popup too: installed state changed. */
    @Override
    protected void onResume() {
        super.onResume();
        if (findViewById(R.id.search) != null) load();
    }

    private void switchTab(boolean usb) {
        usbTab = usb;
        showRows();
    }

    /** The stick answers at once; the portal may take its whole connect timeout. */
    private void load() {
        final int gen = ++loadGen;
        new Thread(new Runnable() {
            public void run() {
                Map<String, String> info = Cli.fields(Cli.run("usb-info"));
                final boolean hasList = "list".equals(info.get("type"));
                final String root = info.get("root");
                final List<StoreList.Entry> rows = hasList
                        ? StoreList.parseList(Cli.run("usb-info", "--list"))
                        : new ArrayList<StoreList.Entry>();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (gen != loadGen || isFinishing()) return;
                        usbOk = hasList;
                        usbRoot = root;
                        usbRows = rows;
                        if (usbTab && !usbOk) usbTab = false;
                        showRows();
                    }
                });
                List<StoreList.Entry> online = null;
                try {
                    online = Portal.catalog(StoreActivity.this);
                } catch (Throwable t) {
                    CrashLog.note("update.log", "catalog: " + t);
                }
                final List<StoreList.Entry> onlineFinal = online;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (gen != loadGen || isFinishing()) return;
                        onlineOk = onlineFinal != null;
                        onlineRows = onlineOk ? onlineFinal : new ArrayList<StoreList.Entry>();
                        if (!onlineOk && !offlineShown) {
                            offlineShown = true;
                            Intent i = new Intent(StoreActivity.this, DialogActivity.class);
                            i.putExtra("nemesis_mode", "result_fail");
                            i.putExtra("nemesis_message", getString(R.string.store_offline));
                            startActivity(i);
                        }
                        if (!usbTab) {
                            if (!onlineOk && usbOk) usbTab = true;
                            showRows();
                        } else {
                            refresh();
                        }
                    }
                });
            }
        }).start();
    }

    private void showRows() {
        all = usbTab ? usbRows : onlineRows;
        refresh();
        resolve(++resolveGen, all, usbTab, usbRoot);
    }

    /** Listing uses only the supply data; compatibility comes from each descriptor. */
    private void resolve(final int gen, final List<StoreList.Entry> rows, final boolean usb,
                         final String root) {
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < rows.size() && gen == resolveGen; i++) {
                    StoreList.Entry e = rows.get(i);
                    try {
                        if (usb) resolveUsb(e, root);
                        else resolveOnline(StoreActivity.this, e);
                    } catch (Throwable t) {
                        CrashLog.note("update.log", "descriptor " + e.name + ": " + t);
                    }
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (gen == resolveGen && !isFinishing()) refresh();
                        }
                    });
                }
            }
        }).start();
    }

    static void resolveUsb(StoreList.Entry e, String root) {
        Cli.Result r = Cli.exec("pkg-info", root + "/" + e.path);
        if (r.rc != 0) {
            e.compat = StoreList.NONE;
            return;
        }
        Map<String, String> m = Cli.fields(r.out);
        e.id = or(m.get("id"), "");
        e.version = or(m.get("package-version"), "");
        e.isInstalled = "1".equals(m.get("installed"));
        e.installed = or(m.get("installed-version"), "");
        e.locked = "1".equals(m.get("locked"));
        e.compat = StoreList.compatOf(m.get("compat"));
        e.legacy = StoreList.isLegacy(m.get("version"));
        e.detailedDesc = PackageActivity.unescape(or(m.get("detailed-desc"), ""));
    }

    static void resolveOnline(Context ctx, StoreList.Entry e) throws Exception {
        JSONObject d = Portal.descriptor(ctx, e.id);
        e.version = d.optString("package-version", "");
        e.url = d.optString("url", "");
        e.sha256 = d.optString("sha256", "");
        e.legacy = StoreList.isLegacy(d.optString("descriptor", ""));
        e.detailedDesc = d.optString("detailed-desc", "");
        Map<String, String> m = Cli.fields(Cli.run("pkg-info", e.id));
        e.isInstalled = "1".equals(m.get("installed"));
        e.installed = or(m.get("installed-version"), "");
        e.locked = "1".equals(m.get("locked"));
        Map<String, String> c = Cli.fields(Cli.run("compat-check", d.optString("os", "any"),
                d.optString("models", "any"), d.optString("depends", "")));
        e.compat = StoreList.compatOf(c.get("compat"));
    }

    private static String or(String v, String d) {
        return v == null ? d : v;
    }

    private void refresh() {
        String q = ((EditText) findViewById(R.id.search)).getText().toString();
        shown = StoreList.view(all, q, showAll, byDate, ascending);
        adapter.notifyDataSetChanged();
        tab(R.id.tab_usb, usbOk, usbTab);
        tab(R.id.tab_online, onlineOk, !usbTab);
        ((Button) findViewById(R.id.sort_field)).setText(byDate ? R.string.sort_date : R.string.sort_name);
        ((Button) findViewById(R.id.sort_dir)).setText(ascending ? "↑" : "↓");
        Button more = (Button) findViewById(R.id.show_all);
        int hidden = StoreList.hiddenCount(all);
        more.setVisibility(hidden > 0 ? View.VISIBLE : View.GONE);
        more.setText(showAll ? getString(R.string.show_full_only)
                : getString(R.string.show_all).replace("%d", String.valueOf(hidden)));
    }

    private void tab(int id, boolean enabled, boolean on) {
        Button t = (Button) findViewById(id);
        t.setEnabled(enabled);
        t.setAlpha(enabled ? 1f : 0.35f);
        t.setTextColor(on && enabled ? 0xFFFFFFFF : 0xFF62797F);
    }

    private void open(StoreList.Entry e) {
        Intent i = new Intent(this, StoreDetailActivity.class);
        i.putExtra("store_id", e.id);
        i.putExtra("store_name", e.name);
        i.putExtra("store_short", e.shortDesc);
        i.putExtra("store_published", e.published);
        if (usbTab) {
            i.putExtra("store_root", usbRoot);
            i.putExtra("store_path", e.path);
        }
        startActivity(i);
    }

    private final class RowAdapter extends BaseAdapter {
        public int getCount() {
            return shown.size();
        }

        public Object getItem(int pos) {
            return shown.get(pos);
        }

        public long getItemId(int pos) {
            return pos;
        }

        public View getView(int pos, View convert, ViewGroup parent) {
            View v = convert != null ? convert
                    : LayoutInflater.from(StoreActivity.this).inflate(R.layout.list_row, parent, false);
            StoreList.Entry e = shown.get(pos);
            TextView t = (TextView) v.findViewById(R.id.row_title);
            TextView s = (TextView) v.findViewById(R.id.row_summary);
            String tag = e.installed.length() > 0 ? e.installed : getString(R.string.installed_tag);
            t.setText(e.isInstalled ? e.name + "  (" + tag + ")" : e.name);
            int shade = StoreList.shade(e);
            t.setTextColor(shade == StoreList.FULL ? GREEN : shade == StoreList.PARTIAL ? YELLOW
                    : shade == StoreList.NONE ? RED : PLAIN);
            s.setText(e.legacy ? getString(R.string.legacy_short) + " • " + e.shortDesc : e.shortDesc);
            return v;
        }
    }
}
