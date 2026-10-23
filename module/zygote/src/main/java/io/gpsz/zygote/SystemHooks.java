package io.gpsz.zygote;

import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.IInterface;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class SystemHooks {
    private static final String LMS = "com.android.server.location.LocationManagerService";
    private static final String LPM = "com.android.server.location.provider.LocationProviderManager";

    private static final String[] PROVIDERS = {
        LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER
    };

    private static final int K_LAST = 0, K_BLOCK_VOID = 1, K_BLOCK_BOOL = 2, K_ARG_LOCATION = 3, K_ARG_RESULT = 4, K_STATUS = 5;
    private static final int K_FEED_NMEA = 7, K_FEED_MEAS = 8, K_FEED_ANT = 9, K_UNFEED = 10;
    private static boolean installed = false;
    private static boolean generatorStarted = false;
    private static int lpmHooks = 0, lmsHooks = 0, lmsTries = 0;
    private static int scanFailures = 0;
    private static Class<?> resultClass;
    private static Method resultMap, resultSize, resultGet, resultWrap;
    private static boolean wrapTakesList = true;
    private static final Random RND = new Random();
    private static Class<?> lpmClass, lmsClass;
    private static Method lpmGetName, lpmOnReport, enabledForUser;
    private static Field lpmEmergency;
    private static final Map<String, Object> managers = new ConcurrentHashMap<>();
    private static final Map<Object, String> managerNames = new ConcurrentHashMap<>();
    private static volatile long lastGpsReport = 0;
    private static volatile long lastNetReport = 0;
    private static final long IDLE_WAIT_MS = 10_000;
    private static final long NET_INTERVAL_MS = 30_000;

    static synchronized boolean install() {
        boolean first = !installed;
        installed = true;
        if (lpmHooks > 0 && (lmsHooks > 0 || lmsTries >= 12)) return isLive();
        try {
            ClassLoader cl = systemLoader();
            if (first || lpmClass == null) prepareReflection(cl);
            if (lpmHooks == 0) lpmHooks = hookClass(cl, LPM, false);
            if (lmsHooks == 0 && lmsTries < 12) { lmsTries++; lmsHooks = hookClass(cl, LMS, true); }
            if (!generatorStarted && (first || (lpmOnReport != null && resultWrap != null))) {
                generatorStarted = lpmOnReport != null && resultWrap != null;
                if (generatorStarted) startGenerator();
            }
        } catch (Throwable ignored) { }
        return isLive();
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
        return null;
    }

    private static Field findField(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
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
        } catch (Throwable ignored) { }
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
        } catch (Throwable ignored) { }
        try {
            lpmClass = Class.forName(LPM, false, cl);
            lpmGetName = findMethod(lpmClass, "getName");
            if (resultClass != null) lpmOnReport = findMethod(lpmClass, "onReportLocation", resultClass);
            lpmEmergency = findField(lpmClass, "mEmergencyHelper");
            lmsClass = Class.forName(LMS, false, cl);
            enabledForUser = findMethod(lmsClass, "isProviderEnabledForUser", String.class, int.class);
        } catch (Throwable ignored) { }
    }

    private static int hookClass(ClassLoader cl, String name, boolean isLms) {
        Class<?> c;
        try {
            c = Class.forName(name, false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isAbstract(mod) || Modifier.isNative(mod) || Modifier.isStatic(mod)) continue;
            String n = m.getName();
            Class<?> ret = m.getReturnType();
            Class<?>[] ps = m.getParameterTypes();
            int kind = -1, argIdx = -1, lstIdx = -1;
            if (isLms) {
                for (int i = 0; i < ps.length; i++) {
                    if (IInterface.class.isAssignableFrom(ps[i])) { lstIdx = i + 1; break; }
                }
                if (n.equals("getLastLocation") && ret == Location.class) {
                    kind = K_LAST;
                } else if (n.equals("injectLocation") && ps.length >= 1 && ps[0] == Location.class) {
                    kind = K_ARG_LOCATION; argIdx = 1;
                } else if (lstIdx > 0 && feedKind(n) >= 0) {
                    for (int i = 0; i < ps.length; i++) {
                        if (ps[i] == String.class) { argIdx = i + 1; break; }
                    }
                    if (argIdx > 0) kind = feedKind(n);
                } else if (lstIdx > 0 && isUnfeedTarget(n)) {
                    kind = K_UNFEED;
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
                if (Frames.hook(m, LsNative.sysEnabled(), transformer(kind, argIdx, lstIdx, ret == boolean.class))) hooked++;
            } catch (Throwable ignored) { }
        }
        return hooked;
    }

    private static boolean isGnssBlockTarget(String n) {
        return n.equals("startGnssBatch") || n.equals("addGnssBatchingCallback")
                || n.equals("addGnssNavigationMessageListener");
    }

    private static int feedKind(String n) {
        switch (n) {
            case "registerGnssStatusCallback": return K_STATUS;
            case "registerGnssNmeaCallback": return K_FEED_NMEA;
            case "addGnssMeasurementsListener": return K_FEED_MEAS;
            case "addGnssAntennaInfoListener": return K_FEED_ANT;
            default: return -1;
        }
    }

    private static boolean isUnfeedTarget(String n) {
        return n.equals("unregisterGnssStatusCallback") || n.equals("unregisterGnssNmeaCallback")
                || n.equals("removeGnssMeasurementsListener") || n.equals("removeGnssAntennaInfoListener");
    }

    private static final Set<String> TRUSTED_GNSS = Set.of("android");

    private static boolean active() {
        return Config.enabled && Config.system && !Config.sysHeartbeat && Config.hasPos();
    }

    private static boolean beat() {
        return Config.enabled && Config.system && Config.sysHeartbeat;
    }

    private static final Map<String, Location> realByProvider = new ConcurrentHashMap<>();

    private static void captureReal(Object result) {
        try {
            if (result == null || resultClass == null || !resultClass.isInstance(result)
                    || resultSize == null || resultGet == null) return;
            if (realByProvider.size() > 8) realByProvider.clear();
            int n = (Integer) resultSize.invoke(result);
            for (int i = 0; i < n; i++) {
                Object o = resultGet.invoke(result, i);
                if (o instanceof Location) {
                    Location c = new Location((Location) o);
                    String pv = c.getProvider();
                    if (pv != null) realByProvider.put(pv, c);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static Location realBeat(String provider) {
        Location src = realByProvider.get(provider);
        if (src == null) return null;
        Location l = new Location(src);
        l.setProvider(provider);
        if (!l.hasAccuracy() || l.getAccuracy() <= 0f) l.setAccuracy(20f);
        Fix.stamp(l, provider);
        return l;
    }

    private static void startFeed(int kind, final Object listener) {
        if (listener == null) return;
        final Object key = GnssFake.keyOf(listener);
        switch (kind) {
            case K_STATUS:
                GnssFake.feedStatus(key, SystemHooks::active, new GnssFake.StatusSink() {
                    @Override
                    public void started() throws Throwable {
                        GnssFake.call(listener, "onGnssStarted");
                    }

                    @Override
                    public void firstFix(int ttffMs) throws Throwable {
                        GnssFake.call(listener, "onFirstFix", ttffMs);
                    }

                    @Override
                    public void status(GnssFake.Snapshot s) throws Throwable {
                        GnssFake.deliverStatus(listener, s);
                    }

                    @Override
                    public void stopped() throws Throwable {
                        GnssFake.call(listener, "onGnssStopped");
                    }
                }, null);
                break;
            case K_FEED_NMEA:
                GnssFake.feedNmea(key, SystemHooks::active,
                        (m, ts) -> GnssFake.call(listener, "onNmeaReceived", ts, m), null);
                break;
            case K_FEED_MEAS:
                GnssFake.feedMeasurements(key, SystemHooks::active,
                        ev -> GnssFake.call(listener, "onGnssMeasurementsReceived", ev), null);
                break;
            case K_FEED_ANT:
                GnssFake.feedAntenna(key, SystemHooks::active,
                        info -> GnssFake.call(listener, "onGnssAntennaInfoReceived", info));
                break;
            default:
                break;
        }
    }

    private static Frames.Tx transformer(final int kind, final int argIdx, final int lstIdx, final boolean retBool) {
        return (original, frame) -> {
            boolean handled = false;
            Throwable denied = null;
            try {
                if (kind == K_UNFEED) {
                    GnssFake.stop(GnssFake.keyOf(frame.get(lstIdx)));
                } else if (kind == K_ARG_RESULT) {
                    Object self = frame.get(0);
                    String name = register(self);
                    if (LocationManager.GPS_PROVIDER.equals(name)) lastGpsReport = SystemClock.elapsedRealtime();
                    if (Config.sysHeartbeat) captureReal(frame.get(argIdx));
                    if (active() && !inEmergency(self)) {
                        Object o = frame.get(argIdx);
                        Object replaced = spoofResult(o);
                        if (replaced != null && replaced != o) frame.set(argIdx, replaced);
                    }
                } else if (active()) {
                    switch (kind) {
                        case K_LAST: {
                            Object p = frame.get(1);
                            String provider = p instanceof String ? (String) p : LocationManager.GPS_PROVIDER;
                            if (anyEmergency()) break;
                            handled = true;
                            try {
                                original.invoke();
                            } catch (Throwable e) {
                                denied = e;
                            }
                            if (denied == null && providerEnabled(frame.get(0), provider)) {
                                frame.set(Frames.RET, Fix.lastKnown(provider));
                            }
                            break;
                        }
                        case K_BLOCK_VOID:
                            handled = true;
                            break;
                        case K_STATUS:
                        case K_FEED_NMEA:
                        case K_FEED_MEAS:
                        case K_FEED_ANT: {
                            Object pkg = frame.get(argIdx);
                            if (pkg instanceof String && TRUSTED_GNSS.contains(pkg)) break;
                            if (retBool) frame.set(Frames.RET, Boolean.TRUE);
                            startFeed(kind, frame.get(lstIdx));
                            handled = true;
                            break;
                        }
                        case K_BLOCK_BOOL:
                            frame.set(Frames.RET, Boolean.FALSE);
                            handled = true;
                            break;
                        case K_ARG_LOCATION: {
                            Object o = frame.get(argIdx);
                            if (o instanceof Location) frame.set(argIdx, spoof((Location) o));
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) { }
            if (denied != null) throw denied;
            if (!handled) original.invoke();
        };
    }

    private static Object spoofResult(Object result) throws Throwable {
        if (result == null || resultClass == null || !resultClass.isInstance(result)) return null;
        if (resultWrap == null) return null;
        if (resultSize != null && resultGet != null) {
            int n = (Integer) resultSize.invoke(result);
            List<Location> copies = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Object o = resultGet.invoke(result, i);
                if (o instanceof Location) copies.add(spoof((Location) o));
            }
            if (copies.isEmpty()) return null;
            if (wrapTakesList) return resultWrap.invoke(null, copies);
            return resultWrap.invoke(null, (Object) copies.toArray(new Location[0]));
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
                Object old = managers.put(name, manager);
                if (old != null && old != manager) managerNames.remove(old);
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
                Method m = findMethod(helper.getClass(), "isInEmergency", long.class);
                if (m == null) m = findMethod(helper.getClass(), "isInEmergency");
                c = new EmergencyCall(helper.getClass(), m);
                emergencyCall = c;
            }
            if (c.method == null) return false;
            if (c.method.getParameterCount() == 0) return (Boolean) c.method.invoke(helper);
            return (Boolean) c.method.invoke(helper, 0L);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean providerEnabled(Object lms, String provider) {
        try {
            if (enabledForUser == null || lms == null || lmsClass == null || !lmsClass.isInstance(lms)) return true;
            return (Boolean) enabledForUser.invoke(lms, provider, Binder.getCallingUid() / 100000);
        } catch (Throwable t) {
            return true;
        }
    }

    private static volatile long lastLazyScan = 0;

    private static boolean anyEmergency() {
        long now = SystemClock.elapsedRealtime();
        if (scanFailures < 3 && (managers.isEmpty() || now - lastLazyScan > 5000)) {
            lastLazyScan = now;
            if (scanForManagers()) scanFailures = 0;
            else scanFailures++;
        }
        for (Object m : managers.values()) if (inEmergency(m)) return true;
        return false;
    }

    private static void startGenerator() {
        Thread th = new Thread(() -> {
            long lastScan = 0;
            long wait = 1000;
            long period = 990 + RND.nextInt(21);
            for (;;) {
                try {
                    if (wait > 0) Thread.sleep(wait);
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
                    if (!managers.containsKey(LocationManager.GPS_PROVIDER) && now - lastScan > 5000 && scanFailures < 3) {
                        lastScan = now;
                        if (scanForManagers()) scanFailures = 0;
                        else scanFailures++;
                    }
                    if (anyEmergency()) { wait = period; continue; }
                    boolean anySent = false;
                    for (String name : PROVIDERS) {
                        Object mgr = managers.get(name);
                        if (mgr == null) continue;
                        try {
                            Location fix;
                            if (full) {
                                if (LocationManager.NETWORK_PROVIDER.equals(name)) {
                                    if (now - lastNetReport < NET_INTERVAL_MS) continue;
                                    fix = Fix.fresh(name);
                                    if (fix == null) continue;
                                    lastNetReport = now;
                                } else {
                                    fix = Fix.fresh(name);
                                }
                            } else {
                                if (LocationManager.NETWORK_PROVIDER.equals(name)) {
                                    if (now - lastNetReport < NET_INTERVAL_MS) continue;
                                    fix = realBeat(name);
                                    if (fix == null) continue;
                                    lastNetReport = now;
                                } else {
                                    fix = realBeat(name);
                                }
                            }
                            if (fix == null) continue;
                            Object res = wrap(fix);
                            if (res != null) {
                                lpmOnReport.invoke(mgr, res);
                                anySent = true;
                            }
                        } catch (Throwable ignored) { }
                    }
                    if (anySent) {
                        lastGpsReport = now;
                        wait = period;
                    } else {
                        wait = Math.min(period * 4, 5000);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                    wait = period;
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

    private static boolean scanForManagers() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = findMethod(sm, "getService", String.class);
            if (getService == null || lmsClass == null) return false;
            Object lms = getService.invoke(null, "location");
            if (lms == null || !lmsClass.isInstance(lms)) return false;
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
            return !managers.isEmpty();
        } catch (Throwable ignored) { }
        return false;
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