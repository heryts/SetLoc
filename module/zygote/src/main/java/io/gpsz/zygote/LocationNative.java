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

    /** Baca saja, tanpa kunci: dipanggil dari static initializer Config. */
    static boolean ready() {
        return loaded;
    }

    static native int nativeRoute(int kind);

    static native boolean nativeIsSnapKind(int kind);

    static native int nativeExtValue(int kind, boolean hideNet, boolean hideBt);

    static native boolean nativeActive(boolean parsing, boolean enabled, boolean hasPos, boolean sysHeartbeat);

    static native boolean nativeIsSpoofProvider(String provider);

    static native String[] nativeMergeProviders(String[] existing);

    static native long nativeSnapDecide(long ns, long ms, String provider, boolean hasMark, long markNs, long markMs, long wallNowMs);

    static native boolean nativeIsFix(long ns, long ms, String provider);

    static native double[] nativeApply(String provider, double lat, double lng, float spd, float brg, double alt, float accJit, float acc, long nowMs);

    static native long[] nativeStamp(String provider, long nowNs, long wallMs);

    static native long[] nativeLastKnown(String provider, long nowNs, long wallMs, long phaseNs);

    static native long nativePhaseNs(long bootNs);

    static native double[] nativeSnapshot(double lat, double lng, float spd, float brg, double alt, float altVar, boolean random, float rr, long nowMs);

    static native boolean nativeSnapFresh(long at, long nowMs);

    static native double[] nativeGnssSnapshot(long nowSec, boolean fixed);

    static native String[] nativeGnssNmea(double[] snap, long ms, double lat, double lng, float acc, boolean hasSpd, float spd, boolean hasBrg, float brg, boolean hasAlt, double alt);

    static native long[] nativeGnssMeas(double[] snap, long nowMs, long timeNs);

    private LocationNative() { }
}
