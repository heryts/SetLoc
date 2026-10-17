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

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Hook getter android.location.Location dan LocationManager.getLastKnownLocation di proses app. */
final class LocationHooks {
    private static final int K_LAT = 0, K_LNG = 1, K_ACC = 2, K_MOCK = 3, K_LAST_KNOWN = 4;
    private static final int K_DIST = 11, K_BEARING_TO = 12;
    private static final int K_SPD = 5, K_BRG = 6, K_ALT = 7, K_HAS_SPD = 8, K_HAS_BRG = 9, K_HAS_ALT = 10;
    private static boolean crashed = false;

    static void install() {
        if (Build.VERSION.SDK_INT < 33) {
            Log.w(ZygoteEntry.TAG, "Android < 13 tidak didukung");
            return;
        }
        hook(Location.class, "getLatitude", K_LAT);
        hook(Location.class, "getLongitude", K_LNG);
        hook(Location.class, "getAccuracy", K_ACC);
        hook(Location.class, "getSpeed", K_SPD);
        hook(Location.class, "getBearing", K_BRG);
        hook(Location.class, "getAltitude", K_ALT);
        hook(Location.class, "hasSpeed", K_HAS_SPD);
        hook(Location.class, "hasBearing", K_HAS_BRG);
        hook(Location.class, "hasAltitude", K_HAS_ALT);
        hook(Location.class, "isFromMockProvider", K_MOCK);
        hook(Location.class, "isMock", K_MOCK);
        hook(Location.class, "distanceTo", K_DIST, Location.class);
        hook(Location.class, "bearingTo", K_BEARING_TO, Location.class);
        hook(LocationManager.class, "getLastKnownLocation", K_LAST_KNOWN, String.class);
    }

    private static void hook(Class<?> c, String name, int kind, Class<?>... params) {
        if (crashed) return;
        try {
            Method m = c.getDeclaredMethod(name, params);
            Hooks.hook(m, Hooks.EntryPointType.DIRECT, transformer(kind), Hooks.EntryPointType.DIRECT);
        } catch (NoSuchMethodException e) {
            Log.w(ZygoteEntry.TAG, "no such method " + name);
        } catch (Throwable t) {
            crashed = true;
            Log.e(ZygoteEntry.TAG, "hook failed: " + name, t);
        }
    }

    private static HookTransformer transformer(final int kind) {
        return (original, frame) -> {
            boolean handled = false;
            try {
                if (Config.enabled && !Double.isNaN(Config.lat) && !Double.isNaN(Config.lng)) {
                    Object value;
                    if (kind == K_LAST_KNOWN) value = fakeLastKnown(frame);
                    else if (kind == K_LAT || kind == K_LNG || kind == K_DIST || kind == K_BEARING_TO) value = fakePos(kind, frame);
                    else value = fake(kind);
                    if (value != null) {
                        frame.accessor().setValue(EmulatedStackFrame.RETURN_VALUE_IDX, value);
                        handled = true;
                    }
                }
            } catch (Throwable t) {
                Log.w(ZygoteEntry.TAG, "fake failed: " + t);
            }
            if (!handled) {
                try {
                    Transformers.invokeExactNoChecks(original, frame);
                } catch (Throwable t) {
                    LocationHooks.<RuntimeException>sneaky(t);
                }
            }
        };
    }

    /** Koordinat palsu per objek Location: tiap fix "membeku" saat pertama dibaca, fix baru (cap waktu beda) dapat titik terbaru. */
    private static final class Snap {
        final double lat, lng;
        final long ns, ms;
        Snap(double lat, double lng, long ns, long ms) { this.lat = lat; this.lng = lng; this.ns = ns; this.ms = ms; }
    }

    private static final Map<Location, Snap> PER_OBJ = Collections.synchronizedMap(new WeakHashMap<Location, Snap>());

    private static Snap snapOf(Location l) {
        long ns = l.getElapsedRealtimeNanos();
        long ms = l.getTime();
        Snap e = PER_OBJ.get(l);
        if (e != null && e.ns == ns && e.ms == ms) return e;
        Config.Pt p = Config.snapshot();
        e = new Snap(p.lat, p.lng, ns, ms);
        PER_OBJ.put(l, e);
        return e;
    }

    private static Object fakePos(int kind, EmulatedStackFrame frame) {
        Object self = frame.accessor().getReference(0);
        if (!(self instanceof Location)) return null;
        Snap a = snapOf((Location) self);
        if (kind == K_LAT) return Double.valueOf(a.lat);
        if (kind == K_LNG) return Double.valueOf(a.lng);
        Object other = frame.accessor().getReference(1);
        if (!(other instanceof Location)) return null;
        Snap b = snapOf((Location) other);
        float[] r = new float[2];
        Location.distanceBetween(a.lat, a.lng, b.lat, b.lng, r);
        return Float.valueOf(kind == K_DIST ? r[0] : r[1]);
    }

    private static Object fake(int kind) {
        switch (kind) {
            case K_ACC:
                return Float.valueOf(Config.acc);
            case K_SPD:
                return Float.valueOf(Config.spd);
            case K_BRG: {
                float b = Config.brg;
                return Float.valueOf(Float.isNaN(b) ? 0f : b);
            }
            case K_ALT:
                return Double.valueOf(Config.altitudeNow());
            case K_HAS_SPD:
            case K_HAS_ALT:
                return Boolean.TRUE;
            case K_HAS_BRG:
                return Boolean.valueOf(!Float.isNaN(Config.brg));
            default:
                return Boolean.FALSE;
        }
    }

    private static Location fakeLastKnown(EmulatedStackFrame frame) {
        Object arg = frame.accessor().getReference(1);
        String provider = arg instanceof String ? (String) arg : LocationManager.GPS_PROVIDER;
        Location l = new Location(provider);
        l.setLatitude(Config.lat);
        l.setLongitude(Config.lng);
        l.setAccuracy(Config.acc);
        l.setAltitude(Config.altitudeNow());
        l.setSpeed(Config.spd);
        if (!Float.isNaN(Config.brg)) l.setBearing(Config.brg);
        l.setTime(System.currentTimeMillis() - 300);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return l;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable t) throws T {
        throw (T) t;
    }

    private LocationHooks() { }
}
