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
import java.util.Random;
import java.util.WeakHashMap;

/** Hooks android.location.Location getters and LocationManager.getLastKnownLocation in the app process. */
final class LocationHooks {
    private static final int K_LAT = 0, K_LNG = 1, K_ACC = 2, K_MOCK = 3, K_LAST_KNOWN = 4;
    private static final int K_DIST = 11, K_BEARING_TO = 12;
    private static final int K_SPD = 5, K_BRG = 6, K_ALT = 7, K_HAS_SPD = 8, K_HAS_BRG = 9, K_HAS_ALT = 10;
    private static final Random RND = new Random();
    private static boolean crashed = false;

    // One jittered fix is reused for 200 ms so lat/lng read back-to-back belong to the same point
    // (same cadence as Cobits' updateLocation()).
    private static volatile double snapLat = Double.NaN, snapLng = Double.NaN;
    private static volatile long snapAt = 0;

    static void install() {
        // The direct-entrypoint path used here is only verified for Android 13+ (API 33+).
        if (Build.VERSION.SDK_INT < 33) {
            Log.w(ZygoteEntry.TAG, "Android < 13: not supported yet, no hooks installed");
            return;
        }
        hook(Location.class, "getLatitude", K_LAT);
        hook(Location.class, "getLongitude", K_LNG);
        hook(Location.class, "getAccuracy", K_ACC);
        hook(Location.class, "getSpeed", K_SPD);
        hook(Location.class, "getBearing", K_BRG);
        hook(Location.class, "getAltitude", K_ALT);
        hook(Location.class, "hasSpeed", K_HAS_SPD);     // app sering cek hasX() sebelum membaca nilainya
        hook(Location.class, "hasBearing", K_HAS_BRG);
        hook(Location.class, "hasAltitude", K_HAS_ALT);
        hook(Location.class, "isFromMockProvider", K_MOCK); // present on all supported versions
        hook(Location.class, "isMock", K_MOCK);             // API 31+
        hook(Location.class, "distanceTo", K_DIST, Location.class);       // baca field asli, harus ikut koordinat palsu per objek
        hook(Location.class, "bearingTo", K_BEARING_TO, Location.class);
        hook(LocationManager.class, "getLastKnownLocation", K_LAST_KNOWN, String.class);
    }

    private static void hook(Class<?> c, String name, int kind, Class<?>... params) {
        if (crashed) return; // do not stack more hooks after a failed one
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
                    if (value != null) {   // null = biarkan method asli jalan (mis. argumen null)
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

    /**
     * Koordinat palsu PER OBJEK Location. Getter dulu mengembalikan posisi palsu SAAT INI untuk semua objek, jadi
     * app yang membandingkan Location lama dengan yang baru (WhatsApp live location: "sudah pindah?") selalu
     * mendapat selisih 0 dan ikonnya tidak bergerak. Sekarang tiap fix "membeku" pada posisi palsu saat pertama dibaca;
     * fix berikutnya (cap waktu berbeda) mendapat posisi palsu terbaru. Objek yang dipakai ulang (set()) dikenali
     * dari perubahan cap waktunya.
     */
    private static final class Snap {
        final double lat, lng;
        final long ns, ms;
        Snap(double lat, double lng, long ns, long ms) { this.lat = lat; this.lng = lng; this.ns = ns; this.ms = ms; }
    }

    private static final Map<Location, Snap> PER_OBJ = Collections.synchronizedMap(new WeakHashMap<Location, Snap>());

    private static Snap snapOf(Location l) {
        long ns = l.getElapsedRealtimeNanos();   // tidak di-hook
        long ms = l.getTime();                   // tidak di-hook
        Snap e = PER_OBJ.get(l);
        if (e != null && e.ns == ns && e.ms == ms) return e;
        refreshSnapshot();
        e = new Snap(snapLat, snapLng, ns, ms);
        PER_OBJ.put(l, e);
        return e;
    }

    /** getLatitude / getLongitude / distanceTo / bearingTo berbasis koordinat palsu per objek; null = jalankan method asli. */
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
        Location.distanceBetween(a.lat, a.lng, b.lat, b.lng, r);   // static, tidak di-hook
        return Float.valueOf(kind == K_DIST ? r[0] : r[1]);
    }

    /** Value for the simple getters. */
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

    /** getLastKnownLocation(String provider): always return a location while spoofing is on (even if the real one is null). */
    private static Location fakeLastKnown(EmulatedStackFrame frame) {
        Object arg = frame.accessor().getReference(1); // index 0 = this, 1 = provider
        String provider = arg instanceof String ? (String) arg : LocationManager.GPS_PROVIDER;
        Location l = new Location(provider);
        l.setLatitude(Config.lat);     // setters are not hooked; getters will return the (jittered) config values
        l.setLongitude(Config.lng);
        l.setAccuracy(Config.acc);
        l.setAltitude(Config.altitudeNow());
        l.setSpeed(Config.spd);
        if (!Float.isNaN(Config.brg)) l.setBearing(Config.brg);
        l.setTime(System.currentTimeMillis() - 300);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return l;
    }

    private static double meters() {
        return (RND.nextDouble() * 2.0 - 1.0) * Config.rr; // +-rr meter (default 8)
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable t) throws T {
        throw (T) t;
    }

    private LocationHooks() { }
}
