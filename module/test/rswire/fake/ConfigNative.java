package io.gpsz.zygote;

/** Pengganti uji untuk ConfigNative: port Java dari native/configcore/src/codec.rs (semantik Rust, termasuk penolakan hex ganjil). */
final class ConfigNative {
    static int calls;
    static final String SALT = System.getProperty("fake.salt");
    static final boolean BAD = System.getProperty("fake.badseed") != null;

    static synchronized boolean ensureLoaded() { return true; }

    static boolean ready() { return true; }

    static String nativeTag(String logical) {
        calls++;
        byte[] b = (SALT + logical).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int h = 0x811C9DC5;
        for (byte x : b) h = ((h ^ (x & 0xFF)) * 16777619) & 0x7FFFFFFF;
        if (BAD) h ^= 1;
        return String.format("%06x", h & 0xFFFFFF);
    }

    static String nativeEnc(String plain) {
        calls++;
        byte[] p = plain.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < p.length; i++) o.append(String.format("%02x", ((p[i] & 0xFF) + SALT.charAt(i % SALT.length())) % 256));
        return o.toString();
    }

    static String nativeDec(String hex) {
        calls++;
        if (hex.length() % 2 != 0) return null;
        for (int i = 0; i < hex.length(); i++) if (Character.digit(hex.charAt(i), 16) < 0 || hex.charAt(i) > 0x7F) return null;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int xv = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            out[i] = (byte) Math.floorMod(xv - SALT.charAt(i % SALT.length()), 256);
        }
        return new String(out, java.nio.charset.StandardCharsets.UTF_8);
    }

    static double nativeNetworkAccuracy(long seed, long nowMs, double gpsAcc) { throw new UnsupportedOperationException(); }

    static float nativeAccNow(float acc, float accJit) { throw new UnsupportedOperationException(); }

    static double[] nativeParse(String s, double prevLat, double prevLng, float prevAcc) { throw new UnsupportedOperationException(); }

    static double[] nativeParseTrack(String s, long[] hdr) { throw new UnsupportedOperationException(); }

    static boolean nativeTrackSet(long gen, long t0, int tk, int n, double[] la, double[] lo, double[] al, float[] sp, float[] br) { throw new UnsupportedOperationException(); }

    static void nativeTrackClear() { throw new UnsupportedOperationException(); }

    static double[] nativeApplyTrack(long gen, long nowMs, int stale) { throw new UnsupportedOperationException(); }

    static int nativeStaleLevel(long lateMs) { throw new UnsupportedOperationException(); }

    static int nativeBeatCheck(String beat, boolean playing, long now) { throw new UnsupportedOperationException(); }

    static void nativeBeatDisarm() { throw new UnsupportedOperationException(); }

    static void nativeBeatReset() { throw new UnsupportedOperationException(); }

    private ConfigNative() { }
}
