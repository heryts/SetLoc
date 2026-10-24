package io.gpsz.zygote;

import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationManager;
import android.os.IInterface;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class GnssFake {
    static final int T_GPS = 1, T_GLO = 3, T_BDS = 5, T_GAL = 6;
    private static final int KIND_STATUS = 0, KIND_NMEA = 1, KIND_MEAS = 2, KIND_ANT = 3;
    private static final int A_AGAIN = 1, A_OFF = 2, A_STOPPED = 4, A_STARTED = 8, A_FIRST_FIX = 16, A_STATUS = 32, A_FIXED = 64, A_SEND = 128;
    static final int STRIDE_IN = 7, STRIDE_OUT = 9;
    private static final int LEAP_S = 18;

    interface Body {
        boolean run() throws Throwable;
    }

    interface StatusSink {
        void started() throws Throwable;
        void firstFix(int ttffMs) throws Throwable;
        void status(Snapshot s) throws Throwable;
        void stopped() throws Throwable;
    }

    interface NmeaSink {
        void nmea(String message, long timestamp) throws Throwable;
    }

    interface MeasSink {
        void event(Object event) throws Throwable;
    }

    interface AntSink {
        void antenna(List<Object> info) throws Throwable;
    }

    static final class Snapshot {
        final int n;
        final int[] type, svid, flags;
        final float[] cn0, elev, az, carrier, baseband;
        final boolean[] used;

        Snapshot(int n) {
            this.n = n;
            type = new int[n];
            svid = new int[n];
            cn0 = new float[n];
            elev = new float[n];
            az = new float[n];
            carrier = new float[n];
            used = new boolean[n];
            flags = new int[n];
            baseband = new float[n];
        }

        int usedCount() {
            int c = 0;
            for (boolean u : used) if (u) c++;
            return c;
        }

        double[] pack() {
            double[] a = new double[1 + n * STRIDE_IN];
            a[0] = n;
            for (int i = 0; i < n; i++) {
                int o = 1 + i * STRIDE_IN;
                a[o] = type[i];
                a[o + 1] = svid[i];
                a[o + 2] = cn0[i];
                a[o + 3] = elev[i];
                a[o + 4] = az[i];
                a[o + 5] = carrier[i];
                a[o + 6] = used[i] ? 1.0 : 0.0;
            }
            return a;
        }

        int[] flags() {
            return flags.clone();
        }

        float[] baseband() {
            return baseband.clone();
        }
    }

    private static final boolean NATIVE = verifyNative();

    static boolean nativeOn() {
        return NATIVE;
    }

    static Snapshot snapshot(boolean fixed) {
        if (!NATIVE) return new Snapshot(0);
        Snapshot n = snapshotNative(SystemClock.elapsedRealtime() / 1000, fixed);
        return n != null ? n : new Snapshot(0);
    }

    private static Snapshot probe() {
        Snapshot s = new Snapshot(4);
        int[] type = {T_GPS, T_GLO, T_GAL, T_BDS};
        int[] svid = {5, 10, 12, 21};
        float[] cn0 = {38.5f, 31.25f, 27f, 41.75f};
        float[] elev = {62.5f, 33.25f, 15f, 78.75f};
        float[] az = {123.5f, 250.25f, 12f, 359.5f};
        boolean[] used = {true, true, false, true};
        for (int i = 0; i < 4; i++) {
            s.type[i] = type[i];
            s.svid[i] = svid[i];
            s.cn0[i] = cn0[i];
            s.elev[i] = elev[i];
            s.az[i] = az[i];
            s.carrier[i] = i == 1 ? 1_602_562_500f : 1_575_420_000f;
            s.used[i] = used[i];
        }
        return s;
    }

    private static final String[] PROBE_NMEA = {
            "$GNGGA,221523.00,0612.528000,S,10650.736000,E,1,03,2.5,8.3,M,0.0,M,,*5F\r\n",
            "$GNGSA,A,1,05,,,,,,,,,,,,3.7,2.5,2.8,1*3F\r\n",
            "$GNGSA,A,1,74,,,,,,,,,,,,3.7,2.5,2.8,2*3A\r\n",
            "$GNGSA,A,1,21,,,,,,,,,,,,3.7,2.5,2.8,4*3C\r\n",
            "$GPGSV,1,1,01,05,63,124,39,1*58\r\n",
            "$GLGSV,1,1,01,74,33,250,31,1*4F\r\n",
            "$GAGSV,1,1,01,12,15,012,27,7*43\r\n",
            "$GBGSV,1,1,01,21,79,000,42,1*4C\r\n",
            "$GNRMC,221523.00,A,0612.528000,S,10650.736000,E,2.33,270.50,141123,,,A*54\r\n"
    };
    private static final long[] PROBE_TRAVEL_NS = {72253448L, 78589000L, 82541951L, 68733697L};

    private static boolean verifyNative() {
        if (!Config.locNative()) return false;
        try {
            Snapshot s = probe();
            List<String> r = nmeaNative(s, 1_700_000_123_456L, -6.2088, 106.8456, 12.5f, true, 1.2f, true, 270.5f, true, 8.3);
            if (r == null || !r.equals(Arrays.asList(PROBE_NMEA))) return false;
            long[] m = measNative(s, 1_700_000_123_456L, 98_765_432_101_234L);
            if (m == null || m[0] != 1_384_035_341_456_000_000L || m[1] != 252_941_456_000_000L) return false;
            for (int i = 0; i < s.n; i++) {
                if (m[2 + 2 * i] != PROBE_TRAVEL_NS[i]) return false;
                double rate = Double.longBitsToDouble(m[3 + 2 * i]);
                double base = ((s.svid[i] * 37 % 1600) - 800) * 0.9;
                if (Double.isNaN(rate) || Math.abs(rate - base) > 3.0) return false;
            }
            long[] plan = LocationNative.nativeFeedPlan(KIND_STATUS, 1_234L);
            if (plan == null || plan.length != 3 || plan[0] < 150 || plan[0] >= 350 || plan[1] != 1000 || plan[2] < 700 || plan[2] >= 2200) return false;
            if (LocationNative.nativeFeedStep(KIND_STATUS, true, 0, 0L, plan[2]) != ((1L << 8) | A_STARTED | A_AGAIN)) return false;
            if (LocationNative.nativeFeedStep(KIND_ANT, true, 0, 0L, 0L) != A_SEND) return false;
            Snapshot a = snapshotNative(1_234_567L, true);
            Snapshot b = snapshotNative(1_234_567L, true);
            Snapshot idle = snapshotNative(86_400L, false);
            if (a == null || b == null || idle == null || a.n < 5 || a.n > 20 || a.n != b.n || idle.usedCount() != 0) return false;
            int used = a.usedCount();
            if (used < 5 || used > a.n) return false;
            for (int i = 0; i < a.n; i++) {
                if (a.type[i] != b.type[i] || a.svid[i] != b.svid[i] || a.used[i] != b.used[i] || a.flags[i] != b.flags[i]) return false;
                if (a.cn0[i] < 14f || a.cn0[i] > 47f || a.elev[i] < 8f || a.elev[i] > 85f || a.az[i] < 0f || a.az[i] > 360f) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Snapshot snapshotNative(long nowSec, boolean fixed) {
        try {
            double[] a = LocationNative.nativeGnssSnapshot(nowSec, fixed);
            if (a == null || a.length < 1) return null;
            int n = (int) a[0];
            if (n < 0 || n > 64 || a.length != 1 + n * STRIDE_OUT) return null;
            Snapshot s = new Snapshot(n);
            for (int i = 0; i < n; i++) {
                int o = 1 + i * STRIDE_OUT;
                s.type[i] = (int) a[o];
                s.svid[i] = (int) a[o + 1];
                s.cn0[i] = (float) a[o + 2];
                s.elev[i] = (float) a[o + 3];
                s.az[i] = (float) a[o + 4];
                s.carrier[i] = (float) a[o + 5];
                s.used[i] = a[o + 6] != 0.0;
                s.flags[i] = (int) a[o + 7];
                s.baseband[i] = (float) a[o + 8];
            }
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean statusResolved;
    private static Method statusWrap;
    private static Constructor<?> status7, status6;

    private static synchronized void resolveStatus() {
        if (statusResolved) return;
        statusResolved = true;
        try {
            for (Method m : GnssStatus.class.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!m.getName().equals("wrap") || !Modifier.isStatic(m.getModifiers()) || p.length != 7) continue;
                if (p[0] != int.class || p[1] != int[].class) continue;
                m.setAccessible(true);
                statusWrap = m;
                break;
            }
        } catch (Throwable ignored) { }
        try {
            for (Constructor<?> c : GnssStatus.class.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length < 6 || p.length > 7 || p[0] != int.class || p[1] != int[].class) continue;
                c.setAccessible(true);
                if (p.length == 7) status7 = c; else status6 = c;
            }
        } catch (Throwable ignored) { }
    }

    static Object statusObject(Snapshot s) {
        resolveStatus();
        int[] flags = s.flags();
        float[] bb = s.baseband();
        try {
            if (statusWrap != null) return statusWrap.invoke(null, s.n, flags, s.cn0, s.elev, s.az, s.carrier, bb);
            if (status7 != null) return status7.newInstance(s.n, flags, s.cn0, s.elev, s.az, s.carrier, bb);
            if (status6 != null) return status6.newInstance(s.n, flags, s.cn0, s.elev, s.az, s.carrier);
        } catch (Throwable ignored) { }
        return null;
    }

    static void deliverStatus(Object listener, Snapshot s) throws Throwable {
        Method m = find(listener.getClass(), "onSvStatusChanged", -1);
        if (m == null) return;
        if (m.getParameterCount() == 1) {
            Object st = statusObject(s);
            if (st != null) m.invoke(listener, st);
            return;
        }
        call(listener, "onSvStatusChanged", s.n, s.flags(), s.cn0, s.elev, s.az, s.carrier, s.baseband());
    }

    static List<String> nmea(Snapshot s) {
        Config.Pt pt = Config.snapshot();
        Location fix = new Location(LocationManager.GPS_PROVIDER);
        Fix.apply(fix, LocationManager.GPS_PROVIDER, pt);
        long ms = System.currentTimeMillis();
        double lat = fix.getLatitude(), lng = fix.getLongitude();
        float acc = fix.getAccuracy();
        boolean hasSpd = fix.hasSpeed(), hasBrg = fix.hasBearing(), hasAlt = fix.hasAltitude();
        float spd = hasSpd ? fix.getSpeed() : 0f;
        float brg = hasBrg ? fix.getBearing() : 0f;
        double alt = hasAlt ? fix.getAltitude() : 0.0;
        if (!NATIVE) return new ArrayList<>();
        List<String> r = nmeaNative(s, ms, lat, lng, acc, hasSpd, spd, hasBrg, brg, hasAlt, alt);
        return r != null ? r : new ArrayList<>();
    }

    static List<String> nmeaNative(Snapshot s, long ms, double lat, double lng, float acc,
                                           boolean hasSpd, float spd, boolean hasBrg, float brg,
                                           boolean hasAlt, double alt) {
        try {
            String[] r = LocationNative.nativeGnssNmea(s.pack(), ms, lat, lng, acc, hasSpd, spd, hasBrg, brg, hasAlt, alt);
            if (r == null || r.length == 0) return null;
            for (String x : r) if (x == null) return null;
            return new ArrayList<>(Arrays.asList(r));
        } catch (Throwable t) {
            return null;
        }
    }

    static Object measurementsEvent(Snapshot s) {
        try {
            long nowMs = System.currentTimeMillis();
            long timeNs = SystemClock.elapsedRealtimeNanos();
            if (!NATIVE) return null;
            long[] nums = measNative(s, nowMs, timeNs);
            if (nums == null) return null;
            long gpsNs = nums[0];
            long tow = nums[1];
            Object clock = construct(Class.forName("android.location.GnssClock"));
            trySet(clock, "setTimeNanos", timeNs);
            trySet(clock, "setFullBiasNanos", timeNs - gpsNs);
            trySet(clock, "setBiasNanos", 0.0);
            trySet(clock, "setBiasUncertaintyNanos", 4.0);
            trySet(clock, "setLeapSecond", LEAP_S);
            trySet(clock, "setHardwareClockDiscontinuityCount", 0);
            Class<?> mc = Class.forName("android.location.GnssMeasurement");
            List<Object> list = new ArrayList<>(s.n);
            for (int i = 0; i < s.n; i++) {
                long travelNs = nums[2 + 2 * i];
                double rate = Double.longBitsToDouble(nums[3 + 2 * i]);
                Object m = construct(mc);
                boolean ok = true;
                ok &= trySetOk(m, "setSvid", s.svid[i]);
                ok &= trySetOk(m, "setConstellationType", s.type[i]);
                ok &= trySetOk(m, "setCn0DbHz", (double) s.cn0[i]);
                ok &= trySetOk(m, "setState", 15);
                ok &= trySetOk(m, "setReceivedSvTimeNanos", tow - travelNs);
                if (!ok) continue;
                trySet(m, "setTimeOffsetNanos", 0.0);
                trySet(m, "setReceivedSvTimeUncertaintyNanos", 30L);
                trySet(m, "setPseudorangeRateMetersPerSecond", rate);
                trySet(m, "setPseudorangeRateUncertaintyMetersPerSecond", 0.2);
                trySet(m, "setAccumulatedDeltaRangeState", 0);
                trySet(m, "setCarrierFrequencyHz", s.carrier[i]);
                trySet(m, "setMultipathIndicator", 2);
                list.add(m);
            }
            if (list.isEmpty()) return null;
            return buildEvent(clock, list);
        } catch (Throwable t) {
            return null;
        }
    }

    static long[] measNative(Snapshot s, long nowMs, long timeNs) {
        try {
            long[] r = LocationNative.nativeGnssMeas(s.pack(), nowMs, timeNs);
            if (r == null || r.length != 2 + 2 * s.n) return null;
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object buildEvent(Object clock, List<Object> list) throws Throwable {
        try {
            Object b = construct(Class.forName("android.location.GnssMeasurementsEvent$Builder"));
            call(b, "setClock", clock);
            call(b, "setMeasurements", list);
            return call(b, "build");
        } catch (Throwable first) {
            Class<?> ev = Class.forName("android.location.GnssMeasurementsEvent");
            for (Constructor<?> c : ev.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length != 2) continue;
                c.setAccessible(true);
                Object second = list;
                if (p[1].isArray()) {
                    Object arr = java.lang.reflect.Array.newInstance(p[1].getComponentType(), list.size());
                    for (int i = 0; i < list.size(); i++) java.lang.reflect.Array.set(arr, i, list.get(i));
                    second = arr;
                }
                return c.newInstance(clock, second);
            }
            throw first;
        }
    }

    static List<Object> antennaList() {
        try {
            Class<?> pc = Class.forName("android.location.GnssAntennaInfo$PhaseCenterOffset");
            Object off = null;
            for (Constructor<?> c : pc.getDeclaredConstructors()) {
                if (c.getParameterCount() != 6) continue;
                c.setAccessible(true);
                off = c.newInstance(1.4, 0.3, -0.9, 0.3, 2.2, 0.4);
                break;
            }
            if (off == null) return null;
            Class<?> bc = Class.forName("android.location.GnssAntennaInfo$Builder");
            Object b = null;
            for (Constructor<?> c : bc.getDeclaredConstructors()) {
                c.setAccessible(true);
                if (c.getParameterCount() == 0) { b = c.newInstance(); break; }
            }
            if (b == null) {
                for (Constructor<?> c : bc.getDeclaredConstructors()) {
                    if (c.getParameterCount() != 2) continue;
                    c.setAccessible(true);
                    b = c.newInstance(1575.42, off);
                    break;
                }
            }
            if (b == null) return null;
            trySet(b, "setCarrierFrequencyMHz", 1575.42);
            trySet(b, "setPhaseCenterOffset", off);
            return Collections.singletonList(call(b, "build"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class Key {
        final int kind;
        final Object target;

        Key(int kind, Object target) {
            this.kind = kind;
            this.target = target;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).kind == kind && ((Key) o).target == target;
        }

        @Override
        public int hashCode() {
            return kind * 31 + System.identityHashCode(target);
        }
    }

    private static final class Feed {
        volatile boolean dead;
        volatile ScheduledFuture<?> future;

        void cancel() {
            dead = true;
            ScheduledFuture<?> f = future;
            if (f != null) f.cancel(false);
        }
    }

    private static final Map<Key, Feed> FEEDS = new ConcurrentHashMap<>();
    private static ScheduledExecutorService exec;

    private static synchronized ScheduledExecutorService exec() {
        if (exec == null) {
            exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setDaemon(true);
                return t;
            });
        }
        return exec;
    }

    private static void schedule(int kind, Object target, long initialMs, long periodMs, Body body) {
        final Key key = new Key(kind, target);
        final Feed feed = new Feed();
        Feed old = FEEDS.put(key, feed);
        if (old != null) old.cancel();
        ScheduledFuture<?> f = exec().scheduleWithFixedDelay(() -> {
            boolean again = false;
            try {
                again = !feed.dead && body.run();
            } catch (Throwable ignored) { }
            if (!again) {
                feed.cancel();
                FEEDS.remove(key, feed);
            }
        }, initialMs, periodMs, TimeUnit.MILLISECONDS);
        feed.future = f;
        if (feed.dead) f.cancel(false);
    }

    static Object keyOf(Object listener) {
        try {
            if (listener instanceof IInterface) {
                Object b = ((IInterface) listener).asBinder();
                if (b != null) return b;
            }
        } catch (Throwable ignored) { }
        return listener;
    }

    static void stop(Object target) {
        if (target == null) return;
        for (int kind = KIND_STATUS; kind <= KIND_ANT; kind++) {
            Feed f = FEEDS.remove(new Key(kind, target));
            if (f != null) f.cancel();
        }
    }

    private static long[] plan(int kind) {
        if (!NATIVE) return null;
        try {
            long[] p = LocationNative.nativeFeedPlan(kind, SystemClock.elapsedRealtime() / 60_000L);
            return p != null && p.length == 3 ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int tick(int kind, boolean live, int[] state, long elapsedMs, long ttffMs) {
        try {
            long r = LocationNative.nativeFeedStep(kind, live, state[0], elapsedMs, ttffMs);
            if (r < 0) return 0;
            state[0] = (int) (r >> 8);
            return (int) (r & 0xff);
        } catch (Throwable t) {
            return 0;
        }
    }

    static void feedStatus(Object target, BooleanSupplier live, StatusSink sink, Runnable off) {
        final long[] p = plan(KIND_STATUS);
        if (p == null) return;
        final long begin = SystemClock.elapsedRealtime();
        final int[] state = new int[1];
        schedule(KIND_STATUS, target, p[0], p[1], () -> {
            int acts = tick(KIND_STATUS, live.getAsBoolean(), state, SystemClock.elapsedRealtime() - begin, p[2]);
            if ((acts & A_STOPPED) != 0) sink.stopped();
            if ((acts & A_OFF) != 0 && off != null) off.run();
            if ((acts & A_STARTED) != 0) sink.started();
            if ((acts & A_FIRST_FIX) != 0) sink.firstFix((int) p[2]);
            if ((acts & A_STATUS) != 0) sink.status(snapshot((acts & A_FIXED) != 0));
            return (acts & A_AGAIN) != 0;
        });
    }

    static void feedNmea(Object target, BooleanSupplier live, NmeaSink sink, Runnable off) {
        final long[] p = plan(KIND_NMEA);
        if (p == null) return;
        final int[] state = new int[1];
        schedule(KIND_NMEA, target, p[0], p[1], () -> {
            int acts = tick(KIND_NMEA, live.getAsBoolean(), state, 0L, 0L);
            if ((acts & A_OFF) != 0 && off != null) off.run();
            if ((acts & A_SEND) != 0) {
                long ts = System.currentTimeMillis();
                for (String m : nmea(snapshot(true))) sink.nmea(m, ts);
            }
            return (acts & A_AGAIN) != 0;
        });
    }

    static void feedMeasurements(Object target, BooleanSupplier live, MeasSink sink, Runnable off) {
        final long[] p = plan(KIND_MEAS);
        if (p == null) return;
        final int[] state = new int[1];
        schedule(KIND_MEAS, target, p[0], p[1], () -> {
            int acts = tick(KIND_MEAS, live.getAsBoolean(), state, 0L, 0L);
            if ((acts & A_OFF) != 0 && off != null) off.run();
            if ((acts & A_SEND) != 0) {
                Object ev = measurementsEvent(snapshot(true));
                if (ev == null) return false;
                sink.event(ev);
            }
            return (acts & A_AGAIN) != 0;
        });
    }

    static void feedAntenna(Object target, BooleanSupplier live, AntSink sink) {
        final long[] p = plan(KIND_ANT);
        if (p == null) return;
        final int[] state = new int[1];
        schedule(KIND_ANT, target, p[0], p[1], () -> {
            int acts = tick(KIND_ANT, live.getAsBoolean(), state, 0L, 0L);
            if ((acts & A_SEND) != 0) {
                List<Object> info = antennaList();
                if (info != null) sink.antenna(info);
            }
            return (acts & A_AGAIN) != 0;
        });
    }

    private static Object construct(Class<?> c) throws Throwable {
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            if (k.getParameterCount() != 0) continue;
            k.setAccessible(true);
            return k.newInstance();
        }
        throw new NoSuchMethodException(c.getName());
    }

    private static void trySet(Object target, String name, Object value) {
        try {
            call(target, name, value);
        } catch (Throwable ignored) { }
    }

    private static boolean trySetOk(Object target, String name, Object value) {
        try {
            call(target, name, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean fits(Class<?> p, Object a) {
        if (a == null) return !p.isPrimitive();
        if (p.isPrimitive()) {
            if (p == boolean.class) return a instanceof Boolean;
            return a instanceof Number;
        }
        return p.isInstance(a);
    }

    private static Object coerce(Object a, Class<?> p) {
        if (a instanceof Number && p.isPrimitive()) {
            Number n = (Number) a;
            if (p == int.class) return n.intValue();
            if (p == long.class) return n.longValue();
            if (p == float.class) return n.floatValue();
            if (p == double.class) return n.doubleValue();
            if (p == short.class) return n.shortValue();
            if (p == byte.class) return n.byteValue();
        }
        return a;
    }

    private static Method find(Class<?> type, String name, int count, Object... args) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            Method[] ms;
            try {
                ms = c == type ? c.getMethods() : c.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method m : ms) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (count >= 0 && p.length != count) continue;
                boolean ok = true;
                if (args.length == p.length) {
                    for (int i = 0; i < p.length && ok; i++) ok = fits(p[i], args[i]);
                }
                if (!ok) continue;
                try { m.setAccessible(true); } catch (Throwable ignored) { }
                return m;
            }
        }
        return null;
    }

    static Object call(Object target, String name, Object... args) throws Throwable {
        Method m = find(target.getClass(), name, args.length, args);
        if (m == null) throw new NoSuchMethodException(name);
        Class<?>[] p = m.getParameterTypes();
        Object[] a = new Object[args.length];
        for (int i = 0; i < a.length; i++) a[i] = coerce(args[i], p[i]);
        try {
            return m.invoke(target, a);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private GnssFake() { }
}