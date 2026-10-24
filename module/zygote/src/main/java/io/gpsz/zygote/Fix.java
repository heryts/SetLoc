package io.gpsz.zygote;

import android.location.Location;
import android.os.Bundle;
import android.os.SystemClock;

import java.lang.reflect.Method;

final class Fix {
    private static final long PHASE_NS = initPhase();

    private static long initPhase() {
        try {
            return LocationNative.nativePhaseNs(SystemClock.elapsedRealtimeNanos());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static final ThreadLocal<double[]> APPLY_BUF = ThreadLocal.withInitial(() -> new double[13]);
    private static final ThreadLocal<long[]> PAIR_BUF = ThreadLocal.withInitial(() -> new long[2]);

    private static boolean resolved = false;
    private static Method setMock, removeMsl, removeMslAcc;

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        removeMsl = find("removeMslAltitude");
        removeMslAcc = find("removeMslAltitudeAccuracy");
        for (String n : new String[]{"setMock", "setIsFromMockProvider"}) {
            Method m = find(n, boolean.class);
            if (m != null) { setMock = m; break; }
        }
    }

    private static Method find(String name, Class<?>... ps) {
        try {
            Method m = Location.class.getDeclaredMethod(name, ps);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) { }
        return null;
    }

    static void clearMock(Location l) {
        resolve();
        if (setMock == null) return;
        try { setMock.invoke(l, Boolean.FALSE); } catch (Throwable ignored) { }
    }

    private static void clearHidden(Location l) {
        try {
            if (removeMsl != null) removeMsl.invoke(l);
            if (removeMslAcc != null) removeMslAcc.invoke(l);
        } catch (Throwable ignored) { }
    }

    private static void clearMotion(Location l) {
        l.removeSpeedAccuracy();
        l.removeBearingAccuracy();
        l.removeVerticalAccuracy();
        l.removeSpeed();
        l.removeAltitude();
        l.removeBearing();
        clearHidden(l);
    }

    private static void putExtras(Location l, int sat, int mean, int maxCn0) {
        Bundle b = l.getExtras();
        if (b == null) {
            b = new Bundle();
        } else {
            b.clear();
        }
        b.putInt("satellites", sat);
        b.putInt("meanCn0", mean);
        b.putInt("maxCn0", maxCn0);
        l.setExtras(b);
    }

    private static boolean applyNative(Location l, String provider, Config.Pt p) {
        final double[] a = APPLY_BUF.get();
        try {
            if (!LocationNative.nativeApplyInto(provider, p.lat, p.lng, p.spd, p.brg, p.alt, p.accJit, Config.acc,
                    SystemClock.elapsedRealtime(), a)) return false;
        } catch (Throwable t) {
            return false;
        }
        int flags = (int) a[3];
        l.setLatitude(a[0]);
        l.setLongitude(a[1]);
        l.setAccuracy((float) a[2]);
        if ((flags & 1) != 0) {
            l.setAltitude(a[4]);
            l.setSpeed((float) a[5]);
            l.setVerticalAccuracyMeters((float) a[6]);
            l.setSpeedAccuracyMetersPerSecond((float) a[7]);
            if ((flags & 2) != 0) {
                l.setBearing((float) a[8]);
                l.setBearingAccuracyDegrees((float) a[9]);
            } else {
                l.removeBearing();
                l.removeBearingAccuracy();
            }
            clearHidden(l);
        } else {
            clearMotion(l);
        }
        if ((flags & 4) != 0) putExtras(l, (int) a[10], (int) a[11], (int) a[12]);
        return true;
    }

    static void apply(Location l, String provider, Config.Pt p) {
        resolve();
        if (!applyNative(l, provider, p)) return;
        clearMock(l);
    }

    static void stamp(Location l, String provider) {
        long nowNs = SystemClock.elapsedRealtimeNanos();
        try {
            final long[] r = PAIR_BUF.get();
            if (LocationNative.nativeStampInto(provider, nowNs, System.currentTimeMillis(), r)) {
                l.setElapsedRealtimeNanos(r[0]);
                l.setTime(r[1]);
                return;
            }
        } catch (Throwable ignored) { }
        l.setElapsedRealtimeNanos(nowNs);
        l.setTime(System.currentTimeMillis());
    }

    static Location at(String provider) {
        Location l = new Location(provider);
        apply(l, provider, Config.snapshot());
        return l;
    }

    static Location fresh(String provider) {
        Location l = at(provider);
        stamp(l, provider);
        return l;
    }

    static Location lastKnown(String provider) {
        Location l = at(provider);
        long nowNs = SystemClock.elapsedRealtimeNanos();
        try {
            final long[] r = PAIR_BUF.get();
            if (LocationNative.nativeLastKnownInto(provider, nowNs, System.currentTimeMillis(), PHASE_NS, r)) {
                l.setElapsedRealtimeNanos(r[0]);
                l.setTime(r[1]);
                return l;
            }
        } catch (Throwable ignored) { }
        l.setElapsedRealtimeNanos(nowNs);
        l.setTime(System.currentTimeMillis());
        return l;
    }

    private Fix() { }
}