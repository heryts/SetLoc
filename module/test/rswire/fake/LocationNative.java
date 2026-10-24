package io.gpsz.zygote;

import java.util.HashMap;
import java.util.Map;

final class LocationNative {
    static int calls;
    static final boolean BAD = System.getProperty("fake.badseed") != null;
    private static final Map<String, Long> LAST = new HashMap<>();
    private static long rng = 0x9E3779B97F4A7C15L;

    static synchronized boolean ensureLoaded() { return true; }

    static boolean ready() { return true; }

    private static double h(long a, long b) { return Config.hashUnit(a, b); }

    private static double g(long salt, long bucket) { return (h(salt, bucket) + h(salt + 1000, bucket) + h(salt + 2000, bucket) - 1.5) * 2.0; }

    static long nativePhaseNs(long bootNs) {
        calls++;
        return (long) (h(901, bootNs / 1_000_000_000L) * 1e9) + (BAD ? 1 : 0);
    }

    private static double wave(long axis, double t, double sigma) {
        double[] per = {7.0, 13.0, 23.0, 41.0};
        double sum = 0;
        for (int i = 0; i < 4; i++) sum += Math.sin(2 * Math.PI * t / per[i] + h(100 + axis, i) * 2 * Math.PI);
        return sum * sigma / Math.sqrt(2.0);
    }

    static double[] nativeSnapshot(double lat, double lng, float spd, float brg, double alt, float altVar, boolean random, float rr, long nowMs) {
        calls++;
        double a = alt;
        if (altVar > 0) {
            double n = 0, w = 4, tot = 0;
            for (int i = 0; i < 3; i++) { n += w * (h(41, nowMs / 1000 - i) * 2 - 1); tot += w; w /= 2; }
            a = alt + altVar * n / tot;
        }
        double r = random ? rr : 0.8, jn = 0, je = 0;
        if (r > 0) {
            double sg = r / 3.0, t = nowMs / 1000.0;
            jn = wave(1, t, sg);
            je = wave(2, t, sg);
            double d = Math.hypot(jn, je);
            if (d > r) { jn *= r / d; je *= r / d; }
        }
        double c = Math.cos(Math.toRadians(lat));
        if (Math.abs(c) < 1e-6) c = 1e-6;
        double la = Math.max(-90, Math.min(90, lat + jn / 111320.0));
        double lo = lng + je / (111320.0 * c);
        if (lo > 180) lo -= 360; else if (lo < -180) lo += 360;
        float jit = (float) (g(31, nowMs / 50) * 0.35);
        return new double[]{la, lo, nowMs, spd, brg, a, jit};
    }

    private static float round(double x) { return (float) Math.floor(x + 0.5); }

    static double[] nativeApply(String provider, double lat, double lng, float spd, float brg, double alt, float accJit, float acc, long nowMs) {
        calls++;
        double[] o = new double[13];
        float accNow = Math.max(1f, acc + accJit);
        if ("network".equals(provider)) {
            long k = nowMs / 30_000;
            java.util.function.LongUnaryOperator bd = x -> x * 30_000 + (long) ((h(20, x) - 0.5) * 10_000);
            if (bd.applyAsLong(k + 1) <= nowMs) k++; else if (bd.applyAsLong(k) > nowMs) k--;
            double ang = h(21, k) * 2 * Math.PI, dist = 35 + h(22, k) * 90;
            double c = Math.cos(Math.toRadians(lat));
            if (Math.abs(c) < 1e-6) c = 1e-6;
            o[0] = lat + Math.sin(ang) * dist / 111320.0;
            o[1] = lng + Math.cos(ang) * dist / (111320.0 * c);
            o[2] = Math.max(25f, (float) Math.max(25.0, accNow * 2.5 + 20.0 + g(51, nowMs / 1000) * 6.0));
            return o;
        }
        o[0] = lat; o[1] = lng; o[2] = accNow;
        int flags = 1;
        o[4] = alt;
        o[5] = spd > 0.3f ? Math.max(0f, spd + accJit * 0.15f) : spd;
        o[6] = Math.max(1f, accNow * 1.3f + accJit * 1.5f);
        o[7] = Math.max(0.1f, 0.4f + Math.abs(accJit) * 0.5f);
        if (!Float.isNaN(brg)) { flags |= 2; o[8] = brg; o[9] = Math.max(1f, 10f + Math.abs(accJit) * 8f); }
        if ("gps".equals(provider) || "fused".equals(provider)) {
            long t = nowMs / 1000;
            double p1 = h(11, 0) * 2 * Math.PI, p2 = h(12, 0) * 2 * Math.PI, p3 = h(13, 0) * 2 * Math.PI, p4 = h(14, 0) * 2 * Math.PI;
            int sat = (int) (9 + round(2.2 * Math.sin(t / 53.0 + p1) + 1.6 * Math.sin(t / 17.0 + p2)));
            int mean = (int) (29 + round(3.0 * Math.sin(t / 71.0 + p3) + 1.5 * Math.sin(t / 23.0 + p4)));
            sat = Math.max(5, Math.min(14, sat));
            mean = Math.max(22, Math.min(36, mean));
            flags |= 4;
            o[10] = sat; o[11] = mean; o[12] = mean + 9 + (int) (h(15, t) * 3);
        }
        o[3] = flags;
        return o;
    }

    private static int next(int bound) {
        rng ^= rng >>> 12; rng ^= rng << 25; rng ^= rng >>> 27;
        return (int) (((rng * 0x2545F4914F6CDD1DL) >>> 33) % Math.max(1, bound));
    }

    static synchronized long[] nativeStamp(String provider, long nowNs, long wallMs) {
        calls++;
        final long MS = 1_000_000L, SEC = 1_000_000_000L;
        long fix = nowNs - (30 + next(90)) * MS;
        Long last = LAST.get(provider);
        if (last != null) {
            if (!"network".equals(provider)) {
                long snapped = last + SEC + (next(101) - 50) * MS, age = nowNs - snapped;
                if (Math.abs(snapped - fix) < 100 * MS && age >= 20 * MS && age <= 160 * MS) fix = snapped;
            }
            if (fix <= last) fix = Math.min(last + MS, nowNs);
        }
        if (fix > nowNs) fix = nowNs;
        LAST.put(provider, fix);
        return new long[]{fix, wallMs - (nowNs - fix) / MS};
    }

    static synchronized long[] nativeLastKnown(String provider, long nowNs, long wallMs, long phaseNs) {
        calls++;
        final long SEC = 1_000_000_000L;
        Long last = LAST.get(provider);
        long fix = (last != null && nowNs - last < 5 * SEC) ? last : Math.min(nowNs, nowNs - Math.floorMod(nowNs - phaseNs, SEC));
        return new long[]{fix, wallMs - (nowNs - fix) / 1_000_000L};
    }

    static boolean nativeApplyInto(String provider, double lat, double lng, float spd, float brg, double alt, float accJit, float acc, long nowMs, double[] out) {
        double[] r = nativeApply(provider, lat, lng, spd, brg, alt, accJit, acc, nowMs);
        if (r == null || r.length < 13 || out == null || out.length < 13) return false;
        System.arraycopy(r, 0, out, 0, 13);
        return true;
    }

    static boolean nativeStampInto(String provider, long nowNs, long wallMs, long[] out) {
        long[] r = nativeStamp(provider, nowNs, wallMs);
        if (r == null || r.length < 2 || out == null || out.length < 2) return false;
        out[0] = r[0];
        out[1] = r[1];
        return true;
    }

    static boolean nativeLastKnownInto(String provider, long nowNs, long wallMs, long phaseNs, long[] out) {
        long[] r = nativeLastKnown(provider, nowNs, wallMs, phaseNs);
        if (r == null || r.length < 2 || out == null || out.length < 2) return false;
        out[0] = r[0];
        out[1] = r[1];
        return true;
    }

    private LocationNative() { }
}
