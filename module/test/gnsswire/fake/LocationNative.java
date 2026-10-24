package io.gpsz.zygote;

import java.util.Arrays;

final class LocationNative {
    static volatile int broken;
    static volatile int calls;
    static final boolean BAD = System.getProperty("fake.bad") != null;

    static synchronized boolean ensureLoaded() { return true; }

    static boolean ready() { return true; }

    static long nativePhaseNs(long bootNs) {
        return (long) (Config.hashUnit(901, bootNs / 1_000_000_000L) * 1e9);
    }

    static double[] nativeSnapshot(double lat, double lng, float spd, float brg, double alt, float altVar, boolean random, float rr, long nowMs) {
        throw new UnsatisfiedLinkError("fake");
    }

    static double[] nativeApply(String provider, double lat, double lng, float spd, float brg, double alt, float accJit, float acc, long nowMs) {
        throw new UnsatisfiedLinkError("fake");
    }

    static long[] nativeStamp(String provider, long nowNs, long wallMs) {
        throw new UnsatisfiedLinkError("fake");
    }

    static long[] nativeLastKnown(String provider, long nowNs, long wallMs, long phaseNs) {
        throw new UnsatisfiedLinkError("fake");
    }

    static boolean nativeApplyInto(String provider, double lat, double lng, float spd, float brg, double alt, float accJit, float acc, long nowMs, double[] out) {
        throw new UnsatisfiedLinkError("fake");
    }

    static boolean nativeStampInto(String provider, long nowNs, long wallMs, long[] out) {
        throw new UnsatisfiedLinkError("fake");
    }

    static boolean nativeLastKnownInto(String provider, long nowNs, long wallMs, long phaseNs, long[] out) {
        throw new UnsatisfiedLinkError("fake");
    }

    private static GnssFake.Snapshot unpack(double[] a) {
        int n = (int) a[0];
        GnssFake.Snapshot s = new GnssFake.Snapshot(n);
        for (int i = 0; i < n; i++) {
            int o = 1 + i * GnssFake.STRIDE_IN;
            s.type[i] = (int) a[o];
            s.svid[i] = (int) a[o + 1];
            s.cn0[i] = (float) a[o + 2];
            s.elev[i] = (float) a[o + 3];
            s.az[i] = (float) a[o + 4];
            s.carrier[i] = (float) a[o + 5];
            s.used[i] = a[o + 6] != 0.0;
        }
        return s;
    }

    static double[] nativeGnssSnapshot(long nowSec, boolean fixed) {
        calls++;
        if (broken == 2) throw new IllegalStateException("fake");
        if (broken == 1) return null;
        GnssFake.Snapshot s = GnssFake.snapshotJava(nowSec, fixed);
        int[] fl = s.flags();
        float[] bb = s.baseband();
        double[] a = new double[1 + s.n * GnssFake.STRIDE_OUT];
        a[0] = s.n;
        for (int i = 0; i < s.n; i++) {
            int o = 1 + i * GnssFake.STRIDE_OUT;
            a[o] = s.type[i];
            a[o + 1] = s.svid[i];
            a[o + 2] = s.cn0[i];
            a[o + 3] = s.elev[i];
            a[o + 4] = s.az[i];
            a[o + 5] = s.carrier[i];
            a[o + 6] = s.used[i] ? 1.0 : 0.0;
            a[o + 7] = fl[i];
            a[o + 8] = bb[i];
        }
        if (BAD && s.n > 0) a[2] += 1;
        if (broken == 3) return Arrays.copyOf(a, a.length - 1);
        return a;
    }

    static String[] nativeGnssNmea(double[] snap, long ms, double lat, double lng, float acc, boolean hasSpd, float spd, boolean hasBrg, float brg, boolean hasAlt, double alt) {
        calls++;
        if (broken == 2) throw new IllegalStateException("fake");
        if (broken == 1) return null;
        java.util.List<String> l = GnssFake.nmeaJava(unpack(snap), ms, lat, lng, acc, hasSpd, spd, hasBrg, brg, hasAlt, alt);
        String[] r = l.toArray(new String[0]);
        if (BAD) r[0] = r[0] + "x";
        if (broken == 3) return new String[0];
        return r;
    }

    static long[] nativeGnssMeas(double[] snap, long nowMs, long timeNs) {
        calls++;
        if (broken == 2) throw new IllegalStateException("fake");
        if (broken == 1) return null;
        long[] r = GnssFake.measJava(unpack(snap), nowMs, timeNs);
        if (BAD) r[0]++;
        if (broken == 3) return Arrays.copyOf(r, r.length - 1);
        return r;
    }

    private LocationNative() { }
}
