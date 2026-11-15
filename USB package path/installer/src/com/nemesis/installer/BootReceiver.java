package com.nemesis.installer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Open the installation interface once Android has booted after the USB drop. */
public class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context context, Intent intent) {
        Intent i = new Intent(context, InstallerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(i);
        } catch (Throwable ignored) {
        }
    }
}
