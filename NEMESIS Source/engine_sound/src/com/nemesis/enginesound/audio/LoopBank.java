package com.nemesis.enginesound.audio;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.nemesis.enginesound.synth.EngineVoice;
import com.nemesis.enginesound.synth.SampleVoice;
import com.nemesis.enginesound.synth.ThrottleVoice;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Loads the recorded loops shipped in {@code assets/}. */
public final class LoopBank {
    private static final String TAG = "EngineSound.audio";
    private static SampleVoice honda;
    private static SampleVoice c43;
    private static SampleVoice g37;
    private static ThrottleVoice bac;
    private static ThrottleVoice ferrari;
    private static ThrottleVoice procar;

    private LoopBank() {}

    public static EngineVoice open(Context ctx, String id) {
        if (SampleVoice.HONDA.equals(id)) {
            if (honda == null) {
                honda = load(ctx, SampleVoice.HONDA, "Honda 3.2 V6",
                        new int[] {680, 2300, 3200});
            }
            return honda;
        }
        if (SampleVoice.C43.equals(id)) {
            if (c43 == null) {
                c43 = load(ctx, SampleVoice.C43, "Mercedes C43 V6",
                        new int[] {1760, 5280});
            }
            return c43;
        }
        if (SampleVoice.G37.equals(id)) {
            if (g37 == null) {
                g37 = load(ctx, SampleVoice.G37, "Infiniti G37",
                        new int[] {1500, 2000, 3000, 3300, 3800, 4400, 4800, 5700, 6300, 6900, 7300});
            }
            return g37;
        }
        if (ThrottleVoice.BAC.equals(id)) {
            if (bac == null) {
                bac = loadThrottle(ctx, ThrottleVoice.BAC, "BAC Mono", "bac",
                        3000, 6500, 3000, 6500, 8000);
            }
            return bac;
        }
        if (ThrottleVoice.FERRARI.equals(id)) {
            if (ferrari == null) {
                ferrari = loadThrottle(ctx, ThrottleVoice.FERRARI, "Ferrari 458", "ferrari",
                        5300, 7700, 6900, 7900, 8800);
            }
            return ferrari;
        }
        if (ThrottleVoice.PROCAR.equals(id)) {
            if (procar == null) {
                procar = loadThrottle(ctx, ThrottleVoice.PROCAR, "BMW M1 Procar", "procar",
                        3200, 8000, 3400, 8430, 8000);
            }
            return procar;
        }
        return null;
    }

    private static SampleVoice load(Context ctx, String id, String label, int[] rpm) {
        SampleVoice.Loop[] loops = new SampleVoice.Loop[rpm.length];
        for (int i = 0; i < rpm.length; i++) {
            String name = id + "_" + rpm[i] + ".pcm";
            short[] pcm = read(ctx.getAssets(), name);
            if (pcm == null || pcm.length < 8) {
                Log.w(TAG, "missing loop " + name);
                return null;
            }
            loops[i] = new SampleVoice.Loop(pcm, rpm[i]);
        }
        Log.i(TAG, "loaded " + label + " (" + rpm.length + " loops)");
        return new SampleVoice(id, label, loops);
    }

    /** on low, on high, off low, off high, limiter. Band is the on-loop pair. */
    private static ThrottleVoice loadThrottle(Context ctx, String id, String label, String prefix,
            int onLow, int onHigh, int offLow, int offHigh, int limitRpm) {
        SampleVoice.Loop onL = loop(ctx, prefix + "_on_" + onLow + ".pcm", onLow);
        SampleVoice.Loop onH = loop(ctx, prefix + "_on_" + onHigh + ".pcm", onHigh);
        SampleVoice.Loop offL = loop(ctx, prefix + "_off_" + offLow + ".pcm", offLow);
        SampleVoice.Loop offH = loop(ctx, prefix + "_off_" + offHigh + ".pcm", offHigh);
        SampleVoice.Loop lim = loop(ctx, prefix + "_lim_" + limitRpm + ".pcm", limitRpm);
        if (onL == null || onH == null || offL == null || offH == null) {
            Log.w(TAG, "missing throttle loops " + prefix);
            return null;
        }
        Log.i(TAG, "loaded " + label);
        return new ThrottleVoice(id, label, onL, onH, offL, offH, lim, onLow, onHigh);
    }

    private static SampleVoice.Loop loop(Context ctx, String name, int rpm) {
        short[] pcm = read(ctx.getAssets(), name);
        if (pcm == null || pcm.length < 8) return null;
        return new SampleVoice.Loop(pcm, rpm);
    }

    private static short[] read(AssetManager assets, String name) {
        InputStream in = null;
        try {
            in = assets.open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            byte[] raw = bos.toByteArray();
            int samples = raw.length / 2;
            short[] pcm = new short[samples];
            for (int i = 0; i < samples; i++) {
                int lo = raw[i * 2] & 0xff;
                int hi = raw[i * 2 + 1];
                pcm[i] = (short) ((hi << 8) | lo);
            }
            return pcm;
        } catch (Throwable t) {
            Log.w(TAG, name, t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
