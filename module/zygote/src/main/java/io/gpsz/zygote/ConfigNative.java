package io.gpsz.zygote;

final class ConfigNative {
    static final int OUT_LEN = 10;

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

    static native double[] nativeParseTrack(String s, long[] hdr);

    static native boolean nativeCoreSelfTest();

    static native boolean nativeParseCfg(String s, double prevLat, double prevLng, float prevAcc, double[] out);

    static native boolean nativeCheckBeat(String beat, long now, double[] out);

    static native boolean nativeTick(long now, long uptime, double[] out);

    static native boolean nativeLoadFile(double[] out);

    static native boolean nativeRefresh(String cfg, String trk, String beat, String net, String bt, String sys,
                                        boolean useFile, long now, long uptime, String trkPath, double[] out);

    private ConfigNative() { }
}
