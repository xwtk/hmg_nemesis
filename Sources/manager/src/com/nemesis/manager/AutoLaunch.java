package com.nemesis.manager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/** Apps started once per boot and when the screen comes back, in the order they were checked. */
final class AutoLaunch {
    private static final String PREFS = "auto_launch";
    private static final String KEY = "packages";
    private static final long GAP_MS = 1500;
    static final String ACTION_SCREEN_ON = "com.mobis.intent.action.SCREEN_ON";
    static final String ACTION_SCREEN_OFF = "com.mobis.intent.action.SCREEN_OFF";
    private static final String KEY_OFF = "screen_off_at";

    private AutoLaunch() {
    }

    static List<String> load(Context c) {
        List<String> out = new ArrayList<String>();
        String saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        String[] p = saved.split("\n");
        for (int i = 0; i < p.length; i++) {
            if (p[i].length() > 0) out.add(p[i]);
        }
        return out;
    }

    static void save(Context c, List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkgs.size(); i++) sb.append(pkgs.get(i)).append('\n');
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString(KEY, sb.toString());
        e.commit();
    }

    static void markOff(Context c) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putLong(KEY_OFF, SystemClock.elapsedRealtime());
        e.commit();
    }

    /** SCREEN_ON also fires with the screen already on, so only one after SCREEN_OFF counts.
     *  An off time past the current uptime is from before a reboot: ANDROID_UP covers that. */
    static boolean takeOff(Context c, long now) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long off = p.getLong(KEY_OFF, 0);
        if (off == 0) return false;
        p.edit().remove(KEY_OFF).commit();
        return off <= now;
    }

    /** The app's main activity only. MainActivity has no launcher category of its own. */
    static Intent launchIntent(Context c, String pkg) {
        Intent i;
        if (c.getPackageName().equals(pkg)) i = new Intent(c, MainActivity.class);
        else i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    static void run(Context c) throws InterruptedException {
        List<String> pkgs = load(c);
        for (int i = 0; i < pkgs.size(); i++) {
            if (i > 0) Thread.sleep(GAP_MS);
            String pkg = pkgs.get(i);
            try {
                Intent launch = launchIntent(c, pkg);
                if (launch == null) {
                    CrashLog.note("autolaunch.log", "no main activity: " + pkg);
                    continue;
                }
                c.startActivity(launch);
            } catch (Throwable t) {
                CrashLog.note("autolaunch.log", pkg + ": " + t);
            }
        }
    }
}
