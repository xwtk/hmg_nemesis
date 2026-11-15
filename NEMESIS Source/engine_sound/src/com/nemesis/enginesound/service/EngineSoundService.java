package com.nemesis.enginesound.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.nemesis.enginesound.Prefs;
import com.nemesis.enginesound.R;
import com.nemesis.enginesound.audio.CabinTrack;
import com.nemesis.enginesound.audio.LoopBank;
import com.nemesis.enginesound.audio.NaviBus;
import com.nemesis.enginesound.model.Gearbox;
import com.nemesis.enginesound.model.Inputs;
import com.nemesis.enginesound.synth.EngineVoice;
import com.nemesis.enginesound.synth.V6Synth;
import com.nemesis.enginesound.vehicle.VehicleProbe;
import com.nemesis.enginesound.vehicle.VehicleSnapshot;

/**
 * Polls the car, runs the fake gearbox, and writes the V6 into the cabin mix
 * whenever the feature is on and the car is running.
 */
public final class EngineSoundService extends Service {
    private static final String TAG = "EngineSound";
    private static final int NOTE_ID = 42;
    private static final long POLL_MS = 50L;

    private final Binder binder = new LocalBinder();
    private final Handler handler = new Handler();
    private final Gearbox box = new Gearbox();
    private final Inputs inputs = new Inputs();

    private Prefs prefs;
    private VehicleProbe probe;
    private CabinTrack cabin;
    private NaviBus navi;
    private volatile EngineVoice voice;
    private PowerManager.WakeLock wake;
    private Thread audioThread;
    private volatile boolean audioRun;
    private volatile float outRpm;
    private volatile float outPedal;
    private volatile float outVolume = 0.7f;
    private volatile boolean outLimiter;
    private VehicleSnapshot last = new VehicleSnapshot();
    private String audioError = "";
    private boolean playing;

    public final class LocalBinder extends Binder {
        public EngineSoundService service() {
            return EngineSoundService.this;
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            step();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        probe = new VehicleProbe(this);
        cabin = new CabinTrack();
        navi = new NaviBus(this);
        voice = openVoice(prefs.profile());
        outVolume = prefs.volume() / 100f;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "engine-sound");
        wake.setReferenceCounted(false);
        startForeground(NOTE_ID, buildNote("Engine sound"));
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.post(tick);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        stopAudio();
        if (wake.isHeld()) wake.release();
        super.onDestroy();
    }

    public boolean isEnabled() {
        return prefs.enabled();
    }

    public void setEnabled(boolean on) {
        prefs.setEnabled(on);
        if (!on) stopAudio();
    }

    public String profileId() {
        return voice.id();
    }

    public void setProfile(String id) {
        prefs.setProfile(id);
        voice = openVoice(prefs.profile());
    }

    public int volumePercent() {
        return prefs.volume();
    }

    public void setVolume(int percent) {
        prefs.setVolume(percent);
        outVolume = prefs.volume() / 100f;
    }

    public float rpm() { return box.rpm; }
    public String gearLabel() { return box.gearLabel(); }
    public boolean limiter() { return box.limiter; }
    public boolean freeRev() { return box.freeRev; }
    public boolean playing() { return playing; }
    public VehicleSnapshot vehicle() { return last; }
    public String audioError() { return audioError; }
    public String profileLabel() { return voice.label(); }

    private EngineVoice openVoice(String id) {
        EngineVoice recorded = LoopBank.open(this, id);
        if (recorded != null) return recorded;
        return V6Synth.byId(id, CabinTrack.SAMPLE_RATE);
    }

    private void step() {
        if (!prefs.enabled()) {
            if (playing) stopAudio();
            return;
        }
        VehicleSnapshot snap = probe.poll();
        last = snap;
        if (!snap.running && snap.speedOk && snap.kmh > 1f) {
            snap.running = true;
        }
        inputs.running = snap.running;
        inputs.kmh = snap.speedOk ? snap.kmh : 0f;
        inputs.pedal = snap.pedalOk ? snap.pedal : 0f;
        inputs.brake = snap.brakeOk && snap.brake;
        inputs.shifter = snap.shifter;
        box.update(POLL_MS / 1000f, inputs);
        outRpm = box.rpm;
        outPedal = box.throttle;
        outLimiter = box.limiter;
        if (snap.running && box.rpm > 40f) {
            startAudio();
        } else if (!snap.running && box.rpm < 30f) {
            stopAudio();
        }
    }

    private void startAudio() {
        if (audioRun && audioThread != null && audioThread.isAlive()) {
            playing = cabin.isOpen();
            return;
        }
        String gate = navi.open();
        if (!cabin.open()) {
            navi.close();
            audioError = cabin.error();
            playing = false;
            return;
        }
        audioError = gate;
        if (!wake.isHeld()) wake.acquire();
        audioRun = true;
        playing = true;
        audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                short[] buf = new short[256 * 2];
                float playRpm = outRpm;
                float playPedal = outPedal;
                while (audioRun) {
                    // Glide toward the 50 ms gearbox values in 256-frame steps
                    // (tau ~30 ms) so pitch never jumps at a poll boundary.
                    playRpm += (outRpm - playRpm) * 0.17f;
                    playPedal += (outPedal - playPedal) * 0.17f;
                    EngineVoice current = voice;
                    current.render(buf, 256, playRpm, playPedal, outVolume, outLimiter);
                    try {
                        int wrote = cabin.write(buf, 256);
                        if (wrote < 0) {
                            audioError = "pcm write " + wrote;
                            break;
                        }
                    } catch (Throwable t) {
                        audioError = "pcm write";
                        break;
                    }
                }
            }
        }, "engine-sound");
        audioThread.start();
        Log.i(TAG, "playing " + voice.label());
    }

    private void stopAudio() {
        audioRun = false;
        playing = false;
        Thread t = audioThread;
        audioThread = null;
        if (t != null) {
            try {
                t.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        cabin.release();
        if (navi != null) navi.close();
        if (wake.isHeld()) wake.release();
    }

    private Notification buildNote(String text) {
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_engine)
                .setContentTitle("Engine sound")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }
}
