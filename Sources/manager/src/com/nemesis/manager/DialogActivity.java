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
        finish();
    }

    private void bind(Intent intent) {
        mode = intent.getStringExtra("nemesis_mode");
        if (mode == null) mode = "invalid";
        usb = intent.hasExtra("session_dir");
        sessionDir = intent.getStringExtra("session_dir");
        if (sessionDir == null) sessionDir = "/data/nemesis/usb-session";
        targetId = intent.getStringExtra("nemesis_id");
        String name = intent.getStringExtra("nemesis_name");
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
            showProgress(getString(R.string.wiping));
            runWipe();
            return;
        }
        if ("confirm_execute".equals(mode)) {
            msg.setText(R.string.confirm_execute);
            confirm = true;
        } else if ("confirm_install".equals(mode)) {
            if (name != null && name.length() > 0)
                msg.setText(getString(R.string.confirm_install_named).replace("%s", name));
            else
                msg.setText(R.string.confirm_install);
            confirm = true;
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
        } else if ("wipe".equals(mode)) {
            msg.setText(R.string.wipe_q);
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
                    finish();
                }
            });
        } else {
            yes.setVisibility(View.GONE);
            no.setText(R.string.ok);
            no.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    writeReply("ok");
                    finish();
                }
            });
        }
        wireClose();
    }

    private void onYes() {
        if ("wipe".equals(mode)) {
            showProgress(getString(R.string.wiping));
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
        writeReply("yes");
        if ("confirm_install".equals(mode)) {
            showProgress(getString(R.string.installing));
            return;
        }
        if ("update".equals(mode)) {
            showProgress(getString(R.string.updating));
            return;
        }
        if ("confirm_open".equals(mode)) {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        }
        finish();
    }

    private void runUninstall() {
        final String id = targetId;
        new Thread(new Runnable() {
            public void run() {
                final Cli.Result r = id == null ? null : Cli.exec("uninstall", id);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        boolean ok = r != null && r.rc == 0;
                        Intent i = new Intent(getIntent());
                        i.putExtra("nemesis_mode", ok ? "result_ok" : "result_fail");
                        i.removeExtra("nemesis_message");
                        if (!ok && r != null && r.out.trim().length() > 0)
                            i.putExtra("nemesis_message", r.out.trim());
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
        new Thread(new Runnable() {
            public void run() {
                final int rc = Cli.runRc("wipe", "--confirm");
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (rc == 0 || isFinishing()) return;
                        Intent i = new Intent(getIntent());
                        i.putExtra("nemesis_mode", "result_fail");
                        bind(i);
                    }
                });
            }
        }).start();
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
                finish();
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
