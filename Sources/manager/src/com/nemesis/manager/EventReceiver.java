package com.nemesis.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** ANDROID_UP from the watchdog, UPDATE_TICK from the Update Monitor service. */
public class EventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!UpdateService.ACTION_ANDROID_UP.equals(action)
                && !UpdateService.ACTION_TICK.equals(action)) return;
        Intent i = new Intent(context, UpdateService.class);
        i.setAction(action);
        context.startService(i);
    }
}
