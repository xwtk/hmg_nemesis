package com.nemesis.enginesound.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("EngineSound", "start from " + (intent == null ? "?" : intent.getAction()));
        context.startService(new Intent(context, EngineSoundService.class));
    }
}
