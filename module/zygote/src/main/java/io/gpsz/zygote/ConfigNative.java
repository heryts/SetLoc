package io.gpsz.zygote;

final class ConfigNative {
    private static volatile boolean loaded;

    static synchronized boolean ensureLoaded() {
        if (loaded) return true;
        try {
            System.loadLibrary("configcore");
            loaded = true;
        } catch (Throwable ignored) {
            loaded = false;
        }
        return loaded;
    }

    static boolean ready() {
        return loaded;
    }

    static native String nativeTag(String logical);

    static native String nativeEnc(String plain);

    static native String nativeDec(String hex);

    static native double[] nativeParse(String s, double prevLat, double prevLng, float prevAcc);

    static native double[] nativeParseTrack(String s, long[] hdr);

    static native boolean nativeTrackSet(long gen, long t0, int tk, int n, double[] la, double[] lo, double[] al, float[] sp, float[] br);

    static native void nativeTrackClear();

    static native double[] nativeApplyTrack(long gen, long nowMs, int stale);

    static native int nativeStaleLevel(long lateMs);

    static native int nativeBeatCheck(String beat, boolean playing, long now);

    static native void nativeBeatDisarm();

    static native void nativeBeatReset();

    private ConfigNative() { }
}
