package com.zyvo.torchunlock;

import android.content.Intent;
import android.os.BatteryManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * TorchUnlock — kills the low-battery torch block.
 *
 * Found on this device (Symphony Z60 plus, Android 12, AOSP layout):
 *   - the ROM ships a patched com.android.systemui.qs.tiles.FlashlightTile
 *     with a battery-changed listener (log line
 *     "FlashlightTile receive battery changed:battery="), which refuses to
 *     turn the torch on below ~15%.
 *   - libcameraservice.so and the Unisoc (sprd) camera HAL contain NO
 *     battery guard, so the hardware happily lights the LED.
 *
 * Three independent layers, so it keeps working even if the ROM renames
 * things: lying about battery only while flashlight code is on the stack,
 * neutering battery-named methods in the torch classes, and forcing
 * availability to true.
 */
public class TorchHook implements IXposedHookLoadPackage {

    private static final String TAG = "TorchUnlock";
    private static final String TARGET_PKG = "com.android.systemui";
    private static final String STATUS_ACTION = "com.zyvo.torchunlock.STATUS";

    /** Percentage reported to flashlight code while it is asking. */
    private static final int FAKE_LEVEL = 100;

    private static final String[] TORCH_CLASSES = {
            "com.android.systemui.qs.tiles.FlashlightTile",
            "com.android.systemui.statusbar.policy.FlashlightController",
            "com.android.systemui.statusbar.policy.FlashlightControllerImpl",
            "com.android.systemui.camera.CameraFlashlightController",
            "com.android.systemui.camera.CameraFlashlight",
    };

    private static final String[] BATTERY_HINTS = {
            "battery", "power", "charg", "level", "capacity", "power_save",
    };

    private static final List<String> ARMED = new ArrayList<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        try {
            ClassLoader cl = lpparam.classLoader;
            if (cl == null) return;
            layerBatteryLies();
            layerNeuterGuards(cl);
            layerForceAvailable(cl);
            report();
        } catch (Throwable t) {
            log("install failed", t);
        }
    }

    /* ---------- layer 1: lie about the battery, only inside torch code ---------- */

    private static void layerBatteryLies() {
        // Intent.getIntExtra("level"/"percentage") — how the ROM's broadcast
        // listener reads the level.
        try {
            XposedBridge.hookAllMethods(Intent.class, "getIntExtra",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!inTorchStack()) return;
                            if (p.args == null || p.args.length < 1) return;
                            Object k = p.args[0];
                            if (!(k instanceof String)) return;
                            String key = (String) k;
                            if (key.equals("level") || key.equals("percentage")
                                    || key.equals("level_scaled")) {
                                p.setResult(FAKE_LEVEL);
                            } else if (key.equals("scale")) {
                                p.setResult(100);
                            }
                        }
                    });
            ARMED.add("battery-extra");
        } catch (Throwable t) {
            log("getIntExtra hook failed", t);
        }

        // BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)
        try {
            XposedBridge.hookAllMethods(BatteryManager.class, "getIntProperty",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!inTorchStack()) return;
                            if (p.args != null && p.args.length == 1
                                    && p.args[0] instanceof Integer
                                    && (Integer) p.args[0] == BatteryManager.BATTERY_PROPERTY_CAPACITY) {
                                p.setResult(FAKE_LEVEL);
                            }
                        }
                    });
            ARMED.add("battery-property");
        } catch (Throwable t) {
            log("getIntProperty hook failed", t);
        }

        // PowerManager.isPowerSaveMode() — battery saver auto-arms at 15%
        try {
            Class<?> pm = XposedHelpers.findClass("android.os.PowerManager",
                    TorchHook.class.getClassLoader());
            XposedBridge.hookAllMethods(pm, "isPowerSaveMode",
                    XC_MethodReplacement.returnConstant(Boolean.FALSE));
            ARMED.add("power-save-off");
        } catch (Throwable t) {
            log("isPowerSaveMode hook failed", t);
        }
    }

    /* ---------- layer 2: neuter battery-named methods in the torch classes ---------- */

    private static void layerNeuterGuards(ClassLoader cl) {
        for (String name : TORCH_CLASSES) {
            // class itself plus its anonymous inner classes (the ROM's
            // battery listener lives in FlashlightTile$1)
            for (String n : candidateNames(name)) {
                try {
                    Class<?> c = XposedHelpers.findClass(n, cl);
                    boolean hit = false;
                    for (Method m : c.getDeclaredMethods()) {
                        if (!looksLikeGuard(m.getName())) continue;
                        try {
                            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(
                                    defaultValue(m.getReturnType())));
                            hit = true;
                        } catch (Throwable ignored) {
                        }
                    }
                    if (hit) ARMED.add("neuter:" + c.getSimpleName());
                } catch (Throwable ignored) {
                    // class not present on this ROM — fine
                }
            }
        }
    }

    private static List<String> candidateNames(String base) {
        List<String> out = new ArrayList<>();
        out.add(base);
        for (int i = 1; i <= 8; i++) out.add(base + "$" + i);
        return out;
    }

    private static boolean looksLikeGuard(String name) {
        String n = name.toLowerCase(Locale.US);
        for (String hint : BATTERY_HINTS) {
            if (n.contains(hint)) return true;
        }
        return false;
    }

    /* ---------- layer 3: availability is never the problem ---------- */

    private static void layerForceAvailable(ClassLoader cl) {
        for (String name : TORCH_CLASSES) {
            try {
                Class<?> c = XposedHelpers.findClass(name, cl);
                try {
                    XposedBridge.hookAllMethods(c, "isAvailable",
                            XC_MethodReplacement.returnConstant(Boolean.TRUE));
                    XposedBridge.hookAllMethods(c, "hasFlashlight",
                            XC_MethodReplacement.returnConstant(Boolean.TRUE));
                    ARMED.add("available:" + c.getSimpleName());
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /* ---------- helpers ---------- */

    /**
     * True when a torch/flashlight frame is on the call stack. Cheap enough:
     * it only runs while the ROM is handling a battery broadcast or a torch
     * request, never in a hot loop.
     */
    private static boolean inTorchStack() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.contains("Flashlight") || c.contains("Torch") || c.contains("FlashController")) {
                return true;
            }
        }
        return false;
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return Boolean.FALSE;
        if (t == char.class) return (char) 0;
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        return null;
    }

    private static void report() {
        log("armed: " + ARMED, null);
        try {
            android.content.Context ctx = currentApp();
            if (ctx == null) return;
            Intent i = new Intent(STATUS_ACTION);
            i.setPackage("com.zyvo.torchunlock");
            i.putExtra("armed", true);
            i.putExtra("layers", new ArrayList<String>(ARMED));
            i.putExtra("fakeLevel", FAKE_LEVEL);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /** ActivityThread.currentApplication() without the Xposed helper. */
    private static android.content.Context currentApp() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getDeclaredMethod("currentApplication").invoke(null);
            return app instanceof android.content.Context ? (android.content.Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String msg, Throwable t) {
        // Logcat (visible with `logcat -s TorchUnlock`) and the LSPosed log.
        android.util.Log.i(TAG, msg, t);
        XposedBridge.log(TAG + ": " + msg + (t == null ? "" : " / " + t));
    }
}