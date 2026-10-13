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
import java.util.Random;

/** Hooks android.location.Location getters and LocationManager.getLastKnownLocation in the app process. */
final class LocationHooks {
    private static final int K_LAT = 0, K_LNG = 1, K_ACC = 2, K_MOCK = 3, K_LAST_KNOWN = 4;
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
        hook(Location.class, "isFromMockProvider", K_MOCK); // present on all supported versions
        hook(Location.class, "isMock", K_MOCK);             // API 31+
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
                    Object value = kind == K_LAST_KNOWN ? fakeLastKnown(frame) : fake(kind);
                    frame.accessor().setValue(EmulatedStackFrame.RETURN_VALUE_IDX, value);
                    handled = true;
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

    /** Value for the simple getters. */
    private static Object fake(int kind) {
        switch (kind) {
            case K_LAT:
                refreshSnapshot();
                return Double.valueOf(snapLat);
            case K_LNG:
                refreshSnapshot();
                return Double.valueOf(snapLng);
            case K_ACC:
                return Float.valueOf(Config.acc);
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
        l.setAltitude(0.0);
        l.setSpeed(0f);
        l.setTime(System.currentTimeMillis() - 300);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return l;
    }

    private static double meters() {
        return (RND.nextDouble() * 2.0 - 1.0) * 8.0; // +-8 m
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable t) throws T {
        throw (T) t;
    }

    private LocationHooks() { }
}
