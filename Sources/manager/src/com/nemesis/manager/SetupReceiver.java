package com.nemesis.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * The chosen button's release opens Manager on the third press.
 * Waiting here for am start blocks the main thread, so the activity never
 * becomes idle and the launch times out. The shell is started and this
 * returns at once. A second start a second later covers the button's own
 * screen (Settings, for SETUP). MainActivity re-fronts itself once if that
 * screen still covers it (ButtonOpen).
 */
public class SetupReceiver extends BroadcastReceiver {
    private static final String AM =
            "am start -n com.nemesis.manager/.MainActivity -f 0x10000000";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Trigger.matches(context, action)) {
            // A press of the other button is the usual "does not open" cause.
            CrashLog.note("press.log", action + " ignored, trigger is " + Trigger.label(context));
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean open, counted;
        synchronized (SetupReceiver.class) {
            SharedPreferences prefs = context.getSharedPreferences(
                    "setup_press", Context.MODE_PRIVATE);
            SetupPress presses = new SetupPress();
            presses.restore(prefs.getInt("count", 0), prefs.getLong("last", 0));
            presses.restoreFamily(prefs.getBoolean("mobis", false));
            int before = presses.count();
            long last = presses.lastMs();
            open = presses.note(now, action);
            counted = open || presses.count() != before || presses.lastMs() != last;
            prefs.edit().putInt("count", presses.count()).putLong("last", presses.lastMs())
                    .putBoolean("mobis", presses.sawMobis()).commit();
        }
        CrashLog.note("press.log", action + " t=" + now + (counted ? " counted" : " ignored")
                + (open ? " open" : ""));
        if (!open) return;
        ButtonOpen.mark();
        try {
            new ProcessBuilder(
                    "/system/xbin/nemesis", "root", "-c",
                    AM + "; sleep 1; " + AM)
                    .redirectErrorStream(true)
                    .start();
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(i);
        } catch (Throwable ignored) {
        }
    }
}
