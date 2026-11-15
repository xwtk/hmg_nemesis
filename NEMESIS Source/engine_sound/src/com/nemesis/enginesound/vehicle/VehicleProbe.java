package com.nemesis.enginesound.vehicle;

import android.content.Context;
import android.os.IBinder;
import android.util.Log;

import com.nemesis.enginesound.model.PedalScale;
import com.nemesis.enginesound.model.Shifter;

import java.lang.reflect.Method;

/**
 * Reads 260507 vehicle state without linking the OEM jars at compile time.
 *
 * <ul>
 * <li>Ready: {@code BCMManager.getEngineStatusValue} is the EV ready lamp
 *     (ignition on and {@code EV RDY == 1}). Accessory and ignition-on
 *     without ready are not running. {@code getEvRdy} / {@code getVcuRdy}
 *     from HSCAN are the same lamp.</li>
 * <li>Speed: {@code BCMManager.getSpeed_KMHUnit}, then
 *     {@code VehicleInfoManager.getVehicleSpeed}.</li>
 * <li>Accel: {@code ITM_HSCANService.getAccelPdlVal}, which is
 *     {@code CR_Vcu_AccPedDep_Pc * 0.3921568627} (percent).</li>
 * <li>Gear: micom {@code VehicleInfoListener.onGearStatus}
 *     (P=0 D=5 N=6 R=7), else HSCAN {@code getGearPosition}.</li>
 * <li>Brake: {@code getBrakeNotchStatus} is the parking-brake high-notch
 *     switch, not the foot pedal, so it is not used as brake-pressed.</li>
 * </ul>
 */
public final class VehicleProbe {
    private static final String TAG = "EngineSound.veh";

    private static final int POWER_OFF = 0xff;
    private static final int DELAY_MODE = 6;
    private static final int PRE_SYSTEM_OFF = 7;

    private final Context app;
    private Object power;
    private Object bcm;
    private Object vehicle;
    private Object pgs;
    private Object tmu;
    private Object hscan;
    private boolean lookedUp;
    private String lastError = "";
    private final com.nemesis.enginesound.model.PedalFilter pedalFilter =
            new com.nemesis.enginesound.model.PedalFilter();

    public VehicleProbe(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    public String lastError() {
        return lastError;
    }

    public VehicleSnapshot poll() {
        ensure();
        VehicleSnapshot s = new VehicleSnapshot();
        StringBuilder src = new StringBuilder();

        Boolean powerOff = callBool(power, "isPowerOff");
        Integer powerStatus = callInt(power, "getPowerStatus");
        Boolean engine = callBool(bcm, "getEngineStatusValue");
        Integer evRdy = callInt(hscan, "getEvRdy");
        Integer vcuRdy = callInt(hscan, "getVcuRdy");
        s.powerOk = powerOff != null || powerStatus != null || engine != null
                || evRdy != null || vcuRdy != null;
        s.running = decideRunning(powerOff, powerStatus, engine, evRdy, vcuRdy);
        if (s.powerOk) {
            src.append("pwr");
            if (powerStatus != null) src.append('=').append(powerStatus);
            if (engine != null) src.append(engine.booleanValue() ? " rdy" : " not");
            if (evRdy != null) src.append(" ev=").append(evRdy);
            if (vcuRdy != null) src.append(" vcu=").append(vcuRdy);
            src.append(' ');
        }

        Float kmh = callFloat(bcm, "getSpeed_KMHUnit");
        if (kmh != null && kmh.floatValue() >= 0f && kmh.floatValue() < 800f) {
            // Cluster packet is 2 counts per km/h (3 km/h arrives as 6).
            s.kmh = kmh.floatValue() * 0.5f;
            s.speedOk = true;
            src.append("spd=").append(Math.round(kmh.floatValue())).append(' ');
        } else {
            Integer v = callInt(vehicle, "getVehicleSpeed");
            if (v != null && v.intValue() >= 0 && v.intValue() < 400) {
                s.kmh = v.intValue();
                s.speedOk = true;
                src.append("vim-kmh ");
            }
        }

        if (hscan == null) {
            src.append("hscan? ");
        } else {
            Float aps = callFloat(hscan, "getAccelPdlVal");
            if (aps != null && aps.floatValue() >= 0f) {
                float unit = PedalScale.percent(aps.floatValue());
                s.pedal = pedalFilter.sample(unit, 0.05f);
                s.pedalOk = true;
                src.append("aps=").append(Math.round(aps.floatValue() * 10f) / 10f).append(' ');
            } else {
                src.append("aps? ");
            }
        }

        Integer notch = callInt(bcm, "getBrakeNotchStatus");
        if (notch != null) {
            src.append("notch=").append(notch).append(' ');
        }

        applyGear(s, src);

        if (!s.anySignal()) {
            src.append(lastError.length() == 0 ? "no vehicle service" : lastError);
        }
        s.sources = src.toString().trim();
        return s;
    }

    private static boolean decideRunning(Boolean powerOff, Integer status, Boolean engine,
            Integer evRdy, Integer vcuRdy) {
        if (powerOff != null && powerOff.booleanValue()) return false;
        if (status != null) {
            int v = status.intValue();
            if (v == POWER_OFF || v == DELAY_MODE || v == PRE_SYSTEM_OFF) return false;
        }
        if (engine != null && engine.booleanValue()) return true;
        if (evRdy != null && evRdy.intValue() == 1) return true;
        if (vcuRdy != null && vcuRdy.intValue() == 1) return true;
        return false;
    }

    /**
     * Micom {@code onGearStatus} stays 0 (Park) on this EV after the lever
     * moves. The CAN nibble {@code CF_Vcu_GarSelDisp} is the live value:
     * legacy codes are P=0 D=5 N=6 R=7, and the 4-bit cluster code is
     * P=0 R=1 N=2 D=3. A raw 0 is Park only when the camera P bit agrees.
     */
    private void applyGear(VehicleSnapshot s, StringBuilder src) {
        Integer raw = callInt(hscan, "getGearPosition");
        if (raw != null) src.append("g=").append(raw.intValue()).append(' ');
        int micom = GearSink.seen ? GearSink.code : -1;
        if (micom == Shifter.D || micom == Shifter.N || micom == Shifter.R
                || micom == Shifter.B || micom == Shifter.ECO) {
            s.shifter = micom;
            s.gearOk = true;
            src.append("micom=").append(Shifter.codeLabel(micom)).append(' ');
            return;
        }
        Boolean inP = callBool(pgs, "getGearPStatus");
        Boolean inN = callBool(pgs, "isGearNState");
        if (raw != null && raw.intValue() != 0) {
            int mapped = mapCanGear(raw.intValue());
            s.shifter = mapped;
            s.gearOk = true;
            src.append("can=").append(Shifter.codeLabel(mapped)).append(' ');
            return;
        }
        boolean park = inP != null && inP.booleanValue();
        boolean neutral = inN != null && inN.booleanValue();
        if (raw != null && raw.intValue() == 0 && (inP == null || park)) {
            s.shifter = Shifter.P;
            s.gearOk = true;
            src.append("can=P ");
            return;
        }
        if (park && s.kmh < 2f) {
            s.shifter = Shifter.P;
            s.gearOk = true;
        } else if (neutral) {
            s.shifter = Shifter.N;
            s.gearOk = true;
        } else if (s.kmh > 2f) {
            s.shifter = Shifter.D;
            s.gearOk = true;
        } else if (inP != null && !park) {
            s.shifter = Shifter.N;
            s.gearOk = true;
        }
        if (s.gearOk) src.append("pgs=").append(Shifter.codeLabel(s.shifter)).append(' ');
    }

    /** CAN gear nibble to a shifter code. Unknown nibbles stay as-is so the UI shows them. */
    static int mapCanGear(int raw) {
        if (raw == 0) return Shifter.P;
        if (raw == 1 || raw == 7) return Shifter.R;
        if (raw == 2 || raw == 6) return Shifter.N;
        if (raw == 3 || raw == 5) return Shifter.D;
        return raw;
    }

    private static boolean knownGear(int g) {
        return g == Shifter.P || g == Shifter.B || g == Shifter.ECO
                || g == Shifter.D || g == Shifter.N || g == Shifter.R;
    }

    private void ensure() {
        if (lookedUp && (bcm != null || hscan != null || power != null)) return;
        lookedUp = true;
        power = construct("com.hkmc.power.MicomPowerManager");
        bcm = construct("com.hkmc.bcm.BCMManager");
        if (bcm == null) {
            bcm = bcmFromVehicleInfo();
        }
        vehicle = vehicleInfo();
        pgs = construct("com.hkmc.pgs.PgsManager");
        tmu = construct("com.hkmc.tmu.TMUManager");
        hscan = hscanService();
        listenGear();
        if (power == null && bcm == null && hscan == null) {
            lastError = "Micom/BCM/HSCAN not available";
            Log.w(TAG, lastError);
        }
    }

    private Object construct(String className) {
        try {
            Class<?> c = Class.forName(className);
            return c.getConstructor(Context.class).newInstance(app);
        } catch (Throwable t) {
            Log.w(TAG, className + " : " + t.getClass().getSimpleName());
            note(t);
            return null;
        }
    }

    private Object vehicleInfo() {
        try {
            Class<?> c = Class.forName("com.hkmc.VehicleInfoManager");
            return c.getMethod("getInstance", Context.class).invoke(null, app);
        } catch (Throwable t) {
            note(t);
            return null;
        }
    }

    private Object bcmFromVehicleInfo() {
        try {
            Class<?> vim = Class.forName("com.hkmc.VehicleInfoManager");
            Object inst = vim.getMethod("getInstance", Context.class).invoke(null, app);
            Object name = vim.getField("BCM_SERVICE").get(null);
            Object svc = vim.getMethod("getService", String.class).invoke(inst, name);
            if (svc != null && "com.hkmc.bcm.BCMManager".equals(svc.getClass().getName())) {
                return svc;
            }
        } catch (Throwable t) {
            note(t);
        }
        return null;
    }

    private void listenGear() {
        try {
            Class<?> listener = Class.forName("com.hkmc.micom.VehicleInfoListener");
            Class<?> vim = Class.forName("com.hkmc.micom.VehicleInfoManager");
            Object inst = vim.getConstructor(Context.class).newInstance(app);
            Object hook = Class.forName(
                    "com.nemesis.enginesound.vehicle.GearHook").newInstance();
            vim.getMethod("addListener", listener).invoke(inst, hook);
            Log.i(TAG, "micom gear listener on");
        } catch (Throwable t) {
            Log.w(TAG, "gear listener", t);
        }
    }

    private Object hscanService() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method get = sm.getMethod("getService", String.class);
            IBinder binder = (IBinder) get.invoke(null, "tm_hscan_service");
            if (binder == null) {
                lastError = "tm_hscan_service missing";
                Log.w(TAG, lastError);
                return null;
            }
            Class<?> stub = Class.forName(
                    "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (Throwable t) {
            note(t);
            return null;
        }
    }

    private void note(Throwable t) {
        if (lastError.length() == 0 && t.getMessage() != null) {
            lastError = t.getClass().getSimpleName();
        }
    }

    private static Integer callInt(Object target, String name) {
        if (target == null) return null;
        try {
            Object r = target.getClass().getMethod(name).invoke(target);
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Float callFloat(Object target, String name) {
        if (target == null) return null;
        try {
            Object r = target.getClass().getMethod(name).invoke(target);
            if (r instanceof Float) return (Float) r;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Boolean callBool(Object target, String name) {
        if (target == null) return null;
        try {
            Object r = target.getClass().getMethod(name).invoke(target);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) {
        }
        return null;
    }
}
