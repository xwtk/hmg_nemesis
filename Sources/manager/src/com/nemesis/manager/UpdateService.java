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
            sweep(this);
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
            File f = fetch(this, null);
            if (f == null) return false;
            Intent i = new Intent(this, DialogActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("nemesis_mode", "confirm_online_update");
            i.putExtra("nemesis_path", f.getPath());
            i.putExtra("nemesis_name", zipVersion(f));
            startActivity(i);
            return true;
        } catch (Throwable t) {
            CrashLog.note("update.log", "check failed: " + t);
            return false;
        }
    }

    /** The downloaded update, or null when none is newer than this install. A zip that is
     *  no newer (updated since, by stick or online) or unreadable is deleted. */
    private static File held(Context c) {
        File f = new File(c.getCacheDir(), ZIP_NAME);
        if (!f.isFile()) return null;
        String v = zipVersion(f);
        if (v != null && UpdateRule.compare(v, Ui.versionName(c)) > 0) return f;
        f.delete();
        return null;
    }

    static String zipVersion(File f) {
        Map<String, String> m = Cli.fields(Cli.run("pkg-info", f.getPath()));
        return "nemesis.core".equals(m.get("id")) ? m.get("package-version") : null;
    }

    /** Partial downloads and leftovers; a Store popup on screen may own one, so it waits. */
    static void sweep(Context c) {
        if (DialogActivity.visible) return;
        File[] files = c.getCacheDir().listFiles();
        for (int k = 0; files != null && k < files.length; k++) {
            String n = files[k].getName();
            if (n.startsWith("store-package") || n.equals(ZIP_NAME + ".part")) files[k].delete();
        }
        held(c);
    }

    /** The update to offer: the one on disk when the portal has nothing newer or cannot be
     *  reached, else a fresh download (replacing an older one). Null when up to date.
     *  `onDownload` runs just before a download starts. */
    static File fetch(Context c, Runnable onDownload) throws Exception {
        File have = held(c);
        Portal.Update u;
        try {
            u = latest(c);
        } catch (Exception e) {
            if (have != null) return have;
            throw e;
        }
        // The portal answered: this check counts, even when it only offers what is on disk.
        if (u == null) {
            if (have != null) have.delete();
            saveChecked(c);
            return null;
        }
        if (have != null) {
            if (u.version.equals(zipVersion(have))) {
                saveChecked(c);
                return have;
            }
            have.delete();
        }
        if (onDownload != null) onDownload.run();
        File f = Portal.download(c, u.url, u.sha256, ZIP_NAME);
        if (f == null) throw new java.io.IOException("bad hash for " + u.version);
        saveChecked(c);
        return f;
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
