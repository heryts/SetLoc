package io.gpsz.zygote;

import android.location.Location;
import android.location.LocationManager;
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

final class SystemHooks {
    private static final String TAG = ZygoteEntry.TAG;
    private static final String LMS = "com.android.server.location.LocationManagerService";
    private static final String LPM = "com.android.server.location.provider.LocationProviderManager";

    private static final String[] PROVIDERS = {
        LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER
    };

    private static final int K_LAST = 0, K_BLOCK_VOID = 1, K_BLOCK_BOOL = 2, K_ARG_LOCATION = 3, K_ARG_RESULT = 4, K_STATUS_VOID = 5, K_STATUS_BOOL = 6;
    private static boolean installed = false;
    private static boolean generatorStarted = false;
    private static int lpmHooks = 0, lmsHooks = 0, lmsTries = 0;
    private static Class<?> resultClass;
    private static Method resultMap, resultSize, resultGet, resultWrap;
    private static boolean wrapTakesList = true;
    private static final java.util.Random RND = new java.util.Random();
    private static Class<?> lpmClass, lmsClass;
    private static Method lpmGetName, lpmOnReport, enabledForUser;
    private static Field lpmEmergency;
    private static final Map<String, Object> managers = new ConcurrentHashMap<>();
    private static final Map<Object, String> managerNames = new ConcurrentHashMap<>();
    private static volatile long lastGpsReport = 0;
    private static final long IDLE_WAIT_MS = 10_000;

    static synchronized boolean install() {
        boolean first = !installed;
        installed = true;
        if (lpmHooks > 0 && (lmsHooks > 0 || lmsTries >= 12)) return isLive();
        int lpm0 = lpmHooks, lms0 = lmsHooks;
        try {
            ClassLoader cl = systemLoader();
            if (first || lpmClass == null) prepareReflection(cl);
            if (lpmHooks == 0) lpmHooks = hookClass(cl, LPM, false);
            if (lmsHooks == 0 && lmsTries < 12) { lmsTries++; lmsHooks = hookClass(cl, LMS, true); }
            if (!generatorStarted && (first || (lpmOnReport != null && resultWrap != null))) {
                generatorStarted = lpmOnReport != null && resultWrap != null;
                startGenerator();
            }
        } catch (Throwable t) {
            Log.e(TAG, "system hooks failed", t);
        }
        boolean live = isLive();
        if (first || lpm0 != lpmHooks || lms0 != lmsHooks) {
            Log.i(TAG, "system hooks: lpm=" + lpmHooks + " lms=" + lmsHooks + " live=" + live);
        }
        return live;
    }

    private static boolean isLive() {
        return lpmHooks > 0 && (Config.sysHeartbeat || lmsHooks > 0);
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
        try {
            lpmClass = Class.forName(LPM, false, cl);
            lpmGetName = findMethod(lpmClass, "getName");
            if (resultClass != null) lpmOnReport = findMethod(lpmClass, "onReportLocation", resultClass);
            lpmEmergency = findField(lpmClass, "mEmergencyHelper");
            lmsClass = Class.forName(LMS, false, cl);
            enabledForUser = findMethod(lmsClass, "isProviderEnabledForUser", String.class, int.class);
        } catch (Throwable t) {
            Log.w(TAG, "system hooks: LocationProviderManager lookup failed: " + t);
        }
    }

    private static int hookClass(ClassLoader cl, String name, boolean isLms) {
        Class<?> c;
        try {
            c = Class.forName(name, false, cl);
        } catch (Throwable t) {
            Log.w(TAG, "system hooks: class not found " + name + ": " + t);
            return 0;
        }
        int hooked = 0;
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
                } else if (n.equals("registerGnssStatusCallback") && (ret == void.class || ret == boolean.class)) {
                    for (int i = 0; i < ps.length; i++) {
                        if (ps[i] == String.class) { argIdx = i + 1; break; }
                    }
                    if (argIdx > 0) kind = ret == void.class ? K_STATUS_VOID : K_STATUS_BOOL;
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
                hooked++;
                Log.i(TAG, "system hook: " + c.getSimpleName() + "." + n + " (kind " + kind + ")");
            } catch (Throwable t) {
                Log.e(TAG, "system hook failed: " + c.getSimpleName() + "." + n, t);
            }
        }
        return hooked;
    }

    private static boolean isGnssBlockTarget(String n) {
        return n.equals("startGnssBatch") || n.equals("addGnssBatchingCallback")
                || n.equals("addGnssAntennaInfoListener") || n.equals("addGnssMeasurementsListener")
                || n.equals("addGnssNavigationMessageListener") || n.equals("registerGnssNmeaCallback")
                || n.equals("addNmeaListener");
    }

    private static final java.util.Set<String> TRUSTED_GNSS = new java.util.HashSet<>(java.util.Arrays.asList(
            "android", "com.google.android.gms", "com.google.android.gsf", "com.android.location.fused"));

    private static boolean active() {
        return Config.enabled && Config.system && !Config.sysHeartbeat && !Double.isNaN(Config.lat) && !Double.isNaN(Config.lng);
    }

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
        Fix.stamp(l, provider);
        return l;
    }

    private static HookTransformer transformer(final int kind, final int argIdx) {
        return (original, frame) -> {
            boolean handled = false;
            Throwable denied = null;
            try {
                if (kind == K_ARG_RESULT) {
                    Object self = frame.accessor().getReference(0);
                    String name = register(self);
                    if (LocationManager.GPS_PROVIDER.equals(name)) lastGpsReport = SystemClock.elapsedRealtime();
                    if (Config.sysHeartbeat) captureReal(frame.accessor().getReference(argIdx));
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
                            if (anyEmergency()) break;
                            handled = true;
                            try {
                                Transformers.invokeExactNoChecks(original, frame);
                            } catch (Throwable e) {
                                denied = e;
                            }
                            if (denied == null && providerEnabled(frame.accessor().getReference(0), provider)) {
                                frame.accessor().setValue(EmulatedStackFrame.RETURN_VALUE_IDX, Fix.fresh(provider));
                            }
                            break;
                        }
                        case K_BLOCK_VOID:
                            handled = true;
                            break;
                        case K_STATUS_VOID:
                        case K_STATUS_BOOL: {
                            Object pkg = frame.accessor().getReference(argIdx);
                            if (pkg instanceof String && TRUSTED_GNSS.contains(pkg)) break;
                            if (kind == K_STATUS_BOOL) frame.accessor().setValue(EmulatedStackFrame.RETURN_VALUE_IDX, Boolean.FALSE);
                            handled = true;
                            break;
                        }
                        case K_BLOCK_BOOL:
                            frame.accessor().setValue(EmulatedStackFrame.RETURN_VALUE_IDX, Boolean.FALSE);
                            handled = true;
                            break;
                        case K_ARG_LOCATION: {
                            Object o = frame.accessor().getReference(argIdx);
                            if (o instanceof Location) frame.accessor().setValue(argIdx, spoof((Location) o));
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                ZygoteEntry.warnOnce("system fake", t);
            }
            if (denied != null) ZygoteEntry.<RuntimeException>sneaky(denied);
            if (!handled) {
                try {
                    Transformers.invokeExactNoChecks(original, frame);
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
            }
        };
    }

    private static Object spoofResult(Object result) throws Throwable {
        if (result == null || resultClass == null || !resultClass.isInstance(result)) return null;
        if (resultSize != null && resultGet != null) {
            int n = (Integer) resultSize.invoke(result);
            for (int i = 0; i < n; i++) {
                Object o = resultGet.invoke(result, i);
                if (o instanceof Location) apply((Location) o);
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
        String known = managerNames.get(manager);
        if (known != null) return known;
        try {
            String name = (String) lpmGetName.invoke(manager);
            if (name != null) {
                managers.put(name, manager);
                managerNames.put(manager, name);
            }
            return name;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class EmergencyCall {
        final Class<?> type;
        final Method method;
        EmergencyCall(Class<?> type, Method method) { this.type = type; this.method = method; }
    }

    private static volatile EmergencyCall emergencyCall;

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

    private static boolean providerEnabled(Object lms, String provider) {
        try {
            if (enabledForUser == null || lms == null || lmsClass == null || !lmsClass.isInstance(lms)) return true;
            return (Boolean) enabledForUser.invoke(lms, provider, android.os.Binder.getCallingUid() / 100000);
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean anyEmergency() {
        for (Object m : managers.values()) if (inEmergency(m)) return true;
        return false;
    }

    private static void startGenerator() {
        if (lpmOnReport == null || resultWrap == null) {
            Log.w(TAG, "system hooks: fix generator disabled (onReportLocation=" + (lpmOnReport != null)
                    + " wrap=" + (resultWrap != null) + ")");
            return;
        }
        Thread th = new Thread(() -> {
            long lastScan = 0;
            long wait = 1000;
            long period = 990 + RND.nextInt(21);
            for (;;) {
                try {
                    Thread.sleep(wait);
                    wait = period;
                    final boolean full = active();
                    if (!full && !beat()) {
                        synchronized (Config.WAKE) {
                            if (!active() && !beat()) Config.WAKE.wait(IDLE_WAIT_MS);
                        }
                        wait = 0;
                        continue;
                    }
                    long now = SystemClock.elapsedRealtime();
                    long since = now - lastGpsReport;
                    if (since < period - 100) { wait = Math.max(20, period - since); continue; }
                    period = 990 + RND.nextInt(21);
                    if (!managers.containsKey(LocationManager.GPS_PROVIDER) && now - lastScan > 5000) {
                        lastScan = now;
                        scanForManagers();
                    }
                    if (anyEmergency()) continue;
                    for (String name : PROVIDERS) {
                        Object mgr = managers.get(name);
                        if (mgr == null) continue;
                        try {
                            Location fix = full ? Fix.fresh(name) : realBeat(name);
                            if (fix == null) continue;
                            Object res = wrap(fix);
                            if (res != null) lpmOnReport.invoke(mgr, res);
                        } catch (Throwable t) {
                            ZygoteEntry.warnOnce("fake fix " + name, t);
                        }
                    }
                    lastGpsReport = now;
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    ZygoteEntry.warnOnce("generator tick", t);
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

    private static void apply(Location l) {
        Fix.apply(l, l.getProvider(), Config.snapshot());
    }

    private static Location spoof(Location orig) {
        if (orig == null) return null;
        Location l = new Location(orig);
        apply(l);
        return l;
    }

    private SystemHooks() { }
}
