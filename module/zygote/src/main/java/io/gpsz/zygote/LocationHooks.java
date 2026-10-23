package io.gpsz.zygote;

import android.app.AppOpsManager;
import android.location.GnssAntennaInfo;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiSsid;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;

import com.v7878.unsafe.Reflection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class LocationHooks {
    private static final int K_LAT = 0, K_LNG = 1, K_ACC = 2, K_MOCK = 3, K_LAST_KNOWN = 4;
    private static final int K_DIST = 11, K_BEARING_TO = 12;
    private static final int K_VACC = 13, K_SACC = 14, K_BACC = 15, K_HAS_VACC = 16, K_HAS_SACC = 17, K_HAS_BACC = 18;
    private static final int K_MSL = 19, K_MSL_ACC = 20, K_HAS_MSL = 21, K_HAS_MSL_ACC = 22;
    private static final int K_EXT = 30, K_NO_BOOL = 31, K_BSSID = 32, K_NULL = 33, K_CELL_UPDATE = 34, K_CELL_LIST_ARG = 35, K_DROP = 36;
    private static final int K_BT_DROP = 37, K_BT_INT = 38, K_BT_TRUE = 39, K_CREATE = 41, K_TOSTRING = 43, K_DELIVER = 44;
    private static final int K_WIFI_SCAN = 45, K_WIFI_SSID = 47, K_CELL_ALL = 48, K_CELL_LOC = 49;
    private static final int K_FEED_NMEA = 50, K_FEED_MEAS = 51, K_FEED_ANT = 52, K_SET = 54;
    private static final int K_PROVIDER_ENABLED = 55, K_GET_PROVIDERS = 56, K_LOCATION_ENABLED = 57, K_FEED_GPS = 58, K_GPS_STATUS = 59;
    private static final Object VOID_RESULT = new Object();
    private static final Object NULL_RESULT = new Object();
    private static final int K_SPD = 5, K_BRG = 6, K_ALT = 7, K_HAS_SPD = 8, K_HAS_BRG = 9, K_HAS_ALT = 10;
    private static final long MAX_CLOCK_SKEW_MS = 5_000L;
    private static final int OP_MOCK_LOCATION = 58;
    private static final int MODE_IGNORED = 1;
    private static final List<String> SPOOF_PROVIDERS = Arrays.asList(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.FUSED_PROVIDER);
    private static final ThreadLocal<Boolean> PARSING = new ThreadLocal<>();

    static void install() {
        hook(Location.class, "getLatitude", K_LAT);
        hook(Location.class, "getLongitude", K_LNG);
        hook(Location.class, "getAccuracy", K_ACC);
        hook(Location.class, "getSpeed", K_SPD);
        hook(Location.class, "getBearing", K_BRG);
        hook(Location.class, "getAltitude", K_ALT);
        hook(Location.class, "hasSpeed", K_HAS_SPD);
        hook(Location.class, "hasBearing", K_HAS_BRG);
        hook(Location.class, "hasAltitude", K_HAS_ALT);
        hook(Location.class, "getVerticalAccuracyMeters", K_VACC);
        hook(Location.class, "getSpeedAccuracyMetersPerSecond", K_SACC);
        hook(Location.class, "getBearingAccuracyDegrees", K_BACC);
        hook(Location.class, "hasVerticalAccuracy", K_HAS_VACC);
        hook(Location.class, "hasSpeedAccuracy", K_HAS_SACC);
        hook(Location.class, "hasBearingAccuracy", K_HAS_BACC);
        hook(Location.class, "getMslAltitudeMeters", K_MSL);
        hook(Location.class, "getMslAltitudeAccuracyMeters", K_MSL_ACC);
        hook(Location.class, "hasMslAltitude", K_HAS_MSL);
        hook(Location.class, "hasMslAltitudeAccuracy", K_HAS_MSL_ACC);
        hook(Location.class, "isFromMockProvider", K_MOCK);
        hook(Location.class, "isMock", K_MOCK);
        hook(Location.class, "distanceTo", K_DIST, Location.class);
        hook(Location.class, "bearingTo", K_BEARING_TO, Location.class);
        hook(LocationManager.class, "getLastKnownLocation", K_LAST_KNOWN, String.class);
        hook(LocationManager.class, "isProviderEnabled", K_PROVIDER_ENABLED, String.class);
        hook(LocationManager.class, "isLocationEnabled", K_LOCATION_ENABLED);
        hookExact(LocationManager.class, "getProviders", K_GET_PROVIDERS, boolean.class);
        hookExact(LocationManager.class, "getAllProviders", K_GET_PROVIDERS);
        hookAll(LocationManager.class, "addNmeaListener", K_FEED_NMEA);
        hookAll(LocationManager.class, "registerGnssMeasurementsCallback", K_FEED_MEAS);
        hookAll(LocationManager.class, "registerGnssNavigationMessageCallback", K_NO_BOOL);
        hookAll(LocationManager.class, "registerAntennaInfoListener", K_FEED_ANT);
        hookGnssCallback();
        hookUnfeed(LocationManager.class, "unregisterGnssStatusCallback", GnssStatus.Callback.class);
        hookUnfeed(LocationManager.class, "removeNmeaListener", OnNmeaMessageListener.class);
        hookUnfeed(LocationManager.class, "unregisterGnssMeasurementsCallback", GnssMeasurementsEvent.Callback.class);
        hookUnfeed(LocationManager.class, "unregisterAntennaInfoListener", GnssAntennaInfo.Listener.class);
        hookAll(LocationManager.class, "addGpsStatusListener", K_FEED_GPS);
        hookAll(LocationManager.class, "getGpsStatus", K_GPS_STATUS);
        hookUnfeed(LocationManager.class, "removeGpsStatusListener", GPS_LISTENER);
        hookCreator();
        installAppOps();
        hookGetCurrentLocation();
        SettingsGuard.install();
        PackageGuard.install();
        hookAll("android.net.wifi.WifiManager", "getScanResults", K_WIFI_SCAN);
        hookAll("android.net.wifi.WifiInfo", "getBSSID", K_BSSID);
        hookAll("android.net.wifi.WifiInfo", "getSSID", K_WIFI_SSID);
        hookAll("android.bluetooth.le.BluetoothLeScanner", "startScan", K_BT_DROP);
        hookAll("android.bluetooth.BluetoothAdapter", "startLeScan", K_BT_TRUE);
        hookAll("android.bluetooth.BluetoothAdapter", "startDiscovery", K_BT_TRUE);
        hookAll("android.telephony.TelephonyManager", "getAllCellInfo", K_CELL_ALL);
        hookAll("android.telephony.TelephonyManager", "getCellLocation", K_CELL_LOC);
        hookAll("android.telephony.TelephonyManager", "requestCellInfoUpdate", K_CELL_UPDATE);
        hookAll("android.telephony.NetworkRegistrationInfo", "getCellIdentity", K_NULL);
        for (String stub : new String[]{
                "android.telephony.PhoneStateListener$IPhoneStateListenerStub",
                "android.telephony.TelephonyCallback$IPhoneStateListenerStub"}) {
            hookAll(stub, "onCellInfoChanged", K_CELL_LIST_ARG);
            hookAll(stub, "onCellLocationChanged", K_DROP);
        }
        hook(Location.class, "toString", K_TOSTRING);
        hook(Location.class, "set", K_SET, Location.class);
        hookAll("android.location.LocationManager$LocationListenerTransport", "onLocationChanged", K_DELIVER);
        hookAll("android.location.LocationManager$GetCurrentLocationTransport", "onLocation", K_DELIVER);
    }

    private static void hookGetCurrentLocation() {
        for (Method m : LocationManager.class.getDeclaredMethods()) {
            if (!m.getName().equals("getCurrentLocation")) continue;
            final int nParams = m.getParameterCount();
            try {
                Frames.hook(m, (original, frame) -> {
                    if (!Config.enabled || !Config.hasPos() || Config.sysHeartbeat || PARSING.get() != null) {
                        try { original.invoke(); }
                        catch (Throwable t) { ZygoteEntry.<RuntimeException>sneaky(t); }
                        return;
                    }
                    String provider = LocationManager.GPS_PROVIDER;
                    Executor executor = null;
                    Consumer<Location> consumer = null;
                    for (int i = 1; i <= nParams; i++) {
                        try {
                            Object o = frame.get(i);
                            if (o instanceof String) provider = (String) o;
                            else if (o instanceof Executor) executor = (Executor) o;
                            else if (o instanceof Consumer) consumer = castConsumer(o);
                        } catch (Throwable ignored) { }
                    }
                    if (consumer == null) {
                        try { original.invoke(); }
                        catch (Throwable t) { ZygoteEntry.<RuntimeException>sneaky(t); }
                        return;
                    }
                    Location fake = Fix.fresh(provider);
                    final Consumer<Location> c = consumer;
                    if (executor != null) {
                        try { executor.execute(() -> c.accept(fake)); }
                        catch (Throwable ignored) { try { c.accept(fake); } catch (Throwable ignored2) { } }
                    } else {
                        try { c.accept(fake); } catch (Throwable ignored) { }
                    }
                    if (m.getReturnType() == boolean.class) {
                        frame.set(Frames.RET, Boolean.TRUE);
                    }
                });
            } catch (Throwable ignored) { }
        }
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Location> castConsumer(Object o) {
        try { return (Consumer<Location>) o; } catch (Throwable t) { return null; }
    }

    private static void installAppOps() {
        try {
            for (Method m : AppOpsManager.class.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers()) || Modifier.isNative(m.getModifiers())) continue;
                String n = m.getName();
                if (!n.equals("checkOp") && !n.equals("checkOpNoThrow")
                        && !n.equals("unsafeCheckOp") && !n.equals("unsafeCheckOpNoThrow")
                        && !n.equals("checkOpRaw") && !n.equals("checkOpRawNoThrow")) continue;
                Class<?>[] ps = m.getParameterTypes();
                int opIdx = -1;
                for (int i = 0; i < ps.length; i++) {
                    if (ps[i] == int.class) { opIdx = i + 1; break; }
                }
                if (opIdx < 0) continue;
                final int idx = opIdx;
                final Class<?> ret = m.getReturnType();
                try {
                    Frames.hook(m, (original, frame) -> {
                        try {
                            Object op = frame.get(idx);
                            if (op instanceof Integer && (Integer) op == OP_MOCK_LOCATION) {
                                if (ret == int.class) frame.set(Frames.RET, MODE_IGNORED);
                                return;
                            }
                        } catch (Throwable ignored) { }
                        try {
                            original.invoke();
                        } catch (Throwable t) {
                            ZygoteEntry.<RuntimeException>sneaky(t);
                        }
                    });
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private static void hookCreator() {
        try {
            Object creator = Location.class.getField("CREATOR").get(null);
            for (Method m : creator.getClass().getDeclaredMethods()) {
                if (!m.getName().equals("createFromParcel") || m.isBridge() || m.getReturnType() != Location.class) continue;
                try {
                    Frames.hook(m, transformer(K_CREATE, m.getParameterCount()));
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private static void hookAll(String className, String name, int kind) {
        try {
            hookAll(Class.forName(className), name, kind);
        } catch (Throwable ignored) { }
    }

    private static void hookAll(Class<?> c, String name, int kind) {
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            int k = kind;
            if (kind == K_BT_DROP) {
                Class<?> r = m.getReturnType();
                k = r == void.class ? K_BT_DROP : r == int.class ? K_BT_INT : K_BT_TRUE;
            }
            try {
                Frames.hook(m, transformer(k, m.getParameterCount()));
            } catch (Throwable ignored) { }
        }
    }

    private static void hookExact(Class<?> c, String name, int kind, Class<?>... params) {
        try {
            Method m = c.getDeclaredMethod(name, params);
            Frames.hook(m, transformer(kind, m.getParameterCount()));
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) { }
    }

    private static final int GPS_EVENT_STARTED = 1, GPS_EVENT_STOPPED = 2, GPS_EVENT_FIRST_FIX = 3, GPS_EVENT_SATELLITE_STATUS = 4;
    private static final Class<?> GPS_STATUS = classOrNull("android.location.GpsStatus");
    private static final Class<?> GPS_LISTENER = classOrNull("android.location.GpsStatus$Listener");

    private static Class<?> classOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hookUnfeed(Class<?> c, String name, Class<?> listenerType) {
        if (listenerType == null) return;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] ps = m.getParameterTypes();
            int idx = -1;
            for (int i = 0; i < ps.length; i++) {
                if (listenerType.isAssignableFrom(ps[i])) { idx = i + 1; break; }
            }
            if (idx < 0) continue;
            final int target = idx;
            try {
                Frames.hook(m, (original, frame) -> {
                    try {
                        Object l = frame.get(target);
                        if (l != null) GnssFake.stop(GnssFake.keyOf(l));
                    } catch (Throwable ignored) { }
                    try {
                        original.invoke();
                    } catch (Throwable t) {
                        ZygoteEntry.<RuntimeException>sneaky(t);
                    }
                });
            } catch (Throwable ignored) { }
        }
    }

    private static void hook(Class<?> c, String name, int kind, Class<?>... params) {
        try {
            Method m = c.getDeclaredMethod(name, params);
            Frames.hook(m, transformer(kind, m.getParameterCount()));
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) { }
    }

    private static Frames.Tx transformer(final int kind, final int np) {
        return (original, frame) -> {
            if (kind == K_CREATE) {
                Boolean prev = PARSING.get();
                PARSING.set(Boolean.TRUE);
                try {
                    original.invoke();
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                } finally {
                    if (prev == null) PARSING.remove(); else PARSING.set(prev);
                }
                try {
                    if (Config.enabled && Config.hasPos()) {
                        fixSnap(frame.get(Frames.RET));
                    }
                } catch (Throwable ignored) { }
                return;
            }
            if (kind == K_SET) {
                try {
                    original.invoke();
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
                if (PARSING.get() == null) {
                    try {
                        if (Config.enabled && Config.hasPos()) fixSnap(frame.get(0));
                    } catch (Throwable ignored) { }
                }
                return;
            }
            if (kind == K_LOCATION_ENABLED || kind == K_PROVIDER_ENABLED || kind == K_GET_PROVIDERS) {
                boolean active = PARSING.get() == null && Config.enabled && Config.hasPos() && !Config.sysHeartbeat;
                if (active && kind == K_LOCATION_ENABLED) {
                    frame.set(Frames.RET, Boolean.TRUE);
                    return;
                }
                if (active && kind == K_PROVIDER_ENABLED) {
                    try {
                        Object p = frame.get(1);
                        if (p instanceof String && SPOOF_PROVIDERS.contains(p)) {
                            frame.set(Frames.RET, Boolean.TRUE);
                            return;
                        }
                    } catch (Throwable ignored) { }
                }
                try {
                    original.invoke();
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
                if (active && kind == K_GET_PROVIDERS) {
                    try {
                        Object r = frame.get(Frames.RET);
                        if (r instanceof List) {
                            List<String> dst = new ArrayList<>();
                            for (Object o : (List<?>) r) if (o instanceof String) dst.add((String) o);
                            for (String p : SPOOF_PROVIDERS) if (!dst.contains(p)) dst.add(p);
                            frame.set(Frames.RET, dst);
                        }
                    } catch (Throwable ignored) { }
                }
                return;
            }
            if (PARSING.get() != null) {
                try {
                    original.invoke();
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
                return;
            }
            boolean handled = false;
            Throwable denied = null;
            try {
                if (Config.enabled && Config.hasPos()) {
                    Object value;
                    if (kind == K_LAST_KNOWN) {
                        value = null;
                        handled = true;
                        try {
                            original.invoke();
                        } catch (Throwable e) {
                            denied = e;
                        }
                        if (denied == null && lastKnownAllowed(frame)) {
                            frame.set(Frames.RET, fakeLastKnown(frame));
                        }
                    }
                    else if (kind == K_DELIVER) {
                        value = null;
                        deliver(frame, np);
                    }
                    else if (isSnapKind(kind)) {
                        fixSnap(frame.get(0));
                        value = null;
                    }
                    else if (kind == K_DIST || kind == K_BEARING_TO) {
                        fixSnap(frame.get(0));
                        fixSnap(frame.get(1));
                        value = null;
                    }
                    else if (kind == K_CELL_UPDATE) {
                        value = null;
                        if (Config.hideNet && emptyCellUpdate(frame, np)) handled = true;
                    }
                    else if (kind == K_CELL_LIST_ARG) {
                        value = null;
                        if (Config.hideNet) frame.set(1, Collections.<CellInfo>emptyList());
                    }
                    else if (kind == K_DROP) {
                        value = null;
                        if (Config.hideNet) handled = true;
                    }
                    else if (kind == K_WIFI_SCAN) {
                        value = Config.hideNet ? fakeWifiScan() : null;
                    }
                    else if (kind == K_WIFI_SSID) {
                        value = Config.hideNet ? fakeWifiSsid() : null;
                    }
                    else if (kind == K_CELL_ALL) {
                        value = Config.hideNet ? Collections.<CellInfo>emptyList() : null;
                    }
                    else if (kind == K_CELL_LOC) {
                        value = Config.hideNet ? NULL_RESULT : null;
                    }
                    else if ((kind >= K_FEED_NMEA && kind <= K_FEED_ANT) || kind == K_FEED_GPS) {
                        value = Boolean.valueOf(feed(kind, frame, np));
                    }
                    else if (kind == K_GPS_STATUS) {
                        value = fakeGpsStatus(frame.get(1));
                    }
                    else if (kind >= K_EXT) value = (kind == K_NO_BOOL || (kind >= K_BT_DROP && kind <= K_BT_TRUE ? Config.hideBt : Config.hideNet)) ? fakeExt(kind) : null;
                    else value = isFix(frame.get(0)) ? fake() : null;
                    if (value == VOID_RESULT) {
                        handled = true;
                    } else if (value == NULL_RESULT) {
                        frame.set(Frames.RET, null);
                        handled = true;
                    } else if (value != null) {
                        frame.set(Frames.RET, value);
                        handled = true;
                    }
                }
            } catch (Throwable ignored) { }
            if (denied != null) ZygoteEntry.<RuntimeException>sneaky(denied);
            if (!handled) {
                try {
                    original.invoke();
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
            }
        };
    }

    private static final class Mark {
        final long ns, ms;

        Mark(long ns, long ms) {
            this.ns = ns;
            this.ms = ms;
        }
    }

    private static final WeakIdentityMap<Mark> PER_OBJ = new WeakIdentityMap<>();

    private static boolean isSnapKind(int kind) {
        return kind == K_LAT || kind == K_LNG || kind == K_ACC || kind == K_SPD || kind == K_BRG || kind == K_ALT
                || (kind >= K_HAS_SPD && kind <= K_HAS_ALT)
                || (kind >= K_VACC && kind <= K_HAS_MSL_ACC)
                || kind == K_TOSTRING;
    }

    private static void fixSnap(Object o) {
        if (!(o instanceof Location)) return;
        Location l = (Location) o;
        long ns, ms;
        String p;
        try {
            ns = l.getElapsedRealtimeNanos();
            ms = l.getTime();
            p = l.getProvider();
        } catch (Throwable t) {
            return;
        }
        if (ns == 0 || ms <= 0 || p == null || p.isEmpty()) return;
        Mark e = PER_OBJ.get(l);
        if (e != null && e.ns == ns && e.ms == ms) return;
        Config.Pt pt;
        if (PARSING.get() != null) {
            pt = Config.snap;
            if (pt == null) return;
        } else {
            pt = Config.snapshot();
        }
        Location fix = new Location(p);
        Fix.apply(fix, p, pt);
        long now = System.currentTimeMillis();
        if (ms > now + MAX_CLOCK_SKEW_MS) ms = now;
        PER_OBJ.put(l, new Mark(ns, ms));
        Boolean prev = PARSING.get();
        PARSING.set(Boolean.TRUE);
        try {
            l.set(fix);
            l.setTime(ms);
            l.setElapsedRealtimeNanos(ns);
            Fix.clearMock(l);
        } catch (Throwable ignored) {
        } finally {
            if (prev == null) PARSING.remove(); else PARSING.set(prev);
        }
    }

    private static boolean isFix(Object o) {
        if (!(o instanceof Location)) return false;
        try {
            Location l = (Location) o;
            if (l.getElapsedRealtimeNanos() == 0) return false;
            if (l.getTime() <= 0) return false;
            String p = l.getProvider();
            return p != null && !p.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void deliver(Frames.Fr frame, int np) {
        for (int i = 1; i <= np; i++) {
            try {
                Object o = frame.get(i);
                if (o instanceof List) {
                    for (Object e : (List<?>) o) fixSnap(e);
                } else {
                    fixSnap(o);
                }
            } catch (Throwable ignored) { }
        }
    }

    private static Object fake() {
        Config.tick();
        return Boolean.FALSE;
    }

    private static boolean emptyCellUpdate(Frames.Fr frame, int np) {
        Executor ex = null;
        TelephonyManager.CellInfoCallback cb = null;
        for (int i = 1; i <= np; i++) {
            try {
                Object o = frame.get(i);
                if (o instanceof Executor) ex = (Executor) o;
                else if (o instanceof TelephonyManager.CellInfoCallback) cb = (TelephonyManager.CellInfoCallback) o;
            } catch (Throwable ignored) { }
        }
        if (ex == null || cb == null) return false;
        final TelephonyManager.CellInfoCallback fcb = cb;
        try {
            ex.execute(() -> fcb.onCellInfo(Collections.<CellInfo>emptyList()));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object fakeWifiScan() {
        try {
            Location fix = Fix.at(LocationManager.FUSED_PROVIDER);
            List<EnvSim.Ap> aps = EnvSim.wifiFor(fix);
            List<ScanResult> out = new ArrayList<>(aps.size());
            for (EnvSim.Ap ap : aps) {
                ScanResult sr = newScanResult(ap);
                if (sr != null) out.add(sr);
            }
            return out;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private static ScanResult newScanResult(EnvSim.Ap ap) {
        try {
            Constructor<ScanResult> ctor = ScanResult.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ScanResult sr = ctor.newInstance();
            putField(sr, "wifiSsid", WifiSsid.fromBytes(ap.ssid.getBytes(StandardCharsets.UTF_8)));
            putField(sr, "SSID", ap.ssid);
            putField(sr, "BSSID", ap.bssid);
            putField(sr, "level", ap.rssi);
            putField(sr, "frequency", ap.freq);
            putField(sr, "timestamp", SystemClock.elapsedRealtime() * 1000L);
            return sr;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void putField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Throwable ignored) { }
    }

    private static Object fakeWifiSsid() {
        try {
            List<EnvSim.Ap> aps = EnvSim.wifiFor(Fix.at(LocationManager.FUSED_PROVIDER));
            return aps.isEmpty() ? "<unknown ssid>" : aps.get(0).ssid;
        } catch (Throwable t) {
            return "<unknown ssid>";
        }
    }

    private static final class EnvSim {
        private static final ConcurrentHashMap<String, List<Object[]>> CACHE = new ConcurrentHashMap<>();
        private static final ConcurrentHashMap<String, Long> STAMP = new ConcurrentHashMap<>();
        private static final long TTL_MS = 30_000L;

        static final class Ap {
            final String ssid, bssid;
            final int rssi, freq;
            Ap(String ssid, String bssid, int rssi, int freq) {
                this.ssid = ssid; this.bssid = bssid; this.rssi = rssi; this.freq = freq;
            }
        }

        static List<Ap> wifiFor(Location fix) {
            if (fix == null) return Collections.emptyList();
            long bucket = SystemClock.elapsedRealtime() / TTL_MS;
            String k = String.format(Locale.US, "%.3f,%.3f,%d", fix.getLatitude(), fix.getLongitude(), bucket);
            Long ts = STAMP.get(k);
            long now = SystemClock.elapsedRealtime();
            List<Object[]> cached = CACHE.get(k);
            if (cached != null && ts != null && now - ts < TTL_MS) {
                return jitter(cached);
            }
            if (CACHE.size() > 32) {
                CACHE.clear();
                STAMP.clear();
            }
            long seed = Config.seed() ^ bucket;
            for (byte b : ("wifi:" + k).getBytes(StandardCharsets.UTF_8)) seed = 31 * seed + b;
            Random r = new Random(seed);
            int n = 4 + r.nextInt(5);
            List<Ap> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String[] pref = {"HOME-", "TP-LINK_", "IndiHome-", "MyRepublic-", "Biznet-", "AndroidAP_", "WARP-", "Xiaomi_", "Redmi_", "OPPO-", "vivo-", "realme-", "HUAWEI-", "Tenda_", "MERCURY_", "Cisco-", "DIRECT-"};
                StringBuilder ssid = new StringBuilder(pref[r.nextInt(pref.length)]);
                String cs = "0123456789ABCDEF";
                int len = 4 + r.nextInt(6);
                for (int j = 0; j < len; j++) ssid.append(cs.charAt(r.nextInt(cs.length())));
                StringBuilder bssid = new StringBuilder(17);
                int first = (r.nextInt(256) | 0x02) & 0xFE;
                bssid.append(String.format(Locale.US, "%02X", first)).append(':');
                for (int j = 1; j < 6; j++) {
                    bssid.append(String.format(Locale.US, "%02X", r.nextInt(256)));
                    if (j < 5) bssid.append(':');
                }
                int rssi = -40 - r.nextInt(40);
                int freq = r.nextBoolean() ? 2412 + 5 * r.nextInt(13) : 5180 + 5 * r.nextInt(8);
                out.add(new Ap(ssid.toString(), bssid.toString(), rssi, freq));
            }
            List<Object[]> enc = new ArrayList<>(out.size());
            for (Ap a : out) enc.add(new Object[]{a.ssid, a.bssid, a.rssi, a.freq});
            CACHE.put(k, enc);
            STAMP.put(k, now);
            return out;
        }

        private static List<Ap> jitter(List<Object[]> src) {
            Random jr = new Random();
            List<Ap> o = new ArrayList<>(src.size());
            for (Object[] a : src) {
                int rssi = (Integer) a[2] + jr.nextInt(5) - 2;
                if (rssi > -30) rssi = -30;
                if (rssi < -95) rssi = -95;
                o.add(new Ap((String) a[0], (String) a[1], rssi, (Integer) a[3]));
            }
            return o;
        }
    }

    private static Object fakeExt(int kind) {
        switch (kind) {
            case K_BSSID:
                return "02:00:00:00:00:00";
            case K_NULL:
                return NULL_RESULT;
            case K_BT_DROP:
                return VOID_RESULT;
            case K_BT_INT:
                return Integer.valueOf(0);
            case K_BT_TRUE:
                return Boolean.TRUE;
            default:
                return Boolean.FALSE;
        }
    }

    private static boolean lastKnownAllowed(Frames.Fr frame) {
        try {
            Object self = frame.get(0);
            Object arg = frame.get(1);
            String provider = arg instanceof String ? (String) arg : LocationManager.GPS_PROVIDER;
            return !(self instanceof LocationManager) || ((LocationManager) self).isProviderEnabled(provider);
        } catch (Throwable t) {
            return true;
        }
    }

    private static Location fakeLastKnown(Frames.Fr frame) {
        Object arg = frame.get(1);
        String provider = arg instanceof String ? (String) arg : LocationManager.GPS_PROVIDER;
        Location l = Fix.lastKnown(provider);
        PER_OBJ.put(l, new Mark(l.getElapsedRealtimeNanos(), l.getTime()));
        return l;
    }

    private static boolean live() {
        return Config.enabled && Config.hasPos();
    }

    private static Executor executorOf(Frames.Fr frame, int np) {
        for (int i = 1; i <= np; i++) {
            try {
                Object o = frame.get(i);
                if (o instanceof Executor) return (Executor) o;
                if (o instanceof Handler) {
                    final Handler h = (Handler) o;
                    return h::post;
                }
            } catch (Throwable ignored) { }
        }
        final Handler main = new Handler(Looper.getMainLooper());
        return main::post;
    }

    private static <T> T argOf(Frames.Fr frame, int np, Class<T> type) {
        for (int i = 1; i <= np; i++) {
            try {
                Object o = frame.get(i);
                if (type.isInstance(o)) return type.cast(o);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Method hidden(Class<?> c, String name, Class<?>... ps) {
        try {
            Method m = c.getDeclaredMethod(name, ps);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) { }
        try {
            Method m = Reflection.getDeclaredMethod(c, name, ps);
            if (m != null) m.setAccessible(true);
            return m;
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object fakeGpsStatus(Object arg) {
        try {
            Object st = GnssFake.statusObject(GnssFake.snapshot(true));
            if (!(st instanceof GnssStatus) || GPS_STATUS == null) return null;
            if (GPS_STATUS.isInstance(arg)) {
                Method set = hidden(GPS_STATUS, "setStatus", GnssStatus.class, int.class);
                if (set == null) return null;
                set.invoke(arg, st, 900);
                return arg;
            }
            Method create = hidden(GPS_STATUS, "create", GnssStatus.class, int.class);
            return create == null ? null : create.invoke(null, st, 900);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void feedStatus(final GnssStatus.Callback cb, final Executor ex, final Runnable off) {
        GnssFake.feedStatus(cb, LocationHooks::live, new GnssFake.StatusSink() {
            @Override
            public void started() {
                ex.execute(cb::onStarted);
            }

            @Override
            public void firstFix(int ttffMs) {
                ex.execute(() -> cb.onFirstFix(ttffMs));
            }

            @Override
            public void status(GnssFake.Snapshot s) {
                Object st = GnssFake.statusObject(s);
                if (!(st instanceof GnssStatus)) return;
                final GnssStatus g = (GnssStatus) st;
                ex.execute(() -> cb.onSatelliteStatusChanged(g));
            }

            @Override
            public void stopped() {
                ex.execute(cb::onStopped);
            }
        }, off);
    }

    private static void gpsEvent(Object l, int event) {
        try {
            GnssFake.call(l, "onGpsStatusChanged", event);
        } catch (Throwable ignored) { }
    }

    private static void feedLegacy(final Object l, final Executor ex, Runnable off) {
        GnssFake.feedStatus(l, LocationHooks::live, new GnssFake.StatusSink() {
            @Override
            public void started() {
                ex.execute(() -> gpsEvent(l, GPS_EVENT_STARTED));
            }

            @Override
            public void firstFix(int ttffMs) {
                ex.execute(() -> gpsEvent(l, GPS_EVENT_FIRST_FIX));
            }

            @Override
            public void status(GnssFake.Snapshot s) {
                ex.execute(() -> gpsEvent(l, GPS_EVENT_SATELLITE_STATUS));
            }

            @Override
            public void stopped() {
                ex.execute(() -> gpsEvent(l, GPS_EVENT_STOPPED));
            }
        }, off);
    }

    private static boolean feed(int kind, Frames.Fr frame, int np) {
        final Executor ex = executorOf(frame, np);
        final LocationManager lm = (LocationManager) frame.get(0);
        if (kind == K_FEED_GPS) {
            final Object l = GPS_LISTENER == null ? null : argOf(frame, np, GPS_LISTENER);
            if (l == null) return false;
            feedLegacy(l, ex, () -> {
                try {
                    GnssFake.call(lm, "addGpsStatusListener", l);
                } catch (Throwable ignored) { }
            });
            return true;
        }
        if (kind == K_FEED_NMEA) {
            final OnNmeaMessageListener l = argOf(frame, np, OnNmeaMessageListener.class);
            if (l == null) return false;
            GnssFake.feedNmea(l, LocationHooks::live, (m, ts) -> ex.execute(() -> l.onNmeaMessage(m, ts)),
                    () -> lm.addNmeaListener(ex, l));
            return true;
        }
        if (kind == K_FEED_MEAS) {
            final GnssMeasurementsEvent.Callback cb = argOf(frame, np, GnssMeasurementsEvent.Callback.class);
            if (cb == null) return false;
            GnssFake.feedMeasurements(cb, LocationHooks::live,
                    ev -> ex.execute(() -> cb.onGnssMeasurementsReceived((GnssMeasurementsEvent) ev)),
                    () -> lm.registerGnssMeasurementsCallback(ex, cb));
            return true;
        }
        if (kind == K_FEED_ANT) {
            final GnssAntennaInfo.Listener l = argOf(frame, np, GnssAntennaInfo.Listener.class);
            if (l == null) return false;
            GnssFake.feedAntenna(l, LocationHooks::live, info -> {
                final List<GnssAntennaInfo> typed = new ArrayList<>(info.size());
                for (Object a : info) typed.add((GnssAntennaInfo) a);
                ex.execute(() -> l.onGnssAntennaInfoReceived(typed));
            });
            return true;
        }
        return false;
    }

    private static void hookGnssCallback() {
        for (Method m : LocationManager.class.getDeclaredMethods()) {
            if (!m.getName().equals("registerGnssStatusCallback")) continue;
            final int nParams = m.getParameterCount();
            final boolean returnsBool = (m.getReturnType() == boolean.class);
            try {
                Frames.hook(m, (original, frame) -> {
                    if (!live()) {
                        try { original.invoke(); }
                        catch (Throwable t) { ZygoteEntry.<RuntimeException>sneaky(t); }
                        return;
                    }
                    if (returnsBool) {
                        frame.set(Frames.RET, Boolean.TRUE);
                    }
                    try {
                        GnssStatus.Callback cb = argOf(frame, nParams, GnssStatus.Callback.class);
                        if (cb != null) {
                            final Executor ex = executorOf(frame, nParams);
                            final LocationManager lm = (LocationManager) frame.get(0);
                            feedStatus(cb, ex, () -> lm.registerGnssStatusCallback(ex, cb));
                        }
                    } catch (Throwable ignored) { }
                });
            } catch (Throwable ignored) { }
        }
    }

    private LocationHooks() { }
}