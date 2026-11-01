package com.nemesis.manager;

import android.app.Activity;
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
        sizeAsPopup();
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
            showProgress(getString(R.string.downloading));
            runStoreDownload(intent);
            return;
        }
        if ("confirm_online_update".equals(mode)) {
            msg.setText(R.string.update_ready);
            confirm = true;
        } else if ("confirm_store_install".equals(mode)) {
            msg.setText(getString(R.string.confirm_store_install).replace("%s", name != null ? name : ""));
            confirm = true;
        } else if ("confirm_store_update".equals(mode) || "confirm_reinstall".equals(mode)) {
            // confirm_reinstall is the USB handler's question: the handler does the work.
            msg.setText(R.string.confirm_store_update);
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
            yes.setText(R.string.yes);
            no.setText(R.string.no);
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
        if ("confirm_store_install".equals(mode)
                || ((localPath != null || getIntent().hasExtra("nemesis_url")) && "confirm_risk".equals(mode))
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

    private void onStoreYes() {
        if (getIntent().hasExtra("nemesis_url") && localPath == null) {
            showProgress(getString(R.string.downloading));
            runStoreDownload(getIntent());
            return;
        }
        showProgress(getString(R.string.installing));
        runStoreInstall(localPath);
    }

    /** Online Store row: verified zip in the private cache, then the same install. */
    private void runStoreDownload(final Intent intent) {
        final String url = intent.getStringExtra("nemesis_url");
        final String sha = intent.getStringExtra("nemesis_sha256");
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.io.File f = Portal.download(DialogActivity.this, url, sha, "store-package.zip");
                    if (f == null) {
                        goOnUi("result_fail", getString(R.string.download_bad), null, null);
                        return;
                    }
                    final String path = f.getPath();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            showProgress(getString(R.string.installing));
                            runStoreInstall(path);
                        }
                    });
                } catch (Throwable t) {
                    CrashLog.note("update.log", "store download failed: " + t);
                    goOnUi("result_fail", getString(R.string.download_fail), null, null);
                }
            }
        }).start();
    }

    /** Update removes the old version first, so the dependency guard applies. */
    private void runStoreInstall(final String path) {
        final String replacing = getIntent().getStringExtra("nemesis_replace");
        final boolean risk = "confirm_risk".equals(mode)
                || getIntent().getBooleanExtra("nemesis_risk", false);
        new Thread(new Runnable() {
            public void run() {
                if (replacing != null) {
                    Cli.Result u = Cli.exec("uninstall", replacing);
                    if (u.rc != 0) {
                        String deps = u.rc == 4 ? dependentNames(u.out) : null;
                        finishStore(path, "result_fail", deps == null ? null
                                : getString(R.string.uninstall_blocked).replace("%s", deps));
                        return;
                    }
                }
                Cli.Result r = risk ? Cli.exec("install", "--accept-risk", path) : Cli.exec("install", path);
                finishStore(path, r.rc == 0 ? "result_ok" : "result_fail", null);
            }
        }).start();
    }

    private void finishStore(String path, String resultMode, String message) {
        if (path != null && path.startsWith(getCacheDir().getPath())) new java.io.File(path).delete();
        goOnUi(resultMode, message, null, null);
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
                        String deps = r.rc == 4 ? dependentNames(r.out) : null;
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

    /** Names from the "dependent <id>|<name>" lines of a blocked uninstall, one per line. */
    static String dependentNames(String out) {
        if (out == null) return null;
        StringBuilder sb = new StringBuilder();
        String[] lines = out.split("\n");
        for (int k = 0; k < lines.length; k++) {
            String ln = lines[k].trim();
            if (!ln.startsWith("dependent ")) continue;
            String rest = ln.substring("dependent ".length());
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
