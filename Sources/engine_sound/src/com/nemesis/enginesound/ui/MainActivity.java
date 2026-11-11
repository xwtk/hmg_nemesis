package com.nemesis.enginesound.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.nemesis.enginesound.R;
import com.nemesis.enginesound.model.Shifter;
import com.nemesis.enginesound.service.EngineSoundService;
import com.nemesis.enginesound.synth.SampleVoice;
import com.nemesis.enginesound.synth.ThrottleVoice;
import com.nemesis.enginesound.synth.V6Synth;
import com.nemesis.enginesound.vehicle.VehicleSnapshot;

public final class MainActivity extends Activity {
    private static final String[] IDS = {
            SampleVoice.G37, ThrottleVoice.BAC, ThrottleVoice.FERRARI, ThrottleVoice.PROCAR,
            SampleVoice.HONDA, SampleVoice.C43, V6Synth.ID_VR35, V6Synth.ID_VQ35
    };
    private static final String[] LABELS = {
            "Infiniti G37",
            "BAC Mono",
            "Ferrari 458",
            "BMW M1 Procar",
            "Honda 3.2 V6",
            "Mercedes C43 V6",
            "VR35 twin-turbo (generated)",
            "VQ35 V6 (generated)"
    };

    private EngineSoundService svc;
    private Button enable;
    private Spinner sounds;
    private SeekBar volume;
    private TextView rpm;
    private TextView gear;
    private TextView detail;
    private TextView status;
    private boolean bindingSounds;
    private final Handler handler = new Handler();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((EngineSoundService.LocalBinder) binder).service();
            showPrefs();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            paint();
            handler.postDelayed(this, 100L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        enable = (Button) findViewById(R.id.enable);
        sounds = (Spinner) findViewById(R.id.sounds);
        volume = (SeekBar) findViewById(R.id.volume);
        rpm = (TextView) findViewById(R.id.rpm);
        gear = (TextView) findViewById(R.id.gear);
        detail = (TextView) findViewById(R.id.detail);
        status = (TextView) findViewById(R.id.status);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sounds.setAdapter(adapter);

        enable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (svc == null) return;
                svc.setEnabled(!svc.isEnabled());
                paint();
            }
        });
        sounds.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (bindingSounds || svc == null) return;
                if (position >= 0 && position < IDS.length) {
                    svc.setProfile(IDS[position]);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        volume.setMax(100);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && svc != null) svc.setVolume(progress);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });

        startService(new Intent(this, EngineSoundService.class));
        bindService(new Intent(this, EngineSoundService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (svc != null) {
            unbindService(connection);
            svc = null;
        }
        super.onDestroy();
    }

    private void showPrefs() {
        if (svc == null) return;
        bindingSounds = true;
        String id = svc.profileId();
        int idx = 0;
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) idx = i;
        }
        sounds.setSelection(idx);
        volume.setProgress(svc.volumePercent());
        bindingSounds = false;
        paint();
    }

    private void paint() {
        if (svc == null) {
            status.setText("Service starting");
            return;
        }
        enable.setText(svc.isEnabled() ? "Enabled  —  tap to stop" : "Off  —  tap to play");
        int shown = Math.round(svc.rpm());
        rpm.setText(shown + " rpm");
        String g = svc.gearLabel();
        if (svc.freeRev()) g = g + "  rev";
        if (svc.limiter()) g = g + "  LIMIT";
        gear.setText("Gear " + g);
        VehicleSnapshot v = svc.vehicle();
        String shifter = Shifter.codeLabel(v.shifter);
        detail.setText(
                "Speed " + (v.speedOk ? String.format("%.0f km/h", v.kmh) : "—")
                + "    Pedal " + (v.pedalOk ? String.valueOf(Math.round(v.pedal * 100f)) + "%" : "—")
                + "    Brake " + (v.brakeOk ? (v.brake ? "on" : "off") : "—")
                + "\nShifter " + shifter
                + (v.running ? "    car running" : "    car off")
                + (svc.playing() ? "    sound on" : "    sound idle"));
        String err = svc.audioError();
        if (err != null && err.length() > 0) {
            status.setText(err);
        } else if (!svc.isEnabled()) {
            status.setText("Turn it on to idle while the car is running. Stock ASD is not changed.");
        } else if (!v.anySignal()) {
            status.setText("No vehicle signals yet. " + v.sources);
        } else {
            status.setText(v.sources);
        }
    }
}
