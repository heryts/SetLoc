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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Hook system_server (LocationManagerService / LocationProviderManager): mengganti fix asli, memblokir GNSS,
 * dan mengirim fix 1 Hz (palsu pada mode penuh, salinan fix asli pada mode heartbeat).
 * API tersembunyi dicari lewat find*() (fallback com.v7878.unsafe.Reflection). Setiap langkah di-try/catch:
 * kegagalan hanya dicatat (tag GpsZ) dan tidak boleh melempar ke system_server.
 */
final class SystemHooks {
    private static final String TAG = ZygoteEntry.TAG;
    private static final String LMS = "com.android.server.location.LocationManagerService";
    private static final String LPM = "com.android.server.location.provider.LocationProviderManager";

    private static final String[] PROVIDERS = {
        LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER
    };

    private static final int K_LAST = 0, K_BLOCK_VOID = 1, K_BLOCK_BOOL = 2, K_ARG_LOCATION = 3, K_ARG_RESULT = 4;

    private static boolean installed = false;

    private static Class<?> resultClass;
    private static Method resultMap, resultSize, resultGet, resultWrap;
    private static boolean wrapTakesList = true;
    private static Method setMock;

    private static Class<?> lpmClass, lmsClass;
    private static Method lpmGetName, lpmOnReport;
    private static Field lpmEmergency;
    private static final Map<String, Object> managers = new ConcurrentHashMap<>();
    private static volatile long lastGpsReport = 0;

    static synchronized void install() {
        if (installed) return;
        installed = true;
        if (Build.VERSION.SDK_INT < 33) {
            Log.w(TAG, "system hooks: Android < 13 tidak didukung");
            return;
        }
        try {
            ClassLoader cl = systemLoader();
            prepareReflection(cl);
            hookClass(cl, LPM, false);
            hookClass(cl, LMS, true);
            startGenerator();
        } catch (Throwable t) {
            Log.e(TAG, "system hooks failed", t);
        }
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... ps) {
        try {
            Method m = c.getDeclaredMethod(name, ps);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) { }
        try {
            Method m = com.v7878.unsafe.Reflection.getDeclaredMethod(c, name, ps);
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
        try {
            Method m = com.v7878.unsafe.Reflection.class.getMethod("getDeclaredField", Class.class, String.class);
            Field f = (Field) m.invoke(null, c, name);
            if (f != null) {
                f.setAccessible(true);
                return f;
            }
        } catch (Throwable ignored) { }
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
            if ((resultSize == null || resultGet == null) && resultMap == null) {
                Log.w(TAG, "system hooks: LocationResult.get/size/map tidak terjangkau");
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
            lpmEmergency = findField(lpmClass, "mEmergencyHelper");
            lmsClass = Class.forName(LMS, false, cl);
        } catch (Throwable t) {
            Log.w(TAG, "system hooks: LocationProviderManager lookup failed: " + t);
        }
    }

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
                    kind = K_ARG_LOCATION; argIdx = 1;
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
                Log.e(TAG, "system hook failed: " + c.getSimpleName() + "." + n, t);
            }
        }
    }

    private static boolean isGnssBlockTarget(String n) {
        return n.equals("startGnssBatch") || n.equals("addGnssBatchingCallback")
                || n.equals("addGnssAntennaInfoListener") || n.equals("addGnssMeasurementsListener")
                || n.equals("addGnssNavigationMessageListener");
    }

    /** Mode penuh (gpsz_sys=1): semua app yang membaca lokasi lewat sistem mendapat lokasi palsu. */
    private static boolean active() {
        return Config.enabled && Config.system && !Config.sysHeartbeat && !Double.isNaN(Config.lat) && !Double.isNaN(Config.lng);
    }

    /** Mode heartbeat (gpsz_sys=2): lokasi app lain TIDAK diubah; sistem hanya mengulang fix asli tiap detik. */
    private static boolean beat() {
        return Config.enabled && Config.system && Config.sysHeartbeat;
    }

    private static volatile Location lastReal;

    private static void captureReal(Object result) {
        try {
            if (result == null || resultClass == null || !resultClass.isInstance(result)
                    || resultSize == null || resultGet == null) return;
            int n = (Integer) resultSize.invoke(result);
            if (n <= 0) return;
            Object o = resultGet.invoke(result, n - 1);
            if (o instanceof Location) lastReal = new Location((Location) o);
        } catch (Throwable ignored) { }
    }

    private static Location realBeat(String provider) {
        Location src = lastReal;
        if (src == null) return null;
        Location l = new Location(src);
        l.setProvider(provider);
        if (!l.hasAccuracy() || l.getAccuracy() <= 0f) l.setAccuracy(20f);
        l.setTime(System.currentTimeMillis() - 300);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return l;
    }

    private static HookTransformer transformer(final int kind, final int argIdx) {
        return (original, frame) -> {
            boolean handled = false;
            try {
                if (kind == K_ARG_RESULT) {
                    Object self = frame.accessor().getReference(0);
                    String name = register(self);
                    if (LocationManager.GPS_PROVIDER.equals(name)) lastGpsReport = SystemClock.elapsedRealtime();
                    if (!active()) captureReal(frame.accessor().getReference(argIdx));
                    if (active() && !inEmergency(self)) {
                        Object o = frame.accessor().getReference(argIdx);
                        Object replaced = spoofResult(o);
                        if (replaced != null && replaced != o) frame.accessor().setValue(argIdx, replaced);
                    }
                } else if (active()) {
                    switch (kind) {
                        case K_LAST: {
                            Object p = frame.accessor().getReference(1);
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
                            break;
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

    /** Menimpa setiap Location di result langsung; fallback ke LocationResult.map. */
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
            if (name != null) managers.put(name, manager);
            return name;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Method isInEmergency hasil refleksi, di-cache per kelas helper (dipanggil tiap laporan fix dan tiap tick generator). */
    private static final class EmergencyCall {
        final Class<?> type;
        final Method method;
        EmergencyCall(Class<?> type, Method method) { this.type = type; this.method = method; }
    }

    private static volatile EmergencyCall emergencyCall;

    /** Jangan beri layanan darurat fix palsu. */
    private static boolean inEmergency(Object manager) {
        try {
            if (lpmEmergency == null || manager == null) return false;
            Object helper = lpmEmergency.get(manager);
            if (helper == null) return false;
            EmergencyCall c = emergencyCall;
            if (c == null || c.type != helper.getClass()) {
                c = new EmergencyCall(helper.getClass(), findMethod(helper.getClass(), "isInEmergency", long.class));
                emergencyCall = c;
            }
            return c.method != null && (Boolean) c.method.invoke(helper, 0L);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void startGenerator() {
        if (lpmOnReport == null || resultWrap == null) {
            Log.w(TAG, "system hooks: fix generator disabled (onReportLocation=" + (lpmOnReport != null)
                    + " wrap=" + (resultWrap != null) + ")");
            return;
        }
        Thread th = new Thread(() -> {
            long lastScan = 0;
            for (;;) {
                try {
                    Thread.sleep(250);
                    final boolean full = active();
                    if (!full && !beat()) continue;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastGpsReport < 900) continue;
                    if (!managers.containsKey(LocationManager.GPS_PROVIDER) && now - lastScan > 5000) {
                        lastScan = now;
                        scanForManagers();
                    }
                    boolean emergency = false;
                    for (Object m : managers.values()) if (inEmergency(m)) { emergency = true; break; }
                    if (emergency) continue;
                    for (String name : PROVIDERS) {
                        Object mgr = managers.get(name);
                        if (mgr == null) continue;
                        try {
                            Location fix = full ? freshFix(name) : realBeat(name);
                            if (fix == null) continue;
                            Object res = wrap(fix);
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

    /** Cari LocationProviderManager sebelum fix asli pertama mengungkapkannya (binder "location" = LMS). */
    private static void scanForManagers() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = findMethod(sm, "getService", String.class);
            if (getService == null || lmsClass == null) return;
            Object lms = getService.invoke(null, "location");
            if (lms == null || !lmsClass.isInstance(lms)) return;
            Method getLpm = findMethod(lmsClass, "getLocationProviderManager", String.class);
            if (getLpm != null) {
                for (String n : PROVIDERS) {
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

    private static void clearMock(Location l) {
        if (setMock == null) return;
        try { setMock.invoke(l, Boolean.FALSE); } catch (Throwable ignored) { }
    }

    private static void applyMotion(Location l) {
        l.setAltitude(Config.altitudeNow());
        l.setSpeed(Config.spd);
        float b = Config.brg;
        if (Float.isNaN(b)) l.removeBearing(); else l.setBearing(b);
        if (Build.VERSION.SDK_INT >= 26) {
            l.removeSpeedAccuracy();
            l.removeBearingAccuracy();
            l.removeVerticalAccuracy();
        }
    }

    private static Location spoof(Location orig) {
        if (orig == null) return null;
        Location l = new Location(orig);
        Config.Pt p = Config.snapshot();
        l.setLatitude(p.lat);
        l.setLongitude(p.lng);
        l.setAccuracy(Math.max(1f, Config.acc)); // validate() menolak accuracy <= 0
        applyMotion(l);
        clearMock(l);
        return l;
    }

    private static Location freshFix(String provider) {
        Location l = new Location(provider);
        Config.Pt p = Config.snapshot();
        l.setLatitude(p.lat);
        l.setLongitude(p.lng);
        l.setAccuracy(Math.max(1f, Config.acc));
        applyMotion(l);
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
