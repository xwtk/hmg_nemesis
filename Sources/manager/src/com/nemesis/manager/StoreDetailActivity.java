package com.nemesis.manager;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** One Store package: its details, Install or Update, and Uninstall. Resolved again on
 *  every resume, so the buttons are right after an install or uninstall popup. */
public class StoreDetailActivity extends Activity {
    private String root;
    private String path;
    private StoreList.Entry entry;
    private int gen;
    private boolean failed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.ensureWatchdog();
        if (!Ui.setLayout(this, R.layout.activity_package)) {
            Ui.close(this);
            return;
        }
        Ui.bindTitle(this, R.string.store);
        ((TextView) findViewById(R.id.title_bar_text)).setText(getIntent().getStringExtra("store_name"));
        root = getIntent().getStringExtra("store_root");
        path = getIntent().getStringExtra("store_path");
        findViewById(R.id.btn_a).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onInstall();
            }
        });
        findViewById(R.id.btn_b).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onUninstall();
            }
        });
        show(fresh(), true, false);
    }

    private StoreList.Entry fresh() {
        StoreList.Entry e = new StoreList.Entry();
        Intent i = getIntent();
        e.id = i.getStringExtra("store_id");
        e.name = i.getStringExtra("store_name");
        e.shortDesc = i.getStringExtra("store_short");
        e.published = i.getStringExtra("store_published");
        e.path = path;
        return e;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (findViewById(R.id.log_text) == null) return;
        reload();
    }

    private void reload() {
        final int g = ++gen;
        show(entry != null ? entry : fresh(), true, false);
        new Thread(new Runnable() {
            public void run() {
                final StoreList.Entry e = fresh();
                boolean bad = false;
                try {
                    if (root != null) StoreActivity.resolveUsb(e, root);
                    else StoreActivity.resolveOnline(StoreDetailActivity.this, e);
                } catch (Throwable t) {
                    bad = true;
                    CrashLog.note("update.log", "descriptor " + e.name + ": " + t);
                }
                final boolean fail = bad;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (g == gen && !isFinishing()) show(e, false, fail);
                    }
                });
            }
        }).start();
    }

    private void show(StoreList.Entry e, boolean loading, boolean bad) {
        entry = e;
        failed = bad;
        StringBuilder sb = new StringBuilder(getString(R.string.package_id).replace("%s", e.id == null ? "" : e.id));
        if (e.version.length() > 0) sb.append('\n').append(getString(R.string.version_line).replace("%s", e.version));
        if (e.isInstalled && e.installed.length() > 0) {
            sb.append('\n').append(getString(R.string.installed_version).replace("%s", e.installed));
        }
        if (e.published.length() > 0) {
            sb.append('\n').append(getString(R.string.published_on)
                    .replace("%s", e.published.substring(0, Math.min(10, e.published.length()))));
        }
        String desc = e.detailedDesc.length() > 0 ? e.detailedDesc : e.shortDesc;
        if (desc.length() > 0) sb.append("\n\n").append(desc);
        if (loading) sb.append("\n\n").append(getString(R.string.loading_details));
        if (bad) sb.append("\n\n").append(getString(R.string.details_failed));
        ((TextView) findViewById(R.id.log_text)).setText(sb);
        TextView warn = (TextView) findViewById(R.id.warning);
        // Says why a row is yellow or red: it is the text the car itself compared.
        String why = e.compat != StoreList.FULL ? e.reasons : "";
        String legacy = e.legacy ? getString(R.string.legacy_warning) : "";
        warn.setText(legacy.length() > 0 && why.length() > 0 ? legacy + "\n" + why : legacy + why);
        warn.setVisibility(e.legacy || why.length() > 0 ? View.VISIBLE : View.GONE);
        boolean known = e.compat != StoreList.UNKNOWN && !e.locked;
        boolean differs = e.isInstalled && UpdateRule.compare(e.version, e.installed) != 0;
        Button a = (Button) findViewById(R.id.btn_a);
        Button b = (Button) findViewById(R.id.btn_b);
        a.setText(bad ? R.string.retry : differs ? R.string.update : R.string.install);
        a.setVisibility(!loading && (bad || known && (!e.isInstalled || differs)) ? View.VISIBLE : View.GONE);
        b.setText(R.string.uninstall);
        // Removing needs nothing from the portal, so a failed fetch does not hide it.
        b.setVisibility(!loading && e.isInstalled && !e.locked && (known || bad) ? View.VISIBLE : View.GONE);
    }

    private void onInstall() {
        if (failed) {
            reload();
            return;
        }
        StoreList.Entry e = entry;
        Intent i = DialogActivity.storeIntent(this, e);
        // An incompatible package gets the risk popup from either tab, update or not.
        if (e.compat == StoreList.NONE) {
            i.putExtra("nemesis_mode", "confirm_risk");
            i.putExtra("nemesis_risk", true);
        } else if (e.isInstalled) {
            i.putExtra("nemesis_mode", "confirm_store_update");
        } else {
            i.putExtra("nemesis_mode", "confirm_store_install");
        }
        startActivity(i);
    }

    private void onUninstall() {
        Intent ask = new Intent(this, DialogActivity.class);
        ask.putExtra("nemesis_mode", "confirm_uninstall");
        ask.putExtra("nemesis_id", entry.id);
        ask.putExtra("nemesis_message", getString(R.string.confirm_uninstall).replace("%s", entry.name));
        startActivity(ask);
    }
}
