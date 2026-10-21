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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class GnssFake {
    static final int T_GPS = 1, T_GLO = 3, T_BDS = 5, T_GAL = 6;
    private static final int KIND_STATUS = 0, KIND_NMEA = 1, KIND_MEAS = 2, KIND_ANT = 3;
    private static final int F_EPH = 1, F_ALM = 2, F_USED = 4, F_CARRIER = 8, F_BASEBAND = 16;
    private static final int SVID_SHIFT = 12, TYPE_SHIFT = 8;
    private static final long GPS_EPOCH_MS = 315_964_800_000L;
    private static final int LEAP_S = 18;
    private static final long WEEK_NS = 604_800L * 1_000_000_000L;
    private static final double LIGHT = 299_792_458.0;
    private static final Random RND = new Random();

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

    private static final class Sat {
        final int type, svid;
        final double az0, rate, phase, period;

        Sat(int type, int svid, double az0, double rate, double phase, double period) {
            this.type = type;
            this.svid = svid;
            this.az0 = az0;
            this.rate = rate;
            this.phase = phase;
            this.period = period;
        }
    }

    static final class Snapshot {
        final int n;
        final int[] type, svid;
        final float[] cn0, elev, az, carrier;
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
        }

        int usedCount() {
            int c = 0;
            for (boolean u : used) if (u) c++;
            return c;
        }

        int[] flags() {
            int[] f = new int[n];
            for (int i = 0; i < n; i++) {
                f[i] = (svid[i] << SVID_SHIFT) | (type[i] << TYPE_SHIFT)
                        | F_EPH | F_ALM | F_CARRIER | F_BASEBAND | (used[i] ? F_USED : 0);
            }
            return f;
        }

        float[] baseband() {
            float[] b = new float[n];
            for (int i = 0; i < n; i++) b[i] = Math.max(0f, cn0[i] - 1.5f);
            return b;
        }
    }

    private static final Sat[] POOL = buildPool();

    private static Sat[] buildPool() {
        Random r = new Random();
        int[][] plan = {{T_GPS, 32, 11}, {T_GLO, 24, 6}, {T_GAL, 36, 6}, {T_BDS, 37, 6}};
        List<Sat> out = new ArrayList<>();
        for (int[] p : plan) {
            List<Integer> ids = new ArrayList<>();
            for (int i = 1; i <= p[1]; i++) ids.add(i);
            Collections.shuffle(ids, r);
            for (int i = 0; i < p[2]; i++) {
                double rate = (r.nextBoolean() ? 1 : -1) * (0.004 + r.nextDouble() * 0.006);
                out.add(new Sat(p[0], ids.get(i), r.nextDouble() * 360.0, rate,
                        r.nextDouble() * 2 * Math.PI, 600 + r.nextDouble() * 900));
            }
        }
        return out.toArray(new Sat[0]);
    }

    private static float carrier(int type, int svid) {
        switch (type) {
            case T_GLO: return 1_602_000_000f + (svid % 14 - 7) * 562_500f;
            case T_BDS: return 1_561_098_000f;
            default: return 1_575_420_000f;
        }
    }

    static Snapshot snapshot(boolean fixed) {
        int[] g = Config.gnssExtras();
        int total = POOL.length;
        int want = Math.min(total, g[0] + 3 + (g[0] & 1));
        double t = SystemClock.elapsedRealtime() / 1000.0;
        final double[] el = new double[total];
        Integer[] order = new Integer[total];
        for (int i = 0; i < total; i++) {
            Sat s = POOL[i];
            el[i] = 8.0 + 77.0 * (0.5 + 0.5 * Math.sin(s.phase + t / s.period));
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(el[b], el[a]));
        Snapshot out = new Snapshot(want);
        int usedLeft = fixed ? g[0] : 0;
        for (int k = 0; k < want; k++) {
            int i = order[k];
            Sat s = POOL[i];
            double norm = (el[i] - 8.0) / 77.0;
            double c = g[1] - 7.0 + 14.0 * norm + (RND.nextDouble() - 0.5) * 2.4;
            out.type[k] = s.type;
            out.svid[k] = s.svid;
            out.cn0[k] = (float) Math.max(14.0, Math.min(47.0, c));
            out.elev[k] = (float) el[i];
            out.az[k] = (float) (((s.az0 + s.rate * t) % 360.0 + 360.0) % 360.0);
            out.carrier[k] = carrier(s.type, s.svid);
            if (usedLeft > 0) {
                out.used[k] = true;
                usedLeft--;
            }
        }
        return out;
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

    private static String talker(int type) {
        switch (type) {
            case T_GLO: return "GL";
            case T_GAL: return "GA";
            case T_BDS: return "GB";
            default: return "GP";
        }
    }

    private static int systemId(int type) {
        switch (type) {
            case T_GLO: return 2;
            case T_GAL: return 3;
            case T_BDS: return 4;
            default: return 1;
        }
    }

    private static int signalId(int type) {
        return type == T_GAL ? 7 : 1;
    }

    private static int nmeaId(int type, int svid) {
        return type == T_GLO ? 64 + svid : svid;
    }

    private static String sentence(String body) {
        int cs = 0;
        for (int i = 0; i < body.length(); i++) cs ^= body.charAt(i);
        return "$" + body + "*" + String.format(Locale.US, "%02X", cs) + "\r\n";
    }

    private static String coord(double v, boolean lat) {
        double a = Math.abs(v);
        int d = (int) a;
        double m = (a - d) * 60.0;
        return String.format(Locale.US, lat ? "%02d%010.7f" : "%03d%010.7f", d, m);
    }

    static List<String> nmea(Snapshot s) {
        Location fix = new Location(LocationManager.GPS_PROVIDER);
        Fix.apply(fix, LocationManager.GPS_PROVIDER, Config.snapshot());
        long ms = System.currentTimeMillis();
        ms -= ms % 1000L;
        ZonedDateTime z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
        String hms = String.format(Locale.US, "%02d%02d%02d.00", z.getHour(), z.getMinute(), z.getSecond());
        String dmy = String.format(Locale.US, "%02d%02d%02d", z.getDayOfMonth(), z.getMonthValue(), z.getYear() % 100);
        double lat = fix.getLatitude(), lng = fix.getLongitude();
        char ns = lat >= 0 ? 'N' : 'S', ew = lng >= 0 ? 'E' : 'W';
        double hdop = Math.max(0.7, Math.min(5.0, fix.getAccuracy() / 5.0));
        double vdop = hdop * 1.1;
        double pdop = Math.sqrt(hdop * hdop + vdop * vdop);
        double knots = fix.hasSpeed() ? fix.getSpeed() * 1.943844 : 0.0;
        double course = fix.hasBearing() ? fix.getBearing() : 0.0;
        double alt = fix.hasAltitude() ? fix.getAltitude() : 0.0;
        List<String> out = new ArrayList<>();
        out.add(sentence(String.format(Locale.US, "GNGGA,%s,%s,%c,%s,%c,1,%02d,%.1f,%.1f,M,%.1f,M,,",
                hms, coord(lat, true), ns, coord(lng, false), ew, s.usedCount(), hdop, alt, 0.0)));
        int[] groups = {T_GPS, T_GLO, T_GAL, T_BDS};
        for (int type : groups) {
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < s.n; i++) if (s.type[i] == type && s.used[i]) ids.add(nmeaId(type, s.svid[i]));
            if (ids.isEmpty()) continue;
            StringBuilder slots = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                if (i > 0) slots.append(',');
                if (i < ids.size()) slots.append(String.format(Locale.US, "%02d", ids.get(i)));
            }
            out.add(sentence(String.format(Locale.US, "GNGSA,A,3,%s,%.1f,%.1f,%.1f,%d",
                    slots, pdop, hdop, vdop, systemId(type))));
        }
        for (int type : groups) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < s.n; i++) if (s.type[i] == type) idx.add(i);
            if (idx.isEmpty()) continue;
            int pages = (idx.size() + 3) / 4;
            for (int p = 0; p < pages; p++) {
                StringBuilder sb = new StringBuilder(String.format(Locale.US, "%sGSV,%d,%d,%02d",
                        talker(type), pages, p + 1, idx.size()));
                for (int j = p * 4; j < Math.min(idx.size(), p * 4 + 4); j++) {
                    int i = idx.get(j);
                    sb.append(String.format(Locale.US, ",%02d,%02d,%03d,%02d",
                            nmeaId(type, s.svid[i]), Math.round(s.elev[i]), Math.round(s.az[i]) % 360, Math.round(s.cn0[i])));
                }
                sb.append(',').append(signalId(type));
                out.add(sentence(sb.toString()));
            }
        }
        out.add(sentence(String.format(Locale.US, "GNRMC,%s,A,%s,%c,%s,%c,%.2f,%.2f,%s,,,A",
                hms, coord(lat, true), ns, coord(lng, false), ew, knots, course, dmy)));
        return out;
    }

    static Object measurementsEvent(Snapshot s) {
        try {
            long nowMs = System.currentTimeMillis();
            long timeNs = SystemClock.elapsedRealtimeNanos();
            long gpsNs = (nowMs - GPS_EPOCH_MS + LEAP_S * 1000L) * 1_000_000L;
            Object clock = construct(Class.forName("android.location.GnssClock"));
            trySet(clock, "setTimeNanos", timeNs);
            trySet(clock, "setFullBiasNanos", timeNs - gpsNs);
            trySet(clock, "setBiasNanos", 0.0);
            trySet(clock, "setBiasUncertaintyNanos", 4.0);
            trySet(clock, "setLeapSecond", LEAP_S);
            trySet(clock, "setHardwareClockDiscontinuityCount", 0);
            long tow = gpsNs % WEEK_NS;
            Class<?> mc = Class.forName("android.location.GnssMeasurement");
            List<Object> list = new ArrayList<>(s.n);
            for (int i = 0; i < s.n; i++) {
                double norm = (s.elev[i] - 8.0) / 77.0;
                long travelNs = (long) ((20_200_000.0 + (1.0 - norm) * 5_000_000.0) / LIGHT * 1e9);
                double rate = ((s.svid[i] * 37 % 1600) - 800) * 0.9 + Math.sin(SystemClock.elapsedRealtime() / 60000.0 + i) * 3.0;
                Object m = construct(mc);
                trySet(m, "setSvid", s.svid[i]);
                trySet(m, "setConstellationType", s.type[i]);
                trySet(m, "setCn0DbHz", (double) s.cn0[i]);
                trySet(m, "setTimeOffsetNanos", 0.0);
                trySet(m, "setState", 15);
                trySet(m, "setReceivedSvTimeNanos", tow - travelNs);
                trySet(m, "setReceivedSvTimeUncertaintyNanos", 30L);
                trySet(m, "setPseudorangeRateMetersPerSecond", rate);
                trySet(m, "setPseudorangeRateUncertaintyMetersPerSecond", 0.2);
                trySet(m, "setAccumulatedDeltaRangeState", 0);
                trySet(m, "setCarrierFrequencyHz", s.carrier[i]);
                trySet(m, "setMultipathIndicator", 2);
                list.add(m);
            }
            return buildEvent(clock, list);
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

    static void feedStatus(Object target, BooleanSupplier live, StatusSink sink) {
        final long ttff = 700 + RND.nextInt(1500);
        final long begin = SystemClock.elapsedRealtime();
        final boolean[] flags = new boolean[2];
        schedule(KIND_STATUS, target, 150 + RND.nextInt(200), 1000, () -> {
            if (!live.getAsBoolean()) {
                sink.stopped();
                return false;
            }
            if (!flags[0]) {
                flags[0] = true;
                sink.started();
                return true;
            }
            boolean fixed = SystemClock.elapsedRealtime() - begin >= ttff;
            if (fixed && !flags[1]) {
                flags[1] = true;
                sink.firstFix((int) ttff);
            }
            sink.status(snapshot(fixed));
            return true;
        });
    }

    static void feedNmea(Object target, BooleanSupplier live, NmeaSink sink) {
        schedule(KIND_NMEA, target, 1000 + RND.nextInt(200), 1000, () -> {
            if (!live.getAsBoolean()) return false;
            long ts = System.currentTimeMillis();
            for (String m : nmea(snapshot(true))) sink.nmea(m, ts);
            return true;
        });
    }

    static void feedMeasurements(Object target, BooleanSupplier live, MeasSink sink) {
        schedule(KIND_MEAS, target, 1000 + RND.nextInt(200), 1000, () -> {
            if (!live.getAsBoolean()) return false;
            Object ev = measurementsEvent(snapshot(true));
            if (ev == null) return false;
            sink.event(ev);
            return true;
        });
    }

    static void feedAntenna(Object target, BooleanSupplier live, AntSink sink) {
        schedule(KIND_ANT, target, 200 + RND.nextInt(200), 1000, () -> {
            if (!live.getAsBoolean()) return false;
            List<Object> info = antennaList();
            if (info != null) sink.antenna(info);
            return false;
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
