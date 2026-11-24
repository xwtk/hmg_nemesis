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
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DialogActivity extends Activity {
    private String mode;
    private String sessionDir;
    private String targetId;
    private boolean usb;
    private boolean progressing;
    private boolean confirm;
    /** Longest status line that fits on one line of the 640dp/26sp message view. */
    private static final int STATUS_MAX = 40;
    /** While true, poll the hooks' live status/progress files written to sessionDir. */
    private boolean tracking;
    private String progressBase = "";
    private android.os.Handler uiPoll;
    private final Runnable pollTask = new Runnable() {
        public void run() {
            if (!tracking || isFinishing()) return;
            renderJobUi();
            if (uiPoll != null) uiPoll.postDelayed(this, 400);
        }
    };
    /** Set when this popup runs the job itself rather than answering the USB handler. */
    private String localPath;
    private String targetName;
    /** Steps of a Store job waiting for the user's yes. */
    private List<StoreList.Entry> pending;
    private boolean storeRisk;
    private static final int COUNTDOWN_SEC = 15;
    private int countdown;
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!"reboot_countdown".equals(mode) || !confirm || isFinishing()) return;
            if (--countdown <= 0) {
                onYes();
                return;
            }
            showCountdown();
            uiPoll.postDelayed(this, 1000);
        }
    };
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (uiPoll != null) {
            uiPoll.removeCallbacks(pollTask);
            uiPoll.removeCallbacks(tick);
        }
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
        // A popup is already up, and may be mid-job: Package Manager still shows the count.
        if ("pkg_updates".equals(intent.getStringExtra("nemesis_mode"))) return;
        setIntent(intent);
        bind(intent);
    }

    @Override
    public void onBackPressed() {
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
        stopTracking();
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
        } else if ("confirm_modem".equals(mode)) {
            msg.setText(R.string.confirm_modem);
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
            // Downloads alone are not interrupted by a restart: they start again after it.
            msg.setText(Queue.count(Queue.list(), true) > 0 ? R.string.reboot_busy : R.string.reboot_q);
            confirm = true;
        } else if ("reboot_countdown".equals(mode)) {
            confirm = true;
            countdown = COUNTDOWN_SEC;
            showCountdown();
            if (uiPoll == null) uiPoll = new android.os.Handler();
            uiPoll.removeCallbacks(tick);
            uiPoll.postDelayed(tick, 1000);
        } else if ("confirm_resume".equals(mode)) {
            boolean unclean = false;
            List<Queue.Job> jobs = Queue.list();
            for (int k = 0; k < jobs.size(); k++) {
                if ("interrupted".equals(jobs.get(k).state) && jobs.get(k).unclean) unclean = true;
            }
            msg.setText(unclean ? R.string.confirm_resume_unclean : R.string.confirm_resume);
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
        } else if ("confirm_ota_block".equals(mode)) {
            msg.setText(R.string.confirm_block_ota);
            confirm = true;
        } else if ("confirm_selinux_off".equals(mode)) {
            msg.setText(R.string.confirm_disable_selinux);
            confirm = true;
        } else if ("confirm_adb_usb".equals(mode)) {
            msg.setText(R.string.confirm_adb_usb);
            confirm = true;
        } else if ("confirm_adb_tcp".equals(mode)) {
            msg.setText(R.string.confirm_adb_tcp);
            confirm = true;
        } else if ("wifi_warn".equals(mode)) {
            msg.setText(R.string.wifi_warn);
            confirm = true;
        } else if ("invalid".equals(mode)) {
            msg.setText(custom != null ? custom : getString(R.string.invalid));
        } else if ("forbidden".equals(mode)) {
            msg.setText(R.string.forbidden);
        } else if ("result_ok".equals(mode)) {
            msg.setText(custom != null ? custom : getString(R.string.result_ok));
            clearUiFiles();
        } else {
            String base = custom != null ? custom : getString(R.string.result_fail);
            String err = readUiFile("ui.error");
            msg.setText(err != null && err.length() > 0 ? base + "\n" + err : base);
            clearUiFiles();
        }
        if (confirm) {
            boolean updates = "pkg_updates".equals(mode);
            boolean count = "reboot_countdown".equals(mode);
            if (count) yes.setText(swap() ? R.string.swap_now : R.string.reboot_now);
            else yes.setText(updates ? R.string.view : R.string.yes);
            no.setText(updates || count ? R.string.not_now : R.string.no);
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

    private void showCountdown() {
        TextView msg = (TextView) findViewById(R.id.message);
        if (msg == null) return;
        String app = getIntent().getStringExtra("nemesis_name");
        msg.setText(getString(swap() ? R.string.swap_countdown : R.string.reboot_countdown)
                .replace("%s", app != null ? app : "apps").replace("%d", String.valueOf(countdown)));
    }

    /** A LivePatch swap: only the patched apps restart, not the head unit. */
    private boolean swap() {
        return "1".equals(getIntent().getStringExtra("nemesis_swap"));
    }

    /** Opens Package Manager on `id`'s row; installs show their progress there. */
    static void openPm(Context c, String id) {
        Intent i = new Intent(c, PackageManagerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (id != null) i.putExtra("focus_id", id);
        c.startActivity(i);
    }

    private void runReboot() {
        new Thread(new Runnable() {
            public void run() {
                // nemesis reboot syncs, and outside a job it is a real reboot.
                if (Cli.runRc("reboot") == 0) return;
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
        if ("reboot_countdown".equals(mode)) {
            // nemesis is waiting on the reply and does the reboot.
            writeReply("yes");
            // A swap restarts only the patched apps; nothing would end the progress view.
            if (swap()) Ui.close(this);
            else showProgress(getString(R.string.rebooting));
            return;
        }
        if ("confirm_resume".equals(mode)) {
            new Thread(new Runnable() {
                public void run() {
                    Cli.exec("queue", "resume", "all");
                }
            }).start();
            Downloads.resumeAll(this);
            openPm(this, null);
            Ui.close(this);
            return;
        }
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
            showProgress(getString(R.string.working));
            runUninstall();
            return;
        }
        if ("confirm_stop".equals(mode)) {
            showProgress(getString(R.string.working));
            runStop();
            return;
        }
        if ("confirm_online_update".equals(mode) || (localPath != null && "confirm_update".equals(mode))) {
            showJobProgress(getString(R.string.updating));
            runUpdate(localPath, false);
            return;
        }
        if ("confirm_update_risk".equals(mode)) {
            showJobProgress(getString(R.string.updating));
            runUpdate(localPath, true);
            return;
        }
        if ("pkg_updates".equals(mode)) {
            openPm(this, null);
            Ui.close(this);
            return;
        }
        if ("confirm_modem".equals(mode)) {
            Portal.setUseTelematics(this, true);
            Ui.close(this);
            return;
        }
        if ("confirm_ota_block".equals(mode)) {
            runHostToggle(new String[] { "ota-block", "on" }, true);
            return;
        }
        if ("confirm_selinux_off".equals(mode)) {
            runHostToggle(new String[] { "selinux", "off" }, true);
            return;
        }
        if ("confirm_adb_usb".equals(mode)) {
            runHostToggle(new String[] { "adb", "usb-on" }, false);
            return;
        }
        if ("confirm_adb_tcp".equals(mode)) {
            runHostToggle(new String[] { "adb", "tcp-on" }, false);
            return;
        }
        if ("wifi_warn".equals(mode)) {
            new Thread(new Runnable() {
                public void run() {
                    Cli.runRc("root", "-c", "am start -a android.net.wifi.PICK_WIFI_NETWORK");
                }
            }).start();
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
        // A stick's package goes to the queue; the handler opens Package Manager on it.
        if ("confirm_install".equals(mode) || "confirm_reinstall".equals(mode) || "update".equals(mode)) {
            Ui.close(this);
            return;
        }
        // What is left from the stick is a NEMESIS update, which the handler runs.
        if ("confirm_risk".equals(mode) || "confirm_update".equals(mode)) {
            showJobProgress(getString(R.string.updating));
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
                    java.io.File f = UpdateService.fetch(DialogActivity.this, new Runnable() {
                        public void run() {
                            goOnUi("progress", getString(R.string.downloading), null, null);
                        }
                    });
                    if (f == null) {
                        goOnUi("up_to_date", null, null, null);
                        return;
                    }
                    goOnUi("confirm_online_update", null, f.getPath(), UpdateService.zipVersion(f));
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
                try {
                    planStore(main);
                } catch (Throwable t) {
                    // An uncaught error here left the popup on "Please wait" with nothing happening.
                    CrashLog.note("update.log", "store plan " + main.id + ": " + t);
                    goOnUi("result_fail", null, null, null);
                }
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
            // runJob shows the progress text, which only the UI thread may do.
            runOnUiThread(new Runnable() {
                public void run() {
                    runJob(queue);
                }
            });
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

    /** Dependencies first, the package last: downloads hand each zip to the queue in that order. */
    private void runJob(List<StoreList.Entry> queue) {
        Downloads.start(this, queue, storeRisk);
        openPm(this, queue.get(queue.size() - 1).id);
        Ui.close(this);
    }

    /** Through the queue, so it waits for any install and its reboot request is held. */
    private void runUninstall() {
        final String id = targetId;
        new Thread(new Runnable() {
            public void run() {
                final Cli.Result r = id == null ? new Cli.Result(1, "") : Cli.exec("queue", "uninstall", id);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (r.rc == 0) {
                            openPm(DialogActivity.this, id);
                            Ui.close(DialogActivity.this);
                            return;
                        }
                        Intent i = new Intent(getIntent());
                        i.putExtra("nemesis_mode", "result_fail");
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

    /** Runs one `nemesis <args>` and shows result_ok/fail (showResult=true) or just closes. */
    private void runHostToggle(final String[] args, final boolean showResult) {
        showProgress(getString(R.string.working));
        new Thread(new Runnable() {
            public void run() {
                final int rc = Cli.runRc(args);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (!showResult) {
                            Ui.close(DialogActivity.this);
                            return;
                        }
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
        stopTracking();
        TextView msg = (TextView) findViewById(R.id.message);
        if (msg != null) msg.setText(text);
        showChrome(false);
    }

    /** A long-running install/uninstall/update: show the base line and poll the
     *  hooks' live status and progress until the next bind replaces this view. */
    private void showJobProgress(String base) {
        progressBase = base != null ? base : "";
        // Drop any leftovers from a previous job so stale status never flashes;
        // the native also clears at job start, this just wins the race.
        clearUiFiles();
        showProgress(progressBase);
        tracking = true;
        if (uiPoll == null) uiPoll = new android.os.Handler();
        uiPoll.removeCallbacks(pollTask);
        uiPoll.post(pollTask);
    }

    /** Delete the hooks' live UI files so no stale status/error leaks into a later popup. */
    private void clearUiFiles() {
        new File(sessionDir, "ui.status").delete();
        new File(sessionDir, "ui.progress").delete();
        new File(sessionDir, "ui.error").delete();
    }

    private void stopTracking() {
        tracking = false;
        if (uiPoll != null) uiPoll.removeCallbacks(pollTask);
        View count = findViewById(R.id.progress_count);
        View bar = findViewById(R.id.progress_bar);
        if (count != null) count.setVisibility(View.GONE);
        if (bar != null) bar.setVisibility(View.GONE);
    }

    private void renderJobUi() {
        TextView msg = (TextView) findViewById(R.id.message);
        if (msg != null) {
            String status = readUiFile("ui.status");
            if (status != null && status.length() > 0) {
                if (status.length() > STATUS_MAX) status = status.substring(0, STATUS_MAX) + "…";
                msg.setText(progressBase + "\n" + status);
            } else {
                msg.setText(progressBase);
            }
        }
        applyProgress(readUiFile("ui.progress"));
    }

    private void applyProgress(String line) {
        View countV = findViewById(R.id.progress_count);
        ProgressBar bar = (ProgressBar) findViewById(R.id.progress_bar);
        if (countV == null || bar == null) return;
        String[] t = line == null ? null : line.split("\\s+");
        if (t == null || t.length < 3) {
            countV.setVisibility(View.GONE);
            bar.setVisibility(View.GONE);
            return;
        }
        int type, max, cur;
        try {
            type = Integer.parseInt(t[0]);
            max = Integer.parseInt(t[1]);
            cur = Integer.parseInt(t[2]);
        } catch (NumberFormatException e) {
            countV.setVisibility(View.GONE);
            bar.setVisibility(View.GONE);
            return;
        }
        int pct;
        String label;
        if (type == 0) {
            pct = cur < 0 ? 0 : cur > 100 ? 100 : cur;
            label = pct + "%";
        } else {
            if (max <= 0) max = 1;
            if (cur < 0) cur = 0;
            if (cur > max) cur = max;
            pct = (int) (cur * 100L / max);
            label = cur + "/" + max;
        }
        ((TextView) countV).setText(label);
        bar.setProgress(pct);
        countV.setVisibility(View.VISIBLE);
        bar.setVisibility(View.VISIBLE);
    }

    /** First line of a hook UI file in sessionDir, or null. */
    private String readUiFile(String name) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(new File(sessionDir, name));
            try {
                byte[] buf = new byte[1024];
                int n = in.read(buf);
                if (n <= 0) return null;
                String s = new String(buf, 0, n, "UTF-8");
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(0, nl);
                return s.trim();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
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
        // The countdown has its own session; the USB handler must not read its answer.
        if (!"reboot_countdown".equals(mode)) writeOne(new File("/data/nemesis/usb-session/reply"), value);
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
