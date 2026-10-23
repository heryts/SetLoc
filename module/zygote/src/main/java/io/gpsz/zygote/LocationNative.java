package io.gpsz.zygote;

final class LocationNative {
    private static volatile boolean loaded;

    static synchronized boolean ensureLoaded() {
        if (loaded) return true;
        try {
            System.loadLibrary("lochooks");
            loaded = true;
        } catch (Throwable ignored) {
            loaded = false;
        }
        return loaded;
    }

    static boolean ready() {
        return loaded;
    }

    static native double[] nativeApply(String provider, double lat, double lng, float spd, float brg, double alt, float accJit, float acc, long nowMs);

    static native long[] nativeStamp(String provider, long nowNs, long wallMs);

    static native long[] nativeLastKnown(String provider, long nowNs, long wallMs, long phaseNs);

    static native long nativePhaseNs(long bootNs);

    static native double[] nativeSnapshot(double lat, double lng, float spd, float brg, double alt, float altVar, boolean random, float rr, long nowMs);

    static native double[] nativeGnssSnapshot(long nowSec, boolean fixed);

    static native String[] nativeGnssNmea(double[] snap, long ms, double lat, double lng, float acc, boolean hasSpd, float spd, boolean hasBrg, float brg, boolean hasAlt, double alt);

    static native long[] nativeGnssMeas(double[] snap, long nowMs, long timeNs);

    private LocationNative() { }
}
