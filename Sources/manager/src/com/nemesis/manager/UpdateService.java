package com.nemesis.manager;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.File;
import java.util.Map;

/** Android-up work and the background update check. Runs off the main thread. */
public class UpdateService extends IntentService {
    static final String ACTION_ANDROID_UP = "com.nemesis.manager.ANDROID_UP";
    static final String ACTION_TICK = "com.nemesis.manager.UPDATE_TICK";
    static final String ACTION_WOKE = "com.nemesis.manager.SCREEN_BACK";
    // CarPlay comes up about a second after the screen and would cover an earlier launch.
    private static final long WOKE_DELAY_MS = 3000;
    static final String ZIP_NAME = "nemesis-update.zip";
    private static final String PREFS = "update_check";

    public UpdateService() {
        super("NemesisUpdate");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;
        try {
            if (ACTION_ANDROID_UP.equals(intent.getAction())) AutoLaunch.run(this);
            if (ACTION_WOKE.equals(intent.getAction())) {
                SystemClock.sleep(WOKE_DELAY_MS);
                AutoLaunch.run(this);
            }
            if (!backgroundCheck()) packageCheck();
        } catch (Throwable t) {
            CrashLog.note("update.log", "service: " + t);
        }
    }

    /** Packages get their own 24 h clock. A popup on screen waits for the next tick. */
    private void packageCheck() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (DialogActivity.visible || !UpdateRule.due(System.currentTimeMillis(), p.getLong("pkg_wall", 0),
                SystemClock.elapsedRealtime(), p.getLong("pkg_elapsed", 0))) return;
        PkgUpdates.Found f = PkgUpdates.find(this);
        int n = f.updates.size();
        if (!f.onlineFailed || n > 0) saveMark(this, "pkg_wall", "pkg_elapsed");
        if (n > 0) PkgUpdates.popup(this, n);
    }

    /** Failures stay silent and leave the last-check time alone, so the next tick retries.
     *  True when this put a popup on screen. */
    private boolean backgroundCheck() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!UpdateRule.due(System.currentTimeMillis(), p.getLong("wall", 0),
                SystemClock.elapsedRealtime(), p.getLong("elapsed", 0))) return false;
        try {
            Portal.Update u = latest(this);
            if (u == null) {
                saveChecked(this);
                return false;
            }
            File f = Portal.download(this, u.url, u.sha256, ZIP_NAME);
            if (f == null) {
                CrashLog.note("update.log", "bad hash for " + u.version);
                return false;
            }
            saveChecked(this);
            Intent i = new Intent(this, DialogActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("nemesis_mode", "confirm_online_update");
            i.putExtra("nemesis_path", f.getPath());
            i.putExtra("nemesis_name", u.version);
            startActivity(i);
            return true;
        } catch (Throwable t) {
            CrashLog.note("update.log", "check failed: " + t);
            return false;
        }
    }

    static Portal.Update latest(Context c) throws Exception {
        Map<String, String> ids = Cli.fields(Cli.run("compat-check", "any", "any"));
        return Portal.latestUpdate(c, Ui.versionName(c), ids.get("os"), ids.get("model"));
    }

    static void saveChecked(Context c) {
        saveMark(c, "wall", "elapsed");
    }

    private static void saveMark(Context c, String wallKey, String elapsedKey) {
        long wall = System.currentTimeMillis();
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (UpdateRule.clockSane(wall)) e.putLong(wallKey, wall);
        e.putLong(elapsedKey, SystemClock.elapsedRealtime());
        e.commit();
    }
}
