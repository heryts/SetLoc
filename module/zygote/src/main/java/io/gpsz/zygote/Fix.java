package io.gpsz.zygote;

import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;

import com.v7878.unsafe.Reflection;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

final class Fix {
    private static final Random RND = new Random();
    private static final long PHASE_NS = (long) (Config.hashUnit(901, SystemClock.elapsedRealtimeNanos() / 1_000_000_000L) * 1e9);
    private static final Map<String, Long> LAST_NS = new ConcurrentHashMap<>();
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
        try {
            Method m = Reflection.getDeclaredMethod(Location.class, name, ps);
            if (m != null) {
                m.setAccessible(true);
                return m;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    static boolean isNetwork(String provider) {
        return LocationManager.NETWORK_PROVIDER.equals(provider);
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

    private static void applyMotion(Location l, Config.Pt p) {
        l.setAltitude(p.alt);
        l.setSpeed(p.spd > 0.3f ? Math.max(0f, p.spd + p.accJit * 0.15f) : p.spd);
        l.setVerticalAccuracyMeters(Config.vAcc(p));
        l.setSpeedAccuracyMetersPerSecond(Config.sAcc(p));
        if (Float.isNaN(p.brg)) {
            l.removeBearing();
            l.removeBearingAccuracy();
        } else {
            l.setBearing(p.brg);
            l.setBearingAccuracyDegrees(Config.bAcc(p));
        }
        clearHidden(l);
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

    private static void putExtras(Location l) {
        int[] g = Config.gnssExtras();
        Bundle b = l.getExtras();
        if (b == null) {
            b = new Bundle();
        } else {
            b.clear();
        }
        b.putInt("satellites", g[0]);
        b.putInt("meanCn0", g[1]);
        b.putInt("maxCn0", g[2]);
        l.setExtras(b);
    }

    static void apply(Location l, String provider, Config.Pt p) {
        resolve();
        if (isNetwork(provider)) {
            double[] off = Config.networkOffset(p.lat);
            l.setLatitude(p.lat + off[0]);
            l.setLongitude(p.lng + off[1]);
            l.setAccuracy(Config.networkAccuracy(Config.accNow(p)));
            clearMotion(l);
        } else {
            l.setLatitude(p.lat);
            l.setLongitude(p.lng);
            l.setAccuracy(Config.accNow(p));
            applyMotion(l, p);
            if (LocationManager.GPS_PROVIDER.equals(provider)
                    || LocationManager.FUSED_PROVIDER.equals(provider)) putExtras(l);
        }
        clearMock(l);
    }

    static void stamp(Location l, String provider) {
        long nowNs = SystemClock.elapsedRealtimeNanos();
        long fixNs = nowNs - (30L + RND.nextInt(90)) * 1_000_000L;
        synchronized (LAST_NS) {
            Long last = LAST_NS.get(provider);
            if (last != null) {
                if (!isNetwork(provider)) {
                    long jitter = (RND.nextInt(101) - 50) * 1_000_000L;
                    long snapped = last + 1_000_000_000L + jitter;
                    long age = nowNs - snapped;
                    if (Math.abs(snapped - fixNs) < 100_000_000L && age >= 20_000_000L && age <= 160_000_000L) fixNs = snapped;
                }
                if (fixNs <= last) fixNs = Math.min(last + 1_000_000L, nowNs);
            }
            if (fixNs > nowNs) fixNs = nowNs;
            LAST_NS.put(provider, fixNs);
        }
        long ageMs = (nowNs - fixNs) / 1_000_000L;
        l.setElapsedRealtimeNanos(fixNs);
        l.setTime(System.currentTimeMillis() - ageMs);
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
        long fixNs;
        Long last = LAST_NS.get(provider);
        if (last != null && nowNs - last < 5_000_000_000L) {
            fixNs = last;
        } else {
            fixNs = nowNs - Math.floorMod(nowNs - PHASE_NS, 1_000_000_000L);
            if (fixNs > nowNs) fixNs = nowNs;
        }
        l.setElapsedRealtimeNanos(fixNs);
        l.setTime(System.currentTimeMillis() - (nowNs - fixNs) / 1_000_000L);
        return l;
    }

    private Fix() { }
}