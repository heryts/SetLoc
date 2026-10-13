package io.gpsz.zygote;

import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.v7878.unsafe.invoke.EmulatedStackFrame;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.vmtools.HookTransformer;
import com.v7878.vmtools.Hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * system_server hooks (counterpart of the LSPosed "system_hooked" path in jqssun's LocationHook.kt).
 *
 * v0.1.3 fixes (the "spoof works for a second, then the real location comes back" bug):
 *  1. LocationResult.map / size / get / wrap and LocationProviderManager.getName are hidden-API members.
 *     Under LSPosed the hidden-API checks are already lifted, under Zygisk they are not, so the plain
 *     getDeclaredMethod() threw NoSuchMethodException, resultMap stayed null and
 *     LocationProviderManager.onReportLocation (= every real gps/network/fused fix) was NEVER hooked.
 *     Only getLastLocation was hooked, which is why Maps showed the fake point once and then the real one.
 *     All lookups now go through find*() which retries with com.v7878.unsafe.Reflection.
 *  2. A failure while hooking LocationManagerService used to set 'crashed' and silently skip the
 *     LocationProviderManager hooks. LPM is now hooked first and failures no longer abort the rest.
 *  3. Ported jqssun's 1 Hz fix generator (feeds fake fixes into gps/fused/network when the real provider is
 *     silent) and its emergency-call guard (real location is never replaced during an emergency call/SMS).
 *
 * Every step is try/catch'ed: a missing class or method only logs a warning (tag GpsZ) and skips that hook;
 * it must never throw into system_server.
 * NOT COMPILED ON DEVICE BY THE AUTHOR: verify on a device you can recover (module can be disabled from recovery).
 */
final class SystemHooks {
    private static final String TAG = ZygoteEntry.TAG;
    private static final String LMS = "com.android.server.location.LocationManagerService";
    private static final String LPM = "com.android.server.location.provider.LocationProviderManager";

    private static final int K_LAST = 0, K_BLOCK_VOID = 1, K_BLOCK_BOOL = 2, K_ARG_LOCATION = 3, K_ARG_RESULT = 4;
    private static final Random RND = new Random();

    private static boolean installed = false;
    private static int failures = 0;

    // LocationResult (hidden API) access
    private static Class<?> resultClass;
    private static Method resultMap, resultSize, resultGet, resultWrap;
    private static boolean wrapTakesList = true;
    private static Method setMock;

    // LocationProviderManager access
    private static Class<?> lpmClass, lmsClass;
    private static Method lpmGetName, lpmOnReport;
    private static Field lpmEmergency;
    private static final Map<String, Object> managers = new ConcurrentHashMap<>();
    private static volatile long lastGpsReport = 0;
    private static boolean generatorStarted = false;

    private static double snapLat = Double.NaN, snapLng = Double.NaN;
    private static long snapAt = 0;

    static synchronized void install() {
        if (installed) return;
        installed = true;
        if (Build.VERSION.SDK_INT < 33) {
            Log.w(TAG, "system hooks: Android < 13 not supported");
            return;
        }
        try {
            ClassLoader cl = systemLoader();
            Log.i(TAG, "system hooks: installing (loader " + cl + ", sdk " + Build.VERSION.SDK_INT + ")");
            prepareReflection(cl);
            // order matters: onReportLocation (real fix stream) first, it is the one that must not be skipped
            hookClass(cl, LPM, false);
            hookClass(cl, LMS, true);
            Log.i(TAG, "system hooks: done, failures=" + failures
                    + ", onReportLocation " + (resultSize != null || resultMap != null ? "ready" : "NOT READY"));
            startGenerator(cl);
        } catch (Throwable t) {
            Log.e(TAG, "system hooks failed", t);
        }
    }

    // ---------------------------------------------------------------- reflection helpers (hidden API safe)

    private static Method findMethod(Class<?> c, String name, Class<?>... ps) {
        try {
            Method m = c.getDeclaredMethod(name, ps);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) { }
        try {
            Method m = com.v7878.unsafe.Reflection.getDeclaredMethod(c, name, ps); // hidden-API bypass
            if (m != null) {
                m.setAccessible(true);
                return m;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Field findField(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) { }
        try { // looked up by name so this file compiles whatever the exact helper signature is
            Method m = com.v7878.unsafe.Reflection.class.getMethod("getDeclaredField", Class.class, String.class);
            Field f = (Field) m.invoke(null, c, name);
            if (f != null) {
                f.setAccessible(true);
                return f;
            }
        } catch (Throwable ignored) { }
        // last resort: getDeclaredFields() is not filtered by the hidden-API check
        try {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    f.setAccessible(true);
                    return f;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static ClassLoader systemLoader() {
        try {
            Class<?> zi = Class.forName("com.android.internal.os.ZygoteInit");
            Method m = findMethod(zi, "getOrCreateSystemServerClassLoader");
            if (m != null) {
                ClassLoader cl = (ClassLoader) m.invoke(null);
                if (cl != null) return cl;
            }
        } catch (Throwable t) {
            Log.w(TAG, "system hooks: ZygoteInit loader unavailable: " + t);
        }
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        return cl != null ? cl : SystemHooks.class.getClassLoader();
    }

    private static void prepareReflection(ClassLoader cl) {
        try {
            resultClass = Class.forName("android.location.LocationResult");
            resultSize = findMethod(resultClass, "size");
            resultGet = findMethod(resultClass, "get", int.class);
            resultMap = findMethod(resultClass, "map", Function.class);
            resultWrap = findMethod(resultClass, "wrap", List.class);
            if (resultWrap == null) resultWrap = findMethod(resultClass, "create", List.class);
            if (resultWrap == null) {
                resultWrap = findMethod(resultClass, "wrap", Location[].class);
                wrapTakesList = false;
            }
            Log.i(TAG, "system hooks: LocationResult size=" + (resultSize != null) + " get=" + (resultGet != null)
                    + " map=" + (resultMap != null) + " wrap=" + (resultWrap != null));
            if ((resultSize == null || resultGet == null) && resultMap == null) {
                Log.w(TAG, "system hooks: neither LocationResult.get/size nor map reachable, onReportLocation can't be spoofed");
            }
        } catch (Throwable t) {
            Log.w(TAG, "system hooks: android.location.LocationResult unavailable: " + t);
        }
        for (String n : new String[]{"setMock", "setIsFromMockProvider"}) {
            Method m = findMethod(Location.class, n, boolean.class);
            if (m != null) { setMock = m; break; }
        }
        try {
            lpmClass = Class.forName(LPM, false, cl);
            lpmGetName = findMethod(lpmClass, "getName");
            if (resultClass != null) lpmOnReport = findMethod(lpmClass, "onReportLocation", resultClass);
            lpmEmergency = findField(lpmClass, "mEmergencyHelper"); // Android 14+ only
            lmsClass = Class.forName(LMS, false, cl);
        } catch (Throwable t) {
            Log.w(TAG, "system hooks: LocationProviderManager lookup failed: " + t);
        }
    }

    // ---------------------------------------------------------------- hook installation

    private static void hookClass(ClassLoader cl, String name, boolean isLms) {
        Class<?> c;
        try {
            c = Class.forName(name, false, cl);
        } catch (Throwable t) {
            Log.w(TAG, "system hooks: class not found " + name + ": " + t);
            return;
        }
        for (Method m : c.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isAbstract(mod) || Modifier.isNative(mod) || Modifier.isStatic(mod)) continue;
            String n = m.getName();
            Class<?> ret = m.getReturnType();
            Class<?>[] ps = m.getParameterTypes();
            int kind = -1, argIdx = -1;
            if (isLms) {
                if (n.equals("getLastLocation") && ret == Location.class) {
                    kind = K_LAST;
                } else if (n.equals("injectLocation") && ps.length >= 1 && ps[0] == Location.class) {
                    kind = K_ARG_LOCATION; argIdx = 1; // 0 = this
                } else if (isGnssBlockTarget(n)) {
                    if (ret == void.class) kind = K_BLOCK_VOID;
                    else if (ret == boolean.class) kind = K_BLOCK_BOOL;
                }
            } else if (n.equals("onReportLocation") && resultClass != null) {
                for (int i = 0; i < ps.length; i++) {
                    if (ps[i] == resultClass) { kind = K_ARG_RESULT; argIdx = i + 1; break; }
                }
            }
            if (kind < 0) continue;
            try {
                Hooks.hook(m, Hooks.EntryPointType.DIRECT, transformer(kind, argIdx), Hooks.EntryPointType.DIRECT);
                Log.i(TAG, "system hook: " + c.getSimpleName() + "." + n + " (kind " + kind + ")");
            } catch (Throwable t) {
                failures++;
                Log.e(TAG, "system hook failed: " + c.getSimpleName() + "." + n, t);
                // keep going: one failed hook must not drop the others (this used to skip onReportLocation)
            }
        }
    }

    private static boolean isGnssBlockTarget(String n) {
        return n.equals("startGnssBatch") || n.equals("addGnssBatchingCallback")
                || n.equals("addGnssAntennaInfoListener") || n.equals("addGnssMeasurementsListener")
                || n.equals("addGnssNavigationMessageListener");
    }

    private static boolean active() {
        return Config.enabled && Config.system && !Double.isNaN(Config.lat) && !Double.isNaN(Config.lng);
    }

    private static HookTransformer transformer(final int kind, final int argIdx) {
        return (original, frame) -> {
            boolean handled = false;
            try {
                if (kind == K_ARG_RESULT) {
                    // always learn the provider managers + last real gps report, even while spoofing is off
                    Object self = frame.accessor().getReference(0);
                    String name = register(self);
                    if (LocationManager.GPS_PROVIDER.equals(name)) lastGpsReport = SystemClock.elapsedRealtime();
                    if (active() && !inEmergency(self)) {
                        Object o = frame.accessor().getReference(argIdx);
                        Object replaced = spoofResult(o);
                        if (replaced != null && replaced != o) frame.accessor().setValue(argIdx, replaced);
                    }
                } else if (active()) {
                    switch (kind) {
                        case K_LAST: {
                            Object p = frame.accessor().getReference(1); // index 0 = this, 1 = provider
                            String provider = p instanceof String ? (String) p : LocationManager.GPS_PROVIDER;
                            frame.accessor().setValue(EmulatedStackFrame.RETURN_VALUE_IDX, freshFix(provider));
                            handled = true;
                            break;
                        }
                        case K_BLOCK_VOID:
                            handled = true;
                            break;
                        case K_BLOCK_BOOL:
                            frame.accessor().setValue(EmulatedStackFrame.RETURN_VALUE_IDX, Boolean.FALSE);
                            handled = true;
                            break;
                        case K_ARG_LOCATION: {
                            Object o = frame.accessor().getReference(argIdx);
                            if (o instanceof Location) frame.accessor().setValue(argIdx, spoof((Location) o));
                            break; // original still runs, with the replaced argument
                        }
                        default:
                            break;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "system fake failed: " + t);
                handled = false;
            }
            if (!handled) {
                try {
                    Transformers.invokeExactNoChecks(original, frame);
                } catch (Throwable t) {
                    SystemHooks.<RuntimeException>sneaky(t);
                }
            }
        };
    }

    // ---------------------------------------------------------------- LocationResult rewriting

    /** Rewrites every Location of the result IN PLACE (same approach as jqssun); falls back to LocationResult.map. */
    private static Object spoofResult(Object result) throws Throwable {
        if (result == null || resultClass == null || !resultClass.isInstance(result)) return null;
        if (resultSize != null && resultGet != null) {
            int n = (Integer) resultSize.invoke(result);
            for (int i = 0; i < n; i++) {
                Object o = resultGet.invoke(result, i);
                if (o instanceof Location) ((Location) o).set(spoof((Location) o));
            }
            return result;
        }
        if (resultMap != null) {
            Function<Location, Location> f = SystemHooks::spoof;
            return resultMap.invoke(result, f);
        }
        return null;
    }

    private static String register(Object manager) {
        if (manager == null || lpmClass == null || lpmGetName == null || !lpmClass.isInstance(manager)) return null;
        try {
            String name = (String) lpmGetName.invoke(manager);
            if (name != null && managers.put(name, manager) == null) {
                Log.i(TAG, "system hooks: provider manager " + name);
            }
            return name;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Never hand emergency services a fake fix (same guard as jqssun). */
    private static boolean inEmergency(Object manager) {
        try {
            if (lpmEmergency == null || manager == null) return false;
            Object helper = lpmEmergency.get(manager);
            if (helper == null) return false;
            Method m = findMethod(helper.getClass(), "isInEmergency", long.class);
            return m != null && (Boolean) m.invoke(helper, 0L);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 1 Hz fix generator

    private static void startGenerator(final ClassLoader cl) {
        if (generatorStarted || lpmOnReport == null || resultWrap == null) {
            if (lpmOnReport == null || resultWrap == null) {
                Log.w(TAG, "system hooks: fix generator disabled (onReportLocation=" + (lpmOnReport != null)
                        + " wrap=" + (resultWrap != null) + ")");
            }
            return;
        }
        generatorStarted = true;
        Thread th = new Thread(() -> {
            Log.i(TAG, "system hooks: fix generator started");
            long lastScan = 0;
            for (;;) {
                try {
                    Thread.sleep(250);
                    if (!active()) continue;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastGpsReport < 900) continue; // a gps fix (real or ours) went through just now
                    if (!managers.containsKey(LocationManager.GPS_PROVIDER) && now - lastScan > 5000) {
                        lastScan = now;
                        scanForManagers();
                    }
                    boolean emergency = false;
                    for (Object m : managers.values()) if (inEmergency(m)) { emergency = true; break; }
                    if (emergency) continue;
                    for (String name : new String[]{LocationManager.GPS_PROVIDER,
                            LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                        Object mgr = managers.get(name);
                        if (mgr == null) continue;
                        try {
                            Object res = wrap(freshFix(name));
                            if (res != null) lpmOnReport.invoke(mgr, res);
                        } catch (Throwable t) {
                            Log.w(TAG, "fake fix to " + name + " failed: " + t);
                        }
                    }
                    lastGpsReport = now;
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "generator tick error: " + t);
                }
            }
        }, "gpsz-fixgen");
        th.setDaemon(true);
        th.start();
    }

    private static Object wrap(Location l) throws Throwable {
        if (wrapTakesList) return resultWrap.invoke(null, Collections.singletonList(l));
        return resultWrap.invoke(null, (Object) new Location[]{l});
    }

    /** Finds LocationProviderManagers before any real report has revealed them (via the "location" binder = LMS itself). */
    private static void scanForManagers() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = findMethod(sm, "getService", String.class);
            if (getService == null || lmsClass == null) return;
            Object lms = getService.invoke(null, "location");
            if (lms == null || !lmsClass.isInstance(lms)) return;
            Method getLpm = findMethod(lmsClass, "getLocationProviderManager", String.class);
            if (getLpm != null) {
                for (String n : new String[]{LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER,
                        LocationManager.NETWORK_PROVIDER}) {
                    try { register(getLpm.invoke(lms, n)); } catch (Throwable ignored) { }
                }
            }
            for (Field f : lmsClass.getDeclaredFields()) {
                if (!Iterable.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object it = f.get(lms);
                    if (!(it instanceof Iterable)) continue;
                    List<Object> copy = new ArrayList<>();
                    for (Object o : (Iterable<?>) it) copy.add(o);
                    for (Object o : copy) register(o);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Log.w(TAG, "provider scan failed: " + t);
        }
    }

    // ---------------------------------------------------------------- fake fixes

    private static synchronized void refreshSnapshot() {
        long now = SystemClock.elapsedRealtime();
        if (now - snapAt < 200 && !Double.isNaN(snapLat)) return;
        double la = Config.lat, lo = Config.lng;
        if (Config.jitter) {
            double c = Math.cos(Math.toRadians(la));
            if (Math.abs(c) < 1e-6) c = 1e-6;
            la += meters() / 111320.0;
            lo += meters() / (111320.0 * c);
        }
        snapLat = la; snapLng = lo; snapAt = now;
    }

    private static double meters() {
        return (RND.nextDouble() * 2.0 - 1.0) * 8.0;
    }

    private static void clearMock(Location l) {
        if (setMock == null) return;
        try { setMock.invoke(l, Boolean.FALSE); } catch (Throwable ignored) { }
    }

    /** Copy of the real fix with lat/lng/accuracy replaced (system_server's own Location getters are not hooked). */
    private static Location spoof(Location orig) {
        if (orig == null) return null;
        Location l = new Location(orig);
        refreshSnapshot();
        l.setLatitude(snapLat);
        l.setLongitude(snapLng);
        l.setAccuracy(Math.max(1f, Config.acc)); // LocationResult.validate() rejects accuracy <= 0
        l.setAltitude(0.0);
        l.setSpeed(0f);
        clearMock(l);
        return l;
    }

    private static Location freshFix(String provider) {
        Location l = new Location(provider);
        refreshSnapshot();
        l.setLatitude(snapLat);
        l.setLongitude(snapLng);
        l.setAccuracy(Math.max(1f, Config.acc));
        l.setAltitude(0.0);
        l.setSpeed(0f);
        l.setTime(System.currentTimeMillis() - 300);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        clearMock(l);
        return l;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable t) throws T {
        throw (T) t;
    }

    private SystemHooks() { }
}
