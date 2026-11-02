package com.nemesis.manager;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class DialogActivity extends Activity {
    private String mode;
    private String sessionDir;
    private String targetId;
    private boolean usb;
    private boolean progressing;
    private boolean confirm;
    /** Set when this popup runs the job itself rather than answering the USB handler. */
    private String localPath;
    private String targetName;
    /** Steps of a Store job waiting for the user's yes. */
    private List<StoreList.Entry> pending;
    private boolean storeRisk;
    /** Set while a download can be cancelled. Whoever flips it first decides. */
    private volatile AtomicBoolean downloadCancel;
    /** Background checks stay quiet while this popup is on screen. */
    static volatile boolean visible;

    /** Install or update `e`. The caller adds nemesis_mode. */
    static Intent storeIntent(Context c, StoreList.Entry e) {
        Intent i = new Intent(c, DialogActivity.class);
        i.putExtra("nemesis_name", e.name);
        i.putExtra("nemesis_id", e.id);
        i.putExtra("nemesis_depends", e.depends);
        if (e.localZip.length() > 0) {
            i.putExtra("nemesis_path", e.localZip);
        } else {
            i.putExtra("nemesis_url", e.url);
            i.putExtra("nemesis_sha256", e.sha256);
        }
        if (e.isInstalled) i.putExtra("nemesis_replace", e.id);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Ui.setLayout(this, R.layout.dialog_alert)) {
            Ui.fallbackDialog(this);
        }
        Ui.bindTitle(this, R.string.title);
        sizeAsPopup();
        bind(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        sizeAsPopup();
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
    }

    /** Dimmed full window with a centered card. A floating dialog window is
     *  drawn by this head unit as a panel that rises from the bottom. */
    private void sizeAsPopup() {
        Window w = getWindow();
        if (w == null) return;
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.CENTER;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        lp.dimAmount = 0;
        w.setAttributes(lp);
        View card = findViewById(R.id.popup_card);
        if (card == null || card.getLayoutParams() == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = (int) (dm.widthPixels * 0.46f);
        int cap = Ui.dp(this, 640);
        if (width > cap) width = cap;
        if (width < 1) width = cap;
        card.setMinimumWidth(0);
        card.setMinimumHeight(0);
        card.getLayoutParams().width = width;
        card.getLayoutParams().height = WindowManager.LayoutParams.WRAP_CONTENT;
        card.requestLayout();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A popup is already up, and may be mid-job: the Updates page still shows the count.
        if ("pkg_updates".equals(intent.getStringExtra("nemesis_mode"))) return;
        setIntent(intent);
        bind(intent);
    }

    @Override
    public void onBackPressed() {
        if (downloadCancel != null) {
            cancelDownload();
            return;
        }
        if (progressing) return;
        if (confirm) writeReply("no");
        else writeReply("ok");
        Ui.close(this);
    }

    private void bind(Intent intent) {
        mode = intent.getStringExtra("nemesis_mode");
        if (mode == null) mode = "invalid";
        usb = intent.hasExtra("session_dir");
        sessionDir = intent.getStringExtra("session_dir");
        if (sessionDir == null) sessionDir = "/data/nemesis/usb-session";
        targetId = intent.getStringExtra("nemesis_id");
        localPath = intent.getStringExtra("nemesis_path");
        String name = intent.getStringExtra("nemesis_name");
        targetName = name;
        String custom = intent.getStringExtra("nemesis_message");
        if (findViewById(R.id.message) == null || findViewById(R.id.button1) == null) {
            Ui.fallbackDialog(this);
            Ui.bindTitle(this, R.string.title);
        }
        final TextView msg = (TextView) findViewById(R.id.message);
        final Button yes = (Button) findViewById(R.id.button1);
        final Button no = (Button) findViewById(R.id.button2);
        if (msg == null || yes == null || no == null) return;
        progressing = false;
        confirm = false;
        showChrome(true);
        if ("progress".equals(mode)) {
            showProgress(custom != null ? custom : getString(R.string.working));
            return;
        }
        if ("wipe_run".equals(mode)) {
            runWipe();
            return;
        }
        if ("update_check".equals(mode)) {
            showProgress(getString(R.string.checking_updates));
            runUpdateCheck();
            return;
        }
        if ("store_online".equals(mode)) {
            onStoreYes();
            return;
        }
        if ("install_blocked".equals(mode)) {
            // The handler has given up on this zip; from here this popup does the work.
            writeReply("ok");
            intent.removeExtra("session_dir");
            usb = false;
            onStoreYes();
            return;
        }
        if ("pkg_updates".equals(mode)) {
            int count = intent.getIntExtra("nemesis_count", 0);
            msg.setText(count == 1 ? getString(R.string.pkg_updates_one)
                    : getString(R.string.pkg_updates_many).replace("%d", String.valueOf(count)));
            confirm = true;
        } else if ("confirm_deps".equals(mode)) {
            msg.setText(custom);
            confirm = true;
        } else if ("confirm_online_update".equals(mode)) {
            msg.setText(R.string.update_ready);
            confirm = true;
        } else if ("confirm_store_install".equals(mode)) {
            msg.setText(getString(R.string.confirm_store_install).replace("%s", name != null ? name : ""));
            confirm = true;
        } else if ("confirm_store_update".equals(mode) || "confirm_reinstall".equals(mode)) {
            // confirm_reinstall is the USB handler's question: the handler does the work.
            String q = getString(R.string.confirm_store_update);
            msg.setText(name != null && name.length() > 0 ? q.replace("%s", name) : q.replace("\"%s\" ", ""));
            confirm = true;
        } else if ("confirm_execute".equals(mode)) {
            msg.setText(R.string.confirm_execute);
            confirm = true;
        } else if ("confirm_install".equals(mode)) {
            if (name != null && name.length() > 0)
                msg.setText(getString(R.string.confirm_install_named).replace("%s", name));
            else
                msg.setText(R.string.confirm_install);
            confirm = true;
        } else if ("confirm_risk".equals(mode) || "confirm_update_risk".equals(mode)) {
            msg.setText(R.string.confirm_risk);
            confirm = true;
        } else if ("confirm_update".equals(mode)) {
            String v = name != null ? name : "";
            msg.setText(getString(R.string.confirm_update).replace("%s", v));
            confirm = true;
        } else if ("up_to_date".equals(mode)) {
            msg.setText(R.string.up_to_date);
        } else if ("uninstall_first".equals(mode)) {
            String n = name != null && name.length() > 0 ? name : "";
            if (n.length() > 0)
                msg.setText(getString(R.string.uninstall_first).replace("%s", n));
            else
                msg.setText(R.string.uninstall_first_noname);
        } else if ("confirm_open".equals(mode)) {
            msg.setText(R.string.confirm_open);
            confirm = true;
        } else if ("update".equals(mode)) {
            String n = name != null ? name : "";
            msg.setText("Package \"" + n + "\" is already installed. Do you wish to update it?");
            confirm = true;
        } else if ("confirm_reboot".equals(mode)) {
            msg.setText(R.string.reboot_q);
            confirm = true;
        } else if ("wipe".equals(mode)) {
            msg.setText(R.string.wipe_q);
            confirm = true;
        } else if ("wipe2".equals(mode)) {
            msg.setText(R.string.wipe_q2);
            confirm = true;
        } else if ("confirm_uninstall".equals(mode)) {
            msg.setText(custom != null ? custom : getString(R.string.confirm_uninstall).replace("%s", ""));
            confirm = true;
        } else if ("confirm_stop".equals(mode)) {
            msg.setText(custom != null ? custom : getString(R.string.confirm_stop).replace("%s", ""));
            confirm = true;
        } else if ("invalid".equals(mode)) {
            msg.setText(custom != null ? custom : getString(R.string.invalid));
        } else if ("forbidden".equals(mode)) {
            msg.setText(R.string.forbidden);
        } else if ("result_ok".equals(mode)) {
            msg.setText(custom != null ? custom : getString(R.string.result_ok));
        } else {
            msg.setText(custom != null ? custom : getString(R.string.result_fail));
        }
        if (confirm) {
            boolean updates = "pkg_updates".equals(mode);
            yes.setText(updates ? R.string.view : R.string.yes);
            no.setText(updates ? R.string.not_now : R.string.no);
            yes.setVisibility(View.VISIBLE);
            yes.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    onYes();
                }
            });
            no.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    writeReply("no");
                    Ui.close(DialogActivity.this);
                }
            });
        } else {
            yes.setVisibility(View.GONE);
            no.setText(R.string.ok);
            no.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    writeReply("ok");
                    Ui.close(DialogActivity.this);
                }
            });
        }
        wireClose();
    }

    private void runReboot() {
        new Thread(new Runnable() {
            public void run() {
                if (Cli.runRc("root", "-c", "sync; /system/bin/reboot") == 0) return;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        Intent i = new Intent(getIntent());
                        i.putExtra("nemesis_mode", "result_fail");
                        bind(i);
                    }
                });
            }
        }).start();
    }

    private void onYes() {
        if ("confirm_reboot".equals(mode)) {
            showProgress(getString(R.string.rebooting));
            runReboot();
            return;
        }
        if ("wipe".equals(mode)) {
            Intent i = new Intent(getIntent());
            i.putExtra("nemesis_mode", "wipe2");
            setIntent(i);
            bind(i);
            return;
        }
        if ("wipe2".equals(mode)) {
            runWipe();
            return;
        }
        if ("confirm_uninstall".equals(mode)) {
            showProgress(getString(R.string.uninstalling));
            runUninstall();
            return;
        }
        if ("confirm_stop".equals(mode)) {
            showProgress(getString(R.string.working));
            runStop();
            return;
        }
        if ("confirm_online_update".equals(mode) || (localPath != null && "confirm_update".equals(mode))) {
            showProgress(getString(R.string.updating));
            runUpdate(localPath, false);
            return;
        }
        if ("confirm_update_risk".equals(mode)) {
            showProgress(getString(R.string.updating));
            runUpdate(localPath, true);
            return;
        }
        if ("pkg_updates".equals(mode)) {
            Intent i = new Intent(this, UpdatesActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Ui.close(this);
            return;
        }
        if ("confirm_deps".equals(mode)) {
            runJob(pending);
            return;
        }
        // A stick's confirm_risk is the handler's question: it does the install.
        if ("confirm_store_install".equals(mode) || (!usb && "confirm_risk".equals(mode))
                || "confirm_store_update".equals(mode)) {
            onStoreYes();
            return;
        }
        writeReply("yes");
        if ("confirm_install".equals(mode) || "confirm_risk".equals(mode)) {
            showProgress(getString(R.string.installing));
            return;
        }
        if ("update".equals(mode) || "confirm_update".equals(mode) || "confirm_reinstall".equals(mode)) {
            showProgress(getString(R.string.updating));
            return;
        }
        if ("confirm_open".equals(mode)) {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        }
        Ui.close(this);
    }

    /** Show another mode in this same popup. */
    private void go(String nextMode, String message) {
        if (isFinishing()) return;
        Intent i = new Intent(getIntent());
        i.putExtra("nemesis_mode", nextMode);
        if (message != null) i.putExtra("nemesis_message", message);
        else i.removeExtra("nemesis_message");
        setIntent(i);
        bind(i);
    }

    private void goOnUi(final String nextMode, final String message, final String path,
                        final String name) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (isFinishing()) return;
                Intent i = getIntent();
                if (path != null) i.putExtra("nemesis_path", path);
                if (name != null) i.putExtra("nemesis_name", name);
                go(nextMode, message);
            }
        });
    }

    /** Settings: the stick's update first, then the portal. */
    private void runUpdateCheck() {
        new Thread(new Runnable() {
            public void run() {
                String current = Ui.versionName(DialogActivity.this);
                Cli.Result usbInfo = Cli.exec("usb-info");
                java.util.Map<String, String> info = Cli.fields(usbInfo.out);
                String zip = info.get("path");
                if (usbInfo.rc == 0 && "update".equals(info.get("type")) && zip != null) {
                    java.util.Map<String, String> meta = Cli.fields(Cli.run("pkg-info", zip));
                    String v = meta.get("package-version");
                    if (v == null || !"nemesis.core".equals(meta.get("id"))) {
                        goOnUi("invalid", null, null, null);
                    } else if (UpdateRule.compare(v, current) <= 0) {
                        goOnUi("up_to_date", null, null, null);
                    } else {
                        goOnUi("confirm_update", null, zip, v);
                    }
                    return;
                }
                try {
                    Portal.Update u = UpdateService.latest(DialogActivity.this);
                    if (u == null) {
                        UpdateService.saveChecked(DialogActivity.this);
                        goOnUi("up_to_date", null, null, null);
                        return;
                    }
                    goOnUi("progress", getString(R.string.downloading), null, null);
                    java.io.File f = Portal.download(DialogActivity.this, u.url, u.sha256,
                            UpdateService.ZIP_NAME);
                    if (f == null) {
                        goOnUi("result_fail", getString(R.string.download_bad), null, null);
                        return;
                    }
                    UpdateService.saveChecked(DialogActivity.this);
                    goOnUi("confirm_online_update", null, f.getPath(), u.version);
                } catch (Throwable t) {
                    CrashLog.note("update.log", "check failed: " + t);
                    goOnUi("result_fail", getString(R.string.update_offline), null, null);
                }
            }
        }).start();
    }

    private void runUpdate(final String path, final boolean risk) {
        new Thread(new Runnable() {
            public void run() {
                final Cli.Result r = path == null ? new Cli.Result(1, "")
                        : risk ? Cli.exec("update", "--accept-risk", path) : Cli.exec("update", path);
                if (r.rc == 20) {
                    goOnUi("confirm_update_risk", null, null, null);
                } else if (r.rc == 0) {
                    /* nemesis reboots the unit; this text shows until then. */
                    runOnUiThread(new Runnable() {
                        public void run() {
                            showProgress(getString(R.string.update_rebooting));
                        }
                    });
                } else if (r.rc == 9) {
                    goOnUi("up_to_date", null, null, null);
                } else {
                    CrashLog.note("update.log", "update rc=" + r.rc + " " + r.out);
                    goOnUi("result_fail", null, null, null);
                }
            }
        }).start();
    }

    /** Works out what the package needs before anything is downloaded or changed. */
    private void onStoreYes() {
        storeRisk = "confirm_risk".equals(mode) || getIntent().getBooleanExtra("nemesis_risk", false);
        final StoreList.Entry main = new StoreList.Entry();
        main.id = targetId != null ? targetId : "";
        main.name = targetName != null ? targetName : "";
        main.url = getIntent().getStringExtra("nemesis_url");
        main.sha256 = getIntent().getStringExtra("nemesis_sha256");
        main.localZip = localPath != null ? localPath : "";
        main.isInstalled = getIntent().hasExtra("nemesis_replace");
        main.depends = getIntent().getStringExtra("nemesis_depends");
        showProgress(getString(R.string.working));
        new Thread(new Runnable() {
            public void run() {
                planStore(main);
            }
        }).start();
    }

    private void planStore(StoreList.Entry main) {
        if (main.localZip.length() > 0) {
            Cli.Result info = Cli.exec("pkg-info", main.localZip);
            Map<String, String> m = Cli.fields(info.out);
            if (m.get("id") != null) main.id = m.get("id");
            if (main.name.length() == 0 && m.get("name") != null) main.name = m.get("name");
            main.depends = m.get("depends");
            main.isInstalled = "1".equals(m.get("installed"));
            String clash = blockedMessage(info.out, main.name);
            if (clash != null) {
                goOnUi("result_fail", clash, null, null);
                return;
            }
        }
        DepPlan plan = DepPlan.of(main.id, main.depends, PkgUpdates.source(this));
        if (!plan.conflicts.isEmpty()) {
            List<String> names = new ArrayList<String>();
            for (int k = 0; k < plan.conflicts.size(); k++) {
                String n = Cli.fields(Cli.run("pkg-info", plan.conflicts.get(k))).get("name");
                names.add(n != null ? n : plan.conflicts.get(k));
            }
            goOnUi("result_fail", named(R.string.install_conflict, main.name, bullets(names)), null, null);
            return;
        }
        if (!plan.missing.isEmpty()) {
            goOnUi("result_fail", named(R.string.deps_unmet, main.name, bullets(plan.missing)), null, null);
            return;
        }
        final List<StoreList.Entry> queue = new ArrayList<StoreList.Entry>(plan.steps);
        queue.add(main);
        if (plan.steps.isEmpty()) {
            runJob(queue);
            return;
        }
        List<String> lines = new ArrayList<String>();
        for (int k = 0; k < plan.steps.size(); k++) {
            StoreList.Entry e = plan.steps.get(k);
            String line = (e.name + " " + e.version).trim();
            if (e.isInstalled) line += getString(R.string.deps_update_from).replace("%s", e.installed);
            lines.add(line);
        }
        final String ask = named(R.string.confirm_deps, main.name, bullets(lines));
        runOnUiThread(new Runnable() {
            public void run() {
                pending = queue;
                go("confirm_deps", ask);
            }
        });
    }

    private String named(int res, String name, String list) {
        return getString(res).replace("%n", name).replace("%s", list);
    }

    private static String bullets(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < items.size(); k++) sb.append("\n• ").append(items.get(k));
        return sb.toString();
    }

    /** The user-facing reason in install or pkg-info output, or null if it has none. */
    private String blockedMessage(String out, String name) {
        String conflicts = namesAfter(out, "conflict ");
        if (conflicts != null) return named(R.string.install_conflict, name, conflicts);
        String dependents = namesAfter(out, "dependent ");
        return dependents == null ? null : named(R.string.update_blocked, name, dependents);
    }

    /** Dependencies first, the package last. Runs each step in order and stops at a failure. */
    private void runJob(final List<StoreList.Entry> queue) {
        showProgress(getString(R.string.working));
        new Thread(new Runnable() {
            public void run() {
                sweepCache();
                for (int k = 0; k < queue.size(); k++) {
                    if (!runStep(queue.get(k), k == queue.size() - 1)) return;
                }
                goOnUi("result_ok", null, null, null);
            }
        }).start();
    }

    /** A power cut mid-download leaves a zip nothing else would remove. */
    private void sweepCache() {
        File[] files = getCacheDir().listFiles();
        for (int k = 0; files != null && k < files.length; k++) {
            if (files[k].getName().startsWith("store-package")) files[k].delete();
        }
    }

    private boolean runStep(StoreList.Entry e, boolean last) {
        String path = e.localZip;
        File cached = null;
        if (path.length() == 0) {
            AtomicBoolean cancel = new AtomicBoolean();
            downloadCancel = cancel;
            progressOnUi(getString(R.string.downloading_named).replace("%s", e.name), true);
            try {
                cached = Portal.download(this, e.url, e.sha256, "store-package-" + System.nanoTime() + ".zip", cancel);
            } catch (Portal.Cancelled c) {
                return false;
            } catch (Throwable t) {
                CrashLog.note("update.log", "store download failed: " + t);
                downloadCancel = null;
                goOnUi("result_fail", getString(R.string.download_fail), null, null);
                return false;
            }
            if (!cancel.compareAndSet(false, true)) {
                if (cached != null) cached.delete();
                return false;
            }
            downloadCancel = null;
            if (cached == null) {
                goOnUi("result_fail", getString(R.string.download_bad), null, null);
                return false;
            }
            path = cached.getPath();
        }
        progressOnUi(getString(R.string.installing_named).replace("%s", e.name), false);
        List<String> args = new ArrayList<String>();
        args.add("install");
        if (e.isInstalled) args.add("--update");
        if (last && storeRisk) args.add("--accept-risk");
        args.add(path);
        Cli.Result r = Cli.exec(args.toArray(new String[args.size()]));
        if (cached != null) cached.delete();
        if (r.rc == 0) return true;
        CrashLog.note("update.log", "store install " + e.id + " rc=" + r.rc);
        if (r.rc == 20 && last) {
            // Its own descriptor says no: asked here, as the Store's listing could not know.
            goOnUi("confirm_risk", null, null, null);
            return false;
        }
        String why = r.rc == 22 ? named(R.string.deps_unmet, e.name, bullets(DepPlan.unmet(r.out)))
                : blockedMessage(r.out, e.name);
        if (why == null) why = getString(R.string.result_fail);
        goOnUi("result_fail", last ? why : e.name + ": " + why, null, null);
        return false;
    }

    private void progressOnUi(final String text, final boolean download) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (isFinishing()) return;
                if (download) showDownload(text);
                else showProgress(text);
            }
        });
    }

    private void showDownload(String text) {
        showProgress(text);
        View bar = findViewById(R.id.btn_bar);
        Button yes = (Button) findViewById(R.id.button1);
        Button no = (Button) findViewById(R.id.button2);
        if (bar == null || yes == null || no == null) return;
        bar.setVisibility(View.VISIBLE);
        yes.setVisibility(View.GONE);
        no.setText(R.string.cancel);
        no.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                cancelDownload();
            }
        });
    }

    /** Ignored once the download has finished and its install is about to start. */
    private void cancelDownload() {
        AtomicBoolean c = downloadCancel;
        if (c == null || !c.compareAndSet(false, true)) return;
        downloadCancel = null;
        Ui.close(this);
    }

    private void runUninstall() {
        final String id = targetId;
        new Thread(new Runnable() {
            public void run() {
                final Cli.Result r = id == null ? new Cli.Result(1, "") : Cli.exec("uninstall", id);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        Intent i = new Intent(getIntent());
                        i.putExtra("nemesis_mode", r.rc == 0 ? "result_ok" : "result_fail");
                        String deps = r.rc == 4 ? namesAfter(r.out, "dependent ") : null;
                        if (deps != null)
                            i.putExtra("nemesis_message",
                                    getString(R.string.uninstall_blocked).replace("%s", deps));
                        else
                            i.removeExtra("nemesis_message");
                        bind(i);
                    }
                });
            }
        }).start();
    }

    private void runStop() {
        final String name = targetId;
        new Thread(new Runnable() {
            public void run() {
                final int rc = name == null ? 1 : Cli.runRc("service", "stop", name);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        Intent i = new Intent(getIntent());
                        i.putExtra("nemesis_mode", rc == 0 ? "result_ok" : "result_fail");
                        i.removeExtra("nemesis_message");
                        bind(i);
                    }
                });
            }
        }).start();
    }

    private void runWipe() {
        showProgress(getString(R.string.wiping) + "\n" + getString(R.string.working));
        new Thread(new Runnable() {
            public void run() {
                final Cli.Result r = Cli.exec(new Cli.Lines() {
                    public void line(String s) {
                        final String step = wipeStep(s);
                        if (step == null) return;
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (!isFinishing()) showProgress(getString(R.string.wiping) + "\n" + step);
                            }
                        });
                    }
                }, "wipe", "--confirm");
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        Intent i = new Intent(getIntent());
                        if (r.rc == 0) {
                            /* nemesis reboots the unit; this text shows until then. */
                            showProgress(getString(R.string.wipe_rebooting));
                            return;
                        }
                        String blocked = blockedName(r.out);
                        i.putExtra("nemesis_mode", "result_fail");
                        if (r.rc == 3 && blocked != null)
                            i.putExtra("nemesis_message",
                                    getString(R.string.wipe_blocked).replace("%s", blocked));
                        else
                            i.removeExtra("nemesis_message");
                        bind(i);
                    }
                });
            }
        }).start();
    }

    /** Names from the "<prefix><id>|<name>" lines of native output, one per line. */
    static String namesAfter(String out, String prefix) {
        if (out == null) return null;
        StringBuilder sb = new StringBuilder();
        String[] lines = out.split("\n");
        for (int k = 0; k < lines.length; k++) {
            String ln = lines[k].trim();
            if (!ln.startsWith(prefix)) continue;
            String rest = ln.substring(prefix.length());
            int bar = rest.indexOf('|');
            String name = bar < 0 ? rest : rest.substring(bar + 1);
            if (name.length() == 0) name = rest.substring(0, bar);
            sb.append("\n\u2022 ").append(name);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** Progress text for a "wipe step ..." line of nemesis wipe, else null. */
    private String wipeStep(String ln) {
        ln = ln.trim();
        if (ln.startsWith("wipe step uninstall ")) {
            String rest = ln.substring("wipe step uninstall ".length());
            int bar = rest.indexOf('|');
            String name = bar < 0 ? rest : rest.substring(bar + 1);
            if (name.length() == 0) name = rest.substring(0, bar);
            return getString(R.string.wipe_step_uninstall).replace("%s", name);
        }
        if (ln.equals("wipe step restore")) return getString(R.string.wipe_step_restore);
        if (ln.equals("wipe step remove")) return getString(R.string.wipe_step_remove);
        return null;
    }

    /** "wipe blocked <id>|<name>" from nemesis wipe. */
    static String blockedName(String out) {
        if (out == null) return null;
        String[] lines = out.split("\n");
        for (int k = 0; k < lines.length; k++) {
            String ln = lines[k].trim();
            if (!ln.startsWith("wipe blocked ")) continue;
            String rest = ln.substring("wipe blocked ".length());
            int bar = rest.indexOf('|');
            if (bar < 0) return rest;
            String name = rest.substring(bar + 1);
            return name.length() > 0 ? name : rest.substring(0, bar);
        }
        return null;
    }

    private void showProgress(String text) {
        progressing = true;
        confirm = false;
        TextView msg = (TextView) findViewById(R.id.message);
        if (msg != null) msg.setText(text);
        showChrome(false);
    }

    private void showChrome(boolean show) {
        int vis = show ? View.VISIBLE : View.GONE;
        View bar = findViewById(R.id.btn_bar);
        if (bar != null) bar.setVisibility(vis);
        View close = findViewById(R.id.btn_close);
        if (close != null) close.setVisibility(vis);
    }

    private void wireClose() {
        View c = findViewById(R.id.btn_close);
        if (c == null) return;
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (progressing) return;
                if (confirm) writeReply("no");
                else writeReply("ok");
                Ui.close(DialogActivity.this);
            }
        });
    }

    private void writeReply(String value) {
        if (!usb) return;
        File dir = new File(sessionDir);
        dir.mkdirs();
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        dir.setExecutable(true, false);
        writeOne(new File(dir, "reply"), value);
        writeOne(new File("/data/nemesis/usb-session/reply"), value);
    }

    private void writeOne(File f, String value) {
        try {
            File parent = f.getParentFile();
            if (parent != null) {
                parent.mkdirs();
                parent.setWritable(true, false);
                parent.setExecutable(true, false);
            }
            FileOutputStream fos = new FileOutputStream(f);
            fos.write((value + "\n").getBytes("UTF-8"));
            fos.close();
            f.setReadable(true, false);
            f.setWritable(true, false);
        } catch (Exception ignored) {
        }
    }
}
