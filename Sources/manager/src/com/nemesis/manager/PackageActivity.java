package com.nemesis.manager;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Map;

/** One installed package: its description, Launch and Uninstall. */
public class PackageActivity extends Activity {
    private String id;
    private String name = "";
    private boolean locked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.ensureWatchdog();
        id = getIntent().getStringExtra("nemesis_id");
        if (id == null) {
            Ui.close(this);
            return;
        }
        if (!Ui.setLayout(this, R.layout.activity_package)) {
            Ui.fallbackShell(this, false, true, true);
        }
        Ui.bindTitle(this, R.string.packages);
        Button a = (Button) findViewById(R.id.btn_a);
        Button b = (Button) findViewById(R.id.btn_b);
        if (a == null || b == null) return;
        a.setText(R.string.launch);
        b.setText(R.string.uninstall);
        a.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                launch();
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent ask = new Intent(PackageActivity.this, DialogActivity.class);
                if (locked) {
                    ask.putExtra("nemesis_mode", "forbidden");
                    startActivity(ask);
                    return;
                }
                ask.putExtra("nemesis_mode", "confirm_uninstall");
                ask.putExtra("nemesis_id", id);
                ask.putExtra("nemesis_message",
                        getString(R.string.confirm_uninstall).replace("%s", name));
                startActivity(ask);
            }
        });
    }

    private void launch() {
        new Thread(new Runnable() {
            public void run() {
                // Whatever the package's script printed, without nemesis's own ok line.
                final String text = Cli.run("pkg-launch", id).replace("ok launched " + id, "").trim();
                if (text.length() == 0) return;
                runOnUiThread(new Runnable() {
                    public void run() {
                        Ui.toast(PackageActivity.this, text);
                    }
                });
            }
        }).start();
    }

    /** Also the way back after Uninstall: a missing package closes this screen. */
    @Override
    protected void onResume() {
        super.onResume();
        if (id == null) return;
        new Thread(new Runnable() {
            public void run() {
                final Cli.Result r = Cli.exec("pkg-info", id);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        Map<String, String> m = Cli.fields(r.out);
                        if (r.rc != 0 || !"1".equals(m.get("installed"))) {
                            Ui.close(PackageActivity.this);
                            return;
                        }
                        show(m);
                    }
                });
            }
        }).start();
    }

    private void show(Map<String, String> m) {
        name = or(m.get("name"), id);
        locked = "1".equals(m.get("locked"));
        String ver = or(m.get("installed-version"), or(m.get("package-version"), ""));
        String desc = unescape(or(m.get("detailed-desc"), or(m.get("short-desc"), "")));
        TextView t = (TextView) findViewById(R.id.title_bar_text);
        if (t != null) t.setText(name);
        TextView body = (TextView) findViewById(R.id.log_text);
        if (body != null) {
            body.setText(getString(R.string.package_id).replace("%s", id)
                    + (ver.length() > 0 ? "\n" + getString(R.string.version_line).replace("%s", ver) : "")
                    + (desc.length() > 0 ? "\n\n" + desc : ""));
        }
    }

    private static String or(String v, String d) {
        return v == null || v.length() == 0 ? d : v;
    }

    /** pkg-list rows for My Packages. Locked packages are NEMESIS's own and stay hidden. */
    static ArrayList<TwoLineAdapter.Row> parseList(String out) {
        ArrayList<TwoLineAdapter.Row> list = new ArrayList<TwoLineAdapter.Row>();
        if (out == null) return list;
        String[] lines = out.split("\n");
        int parsed = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.startsWith("nemesis:")) continue;
            String[] p = line.split("\\|", -1);
            if (p.length < 3) continue;
            parsed++;
            if ("1".equals(p[2])) continue;
            String name = p[1].length() > 0 ? p[1] : p[0];
            String desc = p.length > 5 ? unescape(p[5]) : "";
            list.add(new TwoLineAdapter.Row(name, desc, p[0], false));
        }
        // No rows at all is an error from the CLI, not an empty unit: show it.
        if (parsed == 0 && out.trim().length() > 0) {
            list.add(new TwoLineAdapter.Row(out.trim(), "", "", false));
        }
        return list;
    }

    /** Reverses nemesis pkg-list percent-encoding (%25 %7C %0A %0D). */
    static String unescape(String s) {
        if (s.indexOf('%') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '%' && k + 2 < s.length()) {
                try {
                    sb.append((char) Integer.parseInt(s.substring(k + 1, k + 3), 16));
                    k += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
