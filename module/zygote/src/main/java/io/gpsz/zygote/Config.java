package io.gpsz.zygote;

import android.app.Application;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;

import com.v7878.unsafe.Reflection;

import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

final class Config {
    private static final String SALT = "Vzvwk4Rxj7Ps";

    /**
     * true bila configcore termuat DAN salt-nya sama dengan salt Java (library lama yang dibangun
     * sebelum salt diputar akan gagal di sini dan jatuh ke jalur Java).
     */
    private static final boolean CODEC_NATIVE = verifyCodec();

    private static boolean verifyCodec() {
        if (!ConfigNative.ready()) return false;
        try {
            String probe = "cfg:1;ab";
            String e = encJava(probe);
            return tagJava("cfg").equals(ConfigNative.nativeTag("cfg"))
                    && e.equals(ConfigNative.nativeEnc(probe))
                    && probe.equals(ConfigNative.nativeDec(e));
        } catch (Throwable t) {
            return false;
        }
    }

    private static final AtomicLong TRACK_GEN = new AtomicLong();
    private static final int NATIVE_TRACK_MAX_N = 200_000;

    private static final boolean CFG_NATIVE = verifyCfg();

    private static boolean verifyCfg() {
        if (!ConfigNative.ready()) return false;
        try {
            double[] p = ConfigNative.nativeParse("enabled=1;lat=-7.5;lng=110.5;acc=12;random=1;spd=3;brg=90;alt=5;altv=2;rr=10;pl=1;tk=1", 1.0, 2.0, 3f);
            if (p == null || p.length != 12 || p[0] != 1 || p[1] != 1 || p[2] != 1 || p[3] != 1 || p[4] != -7.5 || p[5] != 110.5
                    || p[6] != 12 || p[7] != 3 || p[8] != 90 || p[9] != 5 || p[10] != 2 || p[11] != 10) return false;
            if (ConfigNative.nativeParse("lat=5d", 1.0, 2.0, 3f) != null) return false;
            long[] h = new long[4];
            double[] d = ConfigNative.nativeParseTrack("t=1000;a=900;k=1000;d=,1,2,3,4,5|,6,7,8,9,10|", h);
            if (d == null || d.length != 10 || d[2] != 5 || d[3] != 3 || d[4] != 4 || d[7] != 10 || d[8] != 8 || h[0] != 1000 || h[1] != 900 || h[2] != 1000 || h[3] != 2) return false;
            long g = TRACK_GEN.incrementAndGet();
            if (!ConfigNative.nativeTrackSet(g, 1000, 1000, 2, new double[]{1, 6}, new double[]{2, 7}, new double[]{5, 10}, new float[]{3f, 8f}, new float[]{4f, 9f})) return false;
            double[] a = ConfigNative.nativeApplyTrack(g, 1500, 0);
            boolean ok = a != null && a.length == 5 && a[0] == 3.5 && a[1] == 4.5 && a[2] == 7.5 && a[3] == 5.5 && Math.abs(a[4] - 6.5) < 1e-4
                    && ConfigNative.nativeApplyTrack(g + 1, 1500, 0) == null;
            ConfigNative.nativeTrackClear();
            if (!ok || ConfigNative.nativeApplyTrack(g, 1500, 0) != null) return false;
            if (ConfigNative.nativeStaleLevel(59_999) != 1 || ConfigNative.nativeStaleLevel(60_000) != 2) return false;
            ConfigNative.nativeBeatReset();
            int b0 = ConfigNative.nativeBeatCheck("1", true, 0);
            int b1 = ConfigNative.nativeBeatCheck("1", true, 10_001);
            ConfigNative.nativeBeatReset();
            return b0 == 0 && b1 == (1 | 4);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean cfgNative() {
        return CFG_NATIVE;
    }

    static String tag(String logical) {
        if (CODEC_NATIVE) {
            try {
                String r = ConfigNative.nativeTag(logical);
                if (r != null) return r;
            } catch (Throwable ignored) { }
        }
        return tagJava(logical);
    }

    private static String tagJava(String logical) {
        String s = SALT + logical;
        int h = 0x811C9DC5;
        for (int i = 0; i < s.length(); i++) {
            int o = s.charAt(i) & 0xFF;
            h = ((h ^ o) * 16777619) & 0x7FFFFFFF;
        }
        return String.format("%06x", h & 0xFFFFFF);
    }

    static String enc(String plain) {
        if (plain == null) return null;
        if (CODEC_NATIVE && isAscii(plain)) {
            try {
                String r = ConfigNative.nativeEnc(plain);
                if (r != null) return r;
            } catch (Throwable ignored) { }
        }
        return encJava(plain);
    }

    private static String encJava(String plain) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < plain.length(); i++) {
            int cv = plain.charAt(i) & 0xFF;
            int kv = SALT.charAt(i % SALT.length()) & 0xFF;
            out.append(String.format("%02x", (cv + kv) % 256));
        }
        return out.toString();
    }

    static String dec(String hex) {
        if (hex == null) return null;
        if (CODEC_NATIVE) {
            try {
                // Rust menolak hex ganjil/bertanda dan memakai UTF-8 lossy untuk byte > 0x7F;
                // Java menerimanya (prefiks / Latin-1). Hanya hasil ASCII yang pasti sama, sisanya Java.
                String r = ConfigNative.nativeDec(hex);
                if (r != null && isAscii(r)) return r;
            } catch (Throwable ignored) { }
        }
        return decJava(hex);
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    private static String decJava(String hex) {
        StringBuilder out = new StringBuilder();
        try {
            for (int i = 0; i + 1 < hex.length(); i += 2) {
                int xv = Integer.parseInt(hex.substring(i, i + 2), 16);
                int kv = SALT.charAt((i / 2) % SALT.length()) & 0xFF;
                int v = ((xv - kv) % 256 + 256) % 256;
                out.append((char) v);
            }
        } catch (Throwable t) {
            return null;
        }
        return out.toString();
    }

    static final String KEY = tag("cfg");
    static final String KEY_SYS = tag("sys");
    static final String KEY_BEAT = tag("beat");
    static final String KEY_TRK = tag("trk");
    static final String KEY_NET = tag("net");
    static final String KEY_BT = tag("bt");
    static final String KEY_LIVE = tag("live");
    private static final String[] FILE_PATHS = {"/data/system/gpsz_config", "/data/adb/gpsz_config"};
    private static final int FILE_MAX = 8192;
    private static final long FREEZE_MS = 10_000;
    private static final long OFF_MS = 60_000;
    private static final long TRK_GRACE_MS = 3_000;
    private static final long GAP_MS = 3_000;
    private static final long POLL_MS = 1_000;
    private static final long IDLE_POLL_MS = 5_000;
    private static final long FALLBACK_POLL_MS = 250;
    private static final long RETRY_MS = 5_000;
    private static final long SNAP_MS = 50;
    private static final int TRK_FILE_MAX = 16 << 20;
    static String trkFile = "/data/system/gpsz_trk";
    static volatile boolean enabled;
    static volatile boolean jitter;
    static volatile boolean system;
    static volatile boolean sysHeartbeat;
    static volatile boolean hideNet = true;
    static volatile boolean hideBt = false;
    static volatile boolean playing;
    static volatile boolean tkMode;

    static final class Pos {
        final double lat, lng;
        Pos(double lat, double lng) { this.lat = lat; this.lng = lng; }
    }

    static volatile Pos pos = new Pos(Double.NaN, Double.NaN);

    static boolean hasPos() {
        Pos q = pos;
        return !Double.isNaN(q.lat) && !Double.isNaN(q.lng);
    }

    static volatile float acc = 10f;
    static volatile float spd = 0f;
    static volatile float brg = Float.NaN;
    static volatile double alt = 0.0;
    static volatile float altVar = 0f;
    static volatile float rr = 8f;
    private static final double FLOOR_RADIUS_M = 0.8;
    private static double jitN = 0.0, jitE = 0.0;
    private static final double[] JIT_PERIOD_S = {7.0, 13.0, 23.0, 41.0};

    static final class Pt {
        final double lat, lng;
        final long at;
        final float spd, brg, accJit;
        final double alt;
        Pt(double lat, double lng, long at, float spd, float brg, double alt, float accJit) {
            this.lat = lat; this.lng = lng; this.at = at;
            this.spd = spd; this.brg = brg; this.alt = alt; this.accJit = accJit;
        }
    }

    static final class Track {
        final long gen = TRACK_GEN.incrementAndGet();
        final long t0, wrote;
        final int tk, n;
        final double[] la, lo, al;
        final float[] sp, br;
        Track(long t0, long wrote, int tk, int n, double[] la, double[] lo, double[] al, float[] sp, float[] br) {
            this.t0 = t0; this.wrote = wrote; this.tk = tk; this.n = n;
            this.la = la; this.lo = lo; this.al = al; this.sp = sp; this.br = br;
        }
    }

    static volatile Pt snap;
    private static volatile Track track;
    private static Track winTrack, fileTrack;
    private static String fileTrackSig;
    private static long fileTrackAt;
    private static long lastElapsed, lastUptime;
    private static volatile long resumeAt;
    private static String trkSeen;
    private static volatile boolean cfgEn;
    private static volatile int trkStale = 0;
    private static volatile long trkMissAt = 0;
    private static volatile long tickAt = 0;
    private static String beatSeen = null;
    private static long beatChangedAt = 0;
    private static long lastPollAt = 0;
    private static boolean beatArmed = false;
    private static volatile int beatStale = 0;
    private static final Object REFRESH_LOCK = new Object();
    private static long lastTry = -RETRY_MS;
    private static ContentObserver observer;
    private static volatile String fileCfg, fileSys, fileNet, fileBt;
    static final Object WAKE = new Object();
    static final ThreadLocal<Boolean> READING = new ThreadLocal<>();

    static Pt snapshot() {
        Pt p = snap;
        if (p != null && SystemClock.elapsedRealtime() - p.at < SNAP_MS) return p;
        return renewSnapshot();
    }

    private static synchronized Pt renewSnapshot() {
        tick();
        long now = SystemClock.elapsedRealtime();
        Pt p = snap;
        if (p != null && now - p.at < SNAP_MS) return p;
        Pos q = pos;
        if (LOC_NATIVE) {
            try {
                double[] r = LocationNative.nativeSnapshot(q.lat, q.lng, spd, brg, alt, altVar, jitter, rr, now);
                if (r != null && r.length >= 7) {
                    p = new Pt(r[0], r[1], (long) r[2], (float) r[3], (float) r[4], r[5], (float) r[6]);
                    snap = p;
                    return p;
                }
            } catch (Throwable ignored) { }
        }
        double la = q.lat, lo = q.lng;
        float sSpd = spd, sBrg = brg;
        double sAlt = altitudeNow();
        stepJitter(now, jitter ? rr : FLOOR_RADIUS_M);
        double c = Math.cos(Math.toRadians(la));
        if (Math.abs(c) < 1e-6) c = 1e-6;
        la += jitN / 111320.0;
        lo += jitE / (111320.0 * c);
        la = Math.max(-90.0, Math.min(90.0, la));
        if (lo > 180.0) lo -= 360.0; else if (lo < -180.0) lo += 360.0;
        float jit = (float) (gauss(31, now / SNAP_MS) * 0.35);
        p = new Pt(la, lo, now, sSpd, sBrg, sAlt, jit);
        snap = p;
        return p;
    }

    private static double wave(long axis, double tSec, double sigma) {
        double sum = 0.0;
        for (int i = 0; i < JIT_PERIOD_S.length; i++) {
            double phase = hashUnit(100 + axis, i) * 2 * Math.PI;
            sum += Math.sin(2 * Math.PI * tSec / JIT_PERIOD_S[i] + phase);
        }
        return sum * sigma / Math.sqrt(JIT_PERIOD_S.length / 2.0);
    }

    private static void stepJitter(long now, double r) {
        if (r <= 0f) { jitN = 0; jitE = 0; return; }
        double sigma = r / 3.0;
        double t = now / 1000.0;
        jitN = wave(1, t, sigma);
        jitE = wave(2, t, sigma);
        double d = Math.hypot(jitN, jitE);
        if (d > r) {
            jitN *= r / d;
            jitE *= r / d;
        }
    }

    static double gauss(long salt, long bucket) {
        return (hashUnit(salt, bucket) + hashUnit(salt + 1000, bucket) + hashUnit(salt + 2000, bucket) - 1.5) * 2.0;
    }

    static float accNow(Pt p) {
        return Math.max(1f, acc + (p != null ? p.accJit : 0f));
    }

    private static final long SEED = seedFromSalt();

    private static long seedFromSalt() {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < SALT.length(); i++) {
            h = (h ^ (SALT.charAt(i) & 0xFF)) * 0x100000001B3L;
        }
        return h;
    }

    static long seed() {
        return SEED;
    }

    /** true bila lochooks termuat dan seed-nya (turunan salt) sama dengan seed Java. */
    private static final boolean LOC_NATIVE = verifyLoc();

    static boolean locNative() {
        return LOC_NATIVE;
    }

    private static boolean verifyLoc() {
        if (!LocationNative.ready()) return false;
        try {
            long boot = 123_456_789_012L;
            long want = (long) (hashUnit(901, boot / 1_000_000_000L) * 1e9);
            return LocationNative.nativePhaseNs(boot) == want;
        } catch (Throwable t) {
            return false;
        }
    }

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static double hashUnit(long a, long b) {
        long h = mix(SEED ^ mix(a) ^ (mix(b) << 1));
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    static int[] gnssExtras() {
        return gnssExtrasAt(SystemClock.elapsedRealtime() / 1000);
    }

    static int[] gnssExtrasAt(long t) {
        double p1 = hashUnit(11, 0) * 2 * Math.PI, p2 = hashUnit(12, 0) * 2 * Math.PI;
        double p3 = hashUnit(13, 0) * 2 * Math.PI, p4 = hashUnit(14, 0) * 2 * Math.PI;
        int sat = 9 + (int) Math.round(2.2 * Math.sin(t / 53.0 + p1) + 1.6 * Math.sin(t / 17.0 + p2));
        int mean = 29 + (int) Math.round(3.0 * Math.sin(t / 71.0 + p3) + 1.5 * Math.sin(t / 23.0 + p4));
        sat = Math.max(5, Math.min(14, sat));
        mean = Math.max(22, Math.min(36, mean));
        return new int[]{sat, mean, mean + 9 + (int) (hashUnit(15, t) * 3)};
    }

    static float vAcc(Pt p) {
        return Math.max(1f, accNow(p) * 1.3f + p.accJit * 1.5f);
    }

    static float sAcc(Pt p) {
        return Math.max(0.1f, 0.4f + Math.abs(p.accJit) * 0.5f);
    }

    static float bAcc(Pt p) {
        return Math.max(1f, 10f + Math.abs(p.accJit) * 8f);
    }

    private static final long NET_GRID_MS = 30_000;

    private static long netBoundary(long k) {
        return k * NET_GRID_MS + (long) ((hashUnit(20, k) - 0.5) * 10_000);
    }

    static double[] networkOffset(double refLat) {
        long t = SystemClock.elapsedRealtime();
        long k = t / NET_GRID_MS;
        if (netBoundary(k + 1) <= t) k++;
        else if (netBoundary(k) > t) k--;
        double ang = hashUnit(21, k) * 2 * Math.PI;
        double dist = 35 + hashUnit(22, k) * 90;
        double c = Math.cos(Math.toRadians(refLat));
        if (Math.abs(c) < 1e-6) c = 1e-6;
        return new double[]{Math.sin(ang) * dist / 111320.0, Math.cos(ang) * dist / (111320.0 * c)};
    }

    static float networkAccuracy(float gpsAcc) {
        return Math.max(25f, gpsAcc * 2.5f + 20f + (float) (gauss(51, SystemClock.elapsedRealtime() / 1000) * 6));
    }

    static double altitudeNow() {
        float v = altVar;
        if (v <= 0f) return alt;
        long sec = SystemClock.elapsedRealtime() / 1000;
        double n = 0.0;
        double w = 4.0, tot = 0.0;
        for (int i = 0; i < 3; i++) {
            n += w * (hashUnit(41, sec - i) * 2.0 - 1.0);
            tot += w;
            w /= 2.0;
        }
        return alt + v * n / tot;
    }

    private static void live(Application app, boolean on) {
        try {
            ContentResolver cr = app.getContentResolver();
            String want = "";
            if (on) {
                int bc = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1);
                if (bc < 0) return;
                want = Integer.toString(bc);
            }
            String cur = dec(Settings.Global.getString(cr, KEY_LIVE));
            if (on ? !want.equals(cur) : cur != null && !cur.isEmpty()) Settings.Global.putString(cr, KEY_LIVE, enc(want));
        } catch (Throwable ignored) { }
    }

    private static Track parseTrackNative(String s) {
        try {
            long[] h = new long[4];
            double[] d = ConfigNative.nativeParseTrack(s, h);
            if (d == null || h[3] < 1 || h[3] > Integer.MAX_VALUE / 5 || h[2] <= 0 || h[2] > Integer.MAX_VALUE || d.length != h[3] * 5) return null;
            int n = (int) h[3];
            double[] la = new double[n], lo = new double[n], al = new double[n];
            float[] sp = new float[n], br = new float[n];
            for (int i = 0; i < n; i++) {
                la[i] = d[5 * i];
                lo[i] = d[5 * i + 1];
                al[i] = d[5 * i + 2];
                sp[i] = (float) d[5 * i + 3];
                br[i] = (float) d[5 * i + 4];
            }
            return new Track(h[0], h[1], (int) h[2], n, la, lo, al, sp, br);
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile long nativeGen;

    private static void setTrack(Track t) {
        track = t;
        if (!CFG_NATIVE) return;
        long g = 0;
        try {
            if (t == null) ConfigNative.nativeTrackClear();
            else if (t.n <= NATIVE_TRACK_MAX_N && ConfigNative.nativeTrackSet(t.gen, t.t0, t.tk, t.n, t.la, t.lo, t.al, t.sp, t.br)) g = t.gen;
        } catch (Throwable ignored) { }
        nativeGen = g;
    }

    static Track parseTrack(String s) {
        if (s == null) return null;
        if (CFG_NATIVE && isAscii(s)) {
            Track nt = parseTrackNative(s);
            if (nt != null) return nt;
        }
        long t0 = Long.MIN_VALUE, a = Long.MIN_VALUE;
        int tk = 0;
        String d = null;
        for (String kv : s.split(";")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            String v = kv.substring(i + 1);
            try {
                switch (kv.substring(0, i)) {
                    case "t": t0 = Long.parseLong(v); break;
                    case "a": a = Long.parseLong(v); break;
                    case "k": tk = Integer.parseInt(v); break;
                    case "d": d = v; break;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (d == null || tk <= 0 || t0 == Long.MIN_VALUE) return null;
        String[] rows = d.split("\\|");
        double[] la = new double[rows.length], lo = new double[rows.length], al = new double[rows.length];
        float[] sp = new float[rows.length], br = new float[rows.length];
        int n = 0;
        for (String r : rows) {
            int st = 0;
            while (st < r.length() && r.charAt(st) == ',') st++;
            if (st == r.length()) continue;
            String[] f = r.substring(st).split(",");
            if (f.length < 5) return null;
            try {
                la[n] = Double.parseDouble(f[0]);
                lo[n] = Double.parseDouble(f[1]);
                sp[n] = Float.parseFloat(f[2]);
                br[n] = Float.parseFloat(f[3]);
                al[n] = Double.parseDouble(f[4]);
            } catch (NumberFormatException e) {
                return null;
            }
            n++;
        }
        if (n == 0) return null;
        return new Track(t0, a == Long.MIN_VALUE ? t0 : a, tk, n, la, lo, al, sp, br);
    }

    private static synchronized void noteResume(long now) {
        long up = SystemClock.uptimeMillis();
        if (lastElapsed != 0 && (now - lastElapsed) - (up - lastUptime) > GAP_MS) resumeAt = now;
        lastElapsed = now;
        lastUptime = up;
    }

    private static Track loadTrackFile() {
        java.io.File f = new java.io.File(trkFile);
        long len = f.length();
        if (len <= 0 || len > TRK_FILE_MAX) return null;
        byte[] buf = new byte[(int) len];
        int off = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            int r;
            while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
        } catch (Throwable t) {
            return null;
        }
        if (off != buf.length) return null;
        String s = new String(buf, StandardCharsets.US_ASCII);
        int nl = s.indexOf('\n');
        if (nl < 0) return null;
        long t0 = Long.MIN_VALUE;
        int tk = 0, n = 0;
        for (String kv : s.substring(0, nl).split(";")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            String v = kv.substring(i + 1).trim();
            try {
                switch (kv.substring(0, i).trim()) {
                    case "t": t0 = Long.parseLong(v); break;
                    case "k": tk = Integer.parseInt(v); break;
                    case "n": n = Integer.parseInt(v); break;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (t0 == Long.MIN_VALUE || tk <= 0 || n <= 0 || n > len / 10) return null;
        double[] la = new double[n], lo = new double[n], al = new double[n];
        float[] sp = new float[n], br = new float[n];
        StringTokenizer st = new StringTokenizer(s.substring(nl + 1));
        try {
            for (int r = 0; r < n; r++) {
                la[r] = Double.parseDouble(st.nextToken());
                lo[r] = Double.parseDouble(st.nextToken());
                sp[r] = Float.parseFloat(st.nextToken());
                br[r] = Float.parseFloat(st.nextToken());
                al[r] = Double.parseDouble(st.nextToken());
            }
        } catch (NumberFormatException | NoSuchElementException e) {
            return null;
        }
        return new Track(t0, t0, tk, n, la, lo, al, sp, br);
    }

    private static boolean reloadTrackFile(long now, boolean force) {
        if (!force && now - fileTrackAt < FILE_CHECK_MS) return false;
        fileTrackAt = now;
        java.io.File f = new java.io.File(trkFile);
        String sig = f.lastModified() + ":" + f.length();
        if (sig.equals(fileTrackSig)) return false;
        fileTrackSig = sig;
        fileTrack = loadTrackFile();
        return true;
    }

    private static Track withFile(Track w) {
        Track f = fileTrack;
        if (w == null || f == null || f.tk != w.tk) return w;
        long d = w.t0 - f.t0;
        if (d < 0 || d % f.tk != 0 || d / f.tk >= f.n) return w;
        int i = (int) (d / f.tk);
        if (Math.abs(f.la[i] - w.la[0]) > 1e-5 || Math.abs(f.lo[i] - w.lo[0]) > 1e-5) return w;
        return new Track(f.t0, w.wrote, f.tk, f.n, f.la, f.lo, f.al, f.sp, f.br);
    }

    private static synchronized int trackStale(long now) {
        noteResume(now);
        Track t = track;
        long late;
        if (t == null) {
            if (trkMissAt == 0) trkMissAt = now;
            late = now - Math.max(trkMissAt, resumeAt);
        } else {
            trkMissAt = 0;
            late = now - Math.max(Math.max(t.t0 + (long) (t.n - 1) * t.tk, t.wrote), resumeAt);
        }
        return late >= OFF_MS ? 2 : late >= TRK_GRACE_MS ? 1 : 0;
    }

    private static float lerpAngle(float a, float b, double f) {
        double d = ((b - a + 540.0) % 360.0) - 180.0;
        return (float) ((a + d * f + 360.0) % 360.0);
    }

    static void tick() {
        if (!tkMode) return;
        long now = SystemClock.elapsedRealtime();
        long d = now - tickAt;
        if (d >= 0 && d < 50) return;
        tickAt = now;
        applyTrack(now);
    }

    private static void applyTrack(long now) {
        int st = trackStale(now);
        trkStale = st;
        enabled = cfgEn && st < 2;
        Track t = track;
        if (t == null) return;
        if (CFG_NATIVE && t.gen == nativeGen) {
            try {
                double[] r = ConfigNative.nativeApplyTrack(t.gen, now, st);
                if (r != null && r.length >= 5) {
                    pos = new Pos(r[0], r[1]);
                    alt = r[2];
                    spd = (float) r[3];
                    brg = (float) r[4];
                    return;
                }
            } catch (Throwable ignored) { }
        }
        long rel = now - t.t0;
        double at = rel <= 0 ? 0.0 : (double) rel / t.tk;
        int i;
        double f;
        if (at >= t.n - 1) { i = t.n - 1; f = 0.0; } else { i = (int) at; f = at - i; }
        int j = f > 0.0 ? i + 1 : i;
        double tla = t.la[i] + (t.la[j] - t.la[i]) * f;
        double dl = t.lo[j] - t.lo[i];
        if (dl > 180.0) dl -= 360.0; else if (dl < -180.0) dl += 360.0;
        double lo = t.lo[i] + dl * f;
        if (lo > 180.0) lo -= 360.0; else if (lo < -180.0) lo += 360.0;
        pos = new Pos(tla, lo);
        alt = t.al[i] + (t.al[j] - t.al[i]) * f;
        spd = st >= 1 ? 0f : Math.max(0f, (float) (t.sp[i] + (t.sp[j] - t.sp[i]) * f));
        brg = lerpAngle(t.br[i], t.br[j], f);
    }

    private static boolean tkOn(String s) {
        if (s == null) return false;
        for (String kv : s.split(";")) if (kv.trim().equals("tk=1")) return true;
        return false;
    }

    private static double inRange(double v, double limit, double fallback) {
        return Math.abs(v) <= limit ? v : fallback;
    }

    static void parse(String s) {
        if (s == null || s.equals("null")) {
            enabled = false;
            playing = false;
            tkMode = false;
            cfgEn = false;
            beatStale = 0;
            beatArmed = false;
            if (CFG_NATIVE) {
                try { ConfigNative.nativeBeatDisarm(); } catch (Throwable ignored) { }
            }
            system = false;
            sysHeartbeat = false;
            hideNet = true;
            hideBt = false;
            return;
        }
        boolean en = false, jit = false, pl = false, tk = false;
        Pos q0 = pos;
        double la = q0.lat, lo = q0.lng;
        float ac = acc, sp = 0f, br = Float.NaN, av = 0f, rad = 8f;
        double al = 0.0;
        boolean done = false;
        if (CFG_NATIVE && isAscii(s)) {
            try {
                double[] r = ConfigNative.nativeParse(s, la, lo, ac);
                if (r != null && r.length >= 12) {
                    en = r[0] != 0; jit = r[1] != 0; pl = r[2] != 0; tk = r[3] != 0;
                    la = r[4]; lo = r[5]; ac = (float) r[6]; sp = (float) r[7]; br = (float) r[8];
                    al = r[9]; av = (float) r[10]; rad = (float) r[11];
                    done = true;
                }
            } catch (Throwable ignored) { }
        }
        if (!done) for (String kv : s.split(";")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            String k = kv.substring(0, i).trim();
            String v = kv.substring(i + 1).trim();
            try {
                switch (k) {
                    case "enabled": en = v.equals("1"); break;
                    case "lat": la = inRange(Double.parseDouble(v), 90.0, la); break;
                    case "lng": lo = inRange(Double.parseDouble(v), 180.0, lo); break;
                    case "acc": ac = (float) inRange(Float.parseFloat(v), 1e5, ac); break;
                    case "random": jit = v.equals("1"); break;
                    case "spd": sp = Math.max(0f, (float) inRange(Float.parseFloat(v), 1e4, 0.0)); break;
                    case "brg": {
                        float b = Float.parseFloat(v);
                        br = (Float.isNaN(b) || Float.isInfinite(b) || b < 0f) ? Float.NaN : b % 360f;
                        break;
                    }
                    case "alt": al = inRange(Double.parseDouble(v), 1e5, 0.0); break;
                    case "altv": av = Math.max(0f, (float) inRange(Float.parseFloat(v), 1e4, 0.0)); break;
                    case "rr": rad = Math.max(0f, Math.min(100f, (float) inRange(Float.parseFloat(v), 1e4, 8.0))); break;
                    case "pl": pl = v.equals("1"); break;
                    case "tk": tk = v.equals("1"); break;
                }
            } catch (NumberFormatException ignored) { }
        }
        tkMode = tk;
        cfgEn = en;
        acc = ac; altVar = av; rr = rad; jitter = jit; playing = pl;
        if (!(tk && track != null)) {
            pos = new Pos(la, lo); spd = sp; brg = br; alt = al;
        }
        enabled = en && beatStale < 2 && (!tk || trkStale < 2);
    }

    static void checkBeat(String beat) {
        long now = SystemClock.elapsedRealtime();
        if (CFG_NATIVE) {
            try {
                int c = ConfigNative.nativeBeatCheck(beat, playing, now);
                if (c >= 0) {
                    int level = c & 3;
                    if (level == 2) {
                        beatStale = 2;
                        enabled = false;
                    } else if (level == 1 && (c & 4) != 0) {
                        beatStale = 1;
                        spd = 0f;
                    } else {
                        beatStale = 0;
                    }
                    return;
                }
            } catch (Throwable ignored) { }
        }
        boolean gap = lastPollAt != 0 && now - lastPollAt > GAP_MS;
        lastPollAt = now;
        if (!playing) {
            beatArmed = false;
            beatStale = 0;
            return;
        }
        if (!beatArmed || gap || !Objects.equals(beat, beatSeen)) {
            beatArmed = true;
            beatSeen = beat;
            beatChangedAt = now;
            beatStale = 0;
            return;
        }
        long stale = now - beatChangedAt;
        if (stale >= OFF_MS) {
            beatStale = 2;
            enabled = false;
        } else if (stale >= FREEZE_MS) {
            beatStale = 1;
            spd = 0f;
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m;
            try {
                m = at.getDeclaredMethod("currentApplication");
            } catch (Throwable t) {
                m = Reflection.getDeclaredMethod(at, "currentApplication");
            }
            m.setAccessible(true);
            return (Application) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static void refresh(ContentResolver cr, Application app, BooleanSupplier onReady) {
        synchronized (REFRESH_LOCK) {
            READING.set(Boolean.TRUE);
            try {
                boolean wasOn = enabled && system;
                if (onReady != null) reloadFileFields(SystemClock.elapsedRealtime());
                boolean useFile = onReady != null && fileCfg != null;
                String sc = dec(Settings.Global.getString(cr, KEY));
                settingsEmpty = sc == null;
                String cs = orFile(sc, fileCfg, useFile);
                long rt = SystemClock.elapsedRealtime();
                if (tkOn(cs)) {
                    String ts = Settings.Global.getString(cr, KEY_TRK);
                    boolean fresh = !Objects.equals(ts, trkSeen);
                    if (fresh) { trkSeen = ts; winTrack = parseTrack(ts); }
                    boolean fileNew = reloadTrackFile(rt, fresh);
                    if (fresh || fileNew) setTrack(withFile(winTrack));
                    trkStale = trackStale(rt);
                } else {
                    setTrack(null);
                    winTrack = null;
                    fileTrack = null;
                    fileTrackSig = null;
                    trkSeen = null;
                    trkMissAt = 0;
                    trkStale = 0;
                }
                int staleBefore = beatStale;
                parse(cs);
                if (tkMode) {
                    applyTrack(rt);
                } else {
                    checkBeat(dec(Settings.Global.getString(cr, KEY_BEAT)));
                    if (beatStale != staleBefore) parse(cs);
                }
                String net = orFile(dec(Settings.Global.getString(cr, KEY_NET)), fileNet, useFile);
                String bt = orFile(dec(Settings.Global.getString(cr, KEY_BT)), fileBt, useFile);
                String sv = orFile(dec(Settings.Global.getString(cr, KEY_SYS)), fileSys, useFile);
                boolean wasSystem = system, wasHeartbeat = sysHeartbeat;
                applyFlags(sv, net, bt);
                boolean tightened = system && (!wasSystem || (wasHeartbeat && !sysHeartbeat));
                if ((!wasOn && enabled && system) || tightened) wake();
                if (onReady != null && system) {
                    long now = SystemClock.elapsedRealtime();
                    if (tightened || now - lastTry >= RETRY_MS) {
                        lastTry = now;
                        live(app, onReady.getAsBoolean());
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                READING.remove();
            }
        }
    }

    private static void tryLoadFromFile() {
        for (String path : FILE_PATHS) {
            try {
                String[] f = readFileFields(new java.io.File(path));
                if (f == null) continue;
                synchronized (REFRESH_LOCK) {
                    READING.set(Boolean.TRUE);
                    try {
                        setFileFields(f);
                        parse(fileCfg);
                        applyFlags(fileSys, fileNet, fileBt);
                    } finally {
                        READING.remove();
                    }
                }
                return;
            } catch (Throwable ignored) { }
        }
    }

    private static String[] readFileFields(java.io.File f) throws java.io.IOException {
        byte[] buf = new byte[FILE_MAX];
        int n = 0, r;
        try (FileInputStream in = new FileInputStream(f)) {
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        }
        if (n <= 0) return null;
        String c = null, sy = null, ne = null, b = null;
        for (String line : new String(buf, 0, n, StandardCharsets.UTF_8).split("\n")) {
            int i = line.indexOf(':');
            if (i < 0) continue;
            String k = line.substring(0, i).trim(), v = line.substring(i + 1).trim();
            if (v.isEmpty() || v.equals("null")) continue;
            switch (k) {
                case "cfg": c = v; break;
                case "sys": sy = v; break;
                case "net": ne = v; break;
                case "bt": b = v; break;
            }
        }
        return c == null ? null : new String[]{c, sy, ne, b};
    }

    private static void setFileFields(String[] f) {
        fileCfg = f[0]; fileSys = f[1]; fileNet = f[2]; fileBt = f[3];
    }

    private static String fileSeen;
    private static long fileCheckAt = 0;
    private static volatile boolean settingsEmpty;
    private static final long FILE_CHECK_MS = 2_000;

    private static void reloadFileFields(long now) {
        if (now - fileCheckAt < (settingsEmpty ? FALLBACK_POLL_MS : FILE_CHECK_MS)) return;
        fileCheckAt = now;
        for (String path : FILE_PATHS) {
            try {
                java.io.File f = new java.io.File(path);
                String sig = path + ":" + f.lastModified() + ":" + f.length();
                if (f.length() <= 0) continue;
                if (sig.equals(fileSeen)) return;
                String[] fields = readFileFields(f);
                if (fields == null) continue;
                setFileFields(fields);
                fileSeen = sig;
                return;
            } catch (Throwable ignored) { }
        }
        if (settingsEmpty) {
            fileCfg = null; fileSys = null; fileNet = null; fileBt = null;
            fileSeen = null;
        }
    }

    private static void applyFlags(String sv, String net, String bt) {
        system = "1".equals(sv) || "2".equals(sv);
        sysHeartbeat = "2".equals(sv);
        hideNet = !"0".equals(net);
        hideBt = "1".equals(bt);
    }

    private static String orFile(String v, String f, boolean useFile) {
        return v == null && useFile ? f : v;
    }

    static boolean ownObserver(Object o) {
        return o != null && o == observer;
    }

    static void wake() {
        synchronized (WAKE) { WAKE.notifyAll(); }
    }

    private static boolean observe(ContentResolver cr, Handler h, final Runnable onChange) {
        if (observer != null) return true;
        try {
            observer = new ContentObserver(h) {
                @Override public void onChange(boolean selfChange) { onChange.run(); }
            };
            cr.registerContentObserver(Settings.Global.getUriFor(KEY), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_SYS), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_NET), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_BT), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_BEAT), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_TRK), false, observer);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static void startPolling(final BooleanSupplier onReady) {
        Thread finder = new Thread(() -> {
            if (onReady != null) tryLoadFromFile();
            Application found = null;
            for (int i = 0; i < 2400 && found == null; i++) {
                found = currentApplication();
                if (found == null) try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            }
            if (found == null) return;
            final Application app = found;
            final ContentResolver cr = app.getContentResolver();
            final Runnable refresh = () -> refresh(cr, app, onReady);
            HandlerThread ht = new HandlerThread("pool-4-thread-2");
            ht.start();
            final Handler h = new Handler(ht.getLooper());
            final boolean observed = observe(cr, h, refresh);
            final Runnable tick = new Runnable() {
                @Override public void run() {
                    refresh.run();
                    h.postDelayed(this, !observed || (settingsEmpty && onReady != null) ? FALLBACK_POLL_MS : playing ? POLL_MS : IDLE_POLL_MS);
                }
            };
            h.post(tick);
        }, "pool-4-thread-1");
        finder.setDaemon(true);
        finder.start();
    }

    private Config() { }
}