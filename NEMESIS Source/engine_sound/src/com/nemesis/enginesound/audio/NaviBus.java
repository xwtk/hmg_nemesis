package com.nemesis.enginesound.audio;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Same mix the Bluetooth SMS tone uses. That tone is not the navigation
 * bus (front speaker only) and not the music stream (silent unless the
 * selected source is media). The phone app allocates a stream with
 * {@code createStreamType(id, channel 4, type 1000)}, binds it to the
 * voice-guidance volume group, then {@code startAudio}. The PCM itself
 * is Android {@code STREAM_NOTIFICATION} (5).
 */
public final class NaviBus {
    private static final String TAG = "EngineSound.audio";
    /** {@code AudioManager.STREAM_NOTIFICATION}. SMS {@code Messagetone} uses this. */
    public static final int STREAM_ALERT = 5;
    /** {@code VolumeGroup.VOLUMEGROUP_VOICE_GUIDANCE}. */
    private static final int VOICE_GUIDANCE = 0x27;
    /** Channel the SMS notifier passes. 4 selects the TTS route, not a speaker. */
    private static final int CHANNEL = 4;
    /** {@code StreamTypeParameter.POSITION_FRONT_REAR}. On this spec that is every speaker. */
    private static final int POSITION_ALL = 6;
    /**
     * {@code SoundPriorityOption.SAME_MIX_AV_NAVI}, same value as
     * {@code NAVI_TTS.PRIORITY_SAME}. Zero is {@code NOT_USE}: the service
     * then falls through to navigation-first, which mutes media and leaves
     * the tone on the driver speaker.
     */
    private static final int PRIORITY_SAME = 3;
    /** Type 1000 is rewritten by the audio service into the alert stream flag. */
    private static final int ALERT_TYPE = 1000;

    private final Context app;
    private Object micom;
    private AudioManager audio;
    private int streamType = -1;
    private int savedVolume = -1;

    public NaviBus(Context ctx) {
        app = ctx.getApplicationContext();
    }

    /** @return empty on success, otherwise a short reason */
    public String open() {
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (!ensureMicom()) {
            return "audio service missing";
        }
        int id = createStream();
        if (id < 0) {
            Log.e(TAG, "createStreamType failed");
            return "alert mix not created";
        }
        streamType = id;
        // Type 1000 is a navigation-priority beep. Left alone, the amp mutes
        // media and plays the tone on the driver speaker. Same-priority mix,
        // front+rear position, and mute-main off keep media on every speaker.
        tuneMix(id);
        callBool("setStreamTypeVolumeGroup", new Class<?>[] {int.class, int.class},
                new Object[] {Integer.valueOf(id), Integer.valueOf(VOICE_GUIDANCE)});
        Boolean started = callBool("startAudio", new Class<?>[] {int.class},
                new Object[] {Integer.valueOf(id)});
        // startAudio reads the parameter once. Set it again in case that
        // call rebuilt the stream from the type-1000 defaults.
        tuneMix(id);
        liftIfMuted();
        hideVolumePopup();
        boolean ok = started != null && started.booleanValue();
        Log.i(TAG, "alert mix stream=" + id + " started=" + ok);
        return ok ? "" : "alert mix not started";
    }

    public void close() {
        if (micom != null && streamType >= 0) {
            callBool("endAudio", new Class<?>[] {int.class},
                    new Object[] {Integer.valueOf(streamType)});
        }
        restoreVolume();
        Log.i(TAG, "alert mix closed");
    }

    private boolean ensureMicom() {
        if (micom != null) return true;
        try {
            Class<?> c = Class.forName("com.hkmc.media.MicomAudioManager");
            micom = c.getConstructor(Context.class).newInstance(app);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "MicomAudioManager", t);
            return false;
        }
    }

    private int createStream() {
        try {
            Method m = micom.getClass().getMethod(
                    "createStreamType", String.class, int.class, int.class);
            Object r = m.invoke(micom, app.getPackageName(), Integer.valueOf(CHANNEL),
                    Integer.valueOf(ALERT_TYPE));
            if (r instanceof Integer) return ((Integer) r).intValue();
        } catch (Throwable t) {
            Log.w(TAG, "createStreamType", t);
        }
        return -1;
    }

    private void tuneMix(int id) {
        try {
            Object stp = micom.getClass().getMethod("getStreamTypeParameter", int.class)
                    .invoke(micom, Integer.valueOf(id));
            if (stp == null) {
                Log.w(TAG, "no stream parameter");
                return;
            }
            Class<?> sp = stp.getClass();
            sp.getMethod("setSoundPosition", int.class)
                    .invoke(stp, Integer.valueOf(POSITION_ALL));
            sp.getMethod("setSoundPriority", int.class)
                    .invoke(stp, Integer.valueOf(PRIORITY_SAME));
            try {
                sp.getMethod("setMuteMain", boolean.class)
                        .invoke(stp, Boolean.FALSE);
                sp.getMethod("setForcedAVUnmute", boolean.class)
                        .invoke(stp, Boolean.TRUE);
            } catch (Throwable ignored) {
                // Position and priority are the mix. Mute flags are optional.
            }
            micom.getClass().getMethod("setStreamTypeParameter", int.class, sp)
                    .invoke(micom, Integer.valueOf(id), stp);
            Log.i(TAG, "mix position=all priority=same muteMain=off");
        } catch (Throwable t) {
            Log.w(TAG, "tuneMix", t);
        }
    }

    private Boolean callBool(String name, Class<?>[] sig, Object[] args) {
        try {
            Method m = micom.getClass().getMethod(name, sig);
            Object r = m.invoke(micom, args);
            if (r instanceof Boolean) return (Boolean) r;
            return Boolean.TRUE;
        } catch (Throwable t) {
            Log.w(TAG, name, t);
            return null;
        }
    }

    private void liftIfMuted() {
        if (audio == null) return;
        try {
            int cur = audio.getStreamVolume(STREAM_ALERT);
            int max = audio.getStreamMaxVolume(STREAM_ALERT);
            Log.i(TAG, "alert volume " + cur + "/" + max);
            if (cur == 0 && max > 0) {
                savedVolume = 0;
                audio.setStreamVolume(STREAM_ALERT, Math.max(1, max * 2 / 3), 0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "alert volume", t);
        }
    }

    private void restoreVolume() {
        if (savedVolume < 0 || audio == null) return;
        try {
            audio.setStreamVolume(STREAM_ALERT, savedVolume, 0);
        } catch (Throwable ignored) {
        }
        savedVolume = -1;
    }

    private void hideVolumePopup() {
        if (micom == null) return;
        try {
            micom.getClass().getMethod("hideVolumeDisplay", boolean.class)
                    .invoke(micom, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }
}
