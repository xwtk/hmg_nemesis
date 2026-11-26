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
    private static SampleVoice v8;
    private static ThrottleVoice bac;
    private static ThrottleVoice ferrari;
    private static ThrottleVoice procar;
    private static ThrottleVoice gt;

    private LoopBank() {}

    public static EngineVoice open(Context ctx, String id) {
        if (SampleVoice.HONDA.equals(id)) {
            if (honda == null) {
                honda = load(ctx, SampleVoice.HONDA, "Honda 3.2 V6",
                        new int[] {680, 2300, 3200});
            }
            return honda;
        }
        if (SampleVoice.V8.equals(id)) {
            if (v8 == null) {
                v8 = load(ctx, SampleVoice.V8, "V8",
                        new int[] {2000, 2250, 2600, 2950, 3300, 3650, 4000, 4300});
            }
            return v8;
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
        if (ThrottleVoice.GT.equals(id)) {
            if (gt == null) {
                gt = loadThrottle(ctx, ThrottleVoice.GT, "GT FUSION V8", "gt",
                        new int[] {1900, 4100, 7200}, new int[] {1900, 4100, 7200},
                        new int[] {880, 2100, 4800, 7200}, new int[] {880, 2100, 4800, 7200}, 8100);
            }
            return gt;
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

    /** on low, on high, off low, off high, limiter. Both sets crossfade on the on-loop pair. */
    private static ThrottleVoice loadThrottle(Context ctx, String id, String label, String prefix,
            int onLow, int onHigh, int offLow, int offHigh, int limitRpm) {
        int[] band = {onLow, onHigh};
        return loadThrottle(ctx, id, label, prefix,
                new int[] {onLow, onHigh}, band, new int[] {offLow, offHigh}, band, limitRpm);
    }

    /** Loops are prefix_on_RPM, prefix_off_RPM and prefix_lim_RPM; a band is the RPM points a set crossfades on. */
    private static ThrottleVoice loadThrottle(Context ctx, String id, String label, String prefix,
            int[] on, int[] onBand, int[] off, int[] offBand, int limitRpm) {
        SampleVoice.Loop[] onL = loops(ctx, prefix + "_on_", on);
        SampleVoice.Loop[] offL = loops(ctx, prefix + "_off_", off);
        if (onL == null || offL == null) {
            Log.w(TAG, "missing throttle loops " + prefix);
            return null;
        }
        SampleVoice.Loop lim = loop(ctx, prefix + "_lim_" + limitRpm + ".pcm", limitRpm);
        Log.i(TAG, "loaded " + label);
        return new ThrottleVoice(id, label, onL, floats(onBand), offL, floats(offBand), lim);
    }

    private static SampleVoice.Loop[] loops(Context ctx, String stem, int[] rpm) {
        SampleVoice.Loop[] out = new SampleVoice.Loop[rpm.length];
        for (int i = 0; i < rpm.length; i++) {
            out[i] = loop(ctx, stem + rpm[i] + ".pcm", rpm[i]);
            if (out[i] == null) return null;
        }
        return out;
    }

    private static float[] floats(int[] v) {
        float[] f = new float[v.length];
        for (int i = 0; i < v.length; i++) f[i] = v[i];
        return f;
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
