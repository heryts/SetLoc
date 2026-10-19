package io.gpsz.zygote;

import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;

import com.v7878.unsafe.invoke.EmulatedStackFrame;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.vmtools.HookTransformer;
import com.v7878.vmtools.Hooks;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.WeakHashMap;

final class LocationHooks {
    private static final int K_LAT = 0, K_LNG = 1, K_ACC = 2, K_MOCK = 3, K_LAST_KNOWN = 4;
    private static final int K_DIST = 11, K_BEARING_TO = 12;
    private static final int K_VACC = 13, K_SACC = 14, K_BACC = 15, K_HAS_VACC = 16, K_HAS_SACC = 17, K_HAS_BACC = 18;
    private static final int K_MSL = 19, K_MSL_ACC = 20, K_HAS_MSL = 21, K_HAS_MSL_ACC = 22;
    private static final int K_EXT = 30, K_NO_BOOL = 30, K_EMPTY_LIST = 31, K_BSSID = 32, K_NULL = 33, K_CELL_UPDATE = 34, K_SSID = 40, K_CELL_LIST_ARG = 35, K_DROP = 36;
    private static final int K_BT_DROP = 37, K_BT_INT = 38, K_BT_TRUE = 39, K_CREATE = 41, K_GNSS_TRUE = 42, K_TOSTRING = 43, K_DELIVER = 44;
    private static final Object VOID_RESULT = new Object();
    private static final Object NULL_RESULT = new Object();
    private static final int K_SPD = 5, K_BRG = 6, K_ALT = 7, K_HAS_SPD = 8, K_HAS_BRG = 9, K_HAS_ALT = 10;
    private static boolean crashed = false;

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
        hookAll(LocationManager.class, "addNmeaListener", K_NO_BOOL);
        hookAll(LocationManager.class, "registerGnssMeasurementsCallback", K_NO_BOOL);
        hookAll(LocationManager.class, "registerGnssNavigationMessageCallback", K_NO_BOOL);
        hookAll(LocationManager.class, "registerAntennaInfoListener", K_NO_BOOL);
        hookGnssCallback();
        hookAll(LocationManager.class, "addGpsStatusListener", K_GNSS_TRUE);
        hookCreator();
        SettingsGuard.install();
        PackageGuard.install();
        hookAll("android.net.wifi.WifiManager", "getScanResults", K_EMPTY_LIST);
        hookAll("android.net.wifi.WifiInfo", "getBSSID", K_BSSID);
        hookAll("android.net.wifi.WifiInfo", "getSSID", K_SSID);
        hookAll("android.bluetooth.le.BluetoothLeScanner", "startScan", K_BT_DROP);
        hookAll("android.bluetooth.BluetoothAdapter", "startLeScan", K_BT_TRUE);
        hookAll("android.bluetooth.BluetoothAdapter", "startDiscovery", K_BT_TRUE);
        hookAll("android.telephony.TelephonyManager", "getAllCellInfo", K_EMPTY_LIST);
        hookAll("android.telephony.TelephonyManager", "getCellLocation", K_NULL);
        hookAll("android.telephony.TelephonyManager", "requestCellInfoUpdate", K_CELL_UPDATE);
        hookAll("android.telephony.NetworkRegistrationInfo", "getCellIdentity", K_NULL);
        for (String stub : new String[]{
                "android.telephony.PhoneStateListener$IPhoneStateListenerStub",
                "android.telephony.TelephonyCallback$IPhoneStateListenerStub"}) {
            hookAll(stub, "onCellInfoChanged", K_CELL_LIST_ARG);
            hookAll(stub, "onCellLocationChanged", K_DROP);
        }
        hook(Location.class, "toString", K_TOSTRING);
        hookAll("android.location.LocationManager$LocationListenerTransport", "onLocationChanged", K_DELIVER);
        hookAll("android.location.LocationManager$GetCurrentLocationTransport", "onLocation", K_DELIVER);
    }

    private static void hookCreator() {
        if (crashed) return;
        try {
            Object creator = Location.class.getField("CREATOR").get(null);
            for (Method m : creator.getClass().getDeclaredMethods()) {
                if (!m.getName().equals("createFromParcel") || m.isBridge() || m.getReturnType() != Location.class) continue;
                Hooks.hook(m, Hooks.EntryPointType.DIRECT, transformer(K_CREATE), Hooks.EntryPointType.DIRECT);
            }
        } catch (Throwable t) {
            ZygoteEntry.w("creator hook failed: " + t);
        }
    }

    private static void hookAll(String className, String name, int kind) {
        try {
            hookAll(Class.forName(className), name, kind);
        } catch (Throwable t) {
            ZygoteEntry.w("no such class " + className);
        }
    }

    private static void hookAll(Class<?> c, String name, int kind) {
        if (crashed) return;
        boolean found = false;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            found = true;
            int k = kind;
            if (kind == K_BT_DROP) {
                Class<?> r = m.getReturnType();
                k = r == void.class ? K_BT_DROP : r == int.class ? K_BT_INT : K_BT_TRUE;
            }
            try {
                Hooks.hook(m, Hooks.EntryPointType.DIRECT, transformer(k), Hooks.EntryPointType.DIRECT);
            } catch (Throwable t) {
                crashed = true;
                ZygoteEntry.e("hook failed: " + name, t);
                return;
            }
        }
        if (!found) ZygoteEntry.w("no such method " + name);
    }

    private static void hook(Class<?> c, String name, int kind, Class<?>... params) {
        if (crashed) return;
        try {
            Method m = c.getDeclaredMethod(name, params);
            Hooks.hook(m, Hooks.EntryPointType.DIRECT, transformer(kind), Hooks.EntryPointType.DIRECT);
        } catch (NoSuchMethodException e) {
            ZygoteEntry.w("no such method " + name);
        } catch (Throwable t) {
            crashed = true;
            ZygoteEntry.e("hook failed: " + name, t);
        }
    }

    private static HookTransformer transformer(final int kind) {
        return (original, frame) -> {
            if (kind == K_CREATE) {
                try {
                    Transformers.invokeExactNoChecks(original, frame);
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
                try {
                    if (Config.enabled && !Double.isNaN(Config.lat) && !Double.isNaN(Config.lng)) {
                        Object r = frame.accessor().getReference(EmulatedStackFrame.RETURN_VALUE_IDX);
                        if (isFix(r)) snapOf((Location) r);
                    }
                } catch (Throwable t) {
                    ZygoteEntry.warnOnce("create fix", t);
                }
                return;
            }
            boolean handled = false;
            Throwable denied = null;
            try {
                if (Config.enabled && !Double.isNaN(Config.lat) && !Double.isNaN(Config.lng)) {
                    Object value;
                    if (kind == K_LAST_KNOWN) {
                        value = null;
                        handled = true;
                        try {
                            Transformers.invokeExactNoChecks(original, frame);
                        } catch (Throwable e) {
                            denied = e;
                        }
                        if (denied == null && lastKnownAllowed(frame)) {
                            frame.accessor().setValue(EmulatedStackFrame.RETURN_VALUE_IDX, fakeLastKnown(frame));
                        }
                    }
                    else if (kind == K_DELIVER) {
                        value = null;
                        deliver(frame);
                    }
                    else if (kind == K_TOSTRING) {
                        Object s0 = frame.accessor().getReference(0);
                        value = isFix(s0) ? fakeToString((Location) s0) : null;
                    }
                    else if (kind == K_CELL_UPDATE) {
                        value = null;
                        if (Config.hideNet && emptyCellUpdate(frame)) handled = true;
                    }
                    else if (kind == K_CELL_LIST_ARG) {
                        value = null;
                        if (Config.hideNet) frame.accessor().setValue(1, Collections.<CellInfo>emptyList());
                    }
                    else if (kind == K_DROP) {
                        value = null;
                        if (Config.hideNet) handled = true;
                    }
                    else if (kind >= K_EXT) value = (kind == K_NO_BOOL || kind == K_GNSS_TRUE || (kind >= K_BT_DROP && kind <= K_BT_TRUE ? Config.hideBt : Config.hideNet)) ? fakeExt(kind) : null;
                    else if (kind == K_LAT || kind == K_LNG || kind == K_DIST || kind == K_BEARING_TO) value = fakePos(kind, frame);
                    else if (kind == K_ACC || kind == K_SPD || kind == K_BRG || kind == K_ALT
                            || kind == K_HAS_SPD || kind == K_HAS_BRG || kind == K_HAS_ALT
                            || (kind >= K_VACC && kind <= K_HAS_BACC)) {
                        Object self = frame.accessor().getReference(0);
                        value = (self instanceof Location && isFix((Location) self))
                                ? fakeMotion(kind, (Location) self) : null;
                    }
                    else value = isFix(frame.accessor().getReference(0)) ? fake(kind) : null;
                    if (value == VOID_RESULT) {
                        handled = true;
                    } else if (value == NULL_RESULT) {
                        frame.accessor().setValue(EmulatedStackFrame.RETURN_VALUE_IDX, null);
                        handled = true;
                    } else if (value != null) {
                        frame.accessor().setValue(EmulatedStackFrame.RETURN_VALUE_IDX, value);
                        handled = true;
                    }
                }
            } catch (Throwable t) {
                ZygoteEntry.warnOnce("fake getter", t);
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

    private static final class Snap {
        final Location fix;
        final long ns, ms;
        Snap(Location fix, long ns, long ms) {
            this.fix = fix;
            this.ns = ns;
            this.ms = ms;
        }
    }

    private static final Map<Location, Snap> PER_OBJ = Collections.synchronizedMap(new WeakHashMap<Location, Snap>());

    private static Snap snapOf(Location l) {
        long ns = l.getElapsedRealtimeNanos();
        long ms = l.getTime();
        Snap e = PER_OBJ.get(l);
        if (e != null && e.ns == ns && e.ms == ms) return e;
        String provider = l.getProvider();
        Location fix = new Location(provider == null ? LocationManager.GPS_PROVIDER : provider);
        Fix.apply(fix, provider, Config.snapshot());
        e = new Snap(fix, ns, ms);
        PER_OBJ.put(l, e);
        try {
            l.set(fix);
            l.setTime(ms);
            l.setElapsedRealtimeNanos(ns);
            Fix.clearMock(l);
        } catch (Throwable ignored) { }
        return e;
    }

    private static boolean isFix(Object o) {
        return o instanceof Location && ((Location) o).getElapsedRealtimeNanos() != 0;
    }

    private static Snap posOf(Location l) {
        return isFix(l) ? snapOf(l) : null;
    }

    private static Object fakePos(int kind, EmulatedStackFrame frame) {
        Object self = frame.accessor().getReference(0);
        if (!(self instanceof Location)) return null;
        Location me = (Location) self;
        if (kind == K_LAT || kind == K_LNG) {
            if (!isFix(me)) return null;
            Location a = snapOf(me).fix;
            return Double.valueOf(kind == K_LAT ? a.getLatitude() : a.getLongitude());
        }
        Object other = frame.accessor().getReference(1);
        if (!(other instanceof Location)) return null;
        Location dest = (Location) other;
        if (!isFix(me) && !isFix(dest)) return null;
        Snap sa = posOf(me), sb = posOf(dest);
        Location a = sa != null ? sa.fix : me, b = sb != null ? sb.fix : dest;
        float[] r = new float[2];
        Location.distanceBetween(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude(), r);
        return Float.valueOf(kind == K_DIST ? r[0] : r[1]);
    }

    private static void deliver(EmulatedStackFrame frame) {
        for (int i = 1; i <= 3; i++) {
            try {
                Object o = frame.accessor().getReference(i);
                if (o instanceof List) {
                    for (Object e : (List<?>) o) if (isFix(e)) snapOf((Location) e);
                } else if (isFix(o)) {
                    snapOf((Location) o);
                }
            } catch (Throwable ignored) { }
        }
    }

    private static String fmtDur(long ms) {
        StringBuilder sb = new StringBuilder("+");
        long d = ms / 86_400_000L; ms %= 86_400_000L;
        long h = ms / 3_600_000L; ms %= 3_600_000L;
        long m = ms / 60_000L; ms %= 60_000L;
        long s = ms / 1000L; ms %= 1000L;
        if (d > 0) sb.append(d).append('d');
        if (h > 0) sb.append(h).append('h');
        if (m > 0) sb.append(m).append('m');
        if (s > 0) sb.append(s).append('s');
        if (ms > 0 || sb.length() == 1) sb.append(ms).append("ms");
        return sb.toString();
    }

    private static String fakeToString(Location me) {
        Location a = snapOf(me).fix;
        StringBuilder sb = new StringBuilder("Location[").append(me.getProvider()).append(' ')
                .append(String.format(Locale.US, "%.6f,%.6f", a.getLatitude(), a.getLongitude()));
        if (a.hasAccuracy()) sb.append(" hAcc=").append(a.getAccuracy());
        sb.append(" et=").append(fmtDur(me.getElapsedRealtimeNanos() / 1_000_000L));
        if (a.hasAltitude()) sb.append(" alt=").append(a.getAltitude());
        if (a.hasSpeed()) sb.append(" vel=").append(a.getSpeed());
        if (a.hasBearing()) sb.append(" bear=").append(a.getBearing());
        return sb.append(']').toString();
    }

    private static Object fakeMotion(int kind, Location self) {
        Location a = snapOf(self).fix;
        switch (kind) {
            case K_ACC:
                return Float.valueOf(a.getAccuracy());
            case K_SPD:
                return Float.valueOf(a.getSpeed());
            case K_BRG:
                return Float.valueOf(a.getBearing());
            case K_ALT:
                return Double.valueOf(a.getAltitude());
            case K_VACC:
                return Float.valueOf(a.getVerticalAccuracyMeters());
            case K_SACC:
                return Float.valueOf(a.getSpeedAccuracyMetersPerSecond());
            case K_BACC:
                return Float.valueOf(a.getBearingAccuracyDegrees());
            case K_HAS_SPD:
                return Boolean.valueOf(a.hasSpeed());
            case K_HAS_BRG:
                return Boolean.valueOf(a.hasBearing());
            case K_HAS_ALT:
                return Boolean.valueOf(a.hasAltitude());
            case K_HAS_VACC:
                return Boolean.valueOf(a.hasVerticalAccuracy());
            case K_HAS_SACC:
                return Boolean.valueOf(a.hasSpeedAccuracy());
            case K_HAS_BACC:
                return Boolean.valueOf(a.hasBearingAccuracy());
            default:
                return null;
        }
    }

    private static Object fake(int kind) {
        Config.tick();
        switch (kind) {
            case K_MSL_ACC:
                return Float.valueOf(0f);
            case K_MSL:
                return Double.valueOf(0d);
            default:
                return Boolean.FALSE;
        }
    }

    private static boolean emptyCellUpdate(EmulatedStackFrame frame) {
        Executor ex = null;
        TelephonyManager.CellInfoCallback cb = null;
        for (int i = 1; i <= 3; i++) {
            try {
                Object o = frame.accessor().getReference(i);
                if (o instanceof Executor) ex = (Executor) o;
                else if (o instanceof TelephonyManager.CellInfoCallback) cb = (TelephonyManager.CellInfoCallback) o;
            } catch (Throwable ignored) { }
        }
        if (ex == null || cb == null) return false;
        final TelephonyManager.CellInfoCallback fcb = cb;
        ex.execute(() -> fcb.onCellInfo(Collections.<CellInfo>emptyList()));
        return true;
    }

    private static Object fakeExt(int kind) {
        switch (kind) {
            case K_EMPTY_LIST:
                return Collections.emptyList();
            case K_BSSID:
                return "02:00:00:00:00:00";
            case K_NULL:
                return NULL_RESULT;
            case K_SSID:
                return "<unknown ssid>";
            case K_BT_DROP:
                return VOID_RESULT;
            case K_BT_INT:
                return Integer.valueOf(0);
            case K_BT_TRUE:
            case K_GNSS_TRUE:
                return Boolean.TRUE;
            default:
                return Boolean.FALSE;
        }
    }

    private static boolean lastKnownAllowed(EmulatedStackFrame frame) {
        try {
            Object self = frame.accessor().getReference(0);
            Object arg = frame.accessor().getReference(1);
            String provider = arg instanceof String ? (String) arg : LocationManager.GPS_PROVIDER;
            return !(self instanceof LocationManager) || ((LocationManager) self).isProviderEnabled(provider);
        } catch (Throwable t) {
            return true;
        }
    }

    private static Location fakeLastKnown(EmulatedStackFrame frame) {
        Object arg = frame.accessor().getReference(1);
        String provider = arg instanceof String ? (String) arg : LocationManager.GPS_PROVIDER;
        Location l = Fix.fresh(provider);
        Location scratch = new Location(l);
        scratch.setElapsedRealtimeNanos(0);
        PER_OBJ.put(l, new Snap(scratch, l.getElapsedRealtimeNanos(), l.getTime()));
        return l;
    }

    private static void hookGnssCallback() {
        Class<?> gnssCallbackClass = null;
        try {
            gnssCallbackClass = Class.forName("android.location.GnssStatus$Callback");
        } catch (Throwable t) {
            ZygoteEntry.w("GnssStatus.Callback not found: " + t);
        }
        final Class<?> cbClass = gnssCallbackClass;

        boolean found = false;
        for (Method m : LocationManager.class.getDeclaredMethods()) {
            if (!m.getName().equals("registerGnssStatusCallback")) continue;
            found = true;
            final int nParams = m.getParameterTypes().length;
            final boolean returnsBool = (m.getReturnType() == boolean.class);
            try {
                Hooks.hook(m, Hooks.EntryPointType.DIRECT, (original, frame) -> {
                    if (!Config.enabled || Double.isNaN(Config.lat) || Double.isNaN(Config.lng)) {
                        try { Transformers.invokeExactNoChecks(original, frame); }
                        catch (Throwable t) { ZygoteEntry.<RuntimeException>sneaky(t); }
                        return;
                    }

                    if (returnsBool) {
                        frame.accessor().setValue(EmulatedStackFrame.RETURN_VALUE_IDX, Boolean.TRUE);
                    }

                    if (cbClass == null) return;
                    for (int i = 1; i <= nParams; i++) {
                        try {
                            Object o = frame.accessor().getReference(i);
                            if (!cbClass.isInstance(o)) continue;
                            final Object cb = o;
                            Handler h = new Handler(Looper.getMainLooper());
                            long delay = 150L + (long) (Math.random() * 200L);
                            h.postDelayed(() -> {
                                try {
                                    Method onStarted = cbClass.getDeclaredMethod("onStarted");
                                    onStarted.setAccessible(true);
                                    onStarted.invoke(cb);
                                } catch (Throwable ignored) { }
                            }, delay);
                            break;
                        } catch (Throwable ignored) { }
                    }
                }, Hooks.EntryPointType.DIRECT);
            } catch (Throwable t) {
                ZygoteEntry.w("hookGnssCallback: " + t);
            }
        }
        if (!found) ZygoteEntry.w("registerGnssStatusCallback: not found");
    }

    private LocationHooks() { }
}
