package com.nemesis.enginesound.audio;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Plays on the notification stream ({@link NaviBus#STREAM_ALERT}), the same
 * Android stream the SMS message tone uses. The micom gate that makes that
 * stream audible over media is opened by {@link NaviBus}.
 */
public final class CabinTrack {
    private static final String TAG = "EngineSound.audio";
    public static final int SAMPLE_RATE = 44100;

    private AudioTrack track;
    private String error = "";

    public String error() {
        return error;
    }

    public boolean isOpen() {
        return track != null
                && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    public boolean open() {
        if (isOpen()) return true;
        release();
        int min = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min < 1) {
            error = "no 44.1 stereo output";
            Log.e(TAG, error);
            return false;
        }
        int bytes = Math.max(min, SAMPLE_RATE / 5 * 4);
        try {
            track = new AudioTrack(
                    NaviBus.STREAM_ALERT,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bytes,
                    AudioTrack.MODE_STREAM);
        } catch (Throwable t) {
            error = "AudioTrack: " + t.getClass().getSimpleName();
            Log.e(TAG, error, t);
            track = null;
            return false;
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            error = "AudioTrack not initialized";
            release();
            return false;
        }
        track.play();
        error = "";
        return track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    /** {@code interleaved} length is frames*2. */
    public int write(short[] interleaved, int frames) {
        if (track == null || frames <= 0) return 0;
        return track.write(interleaved, 0, frames * 2);
    }

    public void release() {
        if (track == null) return;
        try {
            track.pause();
            track.flush();
            track.release();
        } catch (Throwable ignored) {
        }
        track = null;
    }
}
