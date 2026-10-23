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

    /** Baca saja, tanpa kunci: dipanggil dari static initializer Config. */
    static boolean ready() {
        return loaded;
    }

    static native String nativeTag(String logical);

    static native String nativeEnc(String plain);

    static native String nativeDec(String hex);

    static native double nativeNetworkAccuracy(long seed, long nowMs, double gpsAcc);

    static native float nativeAccNow(float acc, float accJit);

    private ConfigNative() { }
}
