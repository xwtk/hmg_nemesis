package com.nemesis.manager;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Updates, installs in progress, interrupted or failed installs, and installed packages.
 *  Every install, update and uninstall shows here; there are no progress popups. */
public class PackageManagerActivity extends Activity {
    private static final int HEAD = 0;
    private static final int UPDATE = 1;
    private static final int JOB = 2;
    private static final int DL = 3;
    private static final int INSTALLED = 4;
    private static final int POLL_MS = 500;

    private static final class Row {
        int kind;
        int section;
        String title = "";
        String summary = "";
        String count = "";
        String icon = "";
        /** -2 no bar, -1 sweep, else percent. */
        int pct = -2;
        String id = "";
        StoreList.Entry entry;
        Queue.Job job;
        Downloads.Item item;
    }

    private static final int[] SECTIONS = {
            R.string.sec_updates, R.string.sec_progress, R.string.sec_interrupted, R.string.sec_installed};
    private final boolean[] open = {true, true, true, true};
    private final Handler handler = new Handler();
    private List<Row> rows = new ArrayList<Row>();
    private List<StoreList.Entry> updates = new ArrayList<StoreList.Entry>();
    private ArrayList<TwoLineAdapter.Row> installed = new ArrayList<TwoLineAdapter.Row>();
    /** id to installed version, from pkg-list. */
    private Map<String, String> versions = new HashMap<String, String>();
    private String jobSig = "";
    private String focusId;
    private int updatesGen;
    private boolean polling;
    private final Adapter adapter = new Adapter();

    private final Runnable poll = new Runnable() {
        public void run() {
            if (!polling || isFinishing()) return;
            rebuild();
            handler.postDelayed(this, POLL_MS);
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
        Ui.bindTitle(this, R.string.pkg_manager);
        findViewById(R.id.btn_bar).setVisibility(View.GONE);
        TextView empty = (TextView) findViewById(R.id.empty);
        empty.setText(R.string.empty_packages);
        ListView lv = (ListView) findViewById(R.id.list);
        lv.setEmptyView(empty);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= rows.size()) return;
                Row r = rows.get(pos);
                if (r.kind == HEAD) {
                    open[r.section] = !open[r.section];
                    rebuild();
                } else if (r.kind == INSTALLED) {
                    Intent i = new Intent(PackageManagerActivity.this, PackageActivity.class);
                    i.putExtra("nemesis_id", r.id);
                    startActivity(i);
                }
            }
        });
        take(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        take(intent);
    }

    private void take(Intent i) {
        focusId = i.getStringExtra("focus_id");
        if (!i.getBooleanExtra("ask_resume", false)) return;
        i.removeExtra("ask_resume");
        Intent ask = new Intent(this, DialogActivity.class);
        ask.putExtra("nemesis_mode", "confirm_resume");
        startActivity(ask);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (findViewById(R.id.list) == null) return;
        jobSig = null;
        loadUpdates();
        polling = true;
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        handler.removeCallbacks(poll);
    }

    /** The network part: once per visit, not per poll. */
    private void loadUpdates() {
        final int g = ++updatesGen;
        new Thread(new Runnable() {
            public void run() {
                final List<StoreList.Entry> found = PkgUpdates.find(PackageManagerActivity.this).updates;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (g != updatesGen || isFinishing()) return;
                        updates = found;
                        rebuild();
                    }
                });
            }
        }).start();
    }

    /** pkg-list again whenever a job came or went. */
    private void loadInstalled() {
        new Thread(new Runnable() {
            public void run() {
                String out = Cli.run("pkg-list");
                final ArrayList<TwoLineAdapter.Row> next = PackageActivity.parseList(out);
                final Map<String, String> vers = StoreList.parseInstalled(out);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        installed = next;
                        versions = vers;
                        rebuild();
                    }
                });
            }
        }).start();
    }

    private void rebuild() {
        List<Queue.Job> jobs = Queue.list();
        List<Downloads.Item> dls = Downloads.items(this);
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < jobs.size(); i++) sig.append(jobs.get(i).seq).append(jobs.get(i).state).append(',');
        if (!sig.toString().equals(jobSig)) {
            jobSig = sig.toString();
            loadInstalled();
        }
        int running = Queue.runningSeq();
        List<Row> progress = new ArrayList<Row>();
        List<Row> stopped = new ArrayList<Row>();
        java.util.HashSet<String> busy = new java.util.HashSet<String>();
        for (int i = 0; i < dls.size(); i++) {
            Downloads.Item it = dls.get(i);
            Row r = dlRow(it);
            busy.add(it.id);
            (it.state == Downloads.DL ? progress : stopped).add(r);
        }
        for (int i = 0; i < jobs.size(); i++) {
            Queue.Job j = jobs.get(i);
            Row r = jobRow(j, j.seq == running);
            busy.add(j.id);
            (j.active() ? progress : stopped).add(r);
        }
        List<Row> ups = new ArrayList<Row>();
        for (int i = 0; i < updates.size(); i++) {
            StoreList.Entry e = updates.get(i);
            String have = versions.get(e.id);
            if (busy.contains(e.id) || (have != null && UpdateRule.compare(e.version, have) <= 0)) continue;
            Row r = new Row();
            r.kind = UPDATE;
            r.entry = e;
            r.id = e.id;
            r.title = e.name;
            r.summary = e.installed + " > " + e.version;
            r.icon = e.favicon.length() > 0 ? e.favicon : Icons.forId(e.id);
            ups.add(r);
        }
        List<Row> inst = new ArrayList<Row>();
        for (int i = 0; i < installed.size(); i++) {
            TwoLineAdapter.Row t = installed.get(i);
            if (t.key.length() == 0) continue;
            Row r = new Row();
            r.kind = INSTALLED;
            r.id = t.key;
            r.title = t.title;
            r.summary = t.summary;
            r.icon = t.icon;
            inst.add(r);
        }
        List<Row> next = new ArrayList<Row>();
        // Updates and In progress fold away; an empty In progress or Interrupted is not shown.
        section(next, 0, ups, true);
        section(next, 1, progress, false);
        section(next, 2, stopped, false);
        section(next, 3, inst, true);
        rows = next;
        adapter.notifyDataSetChanged();
        focus();
    }

    private void section(List<Row> out, int s, List<Row> items, boolean showEmpty) {
        if (items.isEmpty() && !showEmpty) return;
        Row h = new Row();
        h.kind = HEAD;
        h.section = s;
        h.title = (open[s] ? "▾  " : "▸  ") + getString(SECTIONS[s])
                + (items.isEmpty() ? "" : "  (" + items.size() + ")");
        out.add(h);
        if (open[s]) out.addAll(items);
    }

    /** Scroll once to the row a caller asked for: its job, its download, or the package. */
    private void focus() {
        if (focusId == null) return;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.kind == HEAD || !focusId.equals(r.id)) continue;
            ((ListView) findViewById(R.id.list)).setSelection(i);
            focusId = null;
            return;
        }
    }

    private Row dlRow(Downloads.Item it) {
        Row r = new Row();
        r.kind = DL;
        r.item = it;
        r.id = it.id;
        r.title = it.name;
        r.icon = it.favicon.length() > 0 ? it.favicon : Icons.forId(it.id);
        if (it.state == Downloads.FAILED) {
            r.summary = it.error;
        } else if (it.state == Downloads.INTERRUPTED) {
            r.summary = getString(R.string.download_interrupted);
        } else if (it.done) {
            r.summary = getString(R.string.queued);
        } else if (!it.started) {
            r.summary = getString(R.string.waiting_download);
        } else {
            r.summary = getString(R.string.downloading);
            long cur = it.prog[0], max = it.prog[1];
            r.pct = max > 0 ? (int) Math.min(100, cur * 100 / max) : -1;
            r.count = max > 0 ? r.pct + "%" : mb(cur);
        }
        return r;
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private Row jobRow(Queue.Job j, boolean running) {
        Row r = new Row();
        r.kind = JOB;
        r.job = j;
        r.id = j.id;
        r.title = j.name;
        r.icon = Icons.forId(j.id);
        boolean uninstall = "uninstall".equals(j.action);
        if ("repair".equals(j.action) && !j.active()) {
            r.summary = getString(R.string.lp_damaged);
        } else if ("interrupted".equals(j.state)) {
            r.summary = getString(j.unclean ? R.string.interrupted_unclean : R.string.interrupted);
        } else if ("failed".equals(j.state)) {
            r.summary = failReason(j);
        } else if (!running) {
            r.summary = getString(uninstall ? R.string.queued_uninstall : R.string.queued);
        } else {
            String status = Queue.firstLine(new File(Queue.SESSION, "ui.status"));
            r.summary = status != null && status.length() > 0 ? status
                    : getString(uninstall ? R.string.uninstalling
                    : versions.containsKey(j.id) ? R.string.updating : R.string.installing);
            r.pct = -1;
            progress(r, Queue.firstLine(new File(Queue.SESSION, "ui.progress")));
        }
        return r;
    }

    /** ui.progress is "type max cur": type 0 is a percent, else cur of max. */
    private static void progress(Row r, String line) {
        String[] t = line == null ? new String[0] : line.split("\\s+");
        if (t.length < 3) return;
        try {
            int type = Integer.parseInt(t[0]), max = Integer.parseInt(t[1]), cur = Integer.parseInt(t[2]);
            if (type == 0) {
                r.pct = Math.max(0, Math.min(100, cur));
                r.count = r.pct + "%";
            } else {
                if (max <= 0) max = 1;
                cur = Math.max(0, Math.min(max, cur));
                r.pct = (int) (cur * 100L / max);
                r.count = cur + "/" + max;
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /** What the failed install or uninstall printed, in the words the popups used. */
    private String failReason(Queue.Job j) {
        String out = Queue.read(new File(Queue.DIR, String.format("%08d.out", j.seq)), 65536);
        if (j.rc == 20) return getString(R.string.confirm_risk);
        String deps = DialogActivity.namesAfter(out, "dependent ");
        if (j.rc == 4 && deps != null) return getString(R.string.uninstall_blocked).replace("%s", deps);
        String conflicts = DialogActivity.namesAfter(out, "conflict ");
        if (conflicts != null) return named(R.string.install_conflict, j.name, conflicts);
        if (deps != null) return named(R.string.update_blocked, j.name, deps);
        if (j.rc == 22) {
            List<String> unmet = DepPlan.unmet(out);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < unmet.size(); k++) sb.append("\n• ").append(unmet.get(k));
            return named(R.string.deps_unmet, j.name, sb.toString());
        }
        String last = Queue.lastLine(out);
        return last.length() > 0 ? last : getString(R.string.result_fail);
    }

    private String named(int res, String name, String list) {
        return getString(res).replace("%n", name).replace("%s", list);
    }

    private void cli(final String... args) {
        new Thread(new Runnable() {
            public void run() {
                Cli.Result r = Cli.exec(args);
                if (r.rc != 0) CrashLog.note("update.log", "pm " + args[1] + " rc=" + r.rc + " " + r.out);
            }
        }).start();
    }

    /** The row's button and its X or Delete icon. */
    private void bindActions(View v, final Row r) {
        Button act = (Button) v.findViewById(R.id.row_action);
        ImageButton x = (ImageButton) v.findViewById(R.id.row_x);
        act.setVisibility(View.GONE);
        x.setVisibility(View.GONE);
        act.setOnClickListener(null);
        x.setOnClickListener(null);
        if (r.kind == UPDATE) {
            act.setText(R.string.update);
            act.setVisibility(View.VISIBLE);
            act.setOnClickListener(new View.OnClickListener() {
                public void onClick(View b) {
                    Intent i = DialogActivity.storeIntent(PackageManagerActivity.this, r.entry);
                    i.putExtra("nemesis_mode", "store_online");
                    startActivity(i);
                }
            });
        } else if (r.kind == DL) {
            final Downloads.Item it = r.item;
            final boolean stopped = it.state != Downloads.DL;
            if (stopped) {
                act.setText(it.state == Downloads.FAILED ? R.string.retry : R.string.resume);
                act.setVisibility(View.VISIBLE);
                act.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View b) {
                        Downloads.resume(PackageManagerActivity.this, it.group);
                        rebuild();
                    }
                });
            }
            // Once handed to the queue the zip is not ours to cancel.
            if (stopped || !it.done) {
                x.setImageResource(stopped ? android.R.drawable.ic_menu_delete : R.drawable.ic_close);
                x.setVisibility(View.VISIBLE);
                x.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View b) {
                        Downloads.drop(PackageManagerActivity.this, it.group);
                        rebuild();
                    }
                });
            }
        } else if (r.kind == JOB) {
            final Queue.Job j = r.job;
            final String seq = String.valueOf(j.seq);
            if (!j.active()) {
                final boolean risk = j.rc == 20;
                act.setText(risk ? R.string.install_anyway
                        : "interrupted".equals(j.state) && !"repair".equals(j.action) ? R.string.resume : R.string.retry);
                act.setVisibility(View.VISIBLE);
                act.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View b) {
                        if (risk) cli("queue", "resume", seq, "--accept-risk");
                        else cli("queue", "resume", seq);
                    }
                });
            }
            if (!"running".equals(j.state)) {
                x.setImageResource(j.active() ? R.drawable.ic_close : android.R.drawable.ic_menu_delete);
                x.setVisibility(View.VISIBLE);
                x.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View b) {
                        cli("queue", "delete", seq);
                    }
                });
            }
        }
    }

    private final class Adapter extends BaseAdapter {
        public int getCount() {
            return rows.size();
        }

        public Object getItem(int pos) {
            return rows.get(pos);
        }

        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int pos) {
            return rows.get(pos).kind == HEAD ? 0 : 1;
        }

        public View getView(int pos, View convert, ViewGroup parent) {
            Row r = rows.get(pos);
            LayoutInflater inf = LayoutInflater.from(PackageManagerActivity.this);
            if (r.kind == HEAD) {
                View v = convert != null ? convert : inf.inflate(R.layout.pm_header, parent, false);
                ((TextView) v).setText(r.title);
                return v;
            }
            View v = convert != null ? convert : inf.inflate(R.layout.pm_row, parent, false);
            Icons.set((ImageView) v.findViewById(R.id.row_icon), r.icon);
            ((TextView) v.findViewById(R.id.row_title)).setText(r.title);
            TextView s = (TextView) v.findViewById(R.id.row_summary);
            s.setText(r.summary);
            s.setVisibility(r.summary.length() > 0 ? View.VISIBLE : View.GONE);
            TextView c = (TextView) v.findViewById(R.id.row_count);
            c.setText(r.count);
            c.setVisibility(r.count.length() > 0 ? View.VISIBLE : View.GONE);
            SweepBar bar = (SweepBar) v.findViewById(R.id.row_bar);
            bar.setVisibility(r.pct == -2 ? View.GONE : View.VISIBLE);
            bar.setPct(r.pct);
            bindActions(v, r);
            return v;
        }
    }
}
