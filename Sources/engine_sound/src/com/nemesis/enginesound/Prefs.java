package com.nemesis.enginesound;

import android.content.Context;
import android.content.SharedPreferences;

import com.nemesis.enginesound.synth.SampleVoice;

public final class Prefs {
    private static final String NAME = "engine_sound";
    private static final String ENABLED = "enabled";
    private static final String PROFILE = "profile";
    private static final String VOLUME = "volume";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public boolean enabled() {
        return sp.getBoolean(ENABLED, false);
    }

    public void setEnabled(boolean on) {
        sp.edit().putBoolean(ENABLED, on).commit();
    }

    public String profile() {
        return sp.getString(PROFILE, SampleVoice.G37);
    }

    public void setProfile(String id) {
        if (id == null) return;
        sp.edit().putString(PROFILE, id).commit();
    }

    /** 0..100 */
    public int volume() {
        return sp.getInt(VOLUME, 70);
    }

    public void setVolume(int percent) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        sp.edit().putInt(VOLUME, percent).commit();
    }
}
